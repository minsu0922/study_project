package project.study.study_project.learningpath.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.learningpath.domain.LearningPath;

import java.util.List;

public interface LearningPathRepository extends JpaRepository<LearningPath, Long> {

    /** 사용자 로드맵 목록의 관리자 코스 — 공개한 것만, 정한 순서대로 */
    @Query("select p from LearningPath p where p.published = true order by p.sortOrder asc, p.id asc")
    List<LearningPath> findPublished();

    /** 관리 콘솔 목록 — 비공개 포함 */
    @Query("select p from LearningPath p order by p.sortOrder asc, p.id asc")
    List<LearningPath> findAllOrdered();
}
