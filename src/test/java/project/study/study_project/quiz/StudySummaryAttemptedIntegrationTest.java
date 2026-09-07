package project.study.study_project.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "푼 문제"와 "맞힌 문제"를 <b>가르는</b> 통합 테스트 (2026-09-07).
 *
 * <h2>왜 이 필드가 필요해졌나</h2>
 *
 * <p>{@code solvedTotal}은 이름과 달리 <b>맞힌 적 있는</b> 문제 수다(그 필드 주석이 정확히
 * 그렇게 적고 있다). 그런데 화면들이 그 값을 "푼 문제"라는 이름으로 쓰고 있었고, 홈은
 * 그 값이 0이면 <b>"첫 문제를 풀어볼까요"</b>를 띄웠다.
 *
 * <p>그래서 <b>한 문제를 풀고 틀린 사람</b>에게 앱이 이렇게 말했다 —
 * 오늘의 퀴즈는 "1 / 10", 오답노트는 1건, 문제 목록에는 그 문제에 ✗가 붙어 있는데,
 * 같은 화면 위쪽 통계는 "푼 문제 0"이고 홈은 "첫 문제를 풀어볼까요"였다.
 * <b>틀린 것이 안 푼 것이 되어</b> 방금 한 일이 없던 일이 됐다.
 * 초보자일수록 첫 문제를 틀리므로, 이 문구가 가장 필요 없는 사람에게 정확히 그 문구가 갔다.
 *
 * <p>값이 틀렸던 것이 아니라 <b>이름이 두 뜻을 감당하고</b> 있었다. 분야별 진도는 맞힌
 * 수라야 뜻이 통하고(틀린 것을 진도로 치면 막대가 거짓말한다) 그 판단은 옳았다.
 * 그 값에 "푼 문제"라는 이름을 붙인 것이 사고였다. 그래서 값을 하나 더 만들어 가른다.
 *
 * <h2>왜 제출 API를 통해 넣나</h2>
 *
 * <p>{@code Submission}을 직접 저장하면 채점·복습 사다리를 건너뛴다. 이 테스트가 지키려는
 * 것은 "틀리게 제출한 뒤의 통계"인데, 그 상태를 만드는 <b>진짜 경로</b>가 제출 API다.
 * 직접 넣으면 통계는 맞는데 실제로는 안 맞는 상황을 못 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudySummaryAttemptedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProblemRepository problemRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("attempt" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .role(Role.USER)
                .build());
        token = jwtTokenProvider.createToken(user.getId(), Role.USER);
    }

    @Test
    @DisplayName("틀리게 제출해도 '푼 문제'로 센다 — 맞힌 수와 따로 온다")
    void wrongSubmissionStillCountsAsAttempted() throws Exception {
        Problem p = problemRepository.save(ox("틀릴 문제"));

        submit(p.getId(), "X");   // 정답은 "O"이므로 오답

        mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                // 제출했으니 푼 문제는 1
                .andExpect(jsonPath("$.data.stats.attemptedTotal").value(1))
                // 틀렸으니 맞힌 문제는 0 — 두 값이 갈라져야 이 화면들이 같은 말을 한다
                .andExpect(jsonPath("$.data.stats.solvedTotal").value(0));
    }

    @Test
    @DisplayName("맞히면 둘 다 오르고, 같은 문제를 여러 번 풀어도 문제 수로 센다")
    void correctSubmissionRaisesBothAndCountsProblemsNotSubmissions() throws Exception {
        Problem p = problemRepository.save(ox("맞힐 문제"));

        submit(p.getId(), "X");   // 처음엔 틀리고
        submit(p.getId(), "O");   // 다시 풀어 맞힌다
        submit(p.getId(), "O");   // 한 번 더 — 제출은 셋이지만 문제는 하나다

        mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.attemptedTotal").value(1))
                .andExpect(jsonPath("$.data.stats.solvedTotal").value(1));
    }

    @Test
    @DisplayName("아무것도 안 푼 사람은 둘 다 0 — 홈이 '첫 문제를 풀어볼까요'로 갈리는 자리")
    void freshUserHasNothing() throws Exception {
        mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.attemptedTotal").value(0))
                .andExpect(jsonPath("$.data.stats.solvedTotal").value(0));
    }

    /* ── 헬퍼 ── */

    private void submit(Long problemId, String answer) throws Exception {
        mockMvc.perform(post("/api/quiz/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("problemId", problemId, "userAnswer", answer))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summaryRequest() {
        return get("/api/me/study-summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** 정답이 "O"인 OX 한 문제. */
    private Problem ox(String question) {
        return Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, question + " " + UUID.randomUUID(), "O", "해설", null);
    }
}
