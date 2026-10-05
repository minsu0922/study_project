package project.study.study_project.discussion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.study_project.TestDomains;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostWriteRequest;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 문제에 첫 글 둘이 동시에 온다 — 방은 하나만 생기고 글은 둘 다 저장돼야 한다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다. 두 스레드가 서로의 커밋을 봐야 재현되므로 실제로
 * 커밋하고, 만든 것은 끝에 손으로 지운다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
class DiscussionConcurrencyTest {

    @Autowired
    private PostService postService;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate tx;

    private Long problemId;
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> {
            // 제출은 문제를 RESTRICT로 붙잡고 있어 먼저 지운다. 방과 글은 문제와 함께 지워진다.
            userIds.forEach(submissionRepository::deleteAllByUserId);
            if (problemId != null) {
                problemRepository.deleteById(problemId);
            }
            userRepository.deleteAllById(userIds);
        });
    }

    @Test
    @DisplayName("첫 글 둘이 동시에 와도 방은 하나, 글은 둘")
    void twoFirstPostsAtOnce() throws Exception {
        tx.executeWithoutResult(status -> {
            Problem problem = problemRepository.save(Problem.create(
                    TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                    "동시성 확인용", "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
            problemId = problem.getId();
            for (int i = 0; i < 2; i++) {
                String key = UUID.randomUUID().toString().substring(0, 8);
                User user = User.builder().username("conc" + key)
                        .passwordHash(passwordEncoder.encode("password123")).role(Role.USER).build();
                user.changeNickname("동시" + key);
                userIds.add(userRepository.save(user).getId());
                submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PostDetail>> results = new ArrayList<>();
        for (Long userId : userIds) {
            results.add(pool.submit(() -> {
                start.await();
                return postService.write(userId, new PostWriteRequest(problemId, PostCategory.QUESTION, "동시에 쓴 첫 글", "본문"));
            }));
        }
        start.countDown();
        for (Future<PostDetail> result : results) {
            assertThat(result.get(15, TimeUnit.SECONDS).id()).isNotNull();
        }
        pool.shutdown();

        assertThat(discussionRepository.countByProblemId(problemId)).isEqualTo(1);
        assertThat(postService.list(problemId, null, 0).total()).isEqualTo(2);
    }
}
