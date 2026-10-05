package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;

import java.util.Collection;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 한 방의 글을 새 글부터. 지운 글은 빼고 가린 글은 넣는다 — 가린 글은 자리를 남겨 보여 준다.
     *
     * <p>{@code Slice}인 이유: 전체 건수를 세는 쿼리가 따로 나가지 않는다. 화면에 쓰는 건수는
     * 보이는 글만 세므로({@link #countByDiscussionIdAndStatus}) 어차피 다른 쿼리다.
     */
    Slice<Post> findByDiscussionIdAndStatusNotOrderByCreatedAtDescIdDesc(
            Long discussionId, CommentStatus status, Pageable pageable);

    long countByDiscussionIdAndStatus(Long discussionId, CommentStatus status);

    /**
     * 방을 가리지 않고 보이는 글을 새 글부터(V24 인덱스). 문제 id와 제목을 같이 읽는다 —
     * 글마다 따로 읽으면 한 쪽에 조회가 40번 나간다.
     */
    @Query("""
            select p as post, d.problemId as problemId, pr.title as problemTitle
            from Post p
              join Discussion d on d.id = p.discussionId
              join Problem pr on pr.id = d.problemId
            where p.status = :visible
            order by p.createdAt desc, p.id desc
            """)
    Slice<RecentPostRow> findRecent(@Param("visible") CommentStatus visible, Pageable pageable);

    interface RecentPostRow {
        Post getPost();

        Long getProblemId();

        String getProblemTitle();
    }

    /** 문제별 보이는 글 수 — 문제 목록이 한 쪽(20건)의 수를 한 번에 묻는다. 글이 없는 문제는 결과에 없다. */
    @Query("""
            select d.problemId as problemId, count(p) as cnt
            from Post p join Discussion d on d.id = p.discussionId
            where d.problemId in :problemIds and p.status = :visible
            group by d.problemId
            """)
    List<ProblemPostCount> countByProblemIds(@Param("problemIds") Collection<Long> problemIds,
                                             @Param("visible") CommentStatus visible);

    interface ProblemPostCount {
        Long getProblemId();

        long getCnt();
    }
}
