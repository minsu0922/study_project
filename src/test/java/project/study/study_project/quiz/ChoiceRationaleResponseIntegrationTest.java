package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.dto.QuizChoiceResult;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오답 설명이 <b>화면까지</b> 도달하는지 — 2026-08-27 신설(V15 2단계).
 *
 * <p>1단계 테스트({@code LlmChoiceRationaleIntegrationTest})는 값이 DB 컬럼에 <b>들어가는</b>
 * 것까지 봤다. 여기서 보는 것은 그 값이 <b>나오는</b> 길이다 — 채점 응답과 오답노트.
 * 저장은 되는데 응답에 안 실리면 화면은 여전히 빈 채로 그려지고, 그 상태는 컴파일도
 * 테스트도 통과한다.
 *
 * <h2>왜 채점 응답에 보기 <b>번호</b>가 없는지도 여기서 지킨다</h2>
 *
 * <p>보기는 요청마다 다시 섞여 나가므로({@code QuizChoiceItem.shuffledFrom}) 서버는 학습자
 * 화면의 번호를 모른다. 그래서 응답은 {@code id}로만 말하고 번호는 화면이 붙인다
 * ({@code player.js}의 {@code wrongAnalysis}). 누군가 편의를 위해 여기에 순번을 얹으면
 * <b>섞이기 전 순번</b>이 실려 나가고, 화면이 그것을 믿는 순간 옛 버그가 되살아난다.
 *
 * <p>실제 DB가 필요하다(다른 통합 테스트와 같은 전제).
 */
@SpringBootTest
@Transactional
class ChoiceRationaleResponseIntegrationTest {

    @Autowired
    private QuizService quizService;
    @Autowired
    private WrongAnswerService wrongAnswerService;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private UserRepository userRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("rationale" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
    }

    @Test
    @DisplayName("채점 응답에 보기별 정답 여부와 오답 설명이 실린다 — 화면이 이걸로 오답 분석을 그린다")
    void submitCarriesPerChoiceRationale() {
        AdminProblemDetail problem = createMultipleChoice();
        Long wrongChoiceId = idOf(problem, "오답 하나");

        QuizSubmitResponse response = submit(problem.id(), wrongChoiceId);

        assertThat(response.choices()).hasSize(3);
        assertThat(response.choices())
                .filteredOn(QuizChoiceResult::correct)
                .singleElement()
                .extracting(QuizChoiceResult::rationale)
                .as("정답의 근거는 해설이 맡는다 — 보기 쪽은 비어 있어야 한다")
                .isNull();
        assertThat(response.choices())
                .filteredOn(c -> !c.correct())
                .extracting(QuizChoiceResult::rationale)
                .containsExactlyInAnyOrder("첫째 오해를 담은 설명이다", "둘째 오해를 담은 설명이다");
    }

    /**
     * <b>번호를 싣지 않는다는 계약.</b> 화면이 자기가 찍은 번호를 쓰고 서버가 준 {@code id}로
     * 짝을 찾는 구조라야 섞기와 공존한다. 이 테스트는 응답이 {@code id}를 반드시 준다는 것과,
     * 그 id가 <b>실제 보기 id</b>라는 것을 지킨다 — 짝짓기의 유일한 열쇠이기 때문이다.
     */
    @Test
    @DisplayName("보기 결과는 id로만 말한다 — 순번을 실으면 섞이기 전 번호가 나간다")
    void choiceResultsAreKeyedByIdOnly() {
        AdminProblemDetail problem = createMultipleChoice();

        QuizSubmitResponse response = submit(problem.id(), idOf(problem, "정답 보기"));

        assertThat(response.choices())
                .extracting(QuizChoiceResult::id)
                .containsExactlyInAnyOrderElementsOf(
                        problem.choices().stream().map(AdminProblemDetail.ChoiceDetail::id).toList());
    }

    /**
     * 객관식이 아닌 유형은 빈 목록이다. {@code null}이 아니라 빈 목록인 것이 중요하다 —
     * 화면이 유형마다 다른 검사를 하지 않고 "비었으면 안 그린다" 하나로 끝난다.
     */
    @Test
    @DisplayName("OX 문제는 빈 목록을 받는다 — null이 아니라 빈 목록이어야 화면 검사가 하나로 끝난다")
    void nonMultipleChoiceGetsEmptyList() {
        AdminProblemDetail ox = adminProblemService.create(new AdminProblemRequest(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX, "OX 제목",
                "OX 지문 " + UUID.randomUUID(), "O", "해설입니다.", null, null));

        QuizSubmitResponse response = quizService.submit(userId,
                new QuizSubmitRequest(ox.id(), "X"));

        assertThat(response.choices()).isNotNull().isEmpty();
    }

    /**
     * 오답노트는 보기를 그리지 않는 복습 카드라, 넷을 다 싣지 않고 <b>내가 고른 그 보기</b>의
     * 설명만 싣는다({@code AnswerDisplay.userAnswerRationaleOf}).
     */
    @Test
    @DisplayName("오답노트에는 내가 고른 보기의 설명만 실린다 — 안 고른 보기 설명까지 읽히면 안 된다")
    void wrongAnswerCarriesOnlyMyChoiceRationale() {
        AdminProblemDetail problem = createMultipleChoice();
        submit(problem.id(), idOf(problem, "오답 둘"));

        List<WrongAnswerItem> items = wrongAnswerService
                .getWrongAnswers(userId, null, PageRequest.of(0, 20)).content();

        assertThat(items)
                .filteredOn(i -> i.problemId().equals(problem.id()))
                .singleElement()
                .extracting(WrongAnswerItem::myAnswerRationale)
                .isEqualTo("둘째 오해를 담은 설명이다");
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 오답 둘에만 설명이 붙은 객관식 문제. 승인 경로와 같은 서비스를 탄다. */
    private AdminProblemDetail createMultipleChoice() {
        return adminProblemService.create(new AdminProblemRequest(
                Domain.SECURITY, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                "오답 설명 응답 테스트",
                "오답 설명 응답 테스트용 지문 " + UUID.randomUUID(),
                null, "정답인 이유를 적은 해설입니다.",
                List.of(new AdminProblemRequest.ChoiceItem("정답 보기", true, null),
                        new AdminProblemRequest.ChoiceItem("오답 하나", false, "첫째 오해를 담은 설명이다"),
                        new AdminProblemRequest.ChoiceItem("오답 둘", false, "둘째 오해를 담은 설명이다")),
                null));
    }

    private Long idOf(AdminProblemDetail problem, String text) {
        return problem.choices().stream()
                .filter(c -> c.text().equals(text))
                .findFirst().orElseThrow()
                .id();
    }

    private QuizSubmitResponse submit(Long problemId, Long choiceId) {
        return quizService.submit(userId, new QuizSubmitRequest(problemId, String.valueOf(choiceId)));
    }
}
