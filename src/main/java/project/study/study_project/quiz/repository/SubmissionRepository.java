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

    /** 관리자 문제 삭제 전 검사용 — 제출 이력이 있으면 삭제 불가(QUIZ_003, FK RESTRICT 정책과 일치). */
    boolean existsByProblemId(Long problemId);

    /**
     * 대시보드 문제별 정답률 — 제출이 0건인 문제도 보여야 하므로 Problem 기준 LEFT JOIN.
     * (INNER JOIN이면 아무도 안 푼 문제가 통계에서 사라져 "안 풀리는 문제"를 발견할 수 없다)
     * 집계는 SQL(GROUP BY)에서 끝낸다 — 전 제출을 자바로 끌어와 세는 것보다 훨씬 싸다.
     * 별칭(problemId 등)이 인터페이스 getter와 매핑되는 네이티브 프로젝션.
     */
    @Query(value = """
            SELECT p.id            AS problemId,
                   p.domain        AS domain,
                   p.type          AS type,
                   p.question      AS question,
                   COUNT(s.id)     AS attempts,
                   COALESCE(SUM(s.is_correct), 0) AS correctCount
            FROM problem p
            LEFT JOIN submission s ON s.problem_id = p.id
            GROUP BY p.id, p.domain, p.type, p.question
            ORDER BY attempts DESC, p.id
            """, nativeQuery = true)
    java.util.List<ProblemStatRow> aggregateProblemStats();

    /**
     * 오늘의 퀴즈 "취약 도메인" 판정 재료(docs/12) — <b>이 사용자의</b> 도메인별 제출 수·정답 수.
     *
     * <p>⚠️ 기존 {@code domain_stats} 뷰(R__)를 못 쓰는 이유: 뷰는 전체 사용자 합산이라
     * "내" 정답률이 아니다. 그래서 user_id 조건이 들어간 집계를 따로 둔다.
     *
     * <p>HAVING으로 <b>제출 {@code minCount}회 미만 도메인을 제외</b>한다 — 2문제 풀고 1개
     * 틀렸다고 "취약"이라 단정하는 건 통계가 아니라 소음이다(표본이 작으면 신뢰하지 않는다).
     * 정답률 계산·정렬은 서비스가 한다 — 어차피 최대 10행(도메인 수)이라 SQL에서
     * 나눗셈까지 할 이유가 없고, 프로젝션은 원본 숫자만 나르는 게 재사용하기 좋다.
     */
    @Query("""
            select p.domain as domain,
                   count(s) as total,
                   sum(case when s.correct = true then 1 else 0 end) as correctCount
            from Submission s
            join s.problem p
            where s.userId = :userId
            group by p.domain
            having count(s) >= :minCount
            """)
    java.util.List<UserDomainStat> aggregateUserDomainStats(
            @Param("userId") Long userId,
            @Param("minCount") long minCount
    );

    /** {@link #aggregateUserDomainStats} 결과 행. */
    interface UserDomainStat {
        Domain getDomain();
        long getTotal();
        long getCorrectCount();
    }

    /** {@link #aggregateProblemStats} 결과 행. */
    interface ProblemStatRow {
        Long getProblemId();
        String getDomain();
        String getType();
        String getQuestion();
        long getAttempts();
        long getCorrectCount();
    }
}
