package project.study.study_project.review.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;

import java.time.LocalDateTime;
import java.util.List;
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
     * 지금 복습 차례인 문제 수 — 문제 목록 화면의 통계 카드(docs/18, 2026-08-29).
     *
     * <p>조건은 {@link #findDue}의 countQuery와 <b>같아야 한다</b>. 목록의 "복습 대기" 필터와
     * 카드의 숫자가 어긋나면, 9건이라고 해 놓고 눌렀을 때 7건이 나온다.
     * 같은 인덱스({@code idx_reviewitem_user_due})를 그대로 탄다.
     */
    @Query("""
            select count(r) from ReviewItem r
            where r.userId = :userId
              and r.status = project.study.study_project.review.domain.ReviewStatus.LEARNING
              and r.nextReviewAt <= :now
            """)
    long countDue(@Param("userId") Long userId, @Param("now") LocalDateTime now);

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

    /**
     * <b>지정한 문제들</b>의 복습 항목만 — 오답노트가 복습 단계 배지를 그릴 때 쓴다.
     *
     * <p><b>왜 {@link #findAllOfUser}에 파라미터를 하나 더 얹지 않았나.</b>
     * {@code (:problemIds is null or r.problem.id in :problemIds)}로 합치는 방법이 먼저 떠오르지만,
     * JPQL의 {@code in :list}는 목록이 {@code null}이거나 비었을 때 구현체마다 다르게 굴고
     * (빈 목록은 {@code IN ()}이라는 문법 오류가 되는 DB도 있다) 조건이 늘수록 실행 계획도 흐려진다.
     * "전체 목록"과 "지정한 것만"은 부르는 쪽에서 이미 갈라져 있으므로, 쿼리도 갈라 두는 편이
     * 읽기도 쉽고 각자 최적의 인덱스를 탄다.
     *
     * <p><b>{@code userId} 조건이 이 쿼리의 핵심</b>이다. {@code problemIds}는 클라이언트가 준
     * 값이라, 사용자 조건 없이 조회하면 남의 복습 진도(몇 단계인지·언제 다시 볼지)가 그대로 샌다.
     * 여기서는 UNIQUE 제약이 겸하는 인덱스(user_id, problem_id)를 그대로 타므로
     * 성능을 위해서도 userId가 앞에 있는 편이 맞다.
     *
     * <p>정렬을 {@code problem.id}로 두는 것은 <b>결과 순서를 정해 두기 위해서</b>다. 부르는 쪽은
     * 이 결과를 맵으로 만들어 쓰므로 순서에 의존하지 않지만, 순서가 없는 페이징은 페이지 경계에서
     * 항목이 중복되거나 빠질 수 있다. 목록 두 개(findDue·findAllOfUser)가 {@code nextReviewAt}으로
     * 정렬하는 것과 달리 여기서 id를 쓰는 이유는, 이 조회의 결과가 "화면에 보이는 순서"가 아니라
     * 조회 대상 집합 그 자체이기 때문이다.
     */
    @Query(value = """
            select r from ReviewItem r
            join fetch r.problem p
            where r.userId = :userId
              and p.id in :problemIds
            order by p.id asc
            """,
            countQuery = """
            select count(r) from ReviewItem r
            where r.userId = :userId
              and r.problem.id in :problemIds
            """)
    Page<ReviewItem> findAllOfUserByProblemIds(@Param("userId") Long userId,
                                               @Param("problemIds") List<Long> problemIds,
                                               Pageable pageable);
}
