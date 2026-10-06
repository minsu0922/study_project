package project.study.study_project.user;

import com.jayway.jsonpath.JsonPath;
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
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 정지의 경계 — 무엇이 막히고 무엇이 그대로인가, 언제 풀리는가, 누가 정지할 수 있는가.
 *
 * <p>정지는 쓰기만 막는다(2026-10-05 사용자 결정). 학습과 읽기는 그대로다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class UserSuspensionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /* ── 정지하면 막히는 것 ───────────────────────────────── */

    @Test
    @DisplayName("정지된 사용자는 글·댓글을 쓰지도 고치지도 못하고 신고도 못 한다 — 403 DISCUSSION_013")
    void suspendedCannotWrite() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        String token = bearer(user);
        Post own = savePost(problem, user.getId());
        Comment ownComment = commentRepository.saveAndFlush(Comment.of(own.getId(), user.getId(), null, "내 댓글"));
        Post others = savePost(problem, null);
        suspend(user, 7);

        String[][] writes = {
                {"POST", "/api/me/posts", "{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())},
                {"PUT", "/api/me/posts/" + own.getId(), "{\"category\":\"QUESTION\",\"title\":\"고친 제목\",\"body\":\"고친 본문\"}"},
                {"POST", "/api/me/comments", "{\"postId\":%d,\"parentId\":null,\"body\":\"댓글\"}".formatted(others.getId())},
                {"PUT", "/api/me/comments/" + ownComment.getId(), "{\"body\":\"고친 댓글\"}"},
                {"POST", "/api/me/post-reports", "{\"postId\":%d,\"reason\":\"SPAM\"}".formatted(others.getId())},
        };
        for (String[] w : writes) {
            var request = ("POST".equals(w[0]) ? post(w[1]) : put(w[1]))
                    .header("Authorization", token).contentType("application/json").content(w[2]);
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("DISCUSSION_013"));
        }
    }

    /** 언제 풀리는지와 왜 정지됐는지를 모르면 사용자는 고장으로 안다. */
    @Test
    @DisplayName("거절 문구에 풀리는 날짜와 사유가 들어 있다. 무기한이면 날짜 대신 무기한이라고 적는다")
    void rejectionSaysWhenAndWhy() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        String body = "{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId());

        suspend(user, 7);
        String until = userRepository.findById(user.getId()).orElseThrow().getSuspendedUntil().toLocalDate().toString();
        mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(user))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.error.message", containsString(until)))
                .andExpect(jsonPath("$.error.message", containsString("도배")));

        suspend(user, null);
        mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(user))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.error.message", containsString("무기한")));
    }

    @Test
    @DisplayName("정지 중에도 읽기와 학습, 자기 글 삭제는 된다")
    void suspendedCanStillReadStudyAndDelete() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        String token = bearer(user);
        Post own = savePost(problem, user.getId());
        suspend(user, 1);

        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts").header("Authorization", token))
                .andExpect(status().isOk())
                // 쓸 수 없다는 것을 화면이 미리 알아야 글쓰기 버튼을 내지 않는다.
                .andExpect(jsonPath("$.data.canWrite").value(false));
        mockMvc.perform(get("/api/me/reviews/today").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/me/posts/" + own.getId()).header("Authorization", token))
                .andExpect(status().isOk());
    }

    /* ── 정지된 사람에게 알리기 ───────────────────────────── */

    /**
     * 화면은 canWrite가 false면 "문제를 풀면 쓸 수 있습니다"를 낸다. 정지된 사람에게 그 말은 틀리다 —
     * 풀었는데도 못 쓰는 이유를 목록 응답이 함께 준다.
     */
    @Test
    @DisplayName("정지 중이면 글·댓글 목록에 정지 안내가 실린다. 정지가 아니면 실리지 않는다")
    void listsCarrySuspensionNotice() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        Post post = savePost(problem, null);

        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.suspensionNotice").doesNotExist());

        suspend(user, 7);
        String until = userRepository.findById(user.getId()).orElseThrow().getSuspendedUntil().toLocalDate().toString();
        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString(until)))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString("도배")));
        mockMvc.perform(get("/api/quiz/posts/" + post.getId() + "/comments").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString(until)));
        // 남의 정지는 보이지 않는다.
        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts"))
                .andExpect(jsonPath("$.data.suspensionNotice").doesNotExist());
    }

    @Test
    @DisplayName("내 정지 상태를 물을 수 있다 — 마이페이지가 읽는다")
    void myStatus() throws Exception {
        User user = saveUser(Role.USER);

        mockMvc.perform(get("/api/me/suspension")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/suspension").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(false))
                .andExpect(jsonPath("$.data.notice").doesNotExist());

        suspend(user, null);
        mockMvc.perform(get("/api/me/suspension").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.suspended").value(true))
                .andExpect(jsonPath("$.data.notice", containsString("무기한")));
    }

    /* ── 풀리는 것 ───────────────────────────────────────── */

    @Test
    @DisplayName("관리자가 풀면 다시 쓸 수 있다")
    void unsuspendRestoresWriting() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        suspend(user, 30);

        mockMvc.perform(post("/api/admin/users/%d/unsuspend".formatted(user.getId()))
                        .header("Authorization", bearer(saveUser(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(false));

        mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(user)).contentType("application/json")
                        .content("{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("기간이 지나면 아무도 풀지 않아도 다시 쓸 수 있다")
    void expiredSuspensionNoLongerBlocks() throws Exception {
        Problem problem = saveProblem();
        User user = solver(problem);
        user.suspend(LocalDateTime.now().minusMinutes(1), "지난 정지");

        mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(user)).contentType("application/json")
                        .content("{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isCreated());
    }

    /* ── 정지하는 쪽 ─────────────────────────────────────── */

    @Test
    @DisplayName("정지하면 풀리는 시각과 사유가 돌아온다. 기간을 비우면 무기한이다")
    void suspendReturnsState() throws Exception {
        User user = saveUser(Role.USER);
        String admin = bearer(saveUser(Role.ADMIN));

        String response = mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(user.getId()))
                        .header("Authorization", admin).contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"도배\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(true))
                .andExpect(jsonPath("$.data.indefinite").value(false))
                .andExpect(jsonPath("$.data.suspendedReason").value("도배"))
                .andReturn().getResponse().getContentAsString();
        LocalDateTime until = LocalDateTime.parse(JsonPath.read(response, "$.data.suspendedUntil"));
        assertThat(until).isBetween(LocalDateTime.now().plusDays(7).minusMinutes(1), LocalDateTime.now().plusDays(7));

        mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(user.getId()))
                        .header("Authorization", admin).contentType("application/json")
                        .content("{\"days\":null,\"reason\":\"거듭된 욕설\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(true))
                .andExpect(jsonPath("$.data.indefinite").value(true))
                .andExpect(jsonPath("$.data.suspendedUntil").doesNotExist());
    }

    @Test
    @DisplayName("기간은 1·7·30일과 무기한만 받고, 사유는 비울 수 없다 — 400")
    void validatesSuspendRequest() throws Exception {
        User user = saveUser(Role.USER);
        String admin = bearer(saveUser(Role.ADMIN));
        for (String bad : new String[]{
                "{\"days\":3,\"reason\":\"도배\"}", "{\"days\":0,\"reason\":\"도배\"}",
                "{\"days\":7,\"reason\":\"  \"}", "{\"days\":7}"}) {
            mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(user.getId()))
                            .header("Authorization", admin).contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    /** 관리자를 정지할 수 있으면 관리자끼리 서로를, 또는 실수로 자기를 막는다. */
    @Test
    @DisplayName("관리자 계정은 정지할 수 없다 — 400 USER_002. 없는 사용자는 404 USER_001")
    void cannotSuspendAdminOrUnknown() throws Exception {
        String admin = bearer(saveUser(Role.ADMIN));
        User otherAdmin = saveUser(Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(otherAdmin.getId()))
                        .header("Authorization", admin).contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"도배\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_002"));
        mockMvc.perform(post("/api/admin/users/999999999/suspend")
                        .header("Authorization", admin).contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"도배\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_001"));
    }

    @Test
    @DisplayName("정지·해제·사용자 목록은 관리자만 쓴다")
    void adminOnly() throws Exception {
        User target = saveUser(Role.USER);
        String user = bearer(saveUser(Role.USER));

        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/users").header("Authorization", user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(target.getId()))
                        .header("Authorization", user).contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"도배\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/%d/unsuspend".formatted(target.getId())).header("Authorization", user))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("사용자 목록 — 닉네임이나 아이디 일부로 찾고, 정지된 사람만 추릴 수 있다")
    void searchesUsers() throws Exception {
        String admin = bearer(saveUser(Role.ADMIN));
        String key = UUID.randomUUID().toString().substring(0, 8);
        User free = saveUser(Role.USER, "찾" + key + "가");
        User blocked = saveUser(Role.USER, "찾" + key + "나");
        suspend(blocked, 7);

        mockMvc.perform(get("/api/admin/users").param("q", "찾" + key).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                // 비밀번호 해시는 어떤 모양으로도 나가지 않는다.
                .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist());
        mockMvc.perform(get("/api/admin/users").param("q", free.getUsername()).header("Authorization", admin))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].nickname").value(free.getNickname()));
        // "_"와 "%"는 글자 그대로 찾는다. 와일드카드로 읽히면 둘 다 걸린다.
        mockMvc.perform(get("/api/admin/users").param("q", "찾" + key + "_").header("Authorization", admin))
                .andExpect(jsonPath("$.data.content", hasSize(0)));
        mockMvc.perform(get("/api/admin/users").param("q", "찾" + key + "%").header("Authorization", admin))
                .andExpect(jsonPath("$.data.content", hasSize(0)));
        mockMvc.perform(get("/api/admin/users").param("q", "찾" + key).param("suspendedOnly", "true")
                        .header("Authorization", admin))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].id").value(blocked.getId()))
                .andExpect(jsonPath("$.data.content[0].suspended").value(true));
    }

    /* ── 재료 ───────────────────────────────────────────── */

    /** 관리자 API로 정지한다. {@code days}가 null이면 무기한. */
    private void suspend(User user, Integer days) throws Exception {
        mockMvc.perform(post("/api/admin/users/%d/suspend".formatted(user.getId()))
                        .header("Authorization", bearer(saveUser(Role.ADMIN))).contentType("application/json")
                        .content("{\"days\":%s,\"reason\":\"도배\"}".formatted(days)))
                .andExpect(status().isOk());
    }

    private User solver(Problem problem) {
        User user = saveUser(Role.USER);
        submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
        return user;
    }

    private User saveUser(Role role) {
        return saveUser(role, "정" + UUID.randomUUID().toString().substring(0, 8));
    }

    private User saveUser(Role role, String nickname) {
        User user = User.builder()
                .username("susp" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build();
        user.changeNickname(nickname);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), user.getRole());
    }

    private Post savePost(Problem problem, Long userId) {
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        return postRepository.saveAndFlush(Post.of(discussionId, userId, PostCategory.QUESTION, "글", "본문"));
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
