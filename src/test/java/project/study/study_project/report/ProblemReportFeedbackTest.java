package project.study.study_project.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ProblemReport;
import project.study.study_project.report.domain.ReportReason;
import project.study.study_project.report.repository.ProblemReportRepository;
import project.study.study_project.report.service.ProblemReportService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>되먹임 회귀 테스트</b> — 인정된 제보가 다음 생성 프롬프트의 재료에 실제로 섞이는가.
 *
 * <h2>왜 이 테스트가 따로 있나</h2>
 *
 * <p>이 기능에서 <b>조용히 사라지기 가장 쉬운 부분</b>이기 때문이다. 제보를 받고 관리자가
 * 인정하는 데까지는 화면이 있어서 안 되면 눈에 보인다. 하지만 그 사유가 프롬프트로 흘러가는
 * 것은 아무 화면에도 안 나타난다 — 합류 지점({@code LlmProblemService.findRecentRejectionNotes})
 * 한 줄이 사라져도 모든 화면이 멀쩡하고, 몇 주 뒤 "제보를 받는데 같은 실수가 계속 난다"로만
 * 드러난다. 이 저장소가 이미 겪은 종류의 사고다(문서 분야 선택이 옛 규칙을 쓰고 있던 8/13).
 *
 * <p>그래서 <b>구현이 아니라 성질</b>을 검사한다: "인정된 제보의 지문과 사유가 되먹임 목록
 * 안에 있다". 합류를 서비스에서 하든 내보내기에서 하든 이 테스트는 그대로 통한다.
 *
 * <p>MySQL이 필요하다. 클래스 {@code @Transactional}로 롤백된다 — 롤백되므로 스냅샷 파일
 * 내보내기가 깨어나지 않는다({@code @TransactionalEventListener(AFTER_COMMIT)}). 저장소의
 * 추적 파일을 더럽히지 않는다는 뜻이고, 그건 예전에 실제로 겪은 사고다(2026-08-20).
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@Transactional
class ProblemReportFeedbackTest {

    @Autowired
    private LlmProblemService llmProblemService;
    @Autowired
    private ProblemReportService reportService;
    @Autowired
    private ProblemReportRepository reportRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("인정된 제보가 되먹임 목록에 [출제 후 제보] 표시와 함께 들어간다")
    void acceptedReportJoinsRejectionNotes() {
        Problem problem = saveProblem("정답이 둘로 읽히는 문제 지문");
        ProblemReport report = reportRepository.save(
                ProblemReport.of(problem, saveUserId(), ReportReason.WRONG_ANSWER, "2번도 정답입니다"));

        reportService.accept(report.getId(), null);

        List<RejectionNote> notes = llmProblemService.findRecentRejectionNotes();

        assertThat(notes).anySatisfy(note -> {
            assertThat(note.question()).isEqualTo("정답이 둘로 읽히는 문제 지문");
            // 사유 문구 + 제보자의 한 줄이 한 문장으로 이어진다 — 모델에게는 한 덩어리여야 한다
            assertThat(note.reason())
                    .startsWith("[출제 후 제보] ")
                    .contains(ReportReason.WRONG_ANSWER.getLabel())
                    .contains("2번도 정답입니다");
        });
    }

    /**
     * 기각된 제보가 새어 나가면 <b>멀쩡한 출제 방식을 하지 말라고 가르치게 된다</b>.
     * 인정만 흘려보낸다는 조건이 빠져도 위 테스트는 통과하므로, 이 한 건이 따로 필요하다.
     */
    @Test
    @DisplayName("대기·기각된 제보는 되먹임에 들어가지 않는다")
    void onlyAcceptedReportsFeedBack() {
        Problem pending = saveProblem("아직 아무도 안 본 제보의 지문");
        Problem dismissed = saveProblem("틀린 지적이라 기각된 제보의 지문");
        reportRepository.save(ProblemReport.of(pending, saveUserId(), ReportReason.TYPO, null));
        ProblemReport toDismiss = reportRepository.save(
                ProblemReport.of(dismissed, saveUserId(), ReportReason.TYPO, null));

        reportService.dismiss(toDismiss.getId(), "오타 아님");

        List<String> questions = llmProblemService.findRecentRejectionNotes().stream()
                .map(RejectionNote::question).toList();

        assertThat(questions)
                .doesNotContain("아직 아무도 안 본 제보의 지문")
                .doesNotContain("틀린 지적이라 기각된 제보의 지문");
    }

    /**
     * 제보자 계정 하나. 아무 숫자나 넣지 않는 이유: {@code user_id}에 FK가 걸려 있어
     * 없는 id는 INSERT가 거부된다. 처음에 1L을 쓰고 통과한 것은 <b>이 PC의 DB에 마침
     * 그 id가 있었기 때문</b>이라, 빈 DB(CI)에서는 깨질 코드였다 — 실제로 두 번째
     * 테스트가 2L에서 걸려 드러났다.
     */
    private Long saveUserId() {
        return userRepository.save(User.builder()
                .username("fb" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build()).getId();
    }

    private Problem saveProblem(String question) {
        return problemRepository.save(Problem.create(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "되먹임 테스트용", question, "O", "해설", null));
    }
}
