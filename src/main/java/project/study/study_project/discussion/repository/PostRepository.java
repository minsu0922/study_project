package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;

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
}
