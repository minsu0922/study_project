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
 * <p><b>시각 검증 방식</b>: 서비스가 내부에서 {@code LocalDateTime.now()}를 부르므로, 전이
 * 테스트는 기대 시각을 {@link ReviewService#dueAfter}로 직접 계산해 맞춰 본다. 규칙 자체의
 * 경계값(밤 11시·새벽 1시·4시 정각)은 {@code now()}에 기대면 검증할 수 없어서, 시각을
 * 인자로 받는 그 순수 함수를 {@link StudyDayBoundary}에서 따로 때린다 — Clock 빈을 주입하는
 * 대신 함수를 순수하게 만들어 같은 목적을 달성했다.
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
                "TCP의 연결 지향 성질",
                "TCP는 연결 지향 프로토콜이다.", "O", "3-way handshake로 연결을 만든다.", null);
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
        ReviewItem item = ReviewItem.firstWrong(USER_ID, problem,
                ReviewService.dueAfter(LocalDateTime.now(), 1));
        for (int s = 1; s <= stage; s++) {
            item.promote(s, ReviewService.dueAfter(LocalDateTime.now(), ReviewService.INTERVAL_DAYS[s]));
        }
        return item;
    }

    /**
     * 결과 시각이 "days일 뒤 학습일이 열리는 시각"인지.
     *
     * <p>후보를 둘 받는 이유: 서비스가 부르는 {@code now()}와 테스트의 {@code before} 사이에
     * 학습일 경계(새벽 4시)가 끼면 기대값이 하루 갈린다. 실제로 그 순간에 걸릴 일은 거의
     * 없지만, "1년에 한 번 새벽에 깨지는 테스트"는 원인을 찾는 데 드는 시간이 훨씬 비싸다.
     */
    private static void assertDueOnStudyDayAfter(LocalDateTime actual, LocalDateTime before, int days) {
        assertThat(actual).isIn(ReviewService.dueAfter(before, days),
                ReviewService.dueAfter(LocalDateTime.now(), days));
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
            assertDueOnStudyDayAfter(saved.getNextReviewAt(), before, 1);
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
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 1);
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
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 1);
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
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 3); // 사다리 두 번째 칸 간격
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
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before,
                    ReviewService.INTERVAL_DAYS[expectedStage]);
        }

        // 5번째 정답(마지막 칸) → 졸업, 총 5회 풀이 기록
        reviewService.onSubmission(USER_ID, problem, true);
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
        assertThat(item.getReviewCount()).isEqualTo(5);
    }

    /**
     * 학습일 경계 규칙({@link ReviewService#dueAfter}) — "며칠 뒤"를 24시간이 아니라
     * 학습일로 세는지. 시각을 인자로 받는 순수 함수라 경계값을 직접 넣어 볼 수 있다.
     */
    @Nested
    @DisplayName("학습일 경계 — '며칠 뒤'는 24시간이 아니다")
    class StudyDayBoundary {

        @Test
        @DisplayName("저녁에 틀리면 다음 날 새벽 4시부터 복습 대상")
        void eveningWrongIsDueNextMorning() {
            LocalDateTime evening = LocalDateTime.of(2026, 9, 5, 21, 0);

            assertThat(ReviewService.dueAfter(evening, 1))
                    .isEqualTo(LocalDateTime.of(2026, 9, 6, 4, 0));
        }

        @Test
        @DisplayName("밤 11시에 틀려도 마찬가지 — 다음 날 밤 11시까지 기다리지 않는다")
        void lateNightWrongDoesNotSlipToNextNight() {
            LocalDateTime lateNight = LocalDateTime.of(2026, 9, 5, 23, 0);

            assertThat(ReviewService.dueAfter(lateNight, 1))
                    .isEqualTo(LocalDateTime.of(2026, 9, 6, 4, 0));
        }

        @Test
        @DisplayName("자정을 넘긴 새벽 1시는 아직 '어제의 공부' — 오늘 새벽 4시가 다음 차례")
        void pastMidnightStillCountsAsPreviousStudyDay() {
            // 이 규칙이 없으면 밤을 새우는 사람은 한자리에서 이틀 치 복습을 받게 된다.
            LocalDateTime pastMidnight = LocalDateTime.of(2026, 9, 6, 1, 0);

            assertThat(ReviewService.dueAfter(pastMidnight, 1))
                    .isEqualTo(LocalDateTime.of(2026, 9, 6, 4, 0));
        }

        @Test
        @DisplayName("새벽 4시 정각부터 새 학습일 — 3시 59분과 하루가 갈린다")
        void rolloverHourStartsNewStudyDay() {
            LocalDateTime justBefore = LocalDateTime.of(2026, 9, 6, 3, 59);
            LocalDateTime exactly = LocalDateTime.of(2026, 9, 6, 4, 0);

            assertThat(ReviewService.dueAfter(justBefore, 1))
                    .isEqualTo(LocalDateTime.of(2026, 9, 6, 4, 0)); // 아직 9/5의 학습일
            assertThat(ReviewService.dueAfter(exactly, 1))
                    .isEqualTo(LocalDateTime.of(2026, 9, 7, 4, 0)); // 여기부터 9/6의 학습일
        }

        @Test
        @DisplayName("사다리 위쪽 칸도 같은 규칙 — 3·7·14·30일 뒤 학습일이 열리는 시각")
        void appliesToEveryRungOfTheLadder() {
            LocalDateTime evening = LocalDateTime.of(2026, 9, 5, 21, 0);

            assertThat(ReviewService.dueAfter(evening, 3)).isEqualTo(LocalDateTime.of(2026, 9, 8, 4, 0));
            assertThat(ReviewService.dueAfter(evening, 7)).isEqualTo(LocalDateTime.of(2026, 9, 12, 4, 0));
            assertThat(ReviewService.dueAfter(evening, 14)).isEqualTo(LocalDateTime.of(2026, 9, 19, 4, 0));
            assertThat(ReviewService.dueAfter(evening, 30)).isEqualTo(LocalDateTime.of(2026, 10, 5, 4, 0));
        }

        @Test
        @DisplayName("회귀: 매일 비슷한 시간에 공부하는 사용자가 어제 틀린 문제를 오늘 만난다")
        void regularLearnerSeesYesterdaysMistakeToday() {
            // 이 테스트가 이 수정의 이유 그 자체다.
            // 어제 밤 10시 30분에 틀렸고, 오늘도 밤 9시에 앉았다.
            LocalDateTime wrongLastNight = LocalDateTime.of(2026, 9, 4, 22, 30);
            LocalDateTime studyingTonight = LocalDateTime.of(2026, 9, 5, 21, 0);

            // 지금 규칙: 오늘 새벽 4시에 이미 열렸으므로 저녁에 들어오면 복습할 게 있다.
            assertThat(ReviewService.dueAfter(wrongLastNight, 1)).isBefore(studyingTonight);

            // 예전 규칙(now.plusDays(1) = 정확히 24시간)이었다면 9/5 22:30이 예정 시각이라,
            // 밤 9시에 들어온 이 사용자는 "오늘 복습할 문제가 없어요"를 봤다 — 1시간 30분 차이로.
            assertThat(wrongLastNight.plusDays(1)).isAfter(studyingTonight);
        }
    }
}
