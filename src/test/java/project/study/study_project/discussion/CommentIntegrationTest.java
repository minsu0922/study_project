package project.study.study_project.discussion;

import jakarta.persistence.EntityManager;
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
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.dto.WithdrawRequest;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.service.AccountService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문제별 토론의 경계 — 누가 읽고 누가 쓸 수 있는가, 지운 글과 가린 글이 어떻게 보이는가.
 *
 * <p>요청 제한은 끈다. 한 테스트가 쓰기를 여러 번 불러 분당 5건에 걸리면 무관한 실패가 난다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CommentIntegrationTest {

    private static final String WRITE = "/api/me/comments";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    /* ── 읽기 ───────────────────────────────────────────── */

    @Test
    @DisplayName("비로그인도 읽을 수 있다 — 글이 없으면 빈 목록, solved는 false")
    void anonymousCanRead() throws Exception {
        Problem problem = saveProblem();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solved").value(false))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.comments", hasSize(0)));
    }

    @Test
    @DisplayName("없는 문제의 토론은 404 QUIZ_001")
    void unknownProblem() throws Exception {
        mockMvc.perform(get("/api/quiz/999999999/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUIZ_001"));
    }

    /* ── 쓰기 권한 ───────────────────────────────────────── */

    @Test
    @DisplayName("비로그인은 쓸 수 없다")
    void writeRequiresLogin() throws Exception {
        mockMvc.perform(post(WRITE).contentType("application/json")
                        .content(writeBody(saveProblem().getId(), null, "글")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("안 푼 문제에는 쓸 수 없다 — 403 DISCUSSION_002")
    void unsolvedCannotWrite() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_002"));
    }

    @Test
    @DisplayName("닉네임이 없으면 409 DISCUSSION_003 — 화면이 닉네임 입력을 띄운다")
    void needsNickname() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, false);
        solve(user, problem);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_003"));
    }

    @Test
    @DisplayName("틀리게 풀었어도 쓸 수 있다 — 제출이 있으면 푼 것이다")
    void wrongAnswerStillCounts() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        submissionRepository.save(Submission.of(user.getId(), problem, "X", false));

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "왜 틀렸을까요")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("관리자는 풀지 않아도 쓸 수 있다")
    void adminWritesWithoutSolving() throws Exception {
        Problem problem = saveProblem();
        User admin = saveUser(Role.ADMIN, true);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(admin))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "안내드립니다")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본문이 비었거나 1,000자를 넘으면 400")
    void validatesBody() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);

        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problem.getId(), null, "   ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problem.getId(), null, "가".repeat(1001))))
                .andExpect(status().isBadRequest());
    }

    /* ── 쓰기와 목록 ─────────────────────────────────────── */

    @Test
    @DisplayName("쓴 글이 목록에 닉네임과 함께 보이고, 내 글에는 mine이 붙는다")
    void writtenCommentAppears() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        solve(writer, problem);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(writer))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "첫 글")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value(writer.getNickname()))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));

        mockMvc.perform(get(listPath(problem)).header("Authorization", bearer(writer)))
                .andExpect(jsonPath("$.data.solved").value(true))
                .andExpect(jsonPath("$.data.canWrite").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.comments[0].body").value("첫 글"))
                .andExpect(jsonPath("$.data.comments[0].mine").value(true));

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments[0].mine").value(false));
    }

    @Test
    @DisplayName("답글의 답글은 원글 아래로 붙는다 — 한 단계까지만")
    void replyToReplyAttachesToRoot() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);

        long rootId = write(token, problem.getId(), null, "원글");
        long replyId = write(token, problem.getId(), rootId, "답글");
        write(token, problem.getId(), replyId, "답글의 답글");

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].replies", hasSize(2)))
                .andExpect(jsonPath("$.data.comments[0].replies[1].body").value("답글의 답글"))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    /** 받아 주면 한 방의 답글이 다른 방의 원글에 매달린다. */
    @Test
    @DisplayName("다른 문제의 댓글을 부모로 주면 404 DISCUSSION_001")
    void parentFromAnotherProblemIsRejected() throws Exception {
        Problem a = saveProblem();
        Problem b = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, a);
        solve(user, b);
        String token = bearer(user);
        long commentOnB = write(token, b.getId(), null, "B의 글");

        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(a.getId(), commentOnB, "A에 다는 답글")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_001"));
    }

    /* ── 수정·삭제 ───────────────────────────────────────── */

    @Test
    @DisplayName("내 글만 고칠 수 있고, 고치면 edited가 붙는다")
    void editOnlyOwn() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        User other = saveUser(Role.USER, true);
        solve(writer, problem);
        long id = write(bearer(writer), problem.getId(), null, "처음");

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", bearer(other))
                        .contentType("application/json").content("{\"body\":\"남이 고침\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_005"));

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", bearer(writer))
                        .contentType("application/json").content("{\"body\":\"고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("고침"))
                .andExpect(jsonPath("$.data.edited").value(true));
    }

    @Test
    @DisplayName("답글이 달린 글을 지우면 자리가 남고 본문은 안 나간다")
    void deletedWithRepliesKeepsPlaceholder() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);
        long rootId = write(token, problem.getId(), null, "지울 글");
        write(token, problem.getId(), rootId, "답글");

        mockMvc.perform(delete(WRITE + "/" + rootId).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].status").value("DELETED"))
                .andExpect(jsonPath("$.data.comments[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].replies", hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("답글이 없는 글을 지우면 목록에서 빠진다")
    void deletedWithoutRepliesDisappears() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);
        long id = write(token, problem.getId(), null, "지울 글");

        mockMvc.perform(delete(WRITE + "/" + id).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("가려진 글은 자리만 보이고 본문과 닉네임은 응답에 없다")
    void hiddenBodyIsNotExposed() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        long id = write(bearer(user), problem.getId(), null, "가려질 글");
        Comment comment = commentRepository.findById(id).orElseThrow();
        comment.hide();
        em.flush();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.comments[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), id, "가려진 글에 답글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    /* ── 탈퇴 ───────────────────────────────────────────── */

    /** 탈퇴가 외래키에 걸려 실패하지 않고, 남은 글은 글쓴이가 빈 채로 보인다. */
    @Test
    @DisplayName("탈퇴해도 댓글은 남고 글쓴이만 비어 나간다")
    void withdrawKeepsComments() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        solve(writer, problem);
        write(bearer(writer), problem.getId(), null, "남을 글");
        em.flush();

        accountService.withdraw(writer.getId(), new WithdrawRequest("password123"));
        em.clear();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].body").value("남을 글"))
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist());
    }

    /* ── 문제별 개수 ─────────────────────────────────────── */

    @Test
    @DisplayName("문제별 댓글 수 — 글이 없는 문제는 응답에 없다")
    void commentCounts() throws Exception {
        Problem with = saveProblem();
        Problem without = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, with);
        write(bearer(user), with.getId(), null, "하나");
        write(bearer(user), with.getId(), null, "둘");

        mockMvc.perform(get("/api/quiz/comment-counts")
                        .param("problemIds", with.getId() + "," + without.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + with.getId() + "']").value(2))
                .andExpect(jsonPath("$.data['" + without.getId() + "']").doesNotExist());
    }

    /* ── 재료 ───────────────────────────────────────────── */

    private String listPath(Problem problem) {
        return "/api/quiz/" + problem.getId() + "/comments";
    }

    private String writeBody(Long problemId, Long parentId, String body) {
        return "{\"problemId\":%d,\"parentId\":%s,\"body\":\"%s\"}".formatted(problemId, parentId, body);
    }

    private long write(String token, Long problemId, Long parentId, String body) throws Exception {
        String response = mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problemId, parentId, body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int at = response.indexOf("\"id\":");
        return Long.parseLong(response.substring(at + 5, response.indexOf(',', at)).trim());
    }

    private User saveUser(Role role, boolean withNickname) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        User user = User.builder()
                .username("disc" + key)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build();
        if (withNickname) {
            user.changeNickname("닉" + key);
        }
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), user.getRole());
    }

    private void solve(User user, Problem problem) {
        submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
