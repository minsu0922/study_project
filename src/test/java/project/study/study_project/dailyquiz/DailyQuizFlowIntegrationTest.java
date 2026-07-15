package project.study.study_project.dailyquiz;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.dailyquiz.domain.DailyQuiz;
import project.study.study_project.dailyquiz.domain.DailyQuizSource;
import project.study.study_project.dailyquiz.dto.DailyQuizItemResponse;
import project.study.study_project.dailyquiz.dto.DailyQuizResponse;
import project.study.study_project.dailyquiz.repository.DailyQuizRepository;
import project.study.study_project.dailyquiz.service.DailyQuizService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오늘의 퀴즈 통합 테스트 — 실제 DB(MySQL)로 전체 여정을 재생한다:
 * <b>지연 생성 → 배합(복습/취약/새 문제) → 풀이 진행률 → 완료 → 스트릭</b>. 스펙은 docs/12, ADR-0005.
 *
 * <p>검증 포인트가 통합이어야 하는 이유(ReviewFlowIntegrationTest와 같은 판단):
 * 배합 선정 쿼리(네이티브 RAND/NOT EXISTS)의 실동작, 제출 API와의 트랜잭션 연결(MANDATORY 합류),
 * fetch join 조회, UNIQUE 제약과의 상호작용은 단위 테스트로 볼 수 없다.
 *
 * <p><b>시드 데이터 전제</b>: 문제 풀(V2/V3 시드)이 이미 있으므로 "새 문제 칸"은 시드에서
 * 뽑힌다 — 개수 단정은 "세트 크기 이하"처럼 시드 규모에 안전한 형태로만 한다.
 * 테스트 사용자·문제는 매번 새로 만들고 클래스 {@code @Transactional}이 전부 롤백한다.
 */
@SpringBootTest
@Transactional
class DailyQuizFlowIntegrationTest {

