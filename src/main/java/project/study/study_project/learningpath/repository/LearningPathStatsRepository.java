package project.study.study_project.learningpath.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.quiz.domain.Problem;

import java.util.Collection;
import java.util.List;

/**
 * 코스를 만들고 진도를 세는 데 쓰는 읽기 쿼리 모음. 문서·문제·제출을 함께 보므로
 * 어느 한 엔티티의 저장소에 두지 않았다. 숨긴 문제·문서는 전부 뺀다(V30).
 */
public interface LearningPathStatsRepository extends Repository<Problem, Long> {

    /** 분야별 코스의 재료 — 공개 문서 전부. 먼저 만든 문서가 앞이다(주제 순서의 기준) */
    @Query("select d.id as id, d.domain as domain, d.slug as slug, d.title as title "
            + "from Document d where d.hidden = false order by d.id asc")
    List<DocumentRow> findPublicDocuments();

    @Query("select d.id as id, d.domain as domain, d.slug as slug, d.title as title "
            + "from Document d where d.hidden = false and d.id in :ids")
    List<DocumentRow> findPublicDocumentsByIds(@Param("ids") Collection<Long> ids);

    /** 단계마다 문제 수 — 문서로 묶인 문제 */
    @Query("select p.documentSlug as key, count(p) as cnt from Problem p "
            + "where p.hidden = false and p.documentSlug in :slugs group by p.documentSlug")
    List<KeyCount> countProblemsBySlug(@Param("slugs") Collection<String> slugs);

    /** 코드 코스의 단계마다 문제 수 — 난이도로 묶는다 */
    @Query("select p.difficulty as difficulty, count(p) as cnt from Problem p "
            + "where p.hidden = false and p.code is not null and p.codeLanguage = :lang group by p.difficulty")
    List<DifficultyCount> countCodeProblemsByDifficulty(@Param("lang") String lang);

    /**
     * 내 진도 — 단계마다 제출한 문제 수와 맞힌 적 있는 문제 수.
     * 같은 문제를 여러 번 풀어도 한 번으로 센다(distinct). 맞힌 수는 "맞힌 적 있는 문제"다.
     */
    @Query("""
            select p.documentSlug as key,
                   count(distinct p.id) as attempted,
                   count(distinct case when s.correct = true then p.id end) as solved
            from Submission s join s.problem p
            where s.userId = :userId and p.hidden = false and p.documentSlug in :slugs
            group by p.documentSlug
            """)
    List<KeyProgress> progressBySlug(@Param("userId") Long userId, @Param("slugs") Collection<String> slugs);

    @Query("""
            select p.difficulty as difficulty,
                   count(distinct p.id) as attempted,
                   count(distinct case when s.correct = true then p.id end) as solved
            from Submission s join s.problem p
            where s.userId = :userId and p.hidden = false and p.code is not null and p.codeLanguage = :lang
            group by p.difficulty
            """)
    List<DifficultyProgress> codeProgressByDifficulty(@Param("userId") Long userId, @Param("lang") String lang);

    interface DocumentRow {
        Long getId();

        DomainCode getDomain();

        String getSlug();

        String getTitle();
    }

    interface KeyCount {
        String getKey();

        long getCnt();
    }

    interface DifficultyCount {
        Difficulty getDifficulty();

        long getCnt();
    }

    interface KeyProgress {
        String getKey();

        long getAttempted();

        long getSolved();
    }

    interface DifficultyProgress {
        Difficulty getDifficulty();

        long getAttempted();

        long getSolved();
    }
}
