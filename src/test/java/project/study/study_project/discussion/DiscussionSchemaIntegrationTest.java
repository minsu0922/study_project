package project.study.study_project.discussion;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** V21이 약속한 제약을 DB에서 직접 밟아 본다 — 방은 문제당 하나, 문제가 지워지면 함께 지워진다. */
@SpringBootTest
@Transactional
class DiscussionSchemaIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private project.study.study_project.discussion.repository.PostRepository postRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("같은 문제에 방을 두 번 만들어도 하나만 생긴다")
    void roomIsCreatedOncePerProblem() {
        Long problemId = fixtures.problem().getId();

        discussionRepository.insertIfAbsent(problemId);
        discussionRepository.insertIfAbsent(problemId);

        assertThat(discussionRepository.countByProblemId(problemId)).isEqualTo(1);
        assertThat(discussionRepository.findIdByProblemIdForShare(problemId)).isPresent();
    }

    @Test
    @DisplayName("방이 없는 문제는 빈 값을 돌려준다")
    void noRoomYet() {
        assertThat(discussionRepository.findIdByProblemId(fixtures.problem().getId())).isEmpty();
    }

    @Test
    @DisplayName("문제를 지우면 방과 글, 댓글도 함께 지워진다")
    void deletingProblemRemovesDiscussion() {
        Problem problem = fixtures.problem();
        Long postId = savePost(problem.getId());
        Long commentId = commentRepository.saveAndFlush(Comment.of(postId, null, null, "첫 댓글")).getId();

        problemRepository.delete(problem);
        em.flush();
        em.clear();

        assertThat(discussionRepository.countByProblemId(problem.getId())).isZero();
        assertThat(postRepository.findById(postId)).isEmpty();
        assertThat(commentRepository.findById(commentId)).isEmpty();
    }

    @Test
    @DisplayName("댓글은 VISIBLE로 태어나고, 삭제해도 행은 남는다")
    void commentLifecycle() {
        Long postId = savePost(fixtures.problem().getId());
        Comment comment = commentRepository.saveAndFlush(Comment.of(postId, null, null, "첫 댓글"));

        assertThat(comment.getStatus()).isEqualTo(CommentStatus.VISIBLE);
        comment.delete();
        em.flush();
        em.clear();

        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getStatus())
                .isEqualTo(CommentStatus.DELETED);
        assertThat(commentRepository.countByPostIdAndStatus(postId, CommentStatus.VISIBLE)).isZero();
    }

    private Long savePost(Long problemId) {
        discussionRepository.insertIfAbsent(problemId);
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problemId).orElseThrow();
        return postRepository.saveAndFlush(Post.of(discussionId, null, PostCategory.QUESTION, "글", "본문")).getId();
    }
}
