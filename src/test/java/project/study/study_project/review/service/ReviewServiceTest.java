package project.study.study_project.review.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;
import project.study.study_project.review.repository.ReviewItemRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 간격 사다리 상태 전이 단위 테스트 — docs/10의 전이 표 6칸을 전부 검증한다.
 *
 * <p><b>왜 Mockito 단위 테스트인가</b>: 전이 규칙은 순수 자바 로직(서비스+엔티티)이라
 * DB 없이도 완전히 검증된다. DB가 필요한 부분(UNIQUE 제약, due 쿼리, 백필)은
 * 통합 테스트(ReviewFlowIntegrationTest)가 따로 맡는다 — 각 테스트가 자기 층만 책임.
 *
 * <p><b>시각 검증 방식</b>: 서비스가 내부에서 {@code LocalDateTime.now()}를 부르므로 정확한
 * 시각 비교 대신 "호출 전 now+N일 ≤ 결과 ≤ 호출 후 now+N일" 구간 검증을 쓴다.
 * (Clock 주입으로 시각을 고정할 수도 있지만, 일 단위 간격 검증에는 구간 방식이 충분해서
 * 빈 구성 추가라는 비용을 지불하지 않았다 — 분/초 단위 정밀도가 필요해지면 그때 도입)
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ReviewItemRepository reviewItemRepository;

    private ReviewService reviewService;
    private Problem problem;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewItemRepository);
        // OX 문제 하나면 충분 — 전이 규칙은 문제 타입과 무관하다(채점 결과 boolean만 본다).
        problem = Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP는 연결 지향 프로토콜이다.", "O", "3-way handshake로 연결을 만든다.");
    }

    /** found 상황을 만드는 헬퍼 — 리포지토리가 이 항목을 돌려주도록 스텁. */
    private void givenExisting(ReviewItem item) {
        when(reviewItemRepository.findByUserIdAndProblemId(anyLong(), any()))
                .thenReturn(Optional.of(item));
    }

    private void givenNoItem() {
        when(reviewItemRepository.findByUserIdAndProblemId(anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    /** stage N까지 올라간 LEARNING 항목을 만든다(오답 1번 + 정답 N번의 실제 전이를 재사용). */
    private ReviewItem learningItemAtStage(int stage) {
        ReviewItem item = ReviewItem.firstWrong(USER_ID, problem, LocalDateTime.now().plusDays(1));
        for (int s = 1; s <= stage; s++) {
            item.promote(s, LocalDateTime.now().plusDays(ReviewService.INTERVAL_DAYS[s]));
        }
        return item;
    }

    /** 결과 시각이 "기준 구간 + days일" 안에 있는지 — 클래스 주석의 구간 검증 방식. */
    private static void assertAboutDaysLater(LocalDateTime actual, LocalDateTime before, int days) {
        assertThat(actual).isAfterOrEqualTo(before.plusDays(days));
        assertThat(actual).isBeforeOrEqualTo(LocalDateTime.now().plusDays(days));
    }

    @Nested
    @DisplayName("오답 — 전이 표 윗줄")
    class WrongAnswer {

        @Test
        @DisplayName("항목 없음 → stage 0, LEARNING으로 생성. 첫 복습은 1일 뒤")
        void createsItemOnFirstWrong() {
            givenNoItem();
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            ArgumentCaptor<ReviewItem> captor = ArgumentCaptor.forClass(ReviewItem.class);
            verify(reviewItemRepository).save(captor.capture());
            ReviewItem saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getStage()).isZero();
            assertThat(saved.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(saved.getReviewCount()).isZero(); // 사다리 "진입"은 풀이 횟수로 안 센다
            assertAboutDaysLater(saved.getNextReviewAt(), before, 1);
        }

        @Test
        @DisplayName("LEARNING(stage 2) → stage 0으로 리셋, 다음 복습 1일 뒤")
        void resetsLearningItemToStageZero() {
            ReviewItem item = learningItemAtStage(2);
            int countBefore = item.getReviewCount();
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStage()).isZero();
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getReviewCount()).isEqualTo(countBefore + 1);
            assertAboutDaysLater(item.getNextReviewAt(), before, 1);
            verify(reviewItemRepository, never()).save(any()); // 기존 행 변경 감지에 맡긴다(INSERT 없음)
        }

        @Test
        @DisplayName("GRADUATED → LEARNING으로 복귀 + stage 0 (기억은 영구 보증이 아니다)")
        void graduatedItemReturnsToLadder() {
            ReviewItem item = learningItemAtStage(4);
            item.graduate();
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED); // 전제 확인
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getStage()).isZero();
            assertAboutDaysLater(item.getNextReviewAt(), before, 1);
        }
    }

    @Nested
    @DisplayName("정답 — 전이 표 아랫줄")
    class CorrectAnswer {

        @Test
        @DisplayName("항목 없음 → 아무것도 안 함 (틀린 적 없는 문제는 사다리에 안 올린다)")
        void doesNothingWhenNeverWrong() {
            givenNoItem();

            reviewService.onSubmission(USER_ID, problem, true);

            verify(reviewItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("LEARNING(stage 0) → stage 1 승급, 다음 복습 3일 뒤")
        void promotesToNextStage() {
            ReviewItem item = learningItemAtStage(0);
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStage()).isEqualTo(1);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getReviewCount()).isEqualTo(1);
            assertAboutDaysLater(item.getNextReviewAt(), before, 3); // 사다리 두 번째 칸 간격
        }

        @Test
        @DisplayName("LEARNING(stage 4, 마지막 칸) → 졸업. 추천에서 제외된다")
        void graduatesAtLastStage() {
            ReviewItem item = learningItemAtStage(4);
            int countBefore = item.getReviewCount();
            givenExisting(item);

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
            assertThat(item.getStage()).isEqualTo(4); // 졸업 시점의 칸을 그대로 기록
            assertThat(item.getReviewCount()).isEqualTo(countBefore + 1);
        }

        @Test
        @DisplayName("GRADUATED → 그대로 (졸업 후 또 맞혀도 변화 없음)")
        void graduatedItemStaysUnchanged() {
            ReviewItem item = learningItemAtStage(4);
            item.graduate();
            int countBefore = item.getReviewCount();
            LocalDateTime nextBefore = item.getNextReviewAt();
            givenExisting(item);

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
            assertThat(item.getReviewCount()).isEqualTo(countBefore); // 사다리 밖 풀이는 안 센다
            assertThat(item.getNextReviewAt()).isEqualTo(nextBefore);
        }
    }

    @Test
    @DisplayName("전체 여정: 틀림 → 정답 5연속 = 간격 1→3→7→14→30일로 벌어지다 졸업")
    void fullLadderJourney() {
        // 사다리 여정을 한 번에 재생 — 각 칸 간격이 INTERVAL_DAYS와 정확히 일치하는지 본다.
        ReviewItem item = ReviewItem.firstWrong(USER_ID, problem, LocalDateTime.now().plusDays(1));
        givenExisting(item);

        // 정답 4번: stage 1..4 승급, 간격 3/7/14/30일 확인
        for (int expectedStage = 1; expectedStage <= 4; expectedStage++) {
            LocalDateTime before = LocalDateTime.now();
            reviewService.onSubmission(USER_ID, problem, true);
            assertThat(item.getStage()).isEqualTo(expectedStage);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertAboutDaysLater(item.getNextReviewAt(), before,
                    ReviewService.INTERVAL_DAYS[expectedStage]);
        }

        // 5번째 정답(마지막 칸) → 졸업, 총 5회 풀이 기록
        reviewService.onSubmission(USER_ID, problem, true);
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
        assertThat(item.getReviewCount()).isEqualTo(5);
    }
}
