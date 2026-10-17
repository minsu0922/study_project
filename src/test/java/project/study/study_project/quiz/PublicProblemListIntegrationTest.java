package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비로그인 문제 목록(2026-10-18).
 *
 * <p>문제 목록은 로그인해야 열렸다. 가입 전 사람은 어떤 문제가 있는지 훑어볼 수 없었다.
 * 경로를 열면서 지켜야 할 것은 둘이다 — 401이 아니라 목록이 오는 것, 그리고 남의 기록이 섞이지 않는 것.
 * 개발자는 대개 로그인한 채로 확인하므로 손으로는 안 걸린다(PublicStatsIntegrationTest와 같은 사정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicProblemListIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProblemRepository problemRepository;

    @Test
    @DisplayName("비로그인도 문제 목록을 받는다 — 모든 줄이 안 푼 문제로 나온다")
    void anonymousGetsListWithoutPersonalState() throws Exception {
        String 고유어 = "zzp" + UUID.randomUUID().toString().substring(0, 8);
        problemRepository.save(Problem.create(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, "비로그인 목록 " + 고유어, "O", "해설", null));

        mockMvc.perform(get("/api/problems").param("keyword", 고유어))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].state").value("UNSOLVED"))
                .andExpect(jsonPath("$.data.content[0].reviewDue").value(false));
    }

    @Test
    @DisplayName("요약(내 기록)은 여전히 로그인이 필요하다")
    void summaryStillNeedsLogin() throws Exception {
        mockMvc.perform(get("/api/me/study-summary"))
                .andExpect(status().isUnauthorized());
    }
}
