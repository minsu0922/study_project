package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;

import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 한 글의 댓글(답글 제외)을 시간순으로 읽는다.
     *
     * <p>지워진 댓글은 살아 있는 답글이 있을 때만 남긴다. 화면에서 걸러 내면 20개를 읽었는데
     * 몇 개만 보이는 쪽이 생기므로 조회에서 뺀다.
     */
    @Query(value = """
            select c from Comment c
            where c.postId = :postId and c.parentId is null
              and (c.status <> :deleted
                   or exists (select 1 from Comment r where r.parentId = c.id and r.status <> :deleted))
            order by c.createdAt asc, c.id asc
            """,
            countQuery = """
            select count(c) from Comment c
            where c.postId = :postId and c.parentId is null
              and (c.status <> :deleted
                   or exists (select 1 from Comment r where r.parentId = c.id and r.status <> :deleted))
            """)
    Page<Comment> findThreads(@Param("postId") Long postId,
                              @Param("deleted") CommentStatus deleted,
                              Pageable pageable);

    /** 댓글 묶음의 답글을 한 번에 읽는다 — 댓글마다 따로 읽으면 한 화면에 조회가 20번 나간다. */
    @Query("""
            select c from Comment c
            where c.parentId in :parentIds and c.status <> :deleted
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findReplies(@Param("parentIds") Collection<Long> parentIds,
                              @Param("deleted") CommentStatus deleted);

    long countByPostIdAndStatus(Long postId, CommentStatus status);

    /* ── 관리자 화면의 사용자 활동 ── */

    long countByUserIdAndStatusNot(Long userId, CommentStatus status);

    long countByUserIdAndStatus(Long userId, CommentStatus status);

    /** 글별 보이는 댓글 수 — 글 목록이 한 쪽(20건)의 수를 한 번에 묻는다. 댓글이 없는 글은 결과에 없다. */
    @Query("""
            select c.postId as postId, count(c) as cnt
            from Comment c
            where c.postId in :postIds and c.status = :visible
            group by c.postId
            """)
    List<PostCommentCount> countByPostIds(@Param("postIds") Collection<Long> postIds,
                                          @Param("visible") CommentStatus visible);

    interface PostCommentCount {
        Long getPostId();

        long getCnt();
    }
}
