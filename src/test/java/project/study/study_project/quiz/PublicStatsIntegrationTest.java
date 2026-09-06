package project.study.study_project.quiz;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 랜딩용 공개 집계 통합 테스트 (2026-09-06 화면 개편).
 *
 * <h2>왜 API를 새로 만드나</h2>
 *
 * <p>랜딩 화면에 "문제 296개 · 개념 문서 24편"을 <b>글로 박으면</b> 배치가 도는 다음 날
 * 거짓말이 된다. 이 앱은 매일 문제를 더하는 것이 기능이라 정적인 숫자가 특히 빨리 낡는다.
 * 숫자를 말하려면 세어서 말해야 한다.
 *
 * <h2>왜 통합 테스트인가</h2>
 *
 * <p>이 엔드포인트가 조용히 고장 날 자리는 <b>보안 설정</b>이다. 컨트롤러는 멀쩡한데
 * {@code SecurityConfig}에서 안 열어 주면 401이 나가고, 랜딩은 <b>로그인하지 않은 사람이
 * 보는 화면</b>이라 결국 아무도 못 본다. 개발자는 대개 로그인한 상태로 확인하므로
 * 손으로 띄워 봐도 안 걸린다 — 단위 테스트로는 더더욱 못 본다.
 *
 * <h2>왜 절대 개수를 단정하지 않나</h2>
 *
 * <p>DB에는 이미 다른 문제·문서가 들어 있다(로컬에도, CI에도). "문제는 300개"라고
 * 단정하면 그 환경에서만 통과하는 테스트가 된다. 먼저 재고, 넣고, <b>그만큼 늘었는지</b>
 * 본다 — 데이터가 얼마나 있든 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicStatsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProblemRepository problemRepository;

    @Test
    @DisplayName("로그인하지 않아도 집계를 볼 수 있다")
    void openToAnonymous() throws Exception {
        // 헤더를 하나도 붙이지 않는다 — 랜딩을 여는 사람의 상태 그대로다
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.problemCount").exists())
                .andExpect(jsonPath("$.data.documentCount").exists())
                // 분야 수는 DB가 아니라 enum이 안다 — 값이 흔들릴 이유가 없어 단정한다
                .andExpect(jsonPath("$.data.domainCount").value(Domain.values().length));
    }

    @Test
    @DisplayName("문제를 더하면 집계도 따라 는다")
    void problemCountFollowsData() throws Exception {
        long before = problemCount();

        problemRepository.save(Problem.create(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, "집계용 문제 " + UUID.randomUUID(), "O", "해설", null));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problemCount").value((int) before + 1));
    }

    private long problemCount() throws Exception {
        String body = mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.data.problemCount", Integer.class).longValue();
    }
}
