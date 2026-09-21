package project.study.study_project.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분야 설정 관리자 API 통합 테스트 — Task 9.
 *
 * <p><b>여기서 보는 것은 관리 화면의 세 가지 조작이 실제로 DB에 닿는가다</b> — 수정, 순서
 * 이동, 그리고 저장 전 미리보기. {@code DomainSettingServiceTest}가 이미 동기화 규칙을
 * 지키고 있으므로, 여기서는 그 위에 얹은 <b>관리자 API 배선</b>만 본다: 검증이 400으로
 * 나가는지, 순서 이동이 끝에서 안전한지, 미리보기가 저장 없이 계산만 하는지.
 *
 * <p>{@code /api/admin/**} 규칙에 얹혀 컨트롤러에는 권한 코드가 없으므로, 그 규칙이 실제로
 * 걸리는지도 {@link #requiresAdmin} 한 건으로 확인한다({@code AdminLlmUploadGenerateIntegrationTest}와
 * 같은 이유 — 경로를 새로 만들 때 가장 놓치기 쉬운 자리).
 *
 * <p>요청 제한은 끈다 — 한 클래스에서 여러 번 호출하면 분당 상한에 걸려 429가 섞인다
 * ({@code AdminLlmUploadGenerateIntegrationTest}와 같은 판단). 행 자체는 기동 시
 * {@code DomainSettingSyncRunner}가 이미 채워 둔 것을 그대로 쓴다 — enum 상수 수만큼 고정이라
 * 이 테스트가 새로 만들 필요가 없다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminDomainSettingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DomainSettingService domainSettingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("힌트를 고치면 다음 생성부터 그 값이 실린다")
    void editHintIsStored() throws Exception {
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"displayName":"네트워크","hint":"TCP 혼잡 제어 위주"}"""))
                .andExpect(status().isOk());

        assertThat(domainSettingService.hints().rawHintFor(Domain.NETWORK))
                .isEqualTo("TCP 혼잡 제어 위주");
    }

    @Test
    @DisplayName("힌트 상한은 500자 — 프롬프트를 통째로 밀어 넣는 입력을 막는다")
    void hintHasMaxLength() throws Exception {
        String tooLong = "가".repeat(501);

        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"displayName\":\"네트워크\",\"hint\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("화면 이름을 비우면 400 — 목록에서 그 줄이 빈칸으로 뜨는 것을 막는다")
    void displayNameMustNotBeBlank() throws Exception {
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"displayName":"","hint":null}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("순서를 올리면 앞줄과 자리를 바꾼다")
    void moveUpSwapsWithPrevious() throws Exception {
        mockMvc.perform(post("/api/admin/domain-settings/OS/move")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        assertThat(domainSettingService.batchDomains()).startsWith(Domain.OS, Domain.NETWORK);
    }

    @Test
    @DisplayName("맨 위를 더 올려도 아무 일도 없다 — 오류로 만들면 버튼을 눌러 보기가 무서워진다")
    void moveUpAtTopIsNoop() throws Exception {
        mockMvc.perform(post("/api/admin/domain-settings/NETWORK/move")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        assertThat(domainSettingService.batchDomains()).startsWith(Domain.NETWORK);
    }

    @Test
    @DisplayName("미리보기는 저장하지 않은 순서로도 계산한다 — 보고 나서 저장할 수 있어야 한다")
    void previewUsesGivenOrderWithoutSaving() throws Exception {
        List<DomainSettingService.PreviewCell> cells = domainSettingService.preview(
                List.of(Domain.OS, Domain.NETWORK), 7);

        assertThat(cells).hasSize(7);
        assertThat(cells).anyMatch(c -> c.domain().equals("OS"));
        // 저장은 안 됐다
        assertThat(domainSettingService.batchDomains()).startsWith(Domain.NETWORK);
    }

    @Test
    @DisplayName("미리보기 API도 화면이 넘긴 순서로 계산하고, 문서일에는 난이도가 없다")
    void previewEndpointHonoursGivenOrder() throws Exception {
        String body = mockMvc.perform(get("/api/admin/domain-settings/preview")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .param("days", "7")
                        .param("domains", "OS", "NETWORK"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        List<Map<String, Object>> cells = (List<Map<String, Object>>) response.get("data");

        assertThat(cells).hasSize(7);
        // 문서일 칸에는 difficulty가 없어야 한다 — plan.difficulty()가 null인 그대로 옮긴다.
        assertThat(cells).filteredOn(c -> Boolean.TRUE.equals(c.get("documentDay")))
                .allSatisfy(c -> assertThat(c.get("difficulty")).isNull());
        // OS·NETWORK만 후보로 줬으므로 나오는 분야도 그 둘뿐이어야 한다.
        assertThat(cells).extracting(c -> c.get("domain"))
                .allMatch(d -> d.equals("OS") || d.equals("NETWORK"));
    }

    /**
     * 경로를 새로 만들 때 가장 놓치기 쉬운 자리. {@code /api/admin/**} 규칙에 얹혀 있으므로
     * 컨트롤러에는 권한 코드가 없는데, 그래서 <b>규칙이 안 걸려도 컴파일은 된다</b>.
     */
    @Test
    @DisplayName("관리자가 아니면 401 — 새 경로가 /api/admin/** 규칙에 실제로 얹혔는지 본다")
    void requiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/domain-settings"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        User admin = userRepository.save(User.builder()
                .username("domainsetting" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }
}
