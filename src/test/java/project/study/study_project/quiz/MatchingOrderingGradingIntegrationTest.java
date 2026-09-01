package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 짝짓기·순서 배열이 <b>등록되고, 화면까지 나가고, 채점되는</b>지 — 2026-08-31 신설.
 *
 * <p>이 두 유형은 앞선 유형들과 달리 <b>답이 항목 하나에 있지 않다</b>. 순서 배열의 답은 항목
 * 사이의 순서이고, 짝짓기의 답은 두 열의 연결이다. 그래서 "보기 하나를 고른다"를 전제로 짜인
 * 기존 경로(제출 문자열 = 보기 id 하나)가 그대로는 통하지 않는다 — 그 전제가 깨지는 자리를
 * 지키는 것이 이 테스트의 목적이다.
 *
 * <p><b>가장 중요한 것은 마지막 테스트다.</b> 짝짓기는 한 {@code choice} 행이 한 쌍이라(V16),
 * 오른쪽 열을 행 id와 함께 내보내면 왼쪽 id와 맞춰 보는 것만으로 답이 드러난다. 그 사고는
 * 조용하다 — 화면은 멀쩡히 그려지고 채점도 정상이며, 응답을 열어 본 사람만 안다.
 *
 * <p>실제 DB가 필요하다(다른 통합 테스트와 같은 전제).
 */
@SpringBootTest
@Transactional
class MatchingOrderingGradingIntegrationTest {

    @Autowired
    private QuizService quizService;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private UserRepository userRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("matchord" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
    }

    /* ── 순서 배열 ────────────────────────────────────────────── */

    @Test
    @DisplayName("순서 배열: 정답 순서대로 배열하면 정답, 두 개만 뒤바꿔도 오답")
    void gradesOrderingByWholeArrangement() {
        AdminProblemDetail problem = createOrdering();
        // 등록한 항목 순서가 곧 seq 1..4다. answer가 "4|2|1|3"이므로 그 seq 순서로 id를 잇는다.
        String correct = idsInSeqOrder(problem, 4, 2, 1, 3);
        String swapped = idsInSeqOrder(problem, 4, 2, 3, 1);

        assertThat(submit(problem.id(), correct).correct()).isTrue();
        assertThat(submit(problem.id(), swapped).correct())
                .as("부분 정답은 주지 않는다 — 두 칸만 뒤바뀌어도 오답이다")
                .isFalse();
    }

