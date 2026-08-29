package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.ProblemListItem;
import project.study.study_project.quiz.dto.ProblemListItem.SolveState;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.StudySummaryResponse;
import project.study.study_project.quiz.service.ProblemListService;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.review.repository.ReviewItemRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습자 문제 목록(docs/18) — 개인화된 상태 판정과 필터를 지킨다(2026-08-29 신설).
 *
 * <h2>여기서 지키는 것</h2>
 *
 * <p>이 목록의 값은 전부 <b>파생</b>이다. 저장된 컬럼이 아니라 제출 이력에서 그때그때 계산한다.
 * 그래서 규칙이 조용히 바뀌어도 컴파일도 되고 화면도 그려진다 — 다만 <b>다른 문제를
 * 추천하게 될 뿐</b>이다. 그 조용함이 이 테스트가 있는 이유다.
 *
 * <p>특히 {@code CORRECT}의 판정("한 번이라도 맞혔나" vs "마지막 시도가 정답인가")은 2026-08-29에
 * <b>사람이 고른 값</b>이다. 코드만 보면 어느 쪽이든 자연스러워 보이므로, 고른 쪽을 못 박는다.
 *
 * <p>실제 DB가 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로 롤백된다.
 */
@SpringBootTest
@Transactional
class ProblemListIntegrationTest {

    @Autowired
    private ProblemListService problemListService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReviewItemRepository reviewItemRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("plist" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
    }

    /**
     * 이 테스트가 이 파일의 핵심이다. 셋째 문제(틀렸다가 맞힘)가 {@code CORRECT}인 것이
     * 사람이 고른 규칙이고, 반대 규칙("마지막 시도")이었다면 {@code WRONG}이 나온다.
     */
    @Test
    @DisplayName("한 번이라도 맞혔으면 CORRECT — 그 뒤에 틀려도, 그전에 틀렸어도 유지된다")
    void correctMeansEverSolved() {
        Long solved = newOx("맞힌 문제");
        Long neverSolved = newOx("못 맞힌 문제");
        Long wrongThenRight = newOx("틀렸다가 맞힌 문제");

        submit(solved, "O");                    // 정답
        submit(neverSolved, "X");               // 오답만
        submit(wrongThenRight, "X");            // 오답
        submit(wrongThenRight, "O");            // 그다음 정답

        assertThat(stateOf(solved)).isEqualTo(SolveState.CORRECT);
        assertThat(stateOf(neverSolved)).isEqualTo(SolveState.WRONG);
        assertThat(stateOf(wrongThenRight))
                .as("맞힌 적이 있으면 유지한다 — '마지막 시도' 규칙이면 여기서 WRONG이 나온다")
                .isEqualTo(SolveState.CORRECT);
    }

    /**
     * 세 상태가 <b>빠짐없이·겹치지 않게</b> 나뉘는지. 어느 하나라도 조건이 어긋나면
     * 합이 전체와 달라진다 — 화면에서는 "안 푼 문제"를 눌렀는데 푼 게 섞여 나오는 식으로 보인다.
     */
    @Test
    @DisplayName("상태 필터 셋의 합이 전체와 같다 — 빠지거나 겹치는 문제가 없다")
    void stateFiltersPartitionTheList() {
        submit(newOx("정답"), "O");
        submit(newOx("오답"), "X");
        newOx("안 푼 것");

        long all = count(null, false);
        long correct = count(SolveState.CORRECT, false);
        long wrong = count(SolveState.WRONG, false);
        long unsolved = count(SolveState.UNSOLVED, false);

        assertThat(correct + wrong + unsolved).isEqualTo(all);
        assertThat(correct).isEqualTo(1);
        assertThat(wrong).isEqualTo(1);
    }

    /**
     * 안 푼 문제는 마지막 시도일이 {@code null}이어야 한다. 0이나 빈 문자열로 뭉개면
     * 화면이 "1970-01-01에 풀었다"거나 "오늘 풀었다"로 그린다 — 대시보드 정답률에서
     * 이미 배운 구분이다(0%와 "기록 없음"은 다르다).
     */
    @Test
    @DisplayName("안 푼 문제의 마지막 시도일은 null — '안 풀었다'와 '오래전에 풀었다'는 다른 말이다")
    void unsolvedHasNullLastAttempt() {
        Long untouched = newOx("손대지 않은 문제");
        Long touched = newOx("풀어 본 문제");
        submit(touched, "O");

        assertThat(itemOf(untouched).lastAttemptedAt()).isNull();
        assertThat(itemOf(touched).lastAttemptedAt()).isNotNull();
    }

