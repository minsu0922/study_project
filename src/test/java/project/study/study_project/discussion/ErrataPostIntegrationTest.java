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
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 콘솔의 "오류 지적" 글 목록 — 무엇이 나오고, 무엇으로 처리했다고 보는가.
 *
 * <p>개발 DB에 이미 있는 글이 섞여 나올 수 있어, 건수가 아니라 내가 만든 글이 들었는지로 확인한다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ErrataPostIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
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

    @Test
    @DisplayName("오류 지적 글만 나온다 — 다른 말머리의 글과 가린 글·지운 글은 빠진다")
    void listsOnlyVisibleErrata() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER);
        Post errata = savePost(problem, writer, PostCategory.ERRATA);
        Post question = savePost(problem, writer, PostCategory.QUESTION);
        Post hidden = savePost(problem, writer, PostCategory.ERRATA);
        hidden.hide();
        Post deleted = savePost(problem, writer, PostCategory.ERRATA);
        deleted.delete();
        postRepository.flush();

        mockMvc.perform(get("/api/admin/errata-posts").param("unanswered", "false")
                        .header("Authorization", bearer(saveUser(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", hasItem(errata.getId().intValue())))
                .andExpect(jsonPath("$.data.content[*].id", not(hasItem(question.getId().intValue()))))
                .andExpect(jsonPath("$.data.content[*].id", not(hasItem(hidden.getId().intValue()))))
                .andExpect(jsonPath("$.data.content[*].id", not(hasItem(deleted.getId().intValue()))))
                // 판단에 쓰는 것이 한 줄에 다 있어야 한다: 본문 전문과 고치러 갈 문제.
                .andExpect(jsonPath("$.data.content[?(@.id == %d)].body".formatted(errata.getId()),
                        hasItem("본문입니다")))
                .andExpect(jsonPath("$.data.content[?(@.id == %d)].problemId".formatted(errata.getId()),
                        hasItem(problem.getId().intValue())));
    }

    /** 처리 표시를 따로 두지 않는다. 운영진의 댓글이 글쓴이에게 가는 답이자 처리 기록이다. */
    @Test
    @DisplayName("운영진이 댓글을 달면 답한 글이 된다 — 기본 목록에서 빠지고 전체에는 남는다")
    void staffReplyMarksAnswered() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        Post waiting = savePost(problem, writer, PostCategory.ERRATA);
        Post userOnly = savePost(problem, writer, PostCategory.ERRATA);
        Post answered = savePost(problem, writer, PostCategory.ERRATA);
        // 다른 학습자의 댓글은 답이 아니다.
        commentRepository.saveAndFlush(Comment.of(userOnly.getId(), saveUser(Role.USER).getId(), null, "저도 그렇게 봤어요"));
        commentRepository.saveAndFlush(Comment.of(answered.getId(), admin.getId(), null, "고쳤습니다"));

        mockMvc.perform(get("/api/admin/errata-posts").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", hasItem(waiting.getId().intValue())))
                .andExpect(jsonPath("$.data.content[*].id", hasItem(userOnly.getId().intValue())))
                .andExpect(jsonPath("$.data.content[*].id", not(hasItem(answered.getId().intValue()))));

        mockMvc.perform(get("/api/admin/errata-posts").param("unanswered", "false")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.content[?(@.id == %d)].staffReplied".formatted(answered.getId()),
                        hasItem(true)))
                .andExpect(jsonPath("$.data.content[?(@.id == %d)].staffReplied".formatted(userOnly.getId()),
                        hasItem(false)))
                .andExpect(jsonPath("$.data.content[?(@.id == %d)].commentCount".formatted(userOnly.getId()),
                        hasItem(1)));
    }

    @Test
    @DisplayName("운영진의 댓글을 가리면 다시 답을 기다리는 글이 된다")
    void hiddenStaffReplyDoesNotCount() throws Exception {
        Problem problem = saveProblem();
        User admin = saveUser(Role.ADMIN);
        Post post = savePost(problem, saveUser(Role.USER), PostCategory.ERRATA);
        Comment reply = commentRepository.saveAndFlush(Comment.of(post.getId(), admin.getId(), null, "고쳤습니다"));
        reply.hide();
        commentRepository.flush();

        mockMvc.perform(get("/api/admin/errata-posts").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.content[*].id", hasItem(post.getId().intValue())));
    }

    @Test
    @DisplayName("배지 수는 답을 기다리는 글만 센다 — 글이 올라오면 하나 늘고 운영진이 답하면 하나 준다")
    void pendingCountFollowsReplies() throws Exception {
        User admin = saveUser(Role.ADMIN);
        long before = pendingCount(admin);

        Post post = savePost(saveProblem(), saveUser(Role.USER), PostCategory.ERRATA);
        assertThat(pendingCount(admin)).isEqualTo(before + 1);

        commentRepository.saveAndFlush(Comment.of(post.getId(), admin.getId(), null, "고쳤습니다"));
        assertThat(pendingCount(admin)).isEqualTo(before);
    }

    private long pendingCount(User admin) throws Exception {
        String body = mockMvc.perform(get("/api/admin/errata-posts/pending-count").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.count")).longValue();
    }

    @Test
    @DisplayName("관리자만 본다 — 비로그인 401, 일반 사용자 403")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/errata-posts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/errata-posts").header("Authorization", bearer(saveUser(Role.USER))))
                .andExpect(status().isForbidden());
    }

    /* ── 재료 ───────────────────────────────────────────── */

    private User saveUser(Role role) {
        User user = User.builder()
                .username("erra" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build();
        user.changeNickname("오" + UUID.randomUUID().toString().substring(0, 8));
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), user.getRole());
    }

    private Post savePost(Problem problem, User writer, PostCategory category) {
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        return postRepository.saveAndFlush(Post.of(discussionId, writer.getId(), category, "정답이 틀린 것 같습니다", "본문입니다"));
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
