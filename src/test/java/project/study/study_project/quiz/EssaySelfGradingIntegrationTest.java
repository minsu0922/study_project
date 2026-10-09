package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.quiz.dto.AdminProblemRequest;
import project.study.study_project.quiz.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.EssayModelAnswer;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.dto.SubmissionHistoryItem;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.StudyHistoryService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서술형 — 등록 규칙, 모범 답안 공개, 스스로 매긴 결과가 기록되는지. */
@SpringBootTest
@Transactional
class EssaySelfGradingIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private StudyHistoryService studyHistoryService;
    @Autowired
    private WrongAnswerService wrongAnswerService;

    @Test
    @DisplayName("서술형은 모범 답안 없이는 등록할 수 없다")
    void essayRequiresModelAnswer() {
        assertThatThrownBy(() -> adminProblemService.create(essayRequest(null, "  ")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_004));
    }

    @Test
    @DisplayName("모범 답안은 해설 칸을, 요점은 answer 칸을 |로 나눠 준다")
    void modelAnswerSplitsCheckpoints() {
        Long id = essay("3번 교환 | 양쪽 순서 번호 확인");

        EssayModelAnswer model = quizService.modelAnswer(id);

        assertThat(model.modelAnswer()).isEqualTo("SYN, SYN+ACK, ACK를 주고받아 양쪽 순서 번호를 확인한다.");
        assertThat(model.checkpoints()).containsExactly("3번 교환", "양쪽 순서 번호 확인");
    }

    @Test
    @DisplayName("서술형이 아닌 문제는 모범 답안 경로로 해설을 내주지 않는다")
    void modelAnswerIsEssayOnly() {
        Problem ox = fixtures.problem();

        assertThatThrownBy(() -> quizService.modelAnswer(ox.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_002));
    }

    @Test
    @DisplayName("스스로 맞았다고 내면 정답으로, 틀렸다고 내면 오답으로 기록된다")
    void selfGradeIsRecorded() {
        Long id = essay(null);
        User user = fixtures.user(Role.USER);

        QuizSubmitResponse right = quizService.submit(user.getId(), new QuizSubmitRequest(id, "내 설명", true));
        QuizSubmitResponse wrong = quizService.submit(user.getId(), new QuizSubmitRequest(id, "다른 설명", false));

        assertThat(right.correct()).isTrue();
        assertThat(wrong.correct()).isFalse();
        assertThat(wrong.correctAnswer()).isNull();
        List<SubmissionHistoryItem> history = studyHistoryService
                .history(user.getId(), null, PageRequest.of(0, 10)).content();
        assertThat(history).extracting(SubmissionHistoryItem::correct).containsExactly(false, true);
        assertThat(wrongAnswerService.getWrongAnswers(user.getId(), null, PageRequest.of(0, 10)).content())
                .hasSize(1);
    }

    @Test
    @DisplayName("스스로 매긴 결과가 없는 서술형 제출은 400이고 기록되지 않는다")
    void essayWithoutSelfGradeIsRejected() {
        Long id = essay(null);
        User user = fixtures.user(Role.USER);

        assertThatThrownBy(() -> quizService.submit(user.getId(), new QuizSubmitRequest(id, "내 설명")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_001));
        assertThat(studyHistoryService.history(user.getId(), null, PageRequest.of(0, 10)).content()).isEmpty();
    }

    @Test
    @DisplayName("기록 없는 채점은 서술형을 받지 않는다")
    void anonymousCheckRejectsEssay() {
        Long id = essay(null);

        assertThatThrownBy(() -> quizService.check(id, "내 설명"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_002));
    }

    @Test
    @DisplayName("유형을 서술형으로 고르면 나오고, 단건으로도 열린다")
    void essayIsServedWhenAsked() {
        Long id = essay(null);

        assertThat(quizService.getOne(id).problems()).hasSize(1);
        assertThat(quizService.getQuiz(null, null, ProblemType.ESSAY, 50).problems())
                .isNotEmpty()
                .allMatch(p -> p.type() == ProblemType.ESSAY);
        assertThat(quizService.getQuiz(null, null, null, 50).problems())
                .noneMatch(p -> p.type() == ProblemType.ESSAY);
        assertThat(quizService.availableTypes()).contains(ProblemType.ESSAY);
    }

    private Long essay(String checkpoints) {
        return adminProblemService.create(essayRequest(checkpoints,
                "SYN, SYN+ACK, ACK를 주고받아 양쪽 순서 번호를 확인한다.")).id();
    }

    private AdminProblemRequest essayRequest(String checkpoints, String modelAnswer) {
        return new AdminProblemRequest(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.ESSAY,
                "3-way handshake 설명", "TCP 연결이 맺어지는 과정을 설명하세요.",
                checkpoints, modelAnswer, List.of(), null);
    }
}
