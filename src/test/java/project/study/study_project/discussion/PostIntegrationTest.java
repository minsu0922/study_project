package project.study.study_project.discussion;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.TestDomains;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

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
 * 토론방 게시글의 경계 — 방이 언제 생기는가, 누가 읽고 쓰는가, 지운 글과 가린 글이 어떻게 보이는가.
 *
 * <p>요청 제한은 끈다. 한 테스트가 쓰기를 여러 번 불러 분당 5건에 걸리면 무관한 실패가 난다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class PostIntegrationTest {

    private static final String WRITE = "/api/me/posts";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private project.study.study_project.discussion.repository.CommentRepository commentRepository;
    @Autowired
    private DiscussionRepository discussionRepository;

    /* ── 방 ─────────────────────────────────────────────── */

    @Test
    @DisplayName("글이 없는 문제에는 토론방이 없다 — 목록은 빈 채로 200")
    void noRoomUntilFirstPost() throws Exception {
        Problem problem = fixtures.problem();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solved").value(false))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.posts", hasSize(0)));
        assertThat(discussionRepository.countByProblemId(problem.getId())).isZero();
    }

    @Test
    @DisplayName("첫 글이 방을 만들고, 둘째 글은 같은 방에 들어간다")
    void firstPostCreatesRoom() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);

        write(fixtures.bearer(user), problem.getId(), "첫 글", "본문");
        write(fixtures.bearer(user), problem.getId(), "둘째 글", "본문");

        assertThat(discussionRepository.countByProblemId(problem.getId())).isEqualTo(1);
        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.total").value(2))
                // 새 글이 위에 온다.
                .andExpect(jsonPath("$.data.posts[0].title").value("둘째 글"))
                .andExpect(jsonPath("$.data.posts[1].title").value("첫 글"));
    }

    @Test
    @DisplayName("없는 문제의 글 목록은 404 QUIZ_001")
    void unknownProblem() throws Exception {
        mockMvc.perform(get("/api/quiz/999999999/posts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUIZ_001"));
    }

    /* ── 쓰기 권한 ───────────────────────────────────────── */

    @Test
    @DisplayName("비로그인은 쓸 수 없다")
    void writeRequiresLogin() throws Exception {
        mockMvc.perform(post(WRITE).contentType("application/json")
                        .content(writeBody(fixtures.problem().getId(), "제목", "본문")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("안 푼 문제에는 쓸 수 없다 — 403 DISCUSSION_002, 방도 생기지 않는다")
    void unsolvedCannotWrite() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), "제목", "본문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_002"));
        assertThat(discussionRepository.countByProblemId(problem.getId())).isZero();
    }

    @Test
    @DisplayName("닉네임이 없으면 409 DISCUSSION_003")
    void needsNickname() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.userWithoutNickname(Role.USER);
        fixtures.solve(user, problem);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), "제목", "본문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_003"));
    }

    @Test
    @DisplayName("관리자는 안 푼 문제에도 쓸 수 있다")
    void adminCanWriteWithoutSolving() throws Exception {
        Problem problem = fixtures.problem();
        User admin = fixtures.user(Role.ADMIN);

        mockMvc.perform(post(WRITE).header("Authorization", fixtures.bearer(admin))
                        .contentType("application/json").content(writeBody(problem.getId(), "제목", "본문")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("제목은 2~100자, 본문은 1~5000자 — 벗어나면 400")
    void validatesLength() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));

        String[][] bad = {
                {"가", "본문"}, {"   ", "본문"}, {"가".repeat(101), "본문"},
                {"제목", ""}, {"제목", "   "}, {"제목", "가".repeat(5001)}
        };
        for (String[] pair : bad) {
            mockMvc.perform(post(WRITE).header("Authorization", token)
                            .contentType("application/json").content(writeBody(problem.getId(), pair[0], pair[1])))
                    .andExpect(status().isBadRequest());
        }
    }

    /* ── 읽기 ───────────────────────────────────────────── */

    @Test
    @DisplayName("비로그인도 글을 읽는다 — 글쓴이는 닉네임으로 보이고 mine은 false")
    void anonymousReadsDetail() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        long id = write(fixtures.bearer(user), problem.getId(), "502와 504 차이", "첫 줄\\n둘째 줄");

        mockMvc.perform(get(detailPath(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problemId").value(problem.getId()))
                .andExpect(jsonPath("$.data.title").value("502와 504 차이"))
                .andExpect(jsonPath("$.data.body").value("첫 줄\n둘째 줄"))
                .andExpect(jsonPath("$.data.nickname").value(user.getNickname()))
                .andExpect(jsonPath("$.data.mine").value(false))
                .andExpect(jsonPath("$.data.edited").value(false));
    }

    @Test
    @DisplayName("목록은 본문을 싣지 않는다")
    void listHasNoBody() throws Exception {
        Problem problem = fixtures.problem();
        write(fixtures.bearer(fixtures.solver(problem)), problem.getId(), "제목", "본문");

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.posts[0].title").value("제목"))
                .andExpect(jsonPath("$.data.posts[0].body").doesNotExist());
    }

    @Test
    @DisplayName("없는 글은 404 DISCUSSION_011")
    void unknownPost() throws Exception {
        mockMvc.perform(get(detailPath(999999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
    }

    @Test
    @DisplayName("한 쪽은 20건 — 넘으면 hasNext가 true이고 다음 쪽에 나머지가 온다")
    void paginates() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        for (int i = 0; i < 21; i++) {
            write(token, problem.getId(), "제목 " + i, "본문");
        }

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.posts", hasSize(20)));
        mockMvc.perform(get(listPath(problem)).param("page", "1"))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.posts", hasSize(1)));
    }

    /* ── 개수 ───────────────────────────────────────────── */

    @Test
    @DisplayName("글 목록의 한 줄에 보이는 댓글 수가 붙는다 — 지운 댓글은 세지 않는다")
    void listCarriesCommentCount() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        long withComments = write(fixtures.bearer(user), problem.getId(), "댓글 있는 글", "본문");
        write(fixtures.bearer(user), problem.getId(), "댓글 없는 글", "본문");
        commentRepository.save(Comment.of(withComments, user.getId(), null, "하나"));
        commentRepository.save(Comment.of(withComments, user.getId(), null, "둘"));
        commentRepository.save(Comment.of(withComments, user.getId(), null, "지운 것")).delete();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.posts[0].title").value("댓글 없는 글"))
                .andExpect(jsonPath("$.data.posts[0].commentCount").value(0))
                .andExpect(jsonPath("$.data.posts[1].commentCount").value(2));
    }

    @Test
    @DisplayName("문제별 글 수 — 글이 없는 문제는 응답에 없고, 지우거나 가린 글은 세지 않는다")
    void postCounts() throws Exception {
        Problem with = fixtures.problem();
        Problem without = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(with));
        write(token, with.getId(), "글 하나", "본문");
        write(token, with.getId(), "글 둘", "본문");
        long deleted = write(token, with.getId(), "지울 글", "본문");
        postRepository.findById(deleted).orElseThrow().delete();

        mockMvc.perform(get("/api/quiz/post-counts")
                        .param("problemIds", with.getId() + "," + without.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + with.getId() + "']").value(2))
                .andExpect(jsonPath("$.data['" + without.getId() + "']").doesNotExist());
    }

    /* ── 최근 글 ─────────────────────────────────────────── */

    /** 커뮤니티 첫 화면이 읽는다. 글만 보고는 어느 문제 이야기인지 알 수 없어 문제를 함께 싣는다. */
    @Test
    @DisplayName("모든 토론방의 최근 글 — 새 글부터, 어느 문제의 글인지와 함께, 비로그인도 읽는다")
    void recentPostsAcrossRooms() throws Exception {
        Problem a = fixtures.problem();
        Problem b = fixtures.problem();
        User user = fixtures.solver(a);
        fixtures.solve(user, b);
        long onA = write(fixtures.bearer(user), a.getId(), "A 방의 글", "본문");
        long onB = write(fixtures.bearer(user), b.getId(), "B 방의 글", "본문");
        commentRepository.save(Comment.of(onA, user.getId(), null, "댓글"));

        mockMvc.perform(get("/api/quiz/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts[0].id").value(onB))
                .andExpect(jsonPath("$.data.posts[0].problemId").value(b.getId()))
                .andExpect(jsonPath("$.data.posts[0].problemTitle").value("TCP 3-way handshake"))
                .andExpect(jsonPath("$.data.posts[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.posts[1].id").value(onA))
                .andExpect(jsonPath("$.data.posts[1].title").value("A 방의 글"))
                .andExpect(jsonPath("$.data.posts[1].nickname").value(user.getNickname()))
                .andExpect(jsonPath("$.data.posts[1].commentCount").value(1));
    }

    /** 방 안의 목록은 가린 글의 자리를 남기지만, 여기는 여러 방을 섞은 목록이라 자리를 남길 이유가 없다. */
    @Test
    @DisplayName("최근 글에는 지운 글도 가린 글도 나오지 않는다")
    void recentPostsSkipGoneOnes() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        long kept = write(token, problem.getId(), "남는 글", "본문");
        long deleted = write(token, problem.getId(), "지울 글", "본문");
        long hidden = write(token, problem.getId(), "가릴 글", "본문");
        postRepository.findById(deleted).orElseThrow().delete();
        postRepository.findById(hidden).orElseThrow().hide();

        mockMvc.perform(get("/api/quiz/posts"))
                .andExpect(jsonPath("$.data.posts[0].id").value(kept));
    }

    /* ── 찾기·정렬·분야 ──────────────────────────────────── */

    /** 개발 DB에 다른 글이 있어도 깨지지 않게, 이 테스트만 쓰는 낱말로 찾아 결과를 좁힌다. */
    @Test
    @DisplayName("검색어는 제목과 본문에서 찾는다 — 대소문자를 가리지 않고, 없으면 빈 목록")
    void searchesTitleAndBody() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        long inTitle = write(token, problem.getId(), word + " 제목에 있는 글", "본문");
        long inBody = write(token, problem.getId(), "평범한 제목", "본문 안에 " + word + " 가 있다");
        write(token, problem.getId(), "상관없는 글", "본문");

        mockMvc.perform(get("/api/quiz/posts").param("q", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts", hasSize(2)))
                .andExpect(jsonPath("$.data.posts[0].id").value(inBody))
                .andExpect(jsonPath("$.data.posts[1].id").value(inTitle));
        mockMvc.perform(get("/api/quiz/posts").param("q", word.toLowerCase()))
                .andExpect(jsonPath("$.data.posts", hasSize(2)));
        mockMvc.perform(get("/api/quiz/posts").param("q", word + "없는말"))
                .andExpect(jsonPath("$.data.posts", hasSize(0)));
    }

    /** %와 _는 LIKE의 와일드카드다. 그대로 넘기면 "%"로 찾았을 때 모든 글이 나온다. */
    @Test
    @DisplayName("검색어의 %와 _는 글자 그대로 찾는다")
    void searchTreatsWildcardsLiterally() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        long withPercent = write(token, problem.getId(), word + " 100% 확실", "본문");
        write(token, problem.getId(), word + " 100점 확실", "본문");

        mockMvc.perform(get("/api/quiz/posts").param("q", word + " 100%"))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(withPercent));
        mockMvc.perform(get("/api/quiz/posts").param("q", word + " 100_"))
                .andExpect(jsonPath("$.data.posts", hasSize(0)));
    }

    @Test
    @DisplayName("댓글 많은 순으로 정렬할 수 있다 — 같으면 새 글이 먼저, 지운 댓글은 세지 않는다")
    void sortsByCommentCount() throws Exception {
        Problem problem = fixtures.problem();
        User user = fixtures.solver(problem);
        String token = fixtures.bearer(user);
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        long quiet = write(token, problem.getId(), word + " 조용한 글", "본문");
        long busy = write(token, problem.getId(), word + " 붐비는 글", "본문");
        long newest = write(token, problem.getId(), word + " 가장 새 글", "본문");
        commentRepository.save(Comment.of(busy, user.getId(), null, "하나"));
        commentRepository.save(Comment.of(busy, user.getId(), null, "둘"));
        commentRepository.save(Comment.of(quiet, user.getId(), null, "지운 것")).delete();

        mockMvc.perform(get("/api/quiz/posts").param("q", word).param("sort", "comments"))
                .andExpect(jsonPath("$.data.posts[0].id").value(busy))
                .andExpect(jsonPath("$.data.posts[1].id").value(newest))
                .andExpect(jsonPath("$.data.posts[2].id").value(quiet));
        // 모르는 정렬 값은 400이다. 조용히 최신순으로 답하면 화면의 선택과 결과가 어긋난 줄 모른다.
        mockMvc.perform(get("/api/quiz/posts").param("sort", "random"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("분야로 걸러 볼 수 있고, 한 줄에 그 글이 속한 문제의 분야가 실린다")
    void filtersByDomain() throws Exception {
        Problem network = fixtures.problem();
        Problem security = problemRepository.save(Problem.create(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX,
                "보안 문제", "지문", "O", "해설", null));
        User user = fixtures.solver(network);
        fixtures.solve(user, security);
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        long onNetwork = write(fixtures.bearer(user), network.getId(), word + " 네트워크 글", "본문");
        long onSecurity = write(fixtures.bearer(user), security.getId(), word + " 보안 글", "본문");

        mockMvc.perform(get("/api/quiz/posts").param("q", word).param("domain", "SECURITY"))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(onSecurity))
                .andExpect(jsonPath("$.data.posts[0].domain").value("SECURITY"));
        mockMvc.perform(get("/api/quiz/posts").param("q", word).param("domain", "NETWORK"))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(onNetwork));
    }

    /* ── 탭: 토론방 · 내 활동 ─────────────────────────────── */

    /** 개발 DB의 다른 방이 섞여도 깨지지 않게, 이 테스트만 쓰는 분야로 좁혀 본다. */
    @Test
    @DisplayName("토론방 목록 — 보이는 글이 있는 문제만, 최근 글이 달린 방부터, 방마다 글 수와 함께")
    void roomList() throws Exception {
        Problem older = problemRepository.save(Problem.create(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX, "먼저 글이 달린 방", "지문", "O", "해설", null));
        Problem newer = problemRepository.save(Problem.create(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX, "나중에 글이 달린 방", "지문", "O", "해설", null));
        Problem emptied = problemRepository.save(Problem.create(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX, "글을 다 지운 방", "지문", "O", "해설", null));
        User user = fixtures.solver(older);
        fixtures.solve(user, newer);
        fixtures.solve(user, emptied);
        String token = fixtures.bearer(user);
        write(token, older.getId(), "첫째 글", "본문");
        write(token, older.getId(), "둘째 글", "본문");
        write(token, newer.getId(), "셋째 글", "본문");
        long gone = write(token, emptied.getId(), "지울 글", "본문");
        postRepository.findById(gone).orElseThrow().delete();

        String ours = "$.data.rooms[?(@.problemId == %d)]";
        mockMvc.perform(get("/api/quiz/rooms").param("domain", "SECURITY").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(ours.formatted(older.getId()) + ".postCount").value(2))
                .andExpect(jsonPath(ours.formatted(older.getId()) + ".problemTitle").value("먼저 글이 달린 방"))
                .andExpect(jsonPath(ours.formatted(newer.getId()) + ".postCount").value(1))
                .andExpect(jsonPath(ours.formatted(emptied.getId())).isEmpty())
                // 최근 글이 달린 방이 맨 위다.
                .andExpect(jsonPath("$.data.rooms[0].problemId").value(newer.getId()))
                .andExpect(jsonPath("$.data.rooms[0].domain").value("SECURITY"));
    }

    @Test
    @DisplayName("내 활동 — 내가 쓴 글과 내가 댓글 단 글을 따로 본다. 남의 것은 섞이지 않는다")
    void myActivity() throws Exception {
        Problem problem = fixtures.problem();
        User me = fixtures.solver(problem);
        User other = fixtures.solver(problem);
        long mine = write(fixtures.bearer(me), problem.getId(), "내가 쓴 글", "본문");
        long theirs = write(fixtures.bearer(other), problem.getId(), "남이 쓴 글", "본문");
        long untouched = write(fixtures.bearer(other), problem.getId(), "내가 안 건드린 글", "본문");
        commentRepository.save(Comment.of(theirs, me.getId(), null, "내 댓글"));
        commentRepository.save(Comment.of(untouched, me.getId(), null, "지운 내 댓글")).delete();

        mockMvc.perform(get("/api/me/posts").param("kind", "written").header("Authorization", fixtures.bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(mine));
        mockMvc.perform(get("/api/me/posts").param("kind", "commented").header("Authorization", fixtures.bearer(me)))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(theirs));
    }

    @Test
    @DisplayName("내 활동은 로그인해야 본다 — 401. 모르는 종류는 400")
    void myActivityRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/me/posts").param("kind", "written")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/posts").param("kind", "liked")
                        .header("Authorization", fixtures.bearer(fixtures.user(Role.USER))))
                .andExpect(status().isBadRequest());
    }

    /* ── 말머리 ─────────────────────────────────────────── */

    @Test
    @DisplayName("글에는 말머리가 붙는다 — 쓸 때 고르고, 목록·상세·커뮤니티에 이름과 함께 실린다")
    void categoryIsStoredAndShown() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        long id = write(token, problem.getId(), "SUMMARY", word + " 정리한 글", "본문");

        mockMvc.perform(get(detailPath(id)))
                .andExpect(jsonPath("$.data.category").value("SUMMARY"))
                .andExpect(jsonPath("$.data.categoryLabel").value("정리"));
        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.posts[0].category").value("SUMMARY"))
                .andExpect(jsonPath("$.data.posts[0].categoryLabel").value("정리"));
        mockMvc.perform(get("/api/quiz/posts").param("q", word))
                .andExpect(jsonPath("$.data.posts[0].category").value("SUMMARY"))
                .andExpect(jsonPath("$.data.posts[0].categoryLabel").value("정리"));
    }

    @Test
    @DisplayName("말머리 없이는 쓸 수 없고, 모르는 말머리도 받지 않는다 — 400")
    void categoryIsRequired() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));

        mockMvc.perform(post(WRITE).header("Authorization", token).contentType("application/json")
                        .content("{\"problemId\":%d,\"title\":\"제목\",\"body\":\"본문\"}".formatted(problem.getId())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(WRITE).header("Authorization", token).contentType("application/json")
                        .content(writeBody(problem.getId(), "CHAT", "제목", "본문")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("말머리는 고칠 수 있다 — 질문으로 올렸다가 정리로 바꾼다")
    void categoryCanBeEdited() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        long id = write(token, problem.getId(), "QUESTION", "제목", "본문");

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", token).contentType("application/json")
                        .content("{\"category\":\"SUMMARY\",\"title\":\"제목\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("SUMMARY"));
    }

    @Test
    @DisplayName("커뮤니티에서 말머리로 걸러 볼 수 있다")
    void filtersByCategory() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        write(token, problem.getId(), "QUESTION", word + " 질문", "본문");
        long errata = write(token, problem.getId(), "ERRATA", word + " 해설이 틀린 것 같아요", "본문");

        mockMvc.perform(get("/api/quiz/posts").param("q", word).param("category", "ERRATA"))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].id").value(errata))
                .andExpect(jsonPath("$.data.posts[0].categoryLabel").value("오류 지적"));
        mockMvc.perform(get("/api/quiz/posts").param("q", word))
                .andExpect(jsonPath("$.data.posts", hasSize(2)));
    }

    /* ── 수정·삭제 ───────────────────────────────────────── */

    @Test
    @DisplayName("내 글을 고치면 제목·본문이 바뀌고 edited가 true가 된다")
    void editsOwnPost() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        long id = write(token, problem.getId(), "제목", "본문");

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", token)
                        .contentType("application/json").content(editBody("고친 제목", "고친 본문")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("고친 제목"))
                .andExpect(jsonPath("$.data.body").value("고친 본문"))
                .andExpect(jsonPath("$.data.mine").value(true))
                .andExpect(jsonPath("$.data.edited").value(true));
    }

    @Test
    @DisplayName("남의 글은 고치거나 지울 수 없다 — 403 DISCUSSION_005")
    void cannotTouchOthersPost() throws Exception {
        Problem problem = fixtures.problem();
        long id = write(fixtures.bearer(fixtures.solver(problem)), problem.getId(), "제목", "본문");
        String other = fixtures.bearer(fixtures.solver(problem));

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", other)
                        .contentType("application/json").content(editBody("고친 제목", "고친 본문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_005"));
        mockMvc.perform(delete(WRITE + "/" + id).header("Authorization", other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_005"));
    }

    @Test
    @DisplayName("지운 글은 목록에서 빠지고 상세는 404다 — 두 번 지워도 오류가 아니다")
    void deletedPostDisappears() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        long id = write(token, problem.getId(), "제목", "본문");

        mockMvc.perform(delete(WRITE + "/" + id).header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(delete(WRITE + "/" + id).header("Authorization", token)).andExpect(status().isOk());

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.posts", hasSize(0)));
        mockMvc.perform(get(detailPath(id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_011"));
        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", token)
                        .contentType("application/json").content(editBody("고친 제목", "고친 본문")))
                .andExpect(status().isNotFound());
    }

    /** 화면에서만 가리면 응답을 열어 읽을 수 있다. 가린 글은 제목·본문·글쓴이를 싣지 않는다. */
    @Test
    @DisplayName("가린 글은 자리만 남는다 — 제목·본문·닉네임이 비고, 글쓴이도 고칠 수 없다")
    void hiddenPostKeepsOnlyItsPlace() throws Exception {
        Problem problem = fixtures.problem();
        String token = fixtures.bearer(fixtures.solver(problem));
        long id = write(token, problem.getId(), "제목", "본문");
        Post saved = postRepository.findById(id).orElseThrow();
        saved.hide();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.posts", hasSize(1)))
                .andExpect(jsonPath("$.data.posts[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.posts[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.posts[0].nickname").doesNotExist());
        mockMvc.perform(get(detailPath(id)).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").doesNotExist())
                .andExpect(jsonPath("$.data.body").doesNotExist())
                .andExpect(jsonPath("$.data.mine").value(false));
        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", token)
                        .contentType("application/json").content(editBody("고친 제목", "고친 본문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    /* ── 도우미 ─────────────────────────────────────────── */

    private String listPath(Problem problem) {
        return "/api/quiz/" + problem.getId() + "/posts";
    }

    private String detailPath(long postId) {
        return "/api/quiz/posts/" + postId;
    }

    /** 말머리를 따지지 않는 테스트가 쓴다. 말머리는 질문으로 둔다. */
    private String writeBody(Long problemId, String title, String body) {
        return writeBody(problemId, "QUESTION", title, body);
    }

    private String writeBody(Long problemId, String category, String title, String body) {
        return "{\"problemId\":%d,\"category\":\"%s\",\"title\":\"%s\",\"body\":\"%s\"}"
                .formatted(problemId, category, title, body);
    }

    private String editBody(String title, String body) {
        return "{\"category\":\"QUESTION\",\"title\":\"%s\",\"body\":\"%s\"}".formatted(title, body);
    }

    private long write(String token, Long problemId, String title, String body) throws Exception {
        return write(token, problemId, "QUESTION", title, body);
    }

    private long write(String token, Long problemId, String category, String title, String body) throws Exception {
        String response = mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problemId, category, title, body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }
}
