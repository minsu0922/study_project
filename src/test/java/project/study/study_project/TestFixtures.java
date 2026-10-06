package project.study.study_project;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
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

import java.util.UUID;

/**
 * 통합 테스트가 함께 쓰는 재료 — 사용자, 토큰, 문제, 토론방 글.
 *
 * <p>테스트마다 같은 메서드를 따로 두었더니 열두 파일에 흩어졌고, 가입 규칙이 바뀌면
 * (예: 닉네임 필수) 그 열두 곳을 다 고쳐야 했다. 테스트만의 사정이 있는 재료는 여기 넣지 않고
 * 그 테스트에 둔다.
 *
 * <p>저장만 하고 트랜잭션은 열지 않는다. 테스트의 {@code @Transactional}에 얹혀 함께 롤백된다.
 */
@Component
public class TestFixtures {

    /** 계정 탈퇴처럼 비밀번호를 다시 묻는 API를 부를 때 쓴다. */
    public static final String PASSWORD = "password123";

    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final DiscussionRepository discussionRepository;
    private final PostRepository postRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public TestFixtures(UserRepository userRepository, ProblemRepository problemRepository,
                        SubmissionRepository submissionRepository, DiscussionRepository discussionRepository,
                        PostRepository postRepository, PasswordEncoder passwordEncoder,
                        JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.problemRepository = problemRepository;
        this.submissionRepository = submissionRepository;
        this.discussionRepository = discussionRepository;
        this.postRepository = postRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /* ── 사용자 ─────────────────────────────────────────── */

    /** 닉네임이 있는 사용자. 가입하면 닉네임이 있으므로 이것이 보통의 계정이다. */
    public User user(Role role) {
        return user(role, "닉" + key());
    }

    public User user(Role role, String nickname) {
        User user = newUser(role);
        user.changeNickname(nickname);
        return userRepository.save(user);
    }

    /** 닉네임 가입이 필수가 되기 전에 만든 계정. */
    public User userWithoutNickname(Role role) {
        return userRepository.save(newUser(role));
    }

    /** 닉네임이 있고 그 문제를 푼 사용자 — 토론방에 쓸 수 있는 사람이다. */
    public User solver(Problem problem) {
        User user = user(Role.USER);
        solve(user, problem);
        return user;
    }

    public void solve(User user, Problem problem) {
        submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
    }

    /* ── 토큰 ───────────────────────────────────────────── */

    public String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), user.getRole());
    }

    /** 그 권한의 계정을 새로 만들어 토큰만 돌려준다. 누구인지는 상관없고 권한만 필요할 때 쓴다. 닉네임은 없다. */
    public String bearer(Role role) {
        return bearer(userWithoutNickname(role));
    }

    /* ── 문제와 글 ───────────────────────────────────────── */

    public Problem problem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }

    /** @param userId 글쓴이. {@code null}이면 탈퇴한 사용자의 글 */
    public Post post(Problem problem, Long userId) {
        return post(problem, userId, PostCategory.QUESTION, "글", "본문");
    }

    /** 방은 서비스와 같은 길로 만든다 — 첫 글 때 생긴다. */
    public Post post(Problem problem, Long userId, PostCategory category, String title, String body) {
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        return postRepository.saveAndFlush(Post.of(discussionId, userId, category, title, body));
    }

    private User newUser(Role role) {
        return User.builder()
                // 아이디는 30자 제한이라 UUID 앞 8자만 딴다.
                .username("test" + key())
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .build();
    }

    private String key() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
