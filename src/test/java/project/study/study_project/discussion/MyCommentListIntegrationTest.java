package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.dto.MyCommentItem;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.user.domain.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 내가 쓴 댓글 목록 — 내 것만, 최신순으로, 지운 것은 빼고. */
@SpringBootTest
@Transactional
class MyCommentListIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private CommentService commentService;

    @Test
    @DisplayName("내가 쓴 댓글만 글 제목과 함께 최신순으로 나온다")
    void listsOnlyMyCommentsNewestFirst() {
        Problem problem = fixtures.problem();
        User me = fixtures.solver(problem);
        User other = fixtures.solver(problem);
        Post post = fixtures.post(problem, other.getId(), PostCategory.QUESTION, "캐시 질문", "본문");
        commentService.write(me.getId(), new CommentWriteRequest(post.getId(), null, "첫 댓글"));
        commentService.write(other.getId(), new CommentWriteRequest(post.getId(), null, "남의 댓글"));
        commentService.write(me.getId(), new CommentWriteRequest(post.getId(), null, "둘째 댓글"));

        List<MyCommentItem> mine = commentService.mine(me.getId(), 0).comments();

        assertThat(mine).extracting(MyCommentItem::body).containsExactly("둘째 댓글", "첫 댓글");
        assertThat(mine).allSatisfy(c -> {
            assertThat(c.postId()).isEqualTo(post.getId());
            assertThat(c.postTitle()).isEqualTo("캐시 질문");
            assertThat(c.hidden()).isFalse();
        });
    }

    @Test
    @DisplayName("지운 댓글은 목록에서 빠진다")
    void deletedCommentIsExcluded() {
        Problem problem = fixtures.problem();
        User me = fixtures.solver(problem);
        Post post = fixtures.post(problem, me.getId());
        CommentItem written = commentService.write(me.getId(),
                new CommentWriteRequest(post.getId(), null, "지울 댓글"));

        commentService.delete(me.getId(), written.id());

        assertThat(commentService.mine(me.getId(), 0).comments()).isEmpty();
    }
}