    @Autowired
    private DailyQuizService dailyQuizService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private DailyQuizRepository dailyQuizRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @PersistenceContext
    private EntityManager em;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("daily-test-" + UUID.randomUUID() + "@test.local")
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
    }

    /* ── 헬퍼 ── */

    /** OX 문제 생성 — 정답 "O" 고정이라 테스트가 정답/오답을 마음대로 만들 수 있다. */
    private Problem newOx(Domain domain, String question) {
        return problemRepository.save(Problem.create(
                domain, Difficulty.BEGINNER, ProblemType.OX, question, "O", "해설"));
    }

    /** 실제 서비스 경로(채점→이력→사다리→세트 반영)를 그대로 태우는 제출. */
    private void submit(Long problemId, String answer) {
        quizService.submit(userId, new QuizSubmitRequest(problemId, answer));
    }

    /**
     * 시간 여행: 지정한 문제들의 복습 예정 시각만 과거로 돌려 due로 만든다.
     * 벌크 JPQL은 1차 캐시를 우회하므로 flush → UPDATE → clear 순서 필수(ReviewFlow 주석 참고).
     * "지정한 문제만"인 이유: 취약 도메인용 오답까지 전부 due로 만들면 복습 칸과
     * 취약 칸의 재료가 섞여 배합 검증이 불가능해진다.
     */
    private void timeTravelToDue(List<Long> problemIds) {
        em.flush();
        em.createQuery("""
                        update ReviewItem r set r.nextReviewAt = :past
                        where r.userId = :userId and r.problem.id in :pids
                        """)
                .setParameter("past", LocalDateTime.now().minusMinutes(1))
                .setParameter("userId", userId)
                .setParameter("pids", problemIds)
                .executeUpdate();
        em.clear();
    }

    private DailyQuizResponse getToday() {
        return dailyQuizService.getToday(userId);
    }

    /** 항목 유형별로 유효한 답 — 정답 여부는 무관(진행률은 정오답과 무관하게 "풀었냐"로 센다). */
    private String anyValidAnswer(DailyQuizItemResponse item) {
        return switch (item.problem().type()) {
            case MULTIPLE_CHOICE -> String.valueOf(item.problem().choices().get(0).id());
            case OX -> "O";
            case SHORT_ANSWER -> "아무 답";
            case ESSAY -> throw new IllegalStateException("세트에 ESSAY가 있으면 안 된다(docs/12)");
        };
    }

    /* ── 테스트 ── */

    @Test
    @DisplayName("지연 생성 + 멱등: 첫 조회가 세트를 만들고, 다시 조회하면 같은 세트가 온다")
    void lazyCreationIsIdempotent() {
        DailyQuizResponse first = getToday();

        // 세트 기본 성질: 크기 상한, seq 1..N 연속, 문제 중복 없음, 아무것도 안 푼 상태
        assertThat(first.quizDate()).isEqualTo(LocalDate.now());
        assertThat(first.items()).isNotEmpty().hasSizeLessThanOrEqualTo(DailyQuizService.SET_SIZE);
        assertThat(first.items()).extracting(DailyQuizItemResponse::seq)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, first.items().size()).boxed().toList());
        assertThat(first.items().stream().map(i -> i.problem().id()).collect(Collectors.toSet()))
                .hasSize(first.items().size());
        assertThat(first.completed()).isFalse();
        assertThat(first.progress().solved()).isZero();
        // 활동 이력이 전혀 없는 사용자 → 복습/취약 재료가 없어 전부 "새 문제"여야 한다
        assertThat(first.items()).allMatch(i -> i.source() == DailyQuizSource.NEW);

        // 멱등: 두 번째 조회는 생성이 아니라 같은 세트 반환(문제 구성이 그대로)
        DailyQuizResponse second = getToday();
        assertThat(second.items().stream().map(i -> i.problem().id()).toList())
                .isEqualTo(first.items().stream().map(i -> i.problem().id()).toList());
    }

    @Test
    @DisplayName("배합: 복습 due는 REVIEW 칸에, 정답률 낮은 도메인은 WEAK 칸에, 나머지는 NEW로 채워진다")
    void mixRecipe() {
        // 복습 재료: NETWORK 문제 2개를 틀리고 시간 여행으로 due 상태로 만든다
        Problem due1 = newOx(Domain.NETWORK, "복습 대상 1");
        Problem due2 = newOx(Domain.NETWORK, "복습 대상 2");
        submit(due1.getId(), "X");
        submit(due2.getId(), "X");
        timeTravelToDue(List.of(due1.getId(), due2.getId()));

        // 취약 재료: OS 도메인에 제출 5회(오답 4·정답 1 = 정답률 20%).
        // 다른 도메인은 제출 5회 미만이라 판정에서 제외 → OS가 유일한(=가장 약한) 취약 도메인.
        // 이 오답들의 복습 예정은 "내일"이라 due가 아니다 — 복습 칸과 섞이지 않는다(헬퍼 주석).
        for (int i = 0; i < 5; i++) {
            Problem p = newOx(Domain.OS, "취약 재료 " + i);
            submit(p.getId(), i == 0 ? "O" : "X");
        }

        List<DailyQuizItemResponse> items = getToday().items();

        // REVIEW 칸: due로 만든 그 2문제가 정확히 들어온다(목표 4 미달 — 부족분은 NEW가 흡수)
        assertThat(items.stream().filter(i -> i.source() == DailyQuizSource.REVIEW)
                .map(i -> i.problem().id()))
                .containsExactlyInAnyOrder(due1.getId(), due2.getId());

        // WEAK 칸: 3개 전부 OS 도메인에서 뽑혔다
        List<DailyQuizItemResponse> weak =
                items.stream().filter(i -> i.source() == DailyQuizSource.WEAK).toList();
        assertThat(weak).hasSize(DailyQuizService.WEAK_TARGET);
        assertThat(weak).allMatch(i -> i.problem().domain() == Domain.OS);

        // NEW 칸: 남은 자리를 채우고, 전부 내가 제출한 적 없는 문제다
        Set<Long> mySubmitted = em.createQuery(
                        "select s.problem.id from Submission s where s.userId = :userId", Long.class)
                .setParameter("userId", userId)
                .getResultStream().collect(Collectors.toSet());
        List<DailyQuizItemResponse> fresh =
                items.stream().filter(i -> i.source() == DailyQuizSource.NEW).toList();
        assertThat(fresh).isNotEmpty();
        assertThat(fresh).allMatch(i -> !mySubmitted.contains(i.problem().id()));
    }

    @Test
    @DisplayName("진행률→완료→스트릭: 세트 문제만 진행률에 반영되고, 전부 풀면 완료 도장 + 스트릭이 이어진다")
    void progressCompletionAndStreak() {
        // 스트릭 사전 조건: 그저께·어제 세트를 완료 상태로 만들어 둔다(빈 세트 + 완료 도장 벌크 부여 —
        // completedAt의 유일한 정상 경로는 제출이지만, "과거에 완료했다"는 전제는 테스트가 이렇게 만드는 게 최단)
        dailyQuizRepository.save(DailyQuiz.of(userId, LocalDate.now().minusDays(2)));
        dailyQuizRepository.save(DailyQuiz.of(userId, LocalDate.now().minusDays(1)));
        em.flush();
        em.createQuery("update DailyQuiz d set d.completedAt = :t where d.userId = :userId")
                .setParameter("t", LocalDateTime.now().minusDays(1))
                .setParameter("userId", userId)
                .executeUpdate();
        em.clear();

        // 오늘 미완료여도 어제까지 이어졌으면 스트릭은 살아 있다(docs/12 — 아침부터 0을 보여주지 않는다)
        DailyQuizResponse today = getToday();
        assertThat(today.streak()).isEqualTo(2);

        List<DailyQuizItemResponse> items = today.items();
        Long firstProblemId = items.get(0).problem().id();

        // 1문제 풀면 solved 1 — 세트 밖 문제는 아무리 풀어도 진행률과 무관
        submit(firstProblemId, anyValidAnswer(items.get(0)));
        Problem outsider = newOx(Domain.SECURITY, "세트 밖 문제");
        submit(outsider.getId(), "O");
        // 같은 문제 재제출도 진행률 불변(첫 제출만 연결 — docs/12)
        submit(firstProblemId, anyValidAnswer(items.get(0)));
        DailyQuizResponse mid = getToday();
        assertThat(mid.progress().solved()).isEqualTo(1);
        assertThat(mid.completed()).isFalse();

        // 나머지 전부 풀면 → 완료 도장 + 스트릭이 오늘까지 3일로 늘어난다
        items.stream().skip(1).forEach(i -> submit(i.problem().id(), anyValidAnswer(i)));
        DailyQuizResponse done = getToday();
        assertThat(done.completed()).isTrue();
        assertThat(done.progress().solved()).isEqualTo(done.progress().total());
        assertThat(done.streak()).isEqualTo(3);

        // 완료 도장은 DB에도 찍혀 있다(스트릭 계산의 재료)
        DailyQuiz stored = dailyQuizRepository
                .findByUserIdAndQuizDate(userId, LocalDate.now()).orElseThrow();
        assertThat(stored.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("어제 세트는 오늘 세트와 별개다 — 날짜가 바뀌면 새 세트(리셋 정책)")
    void yesterdayIsSeparateSet() {
        // 어제 세트(미완료)가 있어도 오늘 조회는 오늘 날짜의 새 세트를 만든다
        dailyQuizRepository.save(DailyQuiz.of(userId, LocalDate.now().minusDays(1)));
        em.flush();

        DailyQuizResponse today = getToday();
        assertThat(today.quizDate()).isEqualTo(LocalDate.now());
        // 어제 미완료는 스트릭에도 안 잡힌다(완료만 센다)
        assertThat(today.streak()).isZero();
    }
}
