package project.study.study_project.discussion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.PostLike;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    /** 추천. 이미 눌렀으면 아무 일도 하지 않는다 — 연달아 눌러도 UNIQUE 위반이 500으로 새지 않는다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO post_like (post_id, user_id, created_at) VALUES (:postId, :userId, :now)",
            nativeQuery = true)
    void add(@Param("postId") Long postId, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from PostLike l where l.postId = :postId and l.userId = :userId")
    void remove(@Param("postId") Long postId, @Param("userId") Long userId);

    long countByPostId(Long postId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    /** 한 쪽의 글을 한 번에 센다. 추천이 없는 글은 결과에 없다. */
    @Query("select l.postId as postId, count(l) as cnt from PostLike l where l.postId in :postIds group by l.postId")
    List<PostLikeCount> countByPostIds(@Param("postIds") Collection<Long> postIds);

    interface PostLikeCount {
        Long getPostId();
        long getCnt();
    }
}
