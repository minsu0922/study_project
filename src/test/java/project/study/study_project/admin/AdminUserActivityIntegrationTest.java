package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 화면의 사용자 활동 — 정지할지 판단하는 근거가 맞게 세어지는가. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminUserActivityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private CommentRepository commentRepository;

    @Test
    @DisplayName("활동이 없는 사용자는 모두 0이고 목록이 비어 있다")
    void emptyActivity() throws Exception {
        User user = fixtures.user(Role.USER);

        mockMvc.perform(get(path(user)).header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postCount").value(0))
                .andExpect(jsonPath("$.data.commentCount").value(0))
                .andExpect(jsonPath("$.data.hiddenCount").value(0))
                .andExpect(jsonPath("$.data.receivedReportCount").value(0))
                .andExpect(jsonPath("$.data.attemptedProblems").value(0))
                .andExpect(jsonPath("$.data.recentPosts", hasSize(0)))
                .andExpect(jsonPath("$.data.recentReports", hasSize(0)));
    }

    @Test
    @DisplayName("글과 댓글은 지운 것을 빼고 센다. 가려진 것은 세고, 따로 가려진 수로도 센다")
    void countsPostsAndComments() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        Post kept = fixtures.post(problem, user.getId());
        Post hidden = fixtures.post(problem, user.getId());
        hidden.hide();
        Post deleted = fixtures.post(problem, user.getId());
        deleted.delete();
        // 남의 글은 세지 않는다.
        fixtures.post(problem, fixtures.user(Role.USER).getId());
        commentRepository.saveAndFlush(Comment.of(kept.getId(), user.getId(), null, "보이는 댓글"));
        Comment hiddenComment = commentRepository.saveAndFlush(Comment.of(kept.getId(), user.getId(), null, "가려질 댓글"));
        hiddenComment.hide();
        postRepository.flush();
        commentRepository.flush();

        mockMvc.perform(get(path(user)).header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.data.postCount").value(2))
                .andExpect(jsonPath("$.data.commentCount").value(2))
                .andExpect(jsonPath("$.data.hiddenCount").value(2))
                // 한 번 풀었고 맞혔다(fixtures.solver).
                .andExpect(jsonPath("$.data.attemptedProblems").value(1))
                .andExpect(jsonPath("$.data.solvedProblems").value(1))
                .andExpect(jsonPath("$.data.recentPosts", hasSize(2)))
                .andExpect(jsonPath("$.data.recentPosts[*].id", contains(hidden.getId().intValue(), kept.getId().intValue())))
                // 가려진 글도 제목이 온다. 관리자는 무엇이 가려졌는지 봐야 한다.
                .andExpect(jsonPath("$.data.recentPosts[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.recentPosts[0].title").value("글"));
    }

    @Test
    @DisplayName("최근 글은 다섯 건까지만 싣는다. 수는 전부 센다")
    void recentPostsAreCapped() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        for (int i = 0; i < 7; i++) {
            fixtures.post(problem, user.getId());
        }

        mockMvc.perform(get(path(user)).header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.data.postCount").value(7))
                .andExpect(jsonPath("$.data.recentPosts", hasSize(5)));
    }

    /** 신고에는 글쓴이가 적혀 있지 않다. 글과 댓글을 거쳐 "이 사람이 받은 신고"를 찾는다. */
    @Test
    @DisplayName("받은 신고는 글 신고와 댓글 신고를 함께 센다. 남이 받은 신고는 세지 않는다")
    void countsReceivedReports() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        Post own = fixtures.post(problem, user.getId(), PostCategory.QUESTION, "신고될 글", "광고 글입니다");
        Comment ownComment = commentRepository.saveAndFlush(Comment.of(own.getId(), user.getId(), null, "신고될 댓글"));
        Post others = fixtures.post(problem, fixtures.user(Role.USER).getId());

        report("/api/me/post-reports", "{\"postId\":%d,\"reason\":\"SPAM\"}".formatted(own.getId()));
        report("/api/me/post-reports", "{\"postId\":%d,\"reason\":\"ABUSE\"}".formatted(own.getId()));
        report("/api/me/comment-reports", "{\"commentId\":%d,\"reason\":\"ABUSE\"}".formatted(ownComment.getId()));
        report("/api/me/post-reports", "{\"postId\":%d,\"reason\":\"SPAM\"}".formatted(others.getId()));

        mockMvc.perform(get(path(user)).header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.data.receivedReportCount").value(3))
                .andExpect(jsonPath("$.data.recentReports", hasSize(3)))
                // 새 신고부터 온다. 댓글 신고에도 그 댓글이 달린 글이 실려 화면이 그 글로 갈 수 있다.
                .andExpect(jsonPath("$.data.recentReports[0].targetType").value("COMMENT"))
                .andExpect(jsonPath("$.data.recentReports[0].postId").value(own.getId()))
                .andExpect(jsonPath("$.data.recentReports[0].excerpt").value("신고될 댓글"))
                .andExpect(jsonPath("$.data.recentReports[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.recentReports[2].targetType").value("POST"))
                .andExpect(jsonPath("$.data.recentReports[2].excerpt").value("광고 글입니다"))
                .andExpect(jsonPath("$.data.recentReports[2].reasonLabel").value("광고·도배"));
    }

    @Test
    @DisplayName("관리자만 본다. 없는 사용자는 404 USER_001")
    void adminOnlyAndUnknown() throws Exception {
        User user = fixtures.user(Role.USER);

        mockMvc.perform(get(path(user))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path(user)).header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users/999999999/activity").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_001"));
    }

    private String path(User user) {
        return "/api/admin/users/%d/activity".formatted(user.getId());
    }

    /** 신고는 한 사람이 같은 대상에 한 번만 할 수 있어, 부를 때마다 새 사용자로 한다. */
    private void report(String path, String body) throws Exception {
        mockMvc.perform(post(path).header("Authorization", fixtures.bearer(Role.USER))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());
    }
}