    @Test
    @DisplayName("순서 배열: 항목을 빠뜨리거나 같은 것을 두 번 배열하면 오답이 아니라 400")
    void rejectsIncompleteOrdering() {
        AdminProblemDetail problem = createOrdering();

        assertThatThrownBy(() -> submit(problem.id(), idsInSeqOrder(problem, 4, 2, 1)))
                .as("화면에서 나올 수 없는 모양이다 — 조용히 오답으로 처리하면 복습 사다리가 오염된다")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> submit(problem.id(), idsInSeqOrder(problem, 1, 1, 2, 3)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("순서 배열: 정답 표기는 항목 글을 순서대로 이은 것 — 저장값 \"4|2|1|3\"이 그대로 나가면 읽을 수 없다")
    void showsOrderingAnswerAsText() {
        AdminProblemDetail problem = createOrdering();

        QuizSubmitResponse response = submit(problem.id(), idsInSeqOrder(problem, 1, 2, 3, 4));

        assertThat(response.correctAnswer())
                .isEqualTo("요청 값을 검증한다 → 새 값을 DB에 쓰고 커밋한다 → 캐시 키를 삭제한다 → 삭제 실패를 재시도 큐에 넣는다");
    }

    /* ── 짝짓기 ──────────────────────────────────────────────── */

    @Test
    @DisplayName("짝짓기: 네 쌍을 모두 옳게 이으면 정답, 두 쌍만 바꿔 이어도 오답")
    void gradesMatchingByAllPairs() {
        AdminProblemDetail problem = createMatching();
        QuizProblemItem view = viewOf(problem.id());

        assertThat(submit(problem.id(), pairsFor(problem, view, true)).correct()).isTrue();
        assertThat(submit(problem.id(), pairsFor(problem, view, false)).correct())
                .as("두 쌍을 서로 바꿔 이으면 나머지 둘이 맞아도 오답이다")
                .isFalse();
    }

    @Test
    @DisplayName("짝짓기: 이은 순서는 채점에 영향이 없다 — 학습자가 어느 쌍부터 잇든 같은 답이다")
    void matchingIgnoresPairOrder() {
        AdminProblemDetail problem = createMatching();
        QuizProblemItem view = viewOf(problem.id());

        List<String> entries = List.of(pairsFor(problem, view, true).split("\\|"));
        String reversed = entries.reversed().stream().collect(Collectors.joining("|"));

        assertThat(submit(problem.id(), reversed).correct()).isTrue();
    }

    /**
     * <b>이 테스트가 지키는 것은 "정답이 응답에 실리지 않는다"이다.</b>
     *
     * <p>짝짓기는 한 행이 한 쌍이므로, 오른쪽 항목을 그 행의 id와 함께 내보내면 왼쪽 id와
     * 같은 값을 찾는 것만으로 답이 드러난다. 그래서 오른쪽은 되돌릴 수 없는 토큰으로 나가야
     * 한다({@code MatchToken}). 누군가 "id가 편하다"며 바꾸면 여기서 걸린다.
     *
     * <p>섞임까지 함께 본다 — 안 섞으면 왼쪽 n번째와 오른쪽 n번째가 그대로 짝이 된다.
     * 무작위라 한 번은 우연히 제자리일 수 있으므로, 여러 번 뽑아 <b>한 번이라도</b>
     * 어긋나는지를 본다(늘 제자리면 섞지 않는 것이다).
     */
    @Test
    @DisplayName("짝짓기: 오른쪽 열은 보기 id를 노출하지 않고, 섞여서 나간다")
    void matchOptionsHideThePairing() {
        AdminProblemDetail problem = createMatching();
        QuizProblemItem view = viewOf(problem.id());

        assertThat(view.matchOptions()).hasSize(4);
        List<String> leftIds = view.choices().stream().map(c -> String.valueOf(c.id())).toList();
        assertThat(view.matchOptions())
                .as("오른쪽 식별자가 보기 id면 왼쪽과 맞춰 보는 것만으로 답이 드러난다")
                .noneMatch(o -> leftIds.contains(o.token()));

        boolean everShuffled = false;
        for (int i = 0; i < 20 && !everShuffled; i++) {
            QuizProblemItem again = viewOf(problem.id());
            everShuffled = !expectedRightTexts(problem, again).equals(
                    again.matchOptions().stream().map(o -> o.text()).toList());
        }
        assertThat(everShuffled).as("오른쪽 열이 늘 왼쪽과 같은 순서로 나가면 답을 그대로 보여 준 것이다").isTrue();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 캐시 무효화 순서 — answer "4|2|1|3"이 정답 순서다(등록 순서가 seq 1..4). */
    private AdminProblemDetail createOrdering() {
        return adminProblemService.create(new AdminProblemRequest(
                Domain.SYSTEM_DESIGN, Difficulty.INTERMEDIATE, ProblemType.ORDERING,
                "캐시를 함께 쓰는 갱신 처리의 순서",
                "DB와 캐시를 함께 쓰는 갱신 처리에서 권장되는 순서로 배열하시오. " + UUID.randomUUID(),
                "4|2|1|3", "캐시를 먼저 지우면 지운 직후 들어온 조회가 옛 값을 다시 채운다.",
                List.of(new AdminProblemRequest.ChoiceItem("캐시 키를 삭제한다", false),
                        new AdminProblemRequest.ChoiceItem("새 값을 DB에 쓰고 커밋한다", false),
                        new AdminProblemRequest.ChoiceItem("삭제 실패를 재시도 큐에 넣는다", false),
                        new AdminProblemRequest.ChoiceItem("요청 값을 검증한다", false)),
                null));
    }

    /** 격리 수준 짝짓기 — 한 줄이 한 쌍이다(V16). */
    private AdminProblemDetail createMatching() {
        return adminProblemService.create(new AdminProblemRequest(
                Domain.DATABASE, Difficulty.BEGINNER, ProblemType.MATCHING,
                "격리 수준과 그 성질",
                "다음 격리 수준과 그 성질을 알맞게 연결하시오. " + UUID.randomUUID(),
                null, "각 격리 수준이 어디까지 막아 주는지가 다르다.",
                List.of(
                        AdminProblemRequest.ChoiceItem.pair(
                                "READ UNCOMMITTED", "커밋되지 않은 값을 읽을 수 있다"),
                        AdminProblemRequest.ChoiceItem.pair(
                                "READ COMMITTED", "커밋된 값만 읽지만 같은 행을 두 번 읽으면 달라질 수 있다"),
                        AdminProblemRequest.ChoiceItem.pair(
                                "REPEATABLE READ", "같은 행은 늘 같지만 조건에 맞는 행의 수는 늘 수 있다"),
                        AdminProblemRequest.ChoiceItem.pair(
                                "SERIALIZABLE", "순차적으로 실행한 것과 같은 결과를 보장한다")),
                null));
    }

    /** 풀이용 응답(섞인 상태)을 한 번 꺼낸다 — 학습자가 실제로 받는 모양 그대로 본다. */
    private QuizProblemItem viewOf(Long problemId) {
        return QuizProblemItem.from(problemRepository.findById(problemId).orElseThrow());
    }

    /** seq 번호를 그 보기의 id로 바꿔 {@code |}로 잇는다 — 제출 형식이 id 나열이기 때문. */
    private String idsInSeqOrder(AdminProblemDetail problem, int... seqs) {
        StringBuilder sb = new StringBuilder();
        for (int seq : seqs) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(problem.choices().stream()
                    .filter(c -> c.seq() == seq)
                    .findFirst().orElseThrow()
                    .id());
        }
        return sb.toString();
    }

    /**
     * 짝짓기 제출 문자열을 만든다.
     *
     * @param correct {@code false}면 첫 두 쌍의 오른쪽을 서로 바꾼다 — "둘은 맞고 둘은 틀린" 답
     */
    private String pairsFor(AdminProblemDetail problem, QuizProblemItem view, boolean correct) {
        List<String> rightTexts = expectedRightTexts(problem, view);
        if (!correct) {
            rightTexts = new java.util.ArrayList<>(rightTexts);
            java.util.Collections.swap(rightTexts, 0, 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < view.choices().size(); i++) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            String text = rightTexts.get(i);
            String token = view.matchOptions().stream()
                    .filter(o -> o.text().equals(text))
                    .findFirst().orElseThrow()
                    .token();
            sb.append(view.choices().get(i).id()).append('-').append(token);
        }
        return sb.toString();
    }

    /** 응답에 실린 왼쪽 순서대로의 <b>옳은</b> 오른쪽 글 목록 — 등록 데이터에서 뽑는다. */
    private List<String> expectedRightTexts(AdminProblemDetail problem, QuizProblemItem view) {
        return view.choices().stream()
                .map(left -> problem.choices().stream()
                        .filter(c -> c.text().equals(left.text()))
                        .findFirst().orElseThrow()
                        .matchText())
                .toList();
    }

    private QuizSubmitResponse submit(Long problemId, String userAnswer) {
        return quizService.submit(userId, new QuizSubmitRequest(problemId, userAnswer));
    }
}
