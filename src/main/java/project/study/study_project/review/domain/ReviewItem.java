package project.study.study_project.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;

/**
 * 복습 항목 — DB의 {@code review_item} 테이블과 대응(문서 10, ADR-0004).
 *
 * <p>사용자 × 문제당 <b>딱 1행</b>의 "현재 복습 상태"다. 이력이 아니다 — 이력은 계속
 * Submission이 담당하고, 이 엔티티는 간격 사다리(stage 0..4)의 어느 칸에 있는지만 기억한다.
 * 1행 보장은 코드가 아니라 DB의 UNIQUE 제약(uk_reviewitem_user_problem)이 한다.
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code userId}는 연관관계 없이 Long 컬럼</b> — Submission과 같은 이유. 조회가 항상
 *       "토큰에서 꺼낸 내 id" 필터일 뿐 User의 다른 필드를 읽을 일이 없다.
 *   <li><b>{@code problem}은 {@code @ManyToOne} LAZY 연관</b> — 복습 추천 목록에서 지문·보기를
 *       함께 보여줘야 하므로 객체 탐색이 실제로 필요하다.
 *   <li><b>상태 전이 메서드({@link #resetToStart}/{@link #promote}/{@link #graduate})만 공개</b>하고
 *       필드 setter는 없다 — "stage만 바뀌고 next_review_at은 안 바뀐" 식의 어중간한 상태를
 *       만들 수 없게, 유효한 전이 단위로만 변경을 허용한다.
 *   <li><b>간격 값(1/3/7/14/30일)은 여기 없다</b> — 며칠 뒤로 미룰지는 정책이라 ReviewService의
 *       상수가 결정하고, 엔티티는 계산된 시각을 받아 저장만 한다(정책과 저장의 분리, docs/10).
 * </ul>
 */
