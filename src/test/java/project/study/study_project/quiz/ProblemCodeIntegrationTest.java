package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.AdminProblemDetail;
import project.study.study_project.quiz.dto.AdminProblemRequest;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.AdminProblemService;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제에 딸린 코드(V39)가 등록 → 저장 → 출제·오답노트까지 닿는지 본다.
 * 새 열이 실제 MySQL에 쓰이고 읽히는지는 가짜 저장소로 알 수 없다.
 */
@SpringBootTest
@Transactional
class ProblemCodeIntegrationTest {

    private static final String CODE = """
            Integer a = 127, b = 127;
                System.out.println(a == b);""";

    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private WrongAnswerService wrongAnswerService;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private UserRepository userRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.builder()
                .username("code" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build()).getId();
    }

    private AdminProblemRequest request(String code, String language) {
        return new AdminProblemRequest(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                "코드 문제", "다음 코드의 출력은? " + UUID.randomUUID(), null, "캐시 범위 안이라 같은 객체다.",
                List.of(new AdminProblemRequest.ChoiceItem("true", true),
                        new AdminProblemRequest.ChoiceItem("false", false, "값이 아니라 참조를 견준다는 점만 본 오해다"),
                        new AdminProblemRequest.ChoiceItem("컴파일 오류", false, "오토박싱은 컴파일된다")),
                null, code, language);
    }

    @Test
    @DisplayName("코드는 앞뒤 빈 줄만 걷고 줄 안의 들여쓰기는 그대로 저장하며, 언어를 비우면 java로 본다")
    void storesCodeKeepingIndentation() {
        AdminProblemDetail created = adminProblemService.create(request("\n" + CODE + "\n\n", null));

        assertThat(created.code()).isEqualTo(CODE);
        assertThat(created.codeLanguage()).isEqualTo("java");
        Problem saved = problemRepository.findById(created.id()).orElseThrow();
        assertThat(QuizProblemItem.from(saved).code()).as("출제 응답에도 실린다").isEqualTo(CODE);
    }

    @Test
    @DisplayName("코드를 비워 수정하면 코드와 언어가 함께 지워진다")
    void clearingCodeClearsLanguage() {
        AdminProblemDetail created = adminProblemService.create(request(CODE, "Java"));
        assertThat(created.codeLanguage()).isEqualTo("java");

        AdminProblemDetail updated = adminProblemService.update(created.id(), request("   ", "java"));

        assertThat(updated.code()).isNull();
        assertThat(updated.codeLanguage()).isNull();
    }

    @Test
    @DisplayName("틀린 코드 문제는 오답노트에도 코드와 함께 나온다 — 코드 없이 지문만 보면 무엇을 틀렸는지 모른다")
    void wrongAnswerCarriesCode() {
        AdminProblemDetail created = adminProblemService.create(request(CODE, null));
        Long wrongChoiceId = created.choices().stream().filter(c -> !c.correct()).findFirst().orElseThrow().id();

        quizService.submit(userId, new QuizSubmitRequest(created.id(), String.valueOf(wrongChoiceId)));

        List<WrongAnswerItem> items = wrongAnswerService.getWrongAnswers(userId, null, PageRequest.of(0, 20)).content();
        assertThat(items).singleElement().satisfies(w -> assertThat(w.code()).isEqualTo(CODE));
    }

    @Test
    @DisplayName("코드가 없는 보통 문제는 두 칸이 모두 null이다")
    void plainProblemHasNoCode() {
        AdminProblemDetail created = adminProblemService.create(request(null, null));

        assertThat(created.code()).isNull();
        assertThat(created.codeLanguage()).isNull();
    }
}
