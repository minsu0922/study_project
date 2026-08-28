package project.study.study_project.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedRationale;
import project.study.study_project.llm.client.RationaleGenerator;
import project.study.study_project.llm.dto.RationaleBackfillResponse;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 오답 설명 채우기 서비스 단위 테스트 — <b>모델을 믿지 않는 부분</b>만 잰다.
 *
 * <p>{@code ProblemTitleBackfillServiceTest}와 같은 방식이다. 이 서비스가 실제로 하는 일은
 * Claude를 부르는 한 줄이 아니라, 그 응답이 어긋났을 때 조용히 망가지지 않게 막는 코드다.
 * 그래서 검증 대상도 "좋은 설명이 나오는가"가 아니라 <b>응답이 이상할 때 무엇을 하는가</b>다.
 *
 * <p>제목 때보다 사고가 더 잘게 어긋난다는 점이 다르다. 저쪽은 문제 단위로 밀렸지만 여기는
 * <b>보기 단위</b>라, 한 문제 안에서 두 오답의 설명이 서로 바뀌어도 둘 다 그럴듯해 보인다.
 * {@link #matchesByChoiceIdNotByOrder()}가 그것을 막는다.
 */
class ChoiceRationaleBackfillServiceTest {

    private ProblemRepository problemRepository;
    private FakeRationaleGenerator fakeGenerator;
    private ChoiceRationaleBackfillService service;

    @BeforeEach
    void setUp() {
        problemRepository = mock(ProblemRepository.class);
        fakeGenerator = new FakeRationaleGenerator();
        service = new ChoiceRationaleBackfillService(problemRepository, fakeGenerator);
        when(problemRepository.countWithMissingRationale()).thenReturn(0L);
    }

    /**
     * <b>이 테스트가 이 클래스의 존재 이유다.</b> 모델이 한 보기를 빠뜨리고 순서를 뒤집어 보냈을 때,
     * 순서로 짝지으면 "쿠키가 자동으로 실린다"는 보기에 "토큰을 검증하지 않는다"는 설명이 붙는다.
     * 아무 오류도 나지 않고, 화면은 그 어긋난 짝을 그대로 그린다.
     */
    @Test
    @DisplayName("설명은 순서가 아니라 보기 id로 짝짓는다 — 한 보기만 빠져도 순서로 짝지으면 전부 밀린다")
    void matchesByChoiceIdNotByOrder() {
        Problem p = problem(1L, choice(10L, "정답 보기", true), choice(11L, "오답 하나", false),
                choice(12L, "오답 둘", false), choice(13L, "오답 셋", false));
        given(List.of(p));
        // 모델이 12번을 빠뜨렸고, 게다가 순서까지 뒤집어 보냈다
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(13L, "셋째 오답이 담은 오해를 설명한 문장이다"),
                new GeneratedRationale(11L, "첫째 오답이 담은 오해를 설명한 문장이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(rationaleOf(p, 11L)).isEqualTo("첫째 오답이 담은 오해를 설명한 문장이다");
        assertThat(rationaleOf(p, 12L)).as("모델이 안 준 보기는 비어 있어야 한다 — 다음 실행이 다시 집어 온다").isNull();
        assertThat(rationaleOf(p, 13L)).isEqualTo("셋째 오답이 담은 오해를 설명한 문장이다");
        assertThat(result.filled()).isEqualTo(2);
    }

    /**
     * 정답 보기에 설명이 붙으면 학습자가 <b>맞혔을 때</b> "왜 틀렸는지"가 뜬다.
     * 프롬프트가 정답 보기를 아예 안 보여 주는 것으로 한 번 막지만, 모델이 요청하지 않은 id를
     * 지어낼 수도 있으므로 저장 직전에 엔티티가 한 번 더 막는다.
     */
    @Test
    @DisplayName("정답 보기에는 설명을 달지 않는다 — 맞힌 학습자에게 '왜 틀렸는지'가 뜨면 안 된다")
    void neverFillsTheCorrectChoice() {
        Problem p = problem(1L, choice(10L, "정답 보기", true), choice(11L, "오답", false));
        given(List.of(p));
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(10L, "정답 보기에 잘못 붙은 설명이다"),
                new GeneratedRationale(11L, "오답이 담은 오해를 설명한 문장이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(rationaleOf(p, 10L)).isNull();
        assertThat(result.filled()).isEqualTo(1);
    }

    /**
     * 요청하지 않은 id를 모델이 지어내 보낼 수 있다. 그대로 받으면 <b>대상이 아닌 문제</b>의
     * 보기에 설명이 붙는다 — 사람이 손으로 쓴 설명이 갈아 치워질 수도 있는 경로다.
     */
    @Test
    @DisplayName("모르는 id는 버린다 — 대상이 아닌 보기에 설명이 붙으면 안 된다")
    void ignoresUnknownIds() {
        Problem p = problem(1L, choice(10L, "정답 보기", true), choice(11L, "오답", false));
        given(List.of(p));
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(11L, "제대로 붙은 설명이다"),
                new GeneratedRationale(999L, "요청한 적 없는 보기의 설명이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(result.filled()).isEqualTo(1);
        assertThat(result.rationales()).singleElement()
                .satisfies(f -> assertThat(f.choiceId()).isEqualTo(11L));
    }

    /**
     * <b>번호로 가리킨 설명은 고쳐 쓸 수 없다.</b> "②번과 달리"에서 번호만 지우면 문장이 무너지고,
     * 그대로 두면 학습자 화면에서 반드시 어긋난다 — 보기 순서는 요청마다 다시 섞이기 때문이다.
     * 통째로 버리면 그 보기는 설명이 여전히 비어 있어 다음 실행이 다시 집어 온다.
     */
    @Test
    @DisplayName("보기를 번호로 가리킨 설명은 통째로 버린다 — 섞어 내보내므로 반드시 어긋난다")
    void dropsRationalesThatPointAtChoiceNumbers() {
        Problem p = problem(1L, choice(10L, "정답 보기", true),
                choice(11L, "오답 하나", false), choice(12L, "오답 둘", false));
        given(List.of(p));
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(11L, "②번과 달리 이쪽은 읽기 차단을 말한다"),
                new GeneratedRationale(12L, "MVCC를 락 기반과 혼동한 설명이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(rationaleOf(p, 11L)).isNull();
        assertThat(rationaleOf(p, 12L)).isEqualTo("MVCC를 락 기반과 혼동한 설명이다");
        assertThat(result.filled()).isEqualTo(1);
    }

    /**
     * 컬럼이 1000자라 넘치면 <b>저장이 실패하고 트랜잭션이 통째로 롤백된다</b> — 한 보기 때문에
     * 열 문제를 잃는다. 잘라 넣고 검수자가 다듬는 편이 낫다.
     */
    @Test
    @DisplayName("컬럼 상한을 넘는 설명은 잘라 넣는다 — 한 보기 때문에 전체가 롤백되면 안 된다")
    void truncatesRationalesLongerThanTheColumn() {
        Problem p = problem(1L, choice(10L, "정답 보기", true), choice(11L, "오답", false));
        given(List.of(p));
        fakeGenerator.toReturn = List.of(new GeneratedRationale(11L, "설".repeat(1500)));

        service.backfill();

        assertThat(rationaleOf(p, 11L)).hasSize(1000).endsWith("…");
    }

    /**
     * 조회와 저장 사이에 사람이 설명을 썼을 수 있다. 덮어쓰면 <b>손으로 다듬은 문장이 모델
     * 것으로 갈아 치워지고</b>, 되돌릴 방법이 없다. 판단은 엔티티가 한다
     * ({@code Choice.fillRationaleIfAbsent}) — 여기서는 그 결정이 살아 있는지만 확인한다.
     */
    @Test
    @DisplayName("이미 설명이 있으면 덮어쓰지 않는다 — 사람이 쓴 문장을 모델이 갈아 치우면 되돌릴 수 없다")
    void neverOverwritesAnExistingRationale() {
        Choice alreadyExplained = choice(11L, "오답", false);
        alreadyExplained.fillRationaleIfAbsent("사람이 쓴 설명");
        Problem p = problem(1L, choice(10L, "정답 보기", true), alreadyExplained);
        given(List.of(p));
        fakeGenerator.toReturn = List.of(new GeneratedRationale(11L, "모델이 쓴 설명"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(alreadyExplained.getRationale()).isEqualTo("사람이 쓴 설명");
        assertThat(result.filled()).isZero();
    }

    /**
     * 이미 채워진 보기는 <b>프롬프트에도 싣지 않는다</b>. 보내 봐야 저장 단계에서 버릴 값이고,
     * 그동안 토큰 요금만 나간다. 세 오답 중 하나만 비어 있는 문제에서 차이가 난다.
     */
    @Test
    @DisplayName("이미 채워진 보기는 모델에게 보내지도 않는다 — 버릴 값에 요금을 낼 이유가 없다")
    void doesNotSendChoicesThatAlreadyHaveARationale() {
        Choice explained = choice(11L, "설명이 있는 오답", false);
        explained.fillRationaleIfAbsent("이미 있는 설명");
        Problem p = problem(1L, choice(10L, "정답 보기", true), explained, choice(12L, "빈 오답", false));
        given(List.of(p));

        service.backfill();

        assertThat(fakeGenerator.received).singleElement()
                .extracting(RationaleGenerator.ProblemWithoutRationale::wrongChoices)
                .satisfies(list -> assertThat(list)
                        .extracting(RationaleGenerator.ProblemWithoutRationale.WrongChoice::choiceId)
                        .containsExactly(12L));
    }

    /**
     * 해설이 오답 보기를 인용하면 같은 말이 두 번 읽힌다. 그렇다고 서버가 해설을 고치지는 않는다 —
     * 되돌릴 수 없는 변경이고, 되풀이는 읽기에 거슬릴 뿐 틀린 내용이 아니다. 이름만 올린다.
     *
     * <h2>이 판정을 낱말로 하다가 16건을 놓쳤다 (2026-08-28)</h2>
     *
     * <p>처음에는 해설에서 "오답"·"나머지 보기" 같은 <b>낱말</b>을 찾았다. 26건에서 0건이
     * 걸렸고, 그래서 "되풀이가 없다"고 보고했다. 화면으로 확인해 보니 해설 마지막 문단이 세 오답을
     * 하나씩 짚고 있었다 — 해설은 "오답"이라는 말을 쓰지 않고 <b>보기 문장을 따옴표로 인용</b>한다.
     * 다시 재니 26건 중 16건이 그랬다. 낱말 목록을 아무리 늘려도 못 잡았을 종류다.
     *
     * <p>그래서 이 테스트의 지문에는 <b>오답 보기 문장이 그대로 들어 있다</b>. 낱말로 되돌아가면
     * 여기서 깨진다.
     */
    @Test
    @DisplayName("해설이 오답 보기를 인용하면 이름만 올린다 — 낱말이 아니라 보기 원문으로 판정한다")
    void listsProblemsWhoseExplanationQuotesAWrongChoice() {
        Problem quoting = problem(1L,
                "\"출발지 IP를 추가하고 목적지 엔드포인트를 늘려\"는 밖으로 연결을 만들 때의 처방이다.",
                choice(10L, "정답 보기 문장이다", true),
                choice(11L, "출발지 IP를 추가하고 목적지 엔드포인트를 늘려 조합 수를 곱한다", false));
        Problem clean = problem(2L, "정답이 맞는 이유만 적힌 해설이다.",
                choice(20L, "정답 보기 문장이다", true),
                choice(21L, "전혀 다른 말로 쓰인 오답 보기다", false));
        given(List.of(quoting, clean));
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(11L, "오해를 설명한 문장이다"),
                new GeneratedRationale(21L, "오해를 설명한 문장이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(result.explanationsToCheck())
                .as("낱말('오답')은 어느 해설에도 없다 — 인용으로 잡아야 한다")
                .containsExactly(1L);
        assertThat(quoting.getExplanation()).as("해설은 그대로여야 한다")
                .startsWith("\"출발지 IP를");
    }

    /**
     * {@code ping}·{@code traceroute}처럼 짧은 보기는 해설에 정상적으로 등장한다 — 그 개념을
     * 설명하려면 이름을 부를 수밖에 없다. 그것까지 인용으로 세면 거의 모든 문제가 목록에 올라
     * 목록이 아무 뜻도 없어진다.
     */
    @Test
    @DisplayName("짧은 보기가 해설에 나오는 것은 인용이 아니다 — 이름을 부른 것뿐이다")
    void aShortChoiceNameInTheExplanationIsNotAQuote() {
        Problem p = problem(1L, "ping은 ICMP를 쓰므로 포트 도달성은 알 수 없다.",
                choice(10L, "traceroute", true), choice(11L, "ping", false));
        given(List.of(p));
        fakeGenerator.toReturn = List.of(new GeneratedRationale(11L, "오해를 설명한 문장이다"));

        assertThat(service.backfill().explanationsToCheck()).isEmpty();
    }

    /** 빈 요청에 요금을 낼 이유가 없다. 게다가 모델은 빈 목록을 받으면 아무거나 지어내기도 한다. */
    @Test
    @DisplayName("채울 문제가 없으면 모델을 부르지 않는다")
    void doesNotCallTheModelWhenNothingIsMissing() {
        given(List.of());

        RationaleBackfillResponse result = service.backfill();

        assertThat(fakeGenerator.called).isFalse();
        assertThat(result.targeted()).isZero();
    }

    /**
     * 남은 건수는 <b>다시 센 값 그대로</b>다 — 빼지 않는다.
     *
     * <h2>실물에서 음수가 나와서 배운 것이다</h2>
     *
     * <p>처음에는 제목 백필을 본떠 "커밋 전이라 방금 채운 것이 그대로 세어진다"고 보고
     * 이번에 끝낸 문제 수를 뺐다. 26건을 세 번에 나눠 돌렸더니 두 번째 실행에서
     * {@code remaining}이 <b>-4</b>로 나왔다.
     *
     * <p>전제가 틀렸다. JPQL 조회는 실행 전에 <b>자동으로 flush한다</b> — 보류 중인 변경이
     * 조회 대상 테이블과 겹치면 Hibernate가 UPDATE를 먼저 내보낸다. 커밋 전이라 다른
     * 트랜잭션에는 안 보이지만 <b>이 트랜잭션 안에서는 보인다</b>. 그래서 count는 이미
     * 채운 것을 뺀 값이고, 거기서 또 빼면 두 번 빠진다.
     *
     * <p><b>mock으로는 잴 수 없는 종류의 사고다.</b> 저장소가 mock이면 flush라는 것이 아예 없고
     * {@code thenReturn}은 언제 불러도 같은 값이다. 그래서 여기서 지킬 수 있는 것은
     * "빼지 않는다"까지다 — 그 이상은 실물을 돌려야 드러난다.
     */
    @Test
    @DisplayName("남은 문제 수를 다시 센 값 그대로 쓴다 — JPQL 조회가 이미 flush한 뒤라 빼면 음수가 난다")
    void doesNotSubtractFromTheRecountedRemaining() {
        Problem p = problem(1L, choice(10L, "정답 보기", true),
                choice(11L, "오답 하나", false), choice(12L, "오답 둘", false));
        given(List.of(p));
        when(problemRepository.countWithMissingRationale()).thenReturn(5L); // flush 뒤의 값이라는 전제
        fakeGenerator.toReturn = List.of(
                new GeneratedRationale(11L, "첫째 오해를 설명한 문장이다"),
                new GeneratedRationale(12L, "둘째 오해를 설명한 문장이다"));

        RationaleBackfillResponse result = service.backfill();

        assertThat(result.filled()).as("채운 단위는 보기다 — 문제가 아니다").isEqualTo(2);
        assertThat(result.remaining()).as("빼면 3이 되고, 실물에서는 음수가 됐다").isEqualTo(5);
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private void given(List<Problem> targets) {
        when(problemRepository.findWithMissingRationale(any(Pageable.class)))
                .thenReturn(new ArrayList<>(targets));
    }

    private static String rationaleOf(Problem problem, long choiceId) {
        return problem.getChoices().stream()
                .filter(c -> c.getId().equals(choiceId))
                .findFirst().orElseThrow()
                .getRationale();
    }

    private static Problem problem(long id, Choice... choices) {
        return problem(id, "정답이 맞는 이유만 적힌 해설이다.", choices);
    }

    /**
     * id가 있는 Problem — 팩터리는 id를 받지 않는다(DB가 매기는 값이라 옳다).
     * 짝짓기 검증에는 id가 반드시 필요하므로 리플렉션으로 심는다. 엔티티에 테스트 전용
     * setter를 뚫는 것보다 낫다 — 그 setter는 실제 코드에서도 부를 수 있게 된다.
     */
    private static Problem problem(long id, String explanation, Choice... choices) {
        Problem p = Problem.create(Domain.SECURITY, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                null, "지문", null, explanation, null);
        inject(Problem.class, p, "id", id);
        p.replaceChoices(List.of(choices));
        return p;
    }

    private static Choice choice(long id, String text, boolean correct) {
        Choice c = Choice.of(null, text, correct, 1);
        inject(Choice.class, c, "id", id);
        return c;
    }

    private static void inject(Class<?> type, Object target, String field, Object value) {
        try {
            Field f = type.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트용 %s 주입 실패".formatted(field), e);
        }
    }

    /** 정해진 설명을 돌려주는 가짜 — 실제 API는 돈이 들고 결과가 매번 달라 단정할 수 없다. */
    private static class FakeRationaleGenerator implements RationaleGenerator {
        List<GeneratedRationale> toReturn = List.of();
        List<ProblemWithoutRationale> received = List.of();
        boolean called = false;

        @Override
        public List<GeneratedRationale> generateRationales(List<ProblemWithoutRationale> problems) {
            called = true;
            received = problems;
            return toReturn;
        }
    }
}
