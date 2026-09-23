package project.study.study_project.quiz;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * <h2>기대값을 등록부에서 읽는 이유 — 2026-09-23에 바꿨다</h2>
 *
 * <p>예전에는 "첫 항목은 NETWORK, 개수는 기본 11개"라고 단정했다. 분야가 코드에 박힌
 * 고정 목록이던 시절에는 맞는 단정이었다. 지금은 관리자가 화면에서 분야를 추가·삭제하고
 * 순서도 바꾸므로, 그 단정은 <b>이 API가 제대로 동작할수록 깨진다</b> — 실제로 분야 하나를
 * 추가하자마자 이 테스트가 빨간불이 됐다.
 *
 * <p>그래서 개수와 첫 항목을 모두 {@code domain_setting} 표에서 읽어 와 비교한다. 이 테스트가
 * 지키는 것은 "몇 개인가"가 아니라 <b>등록부에 있는 것이 그 순서 그대로 나오는가</b>이고,
 * 그것은 분야가 몇 개든 성립한다.
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
    @Autowired
    private DomainSettingRepository domainSettingRepository;

    @Test
    @DisplayName("분야 목록을 순환 순서대로 준다 — 화면의 하드코딩을 대신한다")
    void listsDomainsInCycleOrder() throws Exception {
        // 기대값을 등록부에서 읽는다. 기본 목록 크기를 박아 두면 관리자가 분야를 하나
        // 추가하는 순간 이 테스트가 깨진다 — 그건 이 API의 버그가 아니라 오히려 정상 동작이다
        // (2026-09-23에 실제로 겪었다). 첫 항목도 "NETWORK"로 못 박지 않고 순서대로 읽은
        // 첫 행과 맞춰, 관리자가 순서를 바꿔도 이 테스트가 "순서대로 준다"만 검사하게 한다.
        List<DomainSetting> registry = domainSettingRepository.findAllByOrderBySortOrderAsc();
        DomainSetting first = registry.get(0);

        mockMvc.perform(get("/api/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value(first.getDomain().value()))
                .andExpect(jsonPath("$.data[0].displayName").value(first.getDisplayName()))
                .andExpect(jsonPath("$.data.length()").value(registry.size()));
    }

    @Test
    @DisplayName("로그인 없이 볼 수 있다 — 문제 목록 화면이 로그인 전에도 필터를 그린다")
    void isPublic() throws Exception {
        mockMvc.perform(get("/api/domains")).andExpect(status().isOk());
    }

    /**
     * 꺼진 분야도 목록에서 빠지면 안 된다는 요구사항(클래스 상단 "왜 꺼진 분야도" 참고)을
     * 실제로 지킨다. 지금 구현({@code DomainSettingService.findAll()})은 {@code enabled}로
     * 거르지 않아서 통과하지만, 바로 옆 {@code batchDomains()}는 거른다 — 그 한 줄을
     * {@code findAll()}에 복붙하는 실수 한 번이면 이 테스트 없이는 초록불이 그대로 유지된다.
     *
     * <p>여기서 <b>직접</b> 한 분야를 끈다(동기화가 기본으로 꺼 두는 CLOUD_INFRA·INTEGRATED에
     * 기대지 않는다) — 그 기본값은 {@code application.yml}의 {@code llm.generation.batch-domains}에
     * 달려 있어서, 그 설정이 늘어나 두 분야가 모두 켜진 채로 태어나게 바뀌면 이 테스트가
     * 아무것도 검증하지 않는 채로 계속 통과한다. 무엇을 끄는지 이 메서드 안에서 결정해야
     * "지금 이 테스트가 실제로 꺼진 행을 상대하고 있다"를 읽는 사람이 코드만 보고 확신할 수 있다.
     * (클래스 롤백 {@code @Transactional}이라 이 변경은 테스트 밖으로 새지 않는다.)
     */
    @Test
    @DisplayName("꺼진 분야도 목록에 남는다 — 이미 그 분야 문제가 있을 수 있어 필터가 이름을 잃으면 안 된다")
    void includesDisabledDomain() throws Exception {
        DomainSetting target = domainSettingRepository.findByDomain(TestDomains.DS_ALGORITHM)
                .orElseThrow(() -> new AssertionError("동기화가 DS_ALGORITHM 행을 만들어 뒀어야 한다"));
        target.edit(false, target.getDisplayName(), target.getHint()); // enabled만 끈다

        String body = mockMvc.perform(get("/api/domains"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.parse(body).read("$.data[*].code");

        // 배치 후보에서 빠졌을 뿐 문제까지 사라진 건 아니다 — 학습자 필터가 여전히
        // 이 분야 이름을 낼 수 있어야, 그 분야 문제를 풀던 사람이 필터에서 못 찾는 일이 없다.
        assertThat(codes).contains("DS_ALGORITHM");
    }
}
