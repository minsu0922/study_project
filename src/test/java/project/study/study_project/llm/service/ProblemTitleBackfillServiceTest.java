package project.study.study_project.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedTitle;
import project.study.study_project.llm.client.TitleGenerator;
import project.study.study_project.llm.dto.TitleBackfillResponse;
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
 * 제목 백필 서비스 단위 테스트 — <b>모델을 믿지 않는 부분</b>만 잰다.
 *
 * <p>이 서비스가 실제로 하는 일은 Claude를 부르는 한 줄이 아니라, 그 응답이 어긋났을 때
 * 조용히 망가지지 않게 막는 코드다. 그래서 검증 대상도 "좋은 제목이 나오는가"가 아니라
 * <b>응답이 이상할 때 무엇을 하는가</b>다 — 그건 API 없이 전부 잴 수 있다.
 *
 * <p>가장 중요한 것은 {@link #matchesByIdNotByOrder()}다. 순서로 짝지으면 모델이 한 건을
 * 빠뜨린 순간 전부 한 칸씩 밀리는데, <b>그 상태는 오류를 내지 않는다</b>. 33건을 사람이
 * 하나씩 대조해야만 드러나는 사고라, 여기서 막지 않으면 막을 곳이 없다.
 */
class ProblemTitleBackfillServiceTest {

    private ProblemRepository problemRepository;
    private FakeTitleGenerator fakeGenerator;
    private ProblemTitleBackfillService service;

    @BeforeEach
    void setUp() {
        problemRepository = mock(ProblemRepository.class);
        fakeGenerator = new FakeTitleGenerator();
        service = new ProblemTitleBackfillService(problemRepository, fakeGenerator);
        when(problemRepository.countByTitleIsNull()).thenReturn(0L);
    }

    /**
     * <b>이 테스트가 이 클래스의 존재 이유다.</b> 모델이 2번을 빠뜨리고 1·3번만 돌려줬을 때
     * 순서로 짝지으면 3번의 제목이 2번에 붙는다. 아무 오류도 나지 않고, 목록에는 엉뚱한
     * 이름이 뜬 채로 남는다.
     */
    @Test
    @DisplayName("제목은 순서가 아니라 id로 짝짓는다 — 한 건만 빠져도 순서로 짝지으면 전부 밀린다")
    void matchesByIdNotByOrder() {
        List<Problem> targets = List.of(problem(1L, "1번 지문"), problem(2L, "2번 지문"), problem(3L, "3번 지문"));
        given(targets);
        // 모델이 2번을 빠뜨렸고, 게다가 순서까지 뒤집어 보냈다
        fakeGenerator.toReturn = List.of(
                new GeneratedTitle(3L, "세 번째 문제의 제목"),
                new GeneratedTitle(1L, "첫 번째 문제의 제목"));

        TitleBackfillResponse result = service.backfill();

        assertThat(targets.get(0).getTitle()).isEqualTo("첫 번째 문제의 제목");
        assertThat(targets.get(1).getTitle()).as("모델이 안 준 건은 비어 있어야 한다 — 다음 실행이 다시 집어 온다").isNull();
        assertThat(targets.get(2).getTitle()).isEqualTo("세 번째 문제의 제목");
        assertThat(result.filled()).isEqualTo(2);
        assertThat(result.targeted()).as("빠뜨린 건이 있다는 것을 화면이 알 수 있어야 한다").isEqualTo(3);
    }

    /**
     * 요청하지 않은 id를 모델이 지어내 보낼 수 있다. 그대로 받으면 <b>백필 대상이 아닌 문제</b>의
     * 제목이 바뀐다 — 사람이 손으로 지은 제목이 갈아 치워질 수도 있는 경로다.
     */
    @Test
    @DisplayName("모르는 id는 버린다 — 대상이 아닌 문제에 제목이 붙으면 안 된다")
    void ignoresUnknownIds() {
        List<Problem> targets = List.of(problem(1L, "1번 지문"));
        given(targets);
        fakeGenerator.toReturn = List.of(
                new GeneratedTitle(1L, "제대로 된 제목"),
                new GeneratedTitle(999L, "요청한 적 없는 문제의 제목"));

        TitleBackfillResponse result = service.backfill();

        assertThat(result.filled()).isEqualTo(1);
        assertThat(result.titles()).singleElement()
                .satisfies(t -> assertThat(t.problemId()).isEqualTo(1L));
    }

    /**
     * 빈 제목을 채우면 "제목이 있다"가 되어 <b>다음 백필이 이 문제를 영영 건너뛴다</b>.
     * 안 채우는 쪽이 낫다 — 제목이 없는 상태는 목록이 지문으로 대신하므로 화면이 깨지지 않고,
     * 다음 실행이 다시 시도한다.
     */
    @Test
    @DisplayName("빈 제목은 채우지 않는다 — 채우면 다음 백필이 이 문제를 영영 건너뛴다")
    void skipsBlankTitles() {
        List<Problem> targets = List.of(problem(1L, "1번 지문"));
        given(targets);
        fakeGenerator.toReturn = List.of(new GeneratedTitle(1L, "   "));

        TitleBackfillResponse result = service.backfill();

        assertThat(targets.get(0).getTitle()).isNull();
        assertThat(result.filled()).isZero();
    }

    /**
     * 컬럼이 120자라 넘치면 <b>저장이 실패하고 트랜잭션이 통째로 롤백된다</b> — 한 건 때문에
     * 39건을 잃는다. 잘라 넣고 검수자가 다듬는 편이 낫다.
     *
     * <p>품질 기준(40자)이 아니라 컬럼 상한(120자)에서 자르는 것이 중요하다. 40자에서 자르면
     * 지시를 조금 넘긴 멀쩡한 제목이 매번 말줄임표를 달고 들어간다.
     */
    @Test
    @DisplayName("컬럼 상한을 넘는 제목은 잘라 넣는다 — 한 건 때문에 배치 전체가 롤백되면 안 된다")
    void truncatesTitlesLongerThanTheColumn() {
        List<Problem> targets = List.of(problem(1L, "1번 지문"));
        given(targets);
        fakeGenerator.toReturn = List.of(new GeneratedTitle(1L, "제".repeat(200)));

        service.backfill();

        assertThat(targets.get(0).getTitle()).hasSize(120).endsWith("…");
    }

    /**
     * 조회와 저장 사이에 사람이 제목을 붙였을 수 있다. 덮어쓰면 <b>손으로 지은 제목이 모델
     * 제목으로 갈아 치워지고</b>, 되돌릴 방법이 없다. 판단은 엔티티가 한다
     * ({@code Problem.fillTitleIfAbsent}) — 여기서는 그 결정이 살아 있는지만 확인한다.
     */
    @Test
    @DisplayName("이미 제목이 있으면 덮어쓰지 않는다 — 사람이 지은 이름을 모델이 갈아 치우면 되돌릴 수 없다")
    void neverOverwritesAnExistingTitle() {
        Problem alreadyTitled = problem(1L, "1번 지문");
        alreadyTitled.fillTitleIfAbsent("사람이 붙인 제목");
        given(List.of(alreadyTitled));
        fakeGenerator.toReturn = List.of(new GeneratedTitle(1L, "모델이 지은 제목"));

        TitleBackfillResponse result = service.backfill();

        assertThat(alreadyTitled.getTitle()).isEqualTo("사람이 붙인 제목");
        assertThat(result.filled()).isZero();
    }

    /** 빈 요청에 요금을 낼 이유가 없다. 게다가 모델은 빈 목록을 받으면 아무거나 지어내기도 한다. */
    @Test
    @DisplayName("채울 문제가 없으면 모델을 부르지 않는다")
    void doesNotCallTheModelWhenNothingIsMissing() {
        given(List.of());

        TitleBackfillResponse result = service.backfill();

        assertThat(fakeGenerator.called).isFalse();
        assertThat(result.targeted()).isZero();
    }

    /**
     * 남은 건수는 <b>다시 센 값 그대로</b>다 — 빼지 않는다.
     *
     * <p><b>원래는 빼고 있었고, 그게 버그였다(2026-08-28 수정).</b> "커밋 전이라 방금 채운 것이
     * 그대로 세어진다"고 봤는데 그 전제가 틀렸다. JPQL 조회는 실행 전에 자동으로 flush하므로
     * {@code countByTitleIsNull}은 <b>이미 채운 것을 뺀</b> 값을 돌려준다. 거기서 또 빼면
     * 두 번 빠지고, 대상이 상한보다 많으면 음수가 나온다.
     *
     * <p><b>이 테스트가 그때 왜 못 잡았는지도 적어 둔다</b> — 저장소가 mock이라 flush라는 것이
     * 아예 없고, {@code thenReturn(3L)}은 언제 불러도 3이다. 즉 이 테스트는 "빼는가"만 봤지
     * "빼는 것이 맞는가"는 볼 수 없었다. 실물에서 음수가 나오고서야 드러났다.
     * 여기서는 <b>빼지 않는다</b>는 것만 지킨다 — flush 동작 자체는 mock으로 잴 수 없다.
     */
    @Test
    @DisplayName("남은 건수를 다시 센 값 그대로 쓴다 — JPQL 조회가 이미 flush한 뒤라 빼면 두 번 빠진다")
    void doesNotSubtractFromTheRecountedRemaining() {
        List<Problem> targets = List.of(problem(1L, "1번 지문"), problem(2L, "2번 지문"));
        given(targets);
        when(problemRepository.countByTitleIsNull()).thenReturn(3L); // flush 뒤의 값이라는 전제
        fakeGenerator.toReturn = List.of(new GeneratedTitle(1L, "제목1"), new GeneratedTitle(2L, "제목2"));

        assertThat(service.backfill().remaining()).isEqualTo(3);
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private void given(List<Problem> targets) {
        when(problemRepository.findWithoutTitle(any(Pageable.class))).thenReturn(new ArrayList<>(targets));
    }

    /**
     * id가 있는 Problem — 팩터리는 id를 받지 않는다(DB가 매기는 값이라 옳다).
     * 짝짓기 검증에는 id가 반드시 필요하므로 리플렉션으로 심는다. 엔티티에 테스트 전용
     * setter를 뚫는 것보다 낫다 — 그 setter는 실제 코드에서도 부를 수 있게 된다.
     */
    private static Problem problem(long id, String question) {
        Problem p = Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, question, "O", "해설", null);
        try {
            Field idField = Problem.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트용 id 주입 실패", e);
        }
        return p;
    }

    /** 정해진 제목을 돌려주는 가짜 — 실제 API는 돈이 들고 결과가 매번 달라 단정할 수 없다. */
    private static class FakeTitleGenerator implements TitleGenerator {
        List<GeneratedTitle> toReturn = List.of();
        boolean called = false;

        @Override
        public List<GeneratedTitle> generateTitles(List<UntitledProblem> problems) {
            called = true;
            return toReturn;
        }
    }
}
