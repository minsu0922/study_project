package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/domains} 통합 테스트 (8번 작업) — {@code static/js/api.js}가 들고 있던
 * 분야 하드코딩을 서버가 대신 준다.
 *
 * <h2>왜 이 테스트가 있나</h2>
 *
 * <p>고칠 자리가 자바 enum 한 곳으로 줄어도, 그 값을 화면까지 실제로 나르는 경로가
 * 끊기면 소용없다. 이 테스트는 그 경로 두 군데를 지킨다.
 *
 * <h2>순서를 단정하는 이유 — data[0]이 NETWORK인가</h2>
 *
 * <p>{@code DomainSettingService.findAll()}은 {@code sortOrder} 오름차순을 준다. 이 값은
 * {@code application.yml}의 {@code llm.generation.batch-domains}(첫 값이 NETWORK)를 초기값
 * 삼아 기동 시 동기화 러너가 채운다 — 관리자가 손으로 순서를 바꾸지 않은 한 로컬·CI 어디서
 * 재도 NETWORK가 맨 앞이다. 흔들릴 값이면 여기서 단정하지 않았겠지만, 이 프로젝트에서
 * "분야 목록의 기본 순서"는 실제로 이렇게 고정돼 있다(PublicStatsIntegrationTest의
 * domainCount 단정과 같은 판단 — enum/설정이 정하는 값은 DB에 무엇이 있든 흔들리지 않는다).
 *
 * <h2>왜 로그인 없이도 되는지 확인하나</h2>
 *
 * <p>이 목록을 맨 먼저 쓰는 화면(문서 목록·자유 퀴즈·오답노트)이 전부 로그인 전에도
 * 필터를 그린다. {@code SecurityConfig}에서 이 경로를 열지 않으면 401이 나서, 문제는
 * 로컬 개발 중(대개 로그인한 채로 확인)에는 안 걸리고 실제 방문자한테만 드러난다 —
 * PublicStatsIntegrationTest가 {@code /api/stats}에서 같은 이유로 지키는 것과 동일한 함정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DomainListIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("분야 목록을 순환 순서대로 준다 — 화면의 하드코딩을 대신한다")
    void listsDomainsInCycleOrder() throws Exception {
        mockMvc.perform(get("/api/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("NETWORK"))
                .andExpect(jsonPath("$.data[0].displayName").value("네트워크"))
                .andExpect(jsonPath("$.data.length()").value(Domain.values().length));
    }

    @Test
    @DisplayName("로그인 없이 볼 수 있다 — 문제 목록 화면이 로그인 전에도 필터를 그린다")
    void isPublic() throws Exception {
        mockMvc.perform(get("/api/domains")).andExpect(status().isOk());
    }
}
