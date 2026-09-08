package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.repository.SubmissionRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기록을 남기지 않는 공개 채점 — 2026-09-08 신설({@code POST /api/quiz/{id}/check}).
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * <p>이 자리는 <b>원칙이 한 칸 움직인 곳</b>이다. "화면과 문제는 누구나, 채점만 로그인"이었던
 * 것이 <b>기록만 로그인</b>으로 좁아졌다({@code QuizService#check}). 규칙이 움직인 자리에는
 * 두 방향의 사고가 함께 생긴다 — 너무 안 열려서 첫 화면이 고장 나거나, 너무 열려서
 * 로그인 없이 이력이 쌓이거나. 그래서 <b>열렸는가</b>와 <b>안 남는가</b>를 나란히 잰다.
 *
 * <h2>왜 통합 테스트인가</h2>
 *
 * <p>이것이 조용히 고장 날 자리는 {@code SecurityConfig}다({@code PublicStatsIntegrationTest}가
 * 적어 둔 것과 같은 이유). 서비스 단위 테스트는 언제나 통과하고, 개발자는 대개 로그인한
 * 상태로 화면을 확인하므로 손으로도 안 걸린다. 401이 나가는 것을 <b>비로그인 방문자만</b>
 * 만나는데, 그 사람은 아직 가입하지 않은 사람이라 제보해 주지도 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnonymousCheckIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AdminProblemService adminProblemService;
    @Autowired SubmissionRepository submissionRepository;

    @Test
    @DisplayName("로그인하지 않아도 정답을 확인할 수 있다 — 첫 화면의 견본 문제가 이 경로로 채점된다")
    void openToAnonymous() throws Exception {
        AdminProblemDetail problem = createMultipleChoice();
        Long correctId = idOf(problem, "정답 보기");

        // 헤더를 하나도 붙이지 않는다 — 첫 화면을 여는 사람의 상태 그대로다
        check(problem.id(), correctId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.explanation").value("정답인 이유를 적은 해설입니다."))
                // 화면이 "내가 고른 것"과 "정답"을 함께 칠하려면 보기별 결과가 필요하다
                .andExpect(jsonPath("$.data.choices.length()").value(3));
    }

    @Test
    @DisplayName("틀리면 틀렸다고 말한다 — 정답 여부를 감추면 견본이 아무것도 안 알려 준다")
    void reportsWrongAnswer() throws Exception {
        AdminProblemDetail problem = createMultipleChoice();

        check(problem.id(), idOf(problem, "오답 하나"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(false));
    }

    /**
     * <b>여기가 이 엔드포인트의 존재 이유이자 한계다.</b> 열어 준 것은 정답 확인까지이고,
     * 이력·복습 사다리·오늘의 퀴즈는 여전히 로그인 뒤에 있다. 저장이 한 줄이라도 생기면
     * 사용자 없는 제출 행이 쌓이거나 {@code userId}가 null인 채로 터진다.
     */
    @Test
    @DisplayName("확인은 제출 이력을 남기지 않는다 — 로그인이 여는 것은 채점이 아니라 기록이다")
    void leavesNoSubmission() throws Exception {
        AdminProblemDetail problem = createMultipleChoice();
        long before = submissionRepository.count();

        check(problem.id(), idOf(problem, "정답 보기")).andExpect(status().isOk());

        assertThat(submissionRepository.count())
                .as("확인만 했는데 제출이 쌓이면 오답노트·복습이 유령 기록을 갖게 된다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("없는 문제를 확인하려 하면 404다 — id를 바꿔 넣어 봐도 새는 것이 없다")
    void unknownProblem() throws Exception {
        check(999_999_999L, 1L).andExpect(status().isNotFound());
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private org.springframework.test.web.servlet.ResultActions check(Long problemId, Long choiceId)
            throws Exception {
        return mockMvc.perform(post("/api/quiz/{id}/check", problemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userAnswer\":\"" + choiceId + "\"}"));
    }

    private AdminProblemDetail createMultipleChoice() {
        return adminProblemService.create(new AdminProblemRequest(
                Domain.SECURITY, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                "공개 채점 테스트",
                "공개 채점 테스트용 지문 " + UUID.randomUUID(),
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
}
