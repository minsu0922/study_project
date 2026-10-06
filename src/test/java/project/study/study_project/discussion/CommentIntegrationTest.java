package project.study.study_project.discussion;

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
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.dto.WithdrawRequest;
import project.study.study_project.user.service.AccountService;

import java.util.HashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Map;

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
    private TestFixtures fixtures;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private EntityManager em;

    /* ── 읽기 ───────────────────────────────────────────── */

    @Test
    @DisplayName("비로그인도 읽을 수 있다 — 글이 없으면 빈 목록, solved는 false")
    void anonymousCanRead() throws Exception {
        Problem problem = fixtures.problem();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solved").value(false))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.comments", hasSize(0)));
    }

    @Test
    @DisplayName("없는 글의 댓글은 404 DISCUSSION_011")
    void unknownPost() throws Exception {
        mockMvc.perform(get("/api/quiz/posts/999999999/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
    }

    @Test
    @DisplayName("지운 글에는 댓글을 읽을 수도 쓸 수도 없다 — 404 DISCUSSION_011")
    void deletedPostHasNoComments() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        write(fixtures.bearer(user), problem.getId(), null, "지워지기 전 댓글");
        postRepository.findById(postId(problem.getId())).orElseThrow().delete();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "늦은 댓글")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
    }

    /** 가린 글 아래의 댓글이 그대로 보이면 댓글만 읽어도 가린 내용을 짐작할 수 있다. */
    @Test
    @DisplayName("가린 글은 댓글도 내보내지 않고, 새 댓글도 받지 않는다 — 409 DISCUSSION_006")
    void hiddenPostHidesComments() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        write(fixtures.bearer(user), problem.getId(), null, "가려지기 전 댓글");
        postRepository.findById(postId(problem.getId())).orElseThrow().hide();

        mockMvc.perform(get(listPath(problem)).header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.comments", hasSize(0)));
        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "늦은 댓글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    /* ── 쓰기 권한 ───────────────────────────────────────── */

    @Test
    @DisplayName("비로그인은 쓸 수 없다")
    void writeRequiresLogin() throws Exception {
        mockMvc.perform(post(WRITE).contentType("application/json")
                        .content(writeBody(fixtures.problem().getId(), null, "글")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("안 푼 문제에는 쓸 수 없다 — 403 DISCUSSION_002")
    void unsolvedCannotWrite() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_002"));
    }

    @Test
    @DisplayName("닉네임이 없으면 409 DISCUSSION_003 — 가입 때 닉네임을 받기 전에 만든 계정이다")
    void needsNickname() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.userWithoutNickname(Role.USER);
        fixtures.solve(user, problem);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_003"));
    }

    @Test
    @DisplayName("틀리게 풀었어도 쓸 수 있다 — 제출이 있으면 푼 것이다")
    void wrongAnswerStillCounts() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        submissionRepository.save(Submission.of(user.getId(), problem, "X", false));

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "왜 틀렸을까요")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("관리자는 풀지 않아도 쓸 수 있다")
    void adminWritesWithoutSolving() throws Exception {
        Problem problem = fixtures.problem();
        User admin = fixtures.user(Role.ADMIN);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(admin))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "안내드립니다")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본문이 비었거나 1,000자를 넘으면 400")
    void validatesBody() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        String token = fixtures.bearer(user);

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
        Problem problem = fixtures.problem();
        User writer = fixtures.user(Role.USER);
        fixtures.solve(writer, problem);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(writer))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "첫 글")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value(writer.getNickname()))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));

        mockMvc.perform(get(listPath(problem)).header("Authorization", fixtures.bearer(writer)))
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
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        String token = fixtures.bearer(user);

        long rootId = write(token, problem.getId(), null, "원글");
        long replyId = write(token, problem.getId(), rootId, "답글");
        write(token, problem.getId(), replyId, "답글의 답글");

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].replies", hasSize(2)))
                .andExpect(jsonPath("$.data.comments[0].replies[1].body").value("답글의 답글"))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    /** 받아 주면 한 글의 답글이 다른 글의 댓글에 매달린다. */
    @Test
    @DisplayName("다른 글의 댓글을 부모로 주면 404 DISCUSSION_001")
    void parentFromAnotherProblemIsRejected() throws Exception {
        Problem a = fixtures.problem();
        Problem b = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, a);
        fixtures.solve(user, b);
        String token = fixtures.bearer(user);
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
        Problem problem = fixtures.problem();
        User writer = fixtures.user(Role.USER);
        User other = fixtures.user(Role.USER);
        fixtures.solve(writer, problem);
        long id = write(fixtures.bearer(writer), problem.getId(), null, "처음");

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", fixtures.bearer(other))
                        .contentType("application/json").content("{\"body\":\"남이 고침\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_005"));

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", fixtures.bearer(writer))
                        .contentType("application/json").content("{\"body\":\"고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("고침"))
                .andExpect(jsonPath("$.data.edited").value(true));
    }

    @Test
    @DisplayName("답글이 달린 글을 지우면 자리가 남고 본문은 안 나간다")
    void deletedWithRepliesKeepsPlaceholder() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        String token = fixtures.bearer(user);
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
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        String token = fixtures.bearer(user);
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
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        fixtures.solve(user, problem);
        long id = write(fixtures.bearer(user), problem.getId(), null, "가려질 글");
        Comment comment = commentRepository.findById(id).orElseThrow();
        comment.hide();
        em.flush();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.comments[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), id, "가려진 글에 답글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    /* ── 탈퇴 ───────────────────────────────────────────── */

    /** 탈퇴가 외래키에 걸려 실패하지 않고, 남은 글은 글쓴이가 빈 채로 보인다. */
    @Test
    @DisplayName("탈퇴해도 댓글은 남고 글쓴이만 비어 나간다")
    void withdrawKeepsComments() throws Exception {
        Problem problem = fixtures.problem();
        User writer = fixtures.user(Role.USER);
        fixtures.solve(writer, problem);
        write(fixtures.bearer(writer), problem.getId(), null, "남을 글");
        em.flush();

        accountService.withdraw(writer.getId(), new WithdrawRequest(TestFixtures.PASSWORD));
        em.clear();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].body").value("남을 글"))
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist());
    }

    /* ── 재료 ───────────────────────────────────────────── */

    /** 문제마다 글 하나를 깔아 둔다. 댓글 테스트는 "그 문제의 글"에 쓰고 읽는다. */
    private final Map<Long, Long> postIds = new HashMap<>();

    private Long postId(Long problemId) {
        return postIds.computeIfAbsent(problemId, id -> {
            discussionRepository.insertIfAbsent(id);
            Long discussionId = discussionRepository.findIdByProblemIdForShare(id).orElseThrow();
            return postRepository.saveAndFlush(Post.of(discussionId, null, PostCategory.QUESTION, "토론할 글", "본문")).getId();
        });
    }

    private String listPath(Problem problem) {
        return "/api/quiz/posts/" + postId(problem.getId()) + "/comments";
    }

    private String writeBody(Long problemId, Long parentId, String body) {
        return "{\"postId\":%d,\"parentId\":%s,\"body\":\"%s\"}".formatted(postId(problemId), parentId, body);
    }

    private long write(String token, Long problemId, Long parentId, String body) throws Exception {
        String response = mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problemId, parentId, body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int at = response.indexOf("\"id\":");
        return Long.parseLong(response.substring(at + 5, response.indexOf(',', at)).trim());
    }
}
