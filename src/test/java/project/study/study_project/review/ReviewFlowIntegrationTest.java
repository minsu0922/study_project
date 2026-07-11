package project.study.study_project.review;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;
import project.study.study_project.review.dto.ReviewListItem;
import project.study.study_project.review.dto.ReviewTodayItem;
import project.study.study_project.review.repository.ReviewItemRepository;
import project.study.study_project.review.service.ReviewService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복습 추천 통합 테스트 — 실제 DB(MySQL)로 전체 여정을 재생한다:
 * <b>틀림 → (다음 날) 오늘의 복습에 등장 → 맞힘 → 간격 증가 → 졸업 → 다시 틀리면 복귀</b>.
 *
 * <p>단위 테스트(ReviewServiceTest)가 전이 규칙을 검증했다면, 여기서는 단위 테스트가
 * 못 보는 것들을 본다: 제출 API와의 트랜잭션 연결(MANDATORY 합류), JPQL due 쿼리의 실동작,
 * 변경 감지 UPDATE가 실제로 커밋 대상에 오르는지.
 *
 * <p><b>시간 여행 방식</b>: "내일 등장"을 실제로 기다릴 수 없으니 {@code next_review_at}을
 * 벌크 UPDATE로 과거로 돌린다. 벌크 JPQL은 영속성 컨텍스트(1차 캐시)를 우회하므로
 * 반드시 flush(대기 중 변경 반영) → 벌크 UPDATE → clear(캐시 비움) 순서를 지킨다 —
 * 안 지키면 캐시의 옛 엔티티가 그대로 읽혀 시간 여행이 "안 먹은 것처럼" 보인다.
 *
 * <p>테스트 데이터는 매번 새 사용자(UUID 이메일)·새 문제를 만들고, 클래스의
 * {@code @Transactional}이 테스트 끝에 전부 롤백한다 — 로컬 DB를 더럽히지 않는다.
 * (MySQL 미가동이면 컨텍스트 로드 단계에서 실패한다 — contextLoads와 같은 전제)
 */
@SpringBootTest
@Transactional
class ReviewFlowIntegrationTest {

    @Autowired
    private QuizService quizService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewItemRepository reviewItemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @PersistenceContext
    private EntityManager em;

    private Long userId;
    private Problem problem;

    @BeforeEach
    void setUp() {
        // FK(user_id, problem_id)가 실제로 걸려 있으므로 부모 행을 진짜로 만든다.
        User user = userRepository.save(User.builder()
                .email("review-test-" + UUID.randomUUID() + "@test.local")
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
        problem = problemRepository.save(Problem.create(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP는 연결 지향 프로토콜이다.", "O", "3-way handshake로 연결을 만든다."));
    }

    /** 제출 헬퍼 — 실제 서비스 경로(채점→이력 저장→복습 훅)를 그대로 태운다. */
    private void submit(String answer) {
        quizService.submit(userId, new QuizSubmitRequest(problem.getId(), answer));
    }

    /** 시간 여행: 이 사용자 복습 항목의 next_review_at을 과거로 돌린다(클래스 주석의 flush→UPDATE→clear). */
    private void timeTravelToDue() {
        em.flush();
        em.createQuery("update ReviewItem r set r.nextReviewAt = :past where r.userId = :userId")
                .setParameter("past", LocalDateTime.now().minusMinutes(1))
                .setParameter("userId", userId)
                .executeUpdate();
        em.clear();
    }

    private ReviewItem myItem() {
        return reviewItemRepository.findByUserIdAndProblemId(userId, problem.getId()).orElseThrow();
    }

    private PageResponse<ReviewTodayItem> today() {
        return reviewService.getTodayReviews(userId, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("전체 여정: 틀림 → 내일 등장 → 맞힐 때마다 간격 증가 → 졸업 → 다시 틀리면 복귀")
    void fullReviewJourney() {
        // ── 1. 틀림 → 사다리 진입(stage 0). 복습은 "내일"이라 오늘 목록엔 아직 없다 ──
        submit("X");
        ReviewItem item = myItem();
        assertThat(item.getStage()).isZero();
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
        assertThat(item.getNextReviewAt()).isAfter(LocalDateTime.now()); // 약 1일 뒤
        assertThat(today().content()).isEmpty();

        // ── 2. 하루 뒤(시간 여행) → 오늘의 복습에 등장. 풀이용 형태(정답 없음) + 복습 메타 확인 ──
        timeTravelToDue();
        PageResponse<ReviewTodayItem> due = today();
        assertThat(due.content()).hasSize(1);
        ReviewTodayItem todayItem = due.content().get(0);
        assertThat(todayItem.problemId()).isEqualTo(problem.getId());
        assertThat(todayItem.domainLabel()).isEqualTo("네트워크");
        assertThat(todayItem.stage()).isZero();

        // ── 3. 맞힐 때마다 승급 — 간격이 1→3→7→14→30일로 벌어진다 ──
        for (int expectedStage = 1; expectedStage <= 4; expectedStage++) {
            LocalDateTime before = LocalDateTime.now();
            submit("O");
            ReviewItem promoted = myItem();
            assertThat(promoted.getStage()).isEqualTo(expectedStage);
            int days = ReviewService.INTERVAL_DAYS[expectedStage];
            assertThat(promoted.getNextReviewAt()).isAfterOrEqualTo(before.plusDays(days));
            // 승급했으니 예정일이 미래로 밀림 → 오늘의 복습에서 빠진다
            assertThat(today().content()).isEmpty();
            timeTravelToDue(); // 다음 복습일까지 또 시간 여행
        }

        // ── 4. 마지막 칸(stage 4)에서 맞힘 → 졸업. 추천에서 제외 ──
        submit("O");
        assertThat(myItem().getStatus()).isEqualTo(ReviewStatus.GRADUATED);
        timeTravelToDue(); // 시각을 과거로 돌려도
        assertThat(today().content()).isEmpty(); // 졸업생은 due 쿼리(status=LEARNING)에 안 걸린다

        // 복습 현황(/api/me/reviews)에는 졸업생도 남아 있고, due는 파생 계산으로 false
        PageResponse<ReviewListItem> all =
                reviewService.getMyReviews(userId, null, PageRequest.of(0, 20));
        assertThat(all.content()).hasSize(1);
        assertThat(all.content().get(0).status()).isEqualTo(ReviewStatus.GRADUATED);
        assertThat(all.content().get(0).due()).isFalse();

        // ── 5. 졸업 후 또 틀림 → stage 0부터 다시(기억은 영구 보증이 아니다) ──
        submit("X");
        ReviewItem relapsed = myItem();
        assertThat(relapsed.getStatus()).isEqualTo(ReviewStatus.LEARNING);
        assertThat(relapsed.getStage()).isZero();
    }

    @Test
    @DisplayName("사용자×문제당 1행 — 여러 번 틀려도 행이 늘지 않는다(UNIQUE 제약 + 단건 갱신)")
    void oneRowPerUserProblem() {
        submit("X");
        submit("X");
        submit("X");

        // Submission(이력)은 3건 쌓이지만 ReviewItem(상태)은 1건뿐이어야 한다.
        Long count = em.createQuery(
                        "select count(r) from ReviewItem r where r.userId = :userId", Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("맞히기만 한 문제는 사다리에 오르지 않는다 — 복습할 이유가 없으므로")
    void correctOnlyProblemNeverEntersLadder() {
        submit("O");
        assertThat(reviewItemRepository.findByUserIdAndProblemId(userId, problem.getId())).isEmpty();
    }
}
