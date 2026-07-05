package project.study.study_project.quiz.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Domain;
import project.study.study_project.quiz.domain.Submission;

/**
 * Submission 저장소 — 채점 시 저장 + 오답노트 조회(ADR-0002: 별도 오답 테이블 없이 조회 기반).
 */
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * 오답노트: 내 오답을 <b>문제당 최신 1건</b>으로 집계해 페이징 조회.
     *
     * <p>쿼리 해설:
     * <ul>
     *   <li><b>"문제당 최신 오답 1건"</b>은 상관 서브쿼리 {@code s.id = (select max(s2.id) ...)}로
     *       구현했다 — 같은 문제의 오답들 중 id가 가장 큰(=가장 나중에 제출된) 행만 남긴다.
     *       GROUP BY로 묶으면 "최신 행의 나머지 컬럼(제출답 등)"을 가져오기 번거롭고,
     *       윈도우 함수(ROW_NUMBER)는 JPQL이 지원하지 않아 서브쿼리가 가장 단순하다.
     *       데이터가 커지면 비용이 문제될 수 있는데, 그때가 ReviewItem 테이블로 분리할
     *       시점이다(ADR-0002의 재검토 트리거).
     *   <li><b>{@code join fetch s.problem}</b>: 오답노트는 문제 지문·해설까지 보여주므로
     *       Problem을 반드시 함께 읽는다. fetch join으로 N+1을 원천 차단(@ManyToOne이라
     *       페이징과 충돌 없음 — 컬렉션 fetch join과 달리 행이 불어나지 않는다).
     *   <li>fetch join + 페이징 조합에서는 Spring Data가 count 쿼리를 못 만들어
     *       {@code countQuery}를 별도로 명시했다(fetch 없이 개수만 센다).
     *   <li>인덱스 {@code idx_submission_user_correct (user_id, is_correct, submitted_at)}가
     *       user+오답 필터를 받쳐 준다(V1, ADR-0002).
     * </ul>
     */
    @Query(value = """
            select s from Submission s
            join fetch s.problem p
            where s.userId = :userId
              and s.correct = false
              and (:domain is null or p.domain = :domain)
              and s.id = (
                  select max(s2.id) from Submission s2
                  where s2.userId = :userId
                    and s2.problem = s.problem
                    and s2.correct = false
              )
            order by s.submittedAt desc
            """,
            countQuery = """
            select count(s) from Submission s
            where s.userId = :userId
              and s.correct = false
              and (:domain is null or s.problem.domain = :domain)
              and s.id = (
                  select max(s2.id) from Submission s2
                  where s2.userId = :userId
                    and s2.problem = s.problem
                    and s2.correct = false
              )
            """)
    Page<Submission> findLatestWrongAnswers(
            @Param("userId") Long userId,
            @Param("domain") Domain domain,
            Pageable pageable
    );
}
