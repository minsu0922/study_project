package project.study.study_project.learningpath.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.learningpath.domain.UserLearningPath;

import java.time.LocalDateTime;
import java.util.List;

public interface UserLearningPathRepository extends JpaRepository<UserLearningPath, Long> {

    /**
     * 코스를 시작하거나, 이미 시작했으면 마지막 학습 시각만 당긴다.
     *
     * <p>"있나 보고 없으면 넣기"로 하지 않는 이유는 북마크와 같다(BookmarkRepository.addProblem).
     * 연달아 누르면 둘 다 "없다"를 보고 넣다가 한쪽이 UNIQUE 위반으로 500이 된다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_learning_path (user_id, path_key, started_at, last_studied_at)
            VALUES (:userId, :pathKey, :now, :now)
            ON DUPLICATE KEY UPDATE last_studied_at = :now
            """, nativeQuery = true)
    void touch(@Param("userId") Long userId, @Param("pathKey") String pathKey, @Param("now") LocalDateTime now);

    /** 내가 시작한 코스, 최근에 공부한 순 — 홈의 "이어서 하기"와 로드맵 목록의 "진행 중" */
    @Query("select u from UserLearningPath u where u.userId = :userId order by u.lastStudiedAt desc, u.id desc")
    List<UserLearningPath> findMine(@Param("userId") Long userId);

    @Modifying
    @Query("delete from UserLearningPath u where u.userId = :userId and u.pathKey = :pathKey")
    void remove(@Param("userId") Long userId, @Param("pathKey") String pathKey);
}
