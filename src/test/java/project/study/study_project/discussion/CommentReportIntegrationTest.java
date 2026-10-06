package project.study.study_project.discussion;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Map;
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
    private TestFixtures fixtures;
    @Autowired
    private UserRepository userRepository;
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
        mockMvc.perform(post(REPORT).header("Authorization", fixtures.bearer(Role.USER))
                        .contentType("application/json").content(body(saveComment().getId(), "ABUSE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("같은 글을 두 번 신고하면 409 DISCUSSION_007")
    void rejectsDuplicate() throws Exception {
        Long commentId = saveComment().getId();
        String token = fixtures.bearer(Role.USER);
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
        String token = fixtures.bearer(Role.USER);
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
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", fixtures.bearer(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /** 개발 DB에 사람이 남긴 신고가 있어도 깨지지 않게 절대값이 아니라 변화량을 본다. */
    @Test
    @DisplayName("가리면 글이 HIDDEN이 되고, 그 글의 대기 신고가 모두 인정으로 바뀐다")
    void hideAcceptsPendingReports() throws Exception {
        Comment comment = saveComment();
        long pendingBefore = reportRepository.countByStatus(ReportStatus.PENDING);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(REPORT).header("Authorization", fixtures.bearer(Role.USER))
                            .contentType("application/json").content(body(comment.getId(), "ABUSE")))
                    .andExpect(status().isCreated());
        }
        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore + 2);

        mockMvc.perform(post("/api/admin/comments/%d/hide".formatted(comment.getId()))
                        .header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore);
    }

    @Test
    @DisplayName("가린 글을 복구하면 다시 보인다. 안 가린 글의 복구는 409")
    void restore() throws Exception {
        Comment comment = saveComment();
        String admin = fixtures.bearer(Role.ADMIN);

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
        String admin = fixtures.bearer(Role.ADMIN);
        String created = mockMvc.perform(post(REPORT).header("Authorization", fixtures.bearer(Role.USER))
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
                .andExpect(jsonPath("$.data.targetStatus").value("VISIBLE"));

        mockMvc.perform(post("/api/admin/comment-reports/%d/dismiss".formatted(reportId))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_009"));
    }

    /* ── 글 신고 ─────────────────────────────────────────── */

    @Test
    @DisplayName("글도 신고할 수 있다 — 신고함 한 줄에 대상이 글이라는 것과 글 제목이 실린다")
    void reportsPost() throws Exception {
        Post post = savePost();

        Map<String, Object> row = reportAndRead(POST_REPORT, postBody(post.getId(), "SPAM"));

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("targetType")).isEqualTo("POST");
        assertThat(id(row, "postId")).isEqualTo(post.getId());
        assertThat(row.get("postTitle")).isEqualTo("신고될 글");
        assertThat(row.get("targetBody")).isEqualTo("본문");
        assertThat(row.get("commentId")).isNull();
    }

    /** 관리자가 신고함에서 "어느 글에 달린 댓글인지"로 건너가려면 글 id가 있어야 한다. */
    @Test
    @DisplayName("댓글 신고에도 그 댓글이 달린 글이 실린다")
    void commentReportCarriesItsPost() throws Exception {
        Comment comment = saveComment();

        Map<String, Object> row = reportAndRead(REPORT, body(comment.getId(), "ABUSE"));

        assertThat(row.get("targetType")).isEqualTo("COMMENT");
        assertThat(id(row, "commentId")).isEqualTo(comment.getId());
        assertThat(id(row, "postId")).isEqualTo(comment.getPostId());
    }

    @Test
    @DisplayName("같은 글을 두 번 신고하면 409 DISCUSSION_007, 없는 글은 404 DISCUSSION_011")
    void postReportRejectsDuplicateAndUnknown() throws Exception {
        Long postId = savePost().getId();
        String token = fixtures.bearer(Role.USER);
        mockMvc.perform(post(POST_REPORT).header("Authorization", token)
                        .contentType("application/json").content(postBody(postId, "SPAM")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(POST_REPORT).header("Authorization", token)
                        .contentType("application/json").content(postBody(postId, "ABUSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_007"));
        mockMvc.perform(post(POST_REPORT).header("Authorization", token)
                        .contentType("application/json").content(postBody(999_999_999L, "ABUSE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
    }

    @Test
    @DisplayName("글을 가리면 HIDDEN이 되고 그 글의 대기 신고가 모두 인정으로 바뀐다. 복구하면 다시 보인다")
    void hideAndRestorePost() throws Exception {
        Post post = savePost();
        String admin = fixtures.bearer(Role.ADMIN);
        long pendingBefore = reportRepository.countByStatus(ReportStatus.PENDING);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(POST_REPORT).header("Authorization", fixtures.bearer(Role.USER))
                            .contentType("application/json").content(postBody(post.getId(), "ABUSE")))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/admin/posts/%d/hide".formatted(post.getId())).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));
        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore);
        mockMvc.perform(get("/api/quiz/posts/" + post.getId()))
                .andExpect(jsonPath("$.data.title").doesNotExist());

        mockMvc.perform(post("/api/admin/posts/%d/restore".formatted(post.getId())).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));
        mockMvc.perform(post("/api/admin/posts/%d/restore".formatted(post.getId())).header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    @Test
    @DisplayName("글 가림·복구는 관리자만 한다")
    void postModerationIsAdminOnly() throws Exception {
        Long postId = savePost().getId();
        mockMvc.perform(post("/api/admin/posts/%d/hide".formatted(postId)).header("Authorization", fixtures.bearer(Role.USER)))
                .andExpect(status().isForbidden());
    }

    /**
     * 화면은 자기 글에 신고 버튼을 내지 않는다. 하지만 주소를 직접 부르면 받아 줬다 —
     * 자기 글을 신고해 신고함을 채우는 장난을 서버가 막는다.
     */
    @Test
    @DisplayName("내가 쓴 글과 댓글은 신고할 수 없다 — 400 DISCUSSION_012, 신고는 저장되지 않는다")
    void cannotReportOwn() throws Exception {
        User writer = userRepository.save(User.builder()
                .username("crep" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build());
        String token = "Bearer " + jwtTokenProvider.createToken(writer.getId(), Role.USER);
        Post seed = savePost();
        Post own = postRepository.saveAndFlush(Post.of(seed.getDiscussionId(), writer.getId(), PostCategory.QUESTION, "내 글", "본문"));
        Comment ownComment = commentRepository.saveAndFlush(Comment.of(seed.getId(), writer.getId(), null, "내 댓글"));
        long before = reportRepository.count();

        mockMvc.perform(post(POST_REPORT).header("Authorization", token)
                        .contentType("application/json").content(postBody(own.getId(), "SPAM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_012"));
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(ownComment.getId(), "SPAM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_012"));
        assertThat(reportRepository.count()).isEqualTo(before);
    }

    /** 신고함에서 글쓴이를 정지하러 건너가려면 누구인지(아이디)와 이미 정지 중인지가 있어야 한다. */
    @Test
    @DisplayName("신고함 한 줄에 글쓴이 아이디와 정지 여부가 실린다. 탈퇴한 글쓴이는 비어 나간다")
    void reportCarriesAuthorForSuspension() throws Exception {
        User writer = userRepository.save(User.builder()
                .username("crep" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build());
        Post seed = savePost();
        Post written = postRepository.saveAndFlush(Post.of(seed.getDiscussionId(), writer.getId(), PostCategory.QUESTION, "신고될 글", "본문"));

        Map<String, Object> row = reportAndRead(POST_REPORT, postBody(written.getId(), "SPAM"));
        assertThat(row.get("targetUsername")).isEqualTo(writer.getUsername());
        assertThat(id(row, "targetUserId")).isEqualTo(writer.getId());
        assertThat(row.get("targetSuspended")).isEqualTo(false);

        writer.suspend(java.time.LocalDateTime.now().plusDays(7), "도배");
        assertThat(reportAndRead(POST_REPORT, postBody(written.getId(), "SPAM")).get("targetSuspended"))
                .isEqualTo(true);

        // savePost는 글쓴이 없이(탈퇴한 사용자의 글처럼) 저장한다.
        Map<String, Object> orphan = reportAndRead(POST_REPORT, postBody(seed.getId(), "SPAM"));
        assertThat(orphan.get("targetUsername")).isNull();
        assertThat(orphan.get("targetUserId")).isNull();
        assertThat(orphan.get("targetSuspended")).isEqualTo(false);
    }

    /**
     * 신고된 글을 글쓴이가 고치면 관리자는 고친 뒤의 멀쩡한 내용만 보게 된다 —
     * 신고가 들어온 순간의 내용을 신고에 함께 남겨 그 길을 막는다.
     */
    @Test
    @DisplayName("신고 뒤에 글을 고쳐도 신고함에는 신고 당시의 제목과 본문이 남는다")
    void keepsPostAsReported() throws Exception {
        Post post = savePost();

        Map<String, Object> row = reportAndRead(POST_REPORT, postBody(post.getId(), "SPAM"));
        assertThat(row.get("reportedTitle")).isEqualTo("신고될 글");
        assertThat(row.get("reportedBody")).isEqualTo("본문");
        assertThat(row.get("editedAfterReport")).isEqualTo(false);

        post.edit(PostCategory.QUESTION, "질문 있어요", "502가 뭐예요?");
        postRepository.flush();

        mockMvc.perform(get("/api/admin/comment-reports").param("status", "PENDING")
                        .param("size", "200").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.data.content[?(@.postId == %d)].reportedTitle".formatted(post.getId()))
                        .value("신고될 글"))
                .andExpect(jsonPath("$.data.content[?(@.postId == %d)].reportedBody".formatted(post.getId()))
                        .value("본문"))
                .andExpect(jsonPath("$.data.content[?(@.postId == %d)].targetBody".formatted(post.getId()))
                        .value("502가 뭐예요?"))
                .andExpect(jsonPath("$.data.content[?(@.postId == %d)].editedAfterReport".formatted(post.getId()))
                        .value(true));
    }

    @Test
    @DisplayName("댓글도 신고 당시의 본문이 남는다. 댓글에는 제목이 없어 제목은 비어 나간다")
    void keepsCommentAsReported() throws Exception {
        Comment comment = saveComment();

        Map<String, Object> row = reportAndRead(REPORT, body(comment.getId(), "ABUSE"));
        assertThat(row.get("reportedBody")).isEqualTo("신고될 글");
        assertThat(row.get("reportedTitle")).isNull();
        assertThat(row.get("editedAfterReport")).isEqualTo(false);

        comment.edit("고친 댓글");
        commentRepository.flush();
        // 두 번째 신고는 고친 뒤에 들어왔으니 그때의 내용이 "신고 당시"다.
        Map<String, Object> second = reportAndRead(REPORT, body(comment.getId(), "ABUSE"));
        assertThat(second.get("reportedBody")).isEqualTo("고친 댓글");
        assertThat(second.get("editedAfterReport")).isEqualTo(false);
    }

    /** 신고함의 한 줄에는 글쓴이의 로그인 아이디가 있다. 신고한 사람에게 그대로 돌려주면 안 된다. */
    @Test
    @DisplayName("신고한 사람은 접수증만 받는다 — 글쓴이 아이디와 정지 여부, 본문은 돌아가지 않는다")
    void reporterGetsOnlyReceipt() throws Exception {
        User writer = fixtures.user(Role.USER);
        Post written = postRepository.saveAndFlush(
                Post.of(savePost().getDiscussionId(), writer.getId(), PostCategory.QUESTION, "신고될 글", "본문"));

        mockMvc.perform(post(POST_REPORT).header("Authorization", fixtures.bearer(Role.USER))
                        .contentType("application/json").content(postBody(written.getId(), "SPAM")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.targetUsername").doesNotExist())
                .andExpect(jsonPath("$.data.targetUserId").doesNotExist())
                .andExpect(jsonPath("$.data.targetSuspended").doesNotExist())
                .andExpect(jsonPath("$.data.targetBody").doesNotExist());
    }

    private static final String POST_REPORT = "/api/me/post-reports";

    /** 새 사용자로 신고하고, 신고함에서 그 한 줄을 꺼낸다. 신고한 사람에게는 접수증만 가므로 내용은 관리자 쪽에서 본다. */
    private Map<String, Object> reportAndRead(String path, String body) throws Exception {
        String receipt = mockMvc.perform(post(path).header("Authorization", fixtures.bearer(Role.USER))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reportId = ((Number) JsonPath.read(receipt, "$.data.id")).longValue();

        String box = mockMvc.perform(get("/api/admin/comment-reports").param("status", "PENDING")
                        .param("size", "100").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(box, "$.data.content[?(@.id == %d)]".formatted(reportId));
        assertThat(rows).as("신고함에 방금 한 신고가 있다").hasSize(1);
        return rows.get(0);
    }

    /** JSON의 수는 크기에 따라 Integer로도 Long으로도 읽힌다. id는 Long으로 맞춰 견준다. */
    private Long id(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private String postBody(Long postId, String reason) {
        return "{\"postId\":%d,\"reason\":\"%s\"}".formatted(postId, reason);
    }

    private Post savePost() {
        return fixtures.post(fixtures.problem(), null, PostCategory.QUESTION, "신고될 글", "본문");
    }

    private String body(Long commentId, String reason) {
        return "{\"commentId\":%d,\"reason\":\"%s\"}".formatted(commentId, reason);
    }

    private Comment saveComment() {
        Long postId = fixtures.post(fixtures.problem(), null, PostCategory.QUESTION, "신고될 댓글이 달린 글", "본문").getId();
        return commentRepository.saveAndFlush(Comment.of(postId, null, null, "신고될 글"));
    }
}
