package project.study.study_project.user;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
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
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.quiz.domain.Problem;
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
    private TestFixtures fixtures;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CommentRepository commentRepository;

    /* ── 정지하면 막히는 것 ───────────────────────────────── */

    @Test
    @DisplayName("정지된 사용자는 글·댓글을 쓰지도 고치지도 못하고 신고도 못 한다 — 403 DISCUSSION_013")
    void suspendedCannotWrite() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        String token = fixtures.bearer(user);
        Post own = fixtures.post(problem, user.getId());
        Comment ownComment = commentRepository.saveAndFlush(Comment.of(own.getId(), user.getId(), null, "내 댓글"));
        Post others = fixtures.post(problem, null);
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
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        String body = "{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId());

        suspend(user, 7);
        String until = userRepository.findById(user.getId()).orElseThrow().getSuspendedUntil().toLocalDate().toString();
        mockMvc.perform(post("/api/me/posts").header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.error.message", containsString(until)))
                .andExpect(jsonPath("$.error.message", containsString("도배")));

        suspend(user, null);
        mockMvc.perform(post("/api/me/posts").header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.error.message", containsString("무기한")));
    }

    @Test
    @DisplayName("정지 중에도 읽기와 학습, 자기 글 삭제는 된다")
    void suspendedCanStillReadStudyAndDelete() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        String token = fixtures.bearer(user);
        Post own = fixtures.post(problem, user.getId());
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
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        Post post = fixtures.post(problem, null);

        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts").header("Authorization", fixtures.bearer(user)))
                .andExpect(jsonPath("$.data.suspensionNotice").doesNotExist());

        suspend(user, 7);
        String until = userRepository.findById(user.getId()).orElseThrow().getSuspendedUntil().toLocalDate().toString();
        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts").header("Authorization", fixtures.bearer(user)))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString(until)))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString("도배")));
        mockMvc.perform(get("/api/quiz/posts/" + post.getId() + "/comments").header("Authorization", fixtures.bearer(user)))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.suspensionNotice", containsString(until)));
        // 남의 정지는 보이지 않는다.
        mockMvc.perform(get("/api/quiz/" + problem.getId() + "/posts"))
                .andExpect(jsonPath("$.data.suspensionNotice").doesNotExist());
    }

    @Test
    @DisplayName("내 정지 상태를 물을 수 있다 — 마이페이지가 읽는다")
    void myStatus() throws Exception {
        User user = fixtures.user(Role.USER);

        mockMvc.perform(get("/api/me/suspension")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/suspension").header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(false))
                .andExpect(jsonPath("$.data.notice").doesNotExist());

        suspend(user, null);
        mockMvc.perform(get("/api/me/suspension").header("Authorization", fixtures.bearer(user)))
                .andExpect(jsonPath("$.data.suspended").value(true))
                .andExpect(jsonPath("$.data.notice", containsString("무기한")));
    }

    /* ── 풀리는 것 ───────────────────────────────────────── */

    @Test
    @DisplayName("관리자가 풀면 다시 쓸 수 있다")
    void unsuspendRestoresWriting() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        suspend(user, 30);

        mockMvc.perform(post("/api/admin/users/%d/unsuspend".formatted(user.getId()))
                        .header("Authorization", fixtures.bearer(fixtures.user(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(false));

        mockMvc.perform(post("/api/me/posts").header("Authorization", fixtures.bearer(user)).contentType("application/json")
                        .content("{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("기간이 지나면 아무도 풀지 않아도 다시 쓸 수 있다")
    void expiredSuspensionNoLongerBlocks() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        user.suspend(LocalDateTime.now().minusMinutes(1), "지난 정지");

        mockMvc.perform(post("/api/me/posts").header("Authorization", fixtures.bearer(user)).contentType("application/json")
                        .content("{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isCreated());
    }

    /* ── 정지하는 쪽 ─────────────────────────────────────── */

    @Test
    @DisplayName("정지하면 풀리는 시각과 사유가 돌아온다. 기간을 비우면 무기한이다")
    void suspendReturnsState() throws Exception {
        User user = fixtures.user(Role.USER);
        String admin = fixtures.bearer(fixtures.user(Role.ADMIN));

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
        User user = fixtures.user(Role.USER);
        String admin = fixtures.bearer(fixtures.user(Role.ADMIN));
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
        String admin = fixtures.bearer(fixtures.user(Role.ADMIN));
        User otherAdmin = fixtures.user(Role.ADMIN);

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
        User target = fixtures.user(Role.USER);
        String user = fixtures.bearer(fixtures.user(Role.USER));

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
    @DisplayName("사용자 한 명 — 상세 화면이 읽는다. 정지 상태가 함께 오고, 없는 사용자는 404 USER_001")
    void readsOneUser() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        User user = fixtures.user(Role.USER);

        mockMvc.perform(get("/api/admin/users/" + user.getId()).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(user.getUsername()))
                .andExpect(jsonPath("$.data.nickname").value(user.getNickname()))
                .andExpect(jsonPath("$.data.suspended").value(false))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        suspend(user, 7);
        mockMvc.perform(get("/api/admin/users/" + user.getId()).header("Authorization", admin))
                .andExpect(jsonPath("$.data.suspended").value(true))
                .andExpect(jsonPath("$.data.suspendedReason").value("도배"));

        mockMvc.perform(get("/api/admin/users/999999999").header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_001"));
        mockMvc.perform(get("/api/admin/users/" + user.getId()).header("Authorization", fixtures.bearer(Role.USER)))
                .andExpect(status().isForbidden());
    }

    /** 닉네임을 비우면 그 사람이 쓴 글이 모두 "탈퇴한 사용자"로 보인다. 그래서 다른 이름으로 바꾼다. */
    @Test
    @DisplayName("닉네임 초기화 — \"사용자\" + 번호로 바뀌고, 쓴 글의 이름도 따라 바뀐다")
    void resetsNickname() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        Post post = fixtures.post(problem, user.getId());
        String placeholder = "사용자" + user.getId();

        mockMvc.perform(post("/api/admin/users/%d/reset-nickname".formatted(user.getId())).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(placeholder));

        mockMvc.perform(get("/api/quiz/posts/" + post.getId()))
                .andExpect(jsonPath("$.data.nickname").value(placeholder));
        // 두 번 눌러도 같은 이름이다.
        mockMvc.perform(post("/api/admin/users/%d/reset-nickname".formatted(user.getId())).header("Authorization", admin))
                .andExpect(jsonPath("$.data.nickname").value(placeholder));
    }

    @Test
    @DisplayName("닉네임 초기화 — 그 이름을 다른 사람이 쓰고 있으면 다른 번호를 붙인다. 닉네임이 없는 계정은 그대로 둔다")
    void resetNicknameAvoidsTakenName() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        User user = fixtures.user(Role.USER);
        fixtures.user(Role.USER, "사용자" + user.getId());

        String response = mockMvc.perform(post("/api/admin/users/%d/reset-nickname".formatted(user.getId()))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nickname = JsonPath.read(response, "$.data.nickname");
        assertThat(nickname).startsWith("사용자").isNotEqualTo("사용자" + user.getId()).hasSizeLessThanOrEqualTo(12);

        User old = fixtures.userWithoutNickname(Role.USER);
        mockMvc.perform(post("/api/admin/users/%d/reset-nickname".formatted(old.getId())).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").doesNotExist());
    }

    @Test
    @DisplayName("닉네임 초기화는 관리자만 한다. 없는 사용자는 404 USER_001")
    void resetNicknameAdminOnly() throws Exception {
        User user = fixtures.user(Role.USER);

        mockMvc.perform(post("/api/admin/users/%d/reset-nickname".formatted(user.getId()))
                        .header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/999999999/reset-nickname").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_001"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(user.getNickname());
    }

    /* ── 권한 변경 ───────────────────────────────────────── */

    @Test
    @DisplayName("권한 변경 — 사용자를 관리자로 올리고 다시 내린다. 같은 권한으로 바꾸면 그대로다")
    void changesRole() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        User user = fixtures.user(Role.USER);
        String path = "/api/admin/users/%d/role".formatted(user.getId());

        mockMvc.perform(post(path).header("Authorization", admin)
                        .contentType("application/json").content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        mockMvc.perform(post(path).header("Authorization", admin)
                        .contentType("application/json").content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(path).header("Authorization", admin)
                        .contentType("application/json").content("{\"role\":\"USER\"}"))
                .andExpect(jsonPath("$.data.role").value("USER"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    /** 실수로 자기를 내리면 관리자가 한 명도 안 남을 수 있다. */
    @Test
    @DisplayName("내 권한은 바꿀 수 없다 — 400 USER_003. 정지 중인 사용자는 관리자로 못 올린다 — 409 USER_004")
    void roleChangeGuards() throws Exception {
        User admin = fixtures.user(Role.ADMIN);
        mockMvc.perform(post("/api/admin/users/%d/role".formatted(admin.getId())).header("Authorization", fixtures.bearer(admin))
                        .contentType("application/json").content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_003"));

        User blocked = fixtures.user(Role.USER);
        suspend(blocked, 7);
        mockMvc.perform(post("/api/admin/users/%d/role".formatted(blocked.getId())).header("Authorization", fixtures.bearer(admin))
                        .contentType("application/json").content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_004"));

        for (String bad : new String[]{"{}", "{\"role\":\"OWNER\"}"}) {
            mockMvc.perform(post("/api/admin/users/%d/role".formatted(blocked.getId())).header("Authorization", fixtures.bearer(admin))
                            .contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/admin/users/%d/role".formatted(blocked.getId())).header("Authorization", fixtures.bearer(Role.USER))
                        .contentType("application/json").content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 토큰에는 발급한 순간의 권한이 적혀 있다. 토큰만 보면 내린 사람이 만료될 때까지(1시간)
     * 관리 API를 계속 쓴다.
     */
    @Test
    @DisplayName("관리자에서 내리면 예전 관리자 토큰으로는 바로 관리 API를 못 쓴다 — 403")
    void demotedAdminLosesAccessAtOnce() throws Exception {
        User demoted = fixtures.user(Role.ADMIN);
        String oldToken = fixtures.bearer(demoted);
        mockMvc.perform(get("/api/admin/users").header("Authorization", oldToken)).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/%d/role".formatted(demoted.getId()))
                        .header("Authorization", fixtures.bearer(Role.ADMIN))
                        .contentType("application/json").content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users").header("Authorization", oldToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_004"));
        // 학습 쪽은 그대로 쓴다.
        mockMvc.perform(get("/api/me/suspension").header("Authorization", oldToken)).andExpect(status().isOk());
    }

    /* ── 강제 탈퇴 ───────────────────────────────────────── */

    @Test
    @DisplayName("강제 탈퇴 — 계정과 학습 기록은 지워지고, 쓴 글은 탈퇴한 사용자의 것으로 남는다")
    void removesUser() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        Post post = fixtures.post(problem, user.getId());
        String oldToken = fixtures.bearer(user);

        mockMvc.perform(delete("/api/admin/users/" + user.getId()).header("Authorization", admin))
                .andExpect(status().isOk());
        // 롤백되는 테스트라 플러시된 SQL이 제약에 걸리지 않았는지는 서비스의 flush가 확인해 준다.
        entityManager.clear();

        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(submissionRepository.existsByUserIdAndProblem_Id(user.getId(), problem.getId())).isFalse();
        mockMvc.perform(get("/api/quiz/posts/" + post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("글"))
                .andExpect(jsonPath("$.data.nickname").doesNotExist());
        mockMvc.perform(get("/api/admin/users/" + user.getId()).header("Authorization", admin))
                .andExpect(status().isNotFound());
        // 지워진 사람의 토큰으로는 쓸 수 없다.
        mockMvc.perform(post("/api/me/posts").header("Authorization", oldToken).contentType("application/json")
                        .content("{\"problemId\":%d,\"category\":\"QUESTION\",\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("강제 탈퇴한 아이디로 다시 가입할 수 있다")
    void removedUsernameCanSignUpAgain() throws Exception {
        User user = fixtures.user(Role.USER);
        String username = user.getUsername();
        String nickname = user.getNickname();

        mockMvc.perform(delete("/api/admin/users/" + user.getId()).header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/signup").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"Password123!\",\"nickname\":\"%s\"}".formatted(username, nickname)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    /** 관리자를 지우려면 먼저 내려야 한다. 두 단계를 거치게 해서 실수로 자기나 다른 관리자를 지우지 않게 한다. */
    @Test
    @DisplayName("관리자 계정은 탈퇴시킬 수 없다 — 400 USER_005. 없는 사용자는 404, 일반 사용자는 403")
    void removeGuards() throws Exception {
        User admin = fixtures.user(Role.ADMIN);
        User otherAdmin = fixtures.user(Role.ADMIN);
        User user = fixtures.user(Role.USER);

        for (User target : new User[]{admin, otherAdmin}) {
            mockMvc.perform(delete("/api/admin/users/" + target.getId()).header("Authorization", fixtures.bearer(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("USER_005"));
        }
        mockMvc.perform(delete("/api/admin/users/999999999").header("Authorization", fixtures.bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_001"));
        mockMvc.perform(delete("/api/admin/users/" + user.getId()).header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    @Test
    @DisplayName("사용자 목록 — 닉네임이나 아이디 일부로 찾고, 정지된 사람만 추릴 수 있다")
    void searchesUsers() throws Exception {
        String admin = fixtures.bearer(fixtures.user(Role.ADMIN));
        String key = UUID.randomUUID().toString().substring(0, 8);
        User free = fixtures.user(Role.USER, "찾" + key + "가");
        User blocked = fixtures.user(Role.USER, "찾" + key + "나");
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
                        .header("Authorization", fixtures.bearer(fixtures.user(Role.ADMIN))).contentType("application/json")
                        .content("{\"days\":%s,\"reason\":\"도배\"}".formatted(days)))
                .andExpect(status().isOk());
    }
}
