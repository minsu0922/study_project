package project.study.study_project.quiz.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;

import java.util.List;

/**
 * Problem 저장소.
 *
 * <p>퀴즈 조회는 <b>네이티브 SQL + {@code ORDER BY RAND()}</b>를 쓴다. 이유와 트레이드오프:
 * <ul>
 *   <li><b>왜 랜덤인가</b>: 매번 같은 문제가 같은 순서로 나오면 "문제를 푸는" 게 아니라
 *       "순서를 외우는" 게 된다. 퀴즈 특성상 무작위 추출이 기본값으로 맞다.
 *   <li><b>왜 네이티브인가</b>: JPQL 표준에는 랜덤 정렬이 없다. 문서 조회처럼 Specification을
 *       쓸 수도 있지만 Sort로 RAND()를 표현할 수 없어, 필터+랜덤+LIMIT을 한 번에 처리하려면
 *       네이티브가 가장 단순하다.
 *   <li><b>ORDER BY RAND()의 비용</b>: 조건에 걸린 전체 행에 난수를 붙여 정렬하므로 O(N log N).
 *       문제 수가 수십만 건이 되면 느려진다 — 그때는 "랜덤 id 샘플링 후 IN 조회" 같은 기법으로
 *       바꾼다(로드맵 1에서 인덱스·쿼리 최적화와 함께 측정). MVP의 수백 문제 규모에선 충분히 빠르다.
 *   <li><b>동적 필터</b>: {@code (:x IS NULL OR col = :x)} 패턴 — 파라미터가 없으면 조건 자체가
 *       항상 참이 되어 무시된다. 필터가 3개뿐이라 이 정도 반복은 Specification 도입보다 싸다.
 *   <li><b>ESSAY 제외 고정</b>: 서술형은 MVP 채점 대상이 아니라서(문서 03) 필터와 무관하게
 *       퀴즈에 나오면 안 된다 → WHERE에 상수로 박아 실수 여지를 없앤다.
 * </ul>
 * enum 파라미터는 네이티브 쿼리라 자동 변환이 안 되므로 서비스에서 {@code name()} 문자열로 넘긴다.
 */
public interface ProblemRepository extends JpaRepository<Problem, Long> {

    @Query(value = """
            SELECT * FROM problem p
            WHERE (:domain     IS NULL OR p.domain     = :domain)
              AND (:difficulty IS NULL OR p.difficulty = :difficulty)
              AND (:type       IS NULL OR p.type       = :type)
              AND p.type <> 'ESSAY'
            ORDER BY RAND()
            LIMIT :size
            """, nativeQuery = true)
    List<Problem> findRandomForQuiz(
            @Param("domain") String domain,
            @Param("difficulty") String difficulty,
            @Param("type") String type,
            @Param("size") int size
    );

    /**
     * 관리자용 문제 목록 — 최신 등록 순, 도메인/유형 필터(선택).
     * 퀴즈 조회와 달리 랜덤이 아니고(관리 화면은 예측 가능한 순서가 편함),
     * ESSAY 제외도 없다(관리자는 전부 봐야 함). JPQL이라 enum 파라미터를 그대로 받는다.
     */
    @Query("""
            select p from Problem p
            where (:domain is null or p.domain = :domain)
              and (:type is null or p.type = :type)
            order by p.id desc
            """)
    Page<Problem> findForAdmin(
            @Param("domain") Domain domain,
            @Param("type") ProblemType type,
            Pageable pageable
    );

    /** 대시보드 현황판: 도메인×난이도별 문제 수. 인터페이스 프로젝션(별칭→getter 매핑)으로 받는다. */
    @Query("""
            select p.domain as domain, p.difficulty as difficulty, count(p) as cnt
            from Problem p
            group by p.domain, p.difficulty
            """)
    List<DomainDifficultyCount> countGroupByDomainAndDifficulty();

    /** {@link #countGroupByDomainAndDifficulty} 결과 행 — select 별칭과 getter 이름이 매핑 규약이다. */
    interface DomainDifficultyCount {
        Domain getDomain();
        Difficulty getDifficulty();
        long getCnt();
    }
}
