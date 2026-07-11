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
     * 졸업 후에는 의미 없는 값이지만 NOT NULL 유지 — 조회가 항상 status로 먼저 거르므로 무해하다.
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
     * @param nextReviewAt 다음 복습 예정 시각(서비스가 첫 칸 간격으로 계산)
     */
    public void resetToStart(LocalDateTime nextReviewAt) {
        this.stage = 0;
        this.status = ReviewStatus.LEARNING;
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
     * 마지막 칸에서 정답 — 졸업. 더는 복습 추천에 나오지 않는다.
     * stage는 마지막 칸 그대로 둔다(졸업 시점의 위치 기록 — 되돌릴 때는 어차피 stage 0부터).
     */
    public void graduate() {
        this.status = ReviewStatus.GRADUATED;
        this.reviewCount++;
    }
}
