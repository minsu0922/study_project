package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CommentReportIntegrationTest {

    private static final String REPORT = "/api/me/comment-reports";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private project.study.study_project.discussion.repository.PostRepository postRepository;
    @Autowired
    private CommentReportRepository reportRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인은 신고할 수 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post(REPORT).contentType("application/json").content(body(saveComment().getId(), "ABUSE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("문제를 풀지 않은 사람도 신고할 수 있다 — 읽을 수 있으면 신고도 할 수 있다")
    void anyLoggedInUserCanReport() throws Exception {
        mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(saveComment().getId(), "ABUSE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reasonLabel").exists());
    }

    @Test
    @DisplayName("같은 글을 두 번 신고하면 409 DISCUSSION_007")
    void rejectsDuplicate() throws Exception {
        Long commentId = saveComment().getId();
        String token = bearer(Role.USER);
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(commentId, "SPAM")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(commentId, "ABUSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_007"));
    }

    @Test
    @DisplayName("없는 댓글은 404, 사유가 없으면 400")
    void validates() throws Exception {
        String token = bearer(Role.USER);
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(999_999_999L, "ABUSE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_001"));
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"commentId\":%d}".formatted(saveComment().getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고함은 관리자만 본다")
    void reportBoxIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/comment-reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /** 개발 DB에 사람이 남긴 신고가 있어도 깨지지 않게 절대값이 아니라 변화량을 본다. */
    @Test
    @DisplayName("가리면 글이 HIDDEN이 되고, 그 글의 대기 신고가 모두 인정으로 바뀐다")
    void hideAcceptsPendingReports() throws Exception {
        Comment comment = saveComment();
        long pendingBefore = reportRepository.countByStatus(ReportStatus.PENDING);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                            .contentType("application/json").content(body(comment.getId(), "ABUSE")))
                    .andExpect(status().isCreated());
        }
        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore + 2);

        mockMvc.perform(post("/api/admin/comments/%d/hide".formatted(comment.getId()))
                        .header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore);
    }

    @Test
    @DisplayName("가린 글을 복구하면 다시 보인다. 안 가린 글의 복구는 409")
    void restore() throws Exception {
        Comment comment = saveComment();
        String admin = bearer(Role.ADMIN);

        mockMvc.perform(post("/api/admin/comments/%d/restore".formatted(comment.getId()))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));

        mockMvc.perform(post("/api/admin/comments/%d/hide".formatted(comment.getId()))
                .header("Authorization", admin)).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/comments/%d/restore".formatted(comment.getId()))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));
    }

    @Test
    @DisplayName("기각하면 글은 그대로이고 신고만 닫힌다. 두 번 기각하면 409 DISCUSSION_009")
    void dismiss() throws Exception {
        Comment comment = saveComment();
        String admin = bearer(Role.ADMIN);
        String created = mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(comment.getId(), "OFF_TOPIC")))
                .andReturn().getResponse().getContentAsString();
        int at = created.indexOf("\"id\":");
        long reportId = Long.parseLong(created.substring(at + 5, created.indexOf(',', at)).trim());

        mockMvc.perform(post("/api/admin/comment-reports/%d/dismiss".formatted(reportId))
                        .header("Authorization", admin)
                        .contentType("application/json").content("{\"note\":\"문제없는 글\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"))
                .andExpect(jsonPath("$.data.adminNote").value("문제없는 글"))
                .andExpect(jsonPath("$.data.commentStatus").value("VISIBLE"));

        mockMvc.perform(post("/api/admin/comment-reports/%d/dismiss".formatted(reportId))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_009"));
    }

    private String body(Long commentId, String reason) {
        return "{\"commentId\":%d,\"reason\":\"%s\"}".formatted(commentId, reason);
    }

    private String bearer(Role role) {
        User user = userRepository.save(User.builder()
                .username("crep" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), role);
    }

    private Comment saveComment() {
        Problem problem = problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        Long postId = postRepository.saveAndFlush(Post.of(discussionId, null, "신고될 댓글이 달린 글", "본문")).getId();
        return commentRepository.saveAndFlush(Comment.of(postId, null, null, "신고될 글"));
    }
}
