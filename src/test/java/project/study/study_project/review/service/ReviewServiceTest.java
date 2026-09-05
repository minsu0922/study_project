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

    /** "복습 차례가 이미 지났다"를 뜻하는 예정 시각 — 1분 전이면 충분하다. */
    private static final LocalDateTime DUE_NOW = LocalDateTime.now().minusMinutes(1);

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

    /**
     * stage N까지 올라간 LEARNING 항목을 만든다(오답 1번 + 정답 N번의 실제 전이를 재사용).
     *
     * <p><b>예정일은 과거로 둔다</b> — 승급은 "복습할 때가 됐을 때"만 일어나므로(2026-09-05),
     * 아무 준비 없이 만든 항목은 전부 예정일 전이라 승급 테스트가 성립하지 않는다. 즉 이
     * 헬퍼가 만드는 것은 "사다리 N칸에 있고 <b>지금 복습 차례인</b> 문제"다.
     * 예정일 전 상태가 필요한 테스트는 {@link EarlyAnswer}가 따로 만든다.
     */
    private ReviewItem learningItemAtStage(int stage) {
        ReviewItem item = ReviewItem.firstWrong(USER_ID, problem, DUE_NOW);
        for (int s = 1; s <= stage; s++) {
            item.promote(s, DUE_NOW);
        }
        return item;
    }

    /**
     * 졸업한 항목을 만든다.
     *
     * @param recheckAt 재확인 예정 시각 — 미래면 "졸업하고 아직 확인할 때가 아님",
     *                  과거면 "오래 안 봐서 다시 확인할 때가 됨"(2026-09-05)
     */
    private ReviewItem graduatedItem(LocalDateTime recheckAt) {
        ReviewItem item = learningItemAtStage(4);
        item.graduate(recheckAt);
        return item;
    }

    /** 재확인은 아직 한참 남았다 — 갓 졸업한 상태를 뜻한다. */
    private static LocalDateTime recheckFarOff() {
        return LocalDateTime.now().plusDays(ReviewService.GRADUATION_RECHECK_DAYS);
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
        @DisplayName("LEARNING(stage 2) → stage 1로 강등, 다음 복습 1일 뒤")
        void demotesLearningItemOneRung() {
            // 2026-09-05 이전에는 stage 0으로 리셋했다. 칸별 규칙은 Demotion에서 자세히 본다.
            ReviewItem item = learningItemAtStage(2);
            int countBefore = item.getReviewCount();
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStage()).isEqualTo(1);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getReviewCount()).isEqualTo(countBefore + 1);
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 1);
            verify(reviewItemRepository, never()).save(any()); // 기존 행 변경 감지에 맡긴다(INSERT 없음)
        }

        @Test
        @DisplayName("GRADUATED → LEARNING으로 복귀 + stage 0 (기억은 영구 보증이 아니다)")
        void graduatedItemReturnsToLadder() {
            // 졸업생만 맨 아래로 떨어진다 — 학습 중 항목은 한 칸만 내려간다(아래 Demotion 참고).
            ReviewItem item = graduatedItem(recheckFarOff());
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
        @DisplayName("GRADUATED → 그대로 (재확인일 전에 또 맞혀도 변화 없음)")
        void graduatedItemStaysUnchanged() {
            ReviewItem item = graduatedItem(recheckFarOff());
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
    @DisplayName("전체 여정: 복습일이 될 때마다 맞히면 간격이 1→3→7→14→30일로 벌어지다 졸업")
    void fullLadderJourney() {
        // 칸마다 "복습 차례가 된 항목"을 새로 만든다. 한 항목으로 연달아 돌리지 <않는> 것이
        // 곧 규칙이다 — 예정일 전 정답은 승급하지 않으므로, 실제로도 칸을 오르려면 날이 바뀌어야
        // 한다(그렇게 연달아 돌아가던 것이 2026-09-05에 막은 조기 승급 구멍이었다).
        for (int stage = 0; stage <= 3; stage++) {
            ReviewItem item = learningItemAtStage(stage);
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStage()).isEqualTo(stage + 1);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before,
                    ReviewService.INTERVAL_DAYS[stage + 1]);
        }

        // 마지막 칸(stage 4)에서 맞히면 졸업 — 사다리에 오른 뒤 총 5회 풀이가 찍힌다
        // (승급 4번 + 졸업 1번. 사다리 진입이 된 최초 오답은 세지 않는다).
        ReviewItem lastRung = learningItemAtStage(4);
        givenExisting(lastRung);

        reviewService.onSubmission(USER_ID, problem, true);

        assertThat(lastRung.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
        assertThat(lastRung.getReviewCount()).isEqualTo(5);
    }

    /**
     * 오답 강등 규칙(2026-09-05) — 학습 중 항목은 한 칸만 내려간다. 전에는 맨 아래로
     * 리셋했는데, 30일 칸에서 한 번 미끄러지면 다시 55일이라 사다리를 오를 의욕이 꺾였다.
     */
    @Nested
    @DisplayName("오답 강등 — 한 칸만 내려간다")
    class Demotion {

        @Test
        @DisplayName("stage 4에서 틀리면 stage 3으로 (맨 아래가 아니다)")
        void wrongAnswerDropsOneRungOnly() {
            ReviewItem item = learningItemAtStage(4);
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStage()).isEqualTo(3);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            // 강등된 칸(14일)이 아니라 다음 학습일이다 — 방금 모른다고 확인된 문제를
            // 14일 뒤에 보자는 건 앞뒤가 안 맞는다.
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 1);
        }

        @Test
        @DisplayName("stage 0에서 틀려도 음수로 내려가지 않는다")
        void stageNeverGoesBelowZero() {
            ReviewItem item = learningItemAtStage(0);
            givenExisting(item);

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStage()).isZero();
        }

        @Test
        @DisplayName("연달아 틀리면 한 칸씩 내려와 결국 맨 아래에 닿는다")
        void repeatedMistakesWalkDownTheLadder() {
            // 한 칸 강등이 "틀려도 벌이 없다"는 뜻은 아니다 — 계속 틀리면 결국 처음으로 온다.
            ReviewItem item = learningItemAtStage(4);
            givenExisting(item);

            assertThat(item.getStage()).isEqualTo(4);
            for (int expected : new int[]{3, 2, 1, 0, 0}) {
                reviewService.onSubmission(USER_ID, problem, false);
                assertThat(item.getStage()).isEqualTo(expected);
            }
        }
    }

    /**
     * 졸업 재확인(2026-09-05) — 졸업은 영구 퇴장이 아니다. 재확인 예정일이 지나면
     * 복습 화면이 "오래 안 본 문제"로 다시 꺼내 준다.
     */
    @Nested
    @DisplayName("졸업 재확인 — 졸업이 영구 퇴장은 아니다")
    class GraduationRecheck {

        @Test
        @DisplayName("졸업할 때 재확인 예정일이 함께 심긴다")
        void graduationPlantsRecheckDate() {
            ReviewItem item = learningItemAtStage(4);
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED);
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before,
                    ReviewService.GRADUATION_RECHECK_DAYS);
        }

        @Test
        @DisplayName("재확인일이 지난 뒤 맞히면 다음 재확인으로 미뤄진다 (졸업 유지)")
        void passingRecheckPushesNextRecheck() {
            ReviewItem item = graduatedItem(DUE_NOW); // 오래 안 봐서 확인할 때가 됐다
            int countBefore = item.getReviewCount();
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.GRADUATED); // 졸업은 유지
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before,
                    ReviewService.GRADUATION_RECHECK_DAYS);
            // 졸업 뒤의 풀이는 세지 않는다 — reviewCount는 "졸업까지 몇 번"이라는 뜻이므로.
            assertThat(item.getReviewCount()).isEqualTo(countBefore);
        }

        @Test
        @DisplayName("재확인 때 틀리면 사다리 맨 아래로 돌아온다")
        void failingRecheckReturnsToLadder() {
            ReviewItem item = graduatedItem(DUE_NOW);
            givenExisting(item);

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getStage()).isZero(); // 졸업생은 한 칸 강등이 아니라 처음부터
        }
    }

    /**
     * 예정일 전 정답 — 사다리를 움직이지 않는다(2026-09-05). 문제를 지목해 푸는 경로가 생기면서
     * 같은 문제를 연달아 맞혀 몇 초 만에 졸업시킬 수 있었던 구멍을 막은 규칙이다.
     */
    @Nested
    @DisplayName("예정일 전 풀이 — 조기 승급을 막는다")
    class EarlyAnswer {

        /** 아직 복습할 때가 아닌 항목(stage 1, 예정일은 사흘 뒤). */
        private ReviewItem notDueYet() {
            ReviewItem item = ReviewItem.firstWrong(USER_ID, problem, DUE_NOW);
            item.promote(1, LocalDateTime.now().plusDays(3));
            return item;
        }

        @Test
        @DisplayName("예정일 전 정답 → 단계·예정일·풀이 횟수 모두 그대로")
        void earlyCorrectAnswerChangesNothing() {
            ReviewItem item = notDueYet();
            int stageBefore = item.getStage();
            int countBefore = item.getReviewCount();
            LocalDateTime dueBefore = item.getNextReviewAt();
            givenExisting(item);

            reviewService.onSubmission(USER_ID, problem, true);

            assertThat(item.getStage()).isEqualTo(stageBefore);
            assertThat(item.getNextReviewAt()).isEqualTo(dueBefore);
            // 사다리를 움직이지 않은 풀이는 세지 않는다 — "졸업까지 몇 번 걸렸나"가 흐려지므로.
            assertThat(item.getReviewCount()).isEqualTo(countBefore);
        }

        @Test
        @DisplayName("회귀: 같은 문제를 연달아 맞혀도 졸업하지 못한다")
        void repeatedCorrectAnswersCannotGraduate() {
            // 이 테스트가 이 규칙의 존재 이유다. 예전에는 /quiz.html?problemId=N 으로 같은 문제를
            // 다섯 번 맞히면 30일 칸까지 올라간 문제가 1분 만에 추천에서 사라졌다.
            ReviewItem item = learningItemAtStage(0); // 지금 복습 차례인 문제
            givenExisting(item);

            for (int i = 0; i < 10; i++) {
                reviewService.onSubmission(USER_ID, problem, true);
            }

            // 첫 제출만 먹힌다 — 승급하는 순간 예정일이 미래로 밀려 나머지는 전부 "예정일 전"이 된다.
            assertThat(item.getStage()).isEqualTo(1);
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertThat(item.getReviewCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("예정일 전 오답은 그대로 리셋 — 모른다는 신호에는 언제든 반응한다")
        void earlyWrongAnswerStillResets() {
            // 비대칭이 의도다. 방금 본 문제를 맞히는 건 기억이 남아 있다는 뜻이라 정보가 거의
            // 없지만, 틀리는 건 언제 나와도 "모른다"는 확실한 신호다.
            ReviewItem item = notDueYet();
            givenExisting(item);
            LocalDateTime before = LocalDateTime.now();

            reviewService.onSubmission(USER_ID, problem, false);

            assertThat(item.getStage()).isZero();
            assertThat(item.getStatus()).isEqualTo(ReviewStatus.LEARNING);
            assertDueOnStudyDayAfter(item.getNextReviewAt(), before, 1);
        }
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
