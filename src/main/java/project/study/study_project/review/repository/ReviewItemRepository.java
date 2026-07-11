package project.study.study_project.review.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ReviewItem 저장소 — 제출 시 상태 전이용 단건 조회 + 복습 목록 페이징 조회(docs/10).
 *
 * <p>두 목록 쿼리 공통 설계(오답노트 findLatestWrongAnswers와 같은 패턴):
 * <ul>
 *   <li><b>{@code join fetch r.problem}</b>: 목록에서 문제 지문(객관식은 보기까지)을 보여주므로
 *       Problem을 반드시 함께 읽는다. @ManyToOne fetch join은 행이 불어나지 않아
 *       페이징과 충돌하지 않는다(컬렉션 fetch join과 다름).
 *   <li>fetch join + 페이징 조합에서는 Spring Data가 count 쿼리를 못 만들어
 *       {@code countQuery}를 별도로 명시했다(fetch 없이 개수만 센다).
 *   <li>정렬은 쿼리에 고정하고 Pageable의 sort는 받지 않는다 — 스펙(docs/10)의 정렬이
 *       하나뿐이라 sort 파라미터를 열면 혼란만 생긴다(오답노트와 같은 판단).
 * </ul>
 */
public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    /**
     * 제출 훅(ReviewService.onSubmission)용 단건 조회 — 사용자×문제당 1행이므로 Optional.
     * UNIQUE 제약(uk_reviewitem_user_problem)이 인덱스를 겸해 이 조회를 받쳐 준다
     * (로드맵 1에서 배운 "UNIQUE = 제약 + 인덱스 겸용").
     */
    Optional<ReviewItem> findByUserIdAndProblemId(Long userId, Long problemId);

    /**
     * 오늘의 복습: 복습 예정 시각이 지난 LEARNING 항목을 <b>오래 밀린 순</b>으로.
     *
     * <p>"미복습"을 상태로 저장하지 않으므로(docs/10) {@code nextReviewAt <= :now} 시간 비교가
     * 곧 due 판정이다. {@code :now}를 파라미터로 받는 이유: 쿼리 안에서 CURRENT_TIMESTAMP를
     * 쓰면 테스트에서 시각을 고정할 수 없다.
     *
     * <p>인덱스 {@code idx_reviewitem_user_due (user_id, status, next_review_at)}가
     * 등치 2개 + 범위 + 정렬을 한 번에 받는다(V4, docs/10).
     */
    @Query(value = """
            select r from ReviewItem r
            join fetch r.problem
            where r.userId = :userId
              and r.status = project.study.study_project.review.domain.ReviewStatus.LEARNING
              and r.nextReviewAt <= :now
            order by r.nextReviewAt asc
            """,
            countQuery = """
            select count(r) from ReviewItem r
            where r.userId = :userId
              and r.status = project.study.study_project.review.domain.ReviewStatus.LEARNING
              and r.nextReviewAt <= :now
            """)
    Page<ReviewItem> findDue(@Param("userId") Long userId,
                             @Param("now") LocalDateTime now,
                             Pageable pageable);

    /**
     * 내 복습 현황 전체(졸업 포함) — 대시보드/진척 확인용. status 필터는 선택(null이면 전체),
     * 정렬은 스펙 기본값(nextReviewAt asc)으로 고정.
     */
    @Query(value = """
            select r from ReviewItem r
            join fetch r.problem
            where r.userId = :userId
              and (:status is null or r.status = :status)
            order by r.nextReviewAt asc
            """,
            countQuery = """
            select count(r) from ReviewItem r
            where r.userId = :userId
              and (:status is null or r.status = :status)
            """)
    Page<ReviewItem> findAllOfUser(@Param("userId") Long userId,
                                   @Param("status") ReviewStatus status,
                                   Pageable pageable);
}