@Entity
@Table(name = "review_item")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 복습하는 사용자 id — JWT의 sub에서 꺼낸 값만 넣는다(요청 본문 값은 신뢰하지 않음, docs/06). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 사다리 칸(0..4). 칸이 높을수록 다음 복습까지의 간격이 길다(1→3→7→14→30일). */
    @Column(nullable = false)
    private int stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ReviewStatus status;

    /** 사다리에 오른 뒤 푼 횟수 — "졸업까지 몇 번 걸렸나" 통계용. 최초 오답(사다리 진입)은 세지 않는다. */
    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    /**
     * 다음 복습 예정 시각. "복습할 때가 됐는지(due)"는 이 값과 현재 시각의 비교로 조회 시점에
     * 계산한다 — 상태로 저장하지 않는다(ReviewStatus 주석 참고).
     *
     * <p>졸업 후에는 <b>재확인 예정 시각</b>이 된다(2026-09-05, {@link #graduate} 참고).
     * 한 컬럼이 상태에 따라 두 가지를 뜻하는 셈인데, 둘 다 "이 문제를 다음에 언제 볼까"라는
     * 같은 질문의 답이라 컬럼을 나누지 않았다. 나누면 어느 쪽을 봐야 하는지 매번 status를
     * 확인해야 하고, NOT NULL이던 값이 nullable 둘로 늘어난다.
     */
    @Column(name = "next_review_at", nullable = false)
    private LocalDateTime nextReviewAt;

    /** 처음 틀린 시각(사다리에 오른 시각). */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private ReviewItem(Long userId, Problem problem, LocalDateTime nextReviewAt) {
        this.userId = userId;
        this.problem = problem;
        this.stage = 0;
        this.status = ReviewStatus.LEARNING;
        this.reviewCount = 0;
        this.nextReviewAt = nextReviewAt;
    }

    /**
     * 처음 틀린 문제를 사다리에 올린다 — stage 0, LEARNING으로 시작.
     * 정적 팩터리로만 생성해 "사다리 밖의 ReviewItem"이라는 상태가 존재하지 않게 한다.
     *
     * @param nextReviewAt 첫 복습 예정 시각(서비스가 사다리 첫 칸 간격으로 계산해서 넘긴다)
     */
    public static ReviewItem firstWrong(Long userId, Problem problem, LocalDateTime nextReviewAt) {
        return new ReviewItem(userId, problem, nextReviewAt);
    }

    /**
     * 오답 — 사다리 맨 아래로 리셋. 졸업했던 문제도 다시 LEARNING으로 복귀한다
     * ("기억은 영구 보증이 아니다", docs/10).
     *
     * <p><b>2026-09-05부터 이 메서드는 졸업생이 틀렸을 때만 쓴다.</b> 학습 중인 항목은
     * 한 칸만 내리는 {@link #demote}로 간다 — 30일 칸까지 올라간 문제를 한 번 미끄러졌다고
     * 맨 아래로 떨어뜨리면 다시 55일이 걸려서, 사용자가 사다리를 오를 의욕을 잃는다.
     * 졸업생은 다르다. 오래전에 통과한 뒤 통째로 잊은 것이므로 처음부터가 맞다.
     *
     * @param nextReviewAt 다음 복습 예정 시각(서비스가 첫 칸 간격으로 계산)
     */
    public void resetToStart(LocalDateTime nextReviewAt) {
        this.stage = 0;
        this.status = ReviewStatus.LEARNING;
        this.nextReviewAt = nextReviewAt;
        this.reviewCount++;
    }

    /**
     * 오답 — 한 칸 아래로 강등(2026-09-05). 학습 중인 항목이 틀렸을 때의 기본 처리다.
     *
     * <p>{@link #promote}와 하는 일이 같은데 이름을 나눈 이유: 호출부에서 <b>전이 방향이
     * 드러나야</b> 상태 전이 표와 코드를 나란히 읽을 수 있다. 하나로 합치면
     * {@code promote(stage - 1, ...)} 같은 "승급인데 칸이 내려간다"는 문장이 생긴다.
     *
     * @param nextStage    강등 후 칸(0 미만이 되지 않게 계산하는 것은 사다리를 아는 서비스 몫)
     * @param nextReviewAt 다음 복습 예정 시각 — 틀렸으므로 첫 칸 간격(다음 학습일)이다.
     *                     강등된 칸의 간격이 아니다: 방금 모른다고 확인된 문제를 14일 뒤에
     *                     다시 보자는 건 앞뒤가 안 맞는다
     */
    public void demote(int nextStage, LocalDateTime nextReviewAt) {
        this.stage = nextStage;
        this.nextReviewAt = nextReviewAt;
        this.reviewCount++;
    }

    /**
     * 정답 — 다음 칸으로 승급. 복습 간격이 그만큼 멀어진다.
     *
     * @param nextStage    승급 후 칸(현재 stage + 1 — 계산은 사다리를 아는 서비스가 한다)
     * @param nextReviewAt 다음 복습 예정 시각(승급 후 칸의 간격으로 계산)
     */
    public void promote(int nextStage, LocalDateTime nextReviewAt) {
        this.stage = nextStage;
        this.nextReviewAt = nextReviewAt;
        this.reviewCount++;
    }

    /**
     * 마지막 칸에서 정답 — 졸업. "오늘의 복습"에는 더 나오지 않는다.
     * stage는 마지막 칸 그대로 둔다(졸업 시점의 위치 기록 — 되돌릴 때는 어차피 stage 0부터).
     *
     * <p><b>재확인 예정일을 함께 받는다(2026-09-05)</b>. 전에는 졸업하면
     * {@code nextReviewAt}을 졸업 전 값 그대로 두고 "의미 없는 값"으로 취급했다. 그랬더니
     * 졸업이 <b>영구 퇴장</b>이 됐다 — 다시 사다리에 오르는 유일한 길이 "그 문제를 어쩌다 또
     * 틀리는 것"인데, 무작위 퀴즈에서 그 문제가 다시 뽑힐 운에 기대야 했다. 30일 뒤에 잊는
     * 기억이 60일 뒤라고 남아 있으리란 법이 없다.
     *
     * <p>이제 졸업 시각에 재확인 예정일을 심어 둔다. 이 날짜가 지나면 복습 화면이 "오래 안 본
     * 문제"로 다시 꺼내 준다. <b>"오늘의 복습" 목록에는 넣지 않는다</b> — 밀린 복습과 섞으면
     * 당장 해야 할 일이 흐려지므로, 재확인은 여유 있을 때 하는 별도 권유로 남긴다.
     *
     * @param nextRecheckAt 다시 확인해 볼 시각(간격은 정책이라 서비스가 계산해서 넘긴다)
     */
    public void graduate(LocalDateTime nextRecheckAt) {
        this.status = ReviewStatus.GRADUATED;
        this.nextReviewAt = nextRecheckAt;
        this.reviewCount++;
    }

    /**
     * 졸업생 재확인 통과 — 졸업 상태를 유지하고 다음 재확인만 미룬다(2026-09-05).
     *
     * <p>{@code reviewCount}를 올리지 않는 이유: 그 값의 뜻이 "졸업<b>까지</b> 몇 번 걸렸나"라
     * 졸업 뒤의 풀이를 세면 통계가 흐려진다(예정일 전 정답을 세지 않는 것과 같은 이유).
     *
     * @param nextRecheckAt 다음 재확인 시각
     */
    public void recheckPassed(LocalDateTime nextRecheckAt) {
        this.nextReviewAt = nextRecheckAt;
    }
}