    /**
     * 복습 대기는 {@code state}와 <b>따로</b> 산다. 맞힌 문제여도 복습할 때가 되면 뜬다 —
     * "맞힌 적 있나"와 "지금도 아는지 확인할 때인가"는 다른 질문이라 이렇게 갈라 뒀다.
     */
    @Test
    @DisplayName("맞힌 문제도 복습 차례면 reviewDue가 뜬다 — state와 서로 가리지 않는다")
    void reviewDueIsIndependentOfState() {
        Long problem = newOx("복습 대상");
        submit(problem, "X");   // 오답 → 복습 사다리에 오른다(ReviewService)
        submit(problem, "O");   // 그다음 정답 → state는 CORRECT

        ProblemListItem item = itemOf(problem);
        assertThat(item.state()).isEqualTo(SolveState.CORRECT);
        // 정답을 맞히면 다음 복습일이 미래로 밀리므로 지금은 대기가 아니다.
        assertThat(item.reviewDue()).isFalse();

        // 복습일을 과거로 당기면 대기로 잡힌다 — 필터도 같은 판정을 쓴다.
        reviewItemRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(userId))
                .forEach(r -> r.resetToStart(java.time.LocalDateTime.now().minusHours(1)));

        assertThat(itemOf(problem).reviewDue()).isTrue();
        assertThat(count(null, true)).isEqualTo(1);
    }

    /**
     * 통계 카드와 목록이 <b>같은 말</b>을 하는지. 둘의 판정 규칙이 갈라지면 "푼 문제 5"라고
     * 해 놓고 CORRECT 필터에 4건이 나오는, 사람이 신뢰를 잃는 종류의 어긋남이 생긴다.
     */
    @Test
    @DisplayName("요약의 푼 문제 수 = 목록의 CORRECT 개수 — 두 화면이 같은 규칙을 쓴다")
    void summaryAgreesWithTheList() {
        submit(newOx("정답 하나"), "O");
        submit(newOx("정답 둘"), "O");
        submit(newOx("오답"), "X");

        StudySummaryResponse summary = problemListService.getSummary(userId);

        assertThat(summary.stats().solvedTotal()).isEqualTo(count(SolveState.CORRECT, false));
        assertThat(summary.stats().solvedTotal()).isEqualTo(2);
        // 제출 3건 중 2건 정답 → 67%(반올림)
        assertThat(summary.stats().correctRate()).isEqualTo(67);
        assertThat(summary.stats().solvedThisWeek()).isEqualTo(2);
    }

    /**
     * 제출이 하나도 없으면 정답률은 {@code 0}이 아니라 {@code null}이다.
     * 0%는 "다 틀렸다"는 뜻이라, 아직 시작도 안 한 사람에게 정반대 신호를 준다.
     */
    @Test
    @DisplayName("제출이 없으면 정답률은 null — 0%(다 틀렸다)와 뜻이 정반대다")
    void correctRateIsNullWithoutSubmissions() {
        StudySummaryResponse summary = problemListService.getSummary(userId);

        assertThat(summary.stats().correctRate()).isNull();
        assertThat(summary.stats().solvedTotal()).isZero();
    }

    /**
     * 사이드바는 손대지 않은 분야도 보여 줘야 한다 — 정작 "여기부터 해 볼까"의 후보가
     * 그쪽이기 때문이다. 집계 쿼리는 그런 분야를 아예 주지 않으므로 서비스가 채운다.
     */
    @Test
    @DisplayName("분야 진척에는 손대지 않은 분야도 0으로 들어 있다 — 빠지면 시작할 곳이 안 보인다")
    void domainProgressIncludesUntouchedDomains() {
        submit(newOx("네트워크 정답"), "O");

        List<StudySummaryResponse.DomainProgress> domains =
                problemListService.getSummary(userId).domains();

        assertThat(domains).hasSize(Domain.values().length);
        assertThat(domains).filteredOn(d -> d.domain() == Domain.NETWORK)
                .singleElement().extracting(StudySummaryResponse.DomainProgress::solved)
                .isEqualTo(1L);
        assertThat(domains).filteredOn(d -> d.domain() == Domain.SECURITY)
                .singleElement().extracting(StudySummaryResponse.DomainProgress::solved)
                .isEqualTo(0L);
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 정답이 "O"인 OX 문제 하나. 지문을 매번 다르게 해 중복 제약에 걸리지 않게 한다. */
    private Long newOx(String title) {
        AdminProblemDetail created = adminProblemService.create(new AdminProblemRequest(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX, title,
                title + " 지문 " + UUID.randomUUID(), "O", "해설입니다.", null, null));
        return created.id();
    }

    private void submit(Long problemId, String answer) {
        quizService.submit(userId, new QuizSubmitRequest(problemId, answer));
    }

    /** 이 사용자 목록에서 그 문제 한 줄. 페이지를 넉넉히 잡아 필터 없이 찾는다. */
    private ProblemListItem itemOf(Long problemId) {
        return list(null, false).content().stream()
                .filter(i -> i.id().equals(problemId))
                .findFirst().orElseThrow(() -> new AssertionError("목록에 없다: " + problemId));
    }

    private SolveState stateOf(Long problemId) {
        return itemOf(problemId).state();
    }

    private long count(SolveState state, boolean onlyDue) {
        return list(state, onlyDue).totalElements();
    }

    private PageResponse<ProblemListItem> list(SolveState state, boolean onlyDue) {
        return problemListService.getList(
                userId, null, null, state, onlyDue, PageRequest.of(0, 500));
    }
}
