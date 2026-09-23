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
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.DomainCode;
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

        assertThat(domainSettingService.hints().rawHintFor(TestDomains.NETWORK))
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

        assertThat(domainSettingService.batchDomains()).startsWith(TestDomains.OS, TestDomains.NETWORK);
    }

    @Test
    @DisplayName("맨 위를 더 올려도 아무 일도 없다 — 오류로 만들면 버튼을 눌러 보기가 무서워진다")
    void moveUpAtTopIsNoop() throws Exception {
        mockMvc.perform(post("/api/admin/domain-settings/NETWORK/move")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        assertThat(domainSettingService.batchDomains()).startsWith(TestDomains.NETWORK);
    }

    @Test
    @DisplayName("미리보기는 저장하지 않은 순서로도 계산한다 — 보고 나서 저장할 수 있어야 한다")
    void previewUsesGivenOrderWithoutSaving() throws Exception {
        List<DomainSettingService.PreviewCell> cells = domainSettingService.preview(
                List.of(TestDomains.OS, TestDomains.NETWORK), 7);

        assertThat(cells).hasSize(7);
        assertThat(cells).anyMatch(c -> c.domain().equals("OS"));
        // 저장은 안 됐다
        assertThat(domainSettingService.batchDomains()).startsWith(TestDomains.NETWORK);
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
        // 401만 보면 "로그인만 하면 누구나 된다"는 구멍을 못 잡는다 — 비로그인은 인증 단계에서
        // 걸러질 뿐, hasRole(ADMIN)까지 가지 않는다. 일반 사용자 토큰으로 403을 따로 본다(Minor 6).
        mockMvc.perform(get("/api/admin/domain-settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.USER)))
                .andExpect(status().isForbidden());
    }

    /* ── 최종 리뷰 수정(2026-09-22) ─────────────────────────────────── */

    /**
     * <b>마지막으로 켜진 분야는 끌 수 없다</b>(Important 3).
     *
     * <p>전부 꺼진 상태를 CLI는 yml 8개로, 앱은 enum 전체로 읽어 배치가 조용히 계속 돌았다.
     * 배치를 멈추는 스위치는 {@code batch-enabled} 하나이므로 이 상태 자체를 못 만들게 막는다.
     * 개발 DB의 실제 켜짐 상태에 기대지 않으려고, OS 하나만 켜진 상태를 먼저 만든다
     * ({@code @Transactional}이 되돌린다).
     */
    @Test
    @DisplayName("마지막으로 켜진 분야를 끄면 400 — 배치를 멈추는 수단은 batch-enabled 하나다")
    void cannotDisableLastEnabledDomain() throws Exception {
        leaveOnlyEnabled(TestDomains.OS);

        String body = mockMvc.perform(put("/api/admin/domain-settings/OS")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"displayName":"운영체제","hint":null}"""))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("DOMAIN_002").contains("batch-enabled");
        assertThat(domainSettingService.batchDomains()).containsExactly(TestDomains.OS);
    }

    @Test
    @DisplayName("다른 분야가 켜져 있으면 끌 수 있고, 마지막 분야의 이름·힌트는 그대로 고칠 수 있다")
    void lastDomainRuleOnlyBlocksTurningOff() throws Exception {
        leaveOnlyEnabled(TestDomains.OS);

        // 켜진 채로 이름만 고치는 것은 막지 않는다 — 막는 것은 "변경 결과 0개"뿐이다.
        mockMvc.perform(put("/api/admin/domain-settings/OS")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"displayName":"운영체제2","hint":"프로세스 위주"}"""))
                .andExpect(status().isOk());
        // 이미 꺼진 분야를 꺼진 채로 고치는 것도 막지 않는다(켜진 OS가 남아 있다).
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"displayName":"네트워크","hint":null}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("화면 이름이 40자를 넘으면 400 — 전에는 DB 오류(500)로 떨어졌다")
    void displayNameHasMaxLength() throws Exception {
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"displayName\":\"" + "가".repeat(41) + "\",\"hint\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("화면 이름의 앞뒤 공백은 서버가 자른다 — 힌트와 같은 취급")
    void displayNameIsTrimmed() throws Exception {
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"displayName":"  네트워크  ","hint":null}"""))
                .andExpect(status().isOk());

        assertThat(domainSettingService.findAll())
                .filteredOn(s -> s.getDomain().equals(TestDomains.NETWORK))
                .singleElement()
                .extracting(s -> s.getDisplayName())
                .isEqualTo("네트워크");
    }

    @Test
    @DisplayName("미리보기 days는 1~60 — 벗어나면 500이 아니라 400")
    void previewDaysIsBounded() throws Exception {
        for (String days : List.of("0", "-1", "61")) {
            mockMvc.perform(get("/api/admin/domain-settings/preview")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .param("days", days))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/admin/domain-settings/preview")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .param("days", "60"))
                .andExpect(status().isOk());
    }

    /**
     * <b>빈 원소가 500을 만들던 자리</b>(최종 리뷰 Minor 4).
     *
     * <p>{@code ?domains=NETWORK,,OS}처럼 쉼표가 겹치면 스프링 변환기가 가운데 빈 조각을
     * {@code null} 원소로 넣는다. 예전에는 그 {@code null}이 그대로 {@code preview}까지 흘러가
     * {@code plan.domain().value()}에서 NPE를 냈고, 관리자는 원인을 알 수 없는 <b>500</b>을 봤다.
     * 이제는 빈 조각만 빼고 나머지 둘로 계산한다 — 결과에 NETWORK·OS만 나오는지까지 본다.
     */
    @Test
    @DisplayName("분야 목록에 빈 조각이 섞여도 500이 아니다 — 그 조각만 빼고 계산한다")
    void previewIgnoresBlankDomainElements() throws Exception {
        String body = mockMvc.perform(get("/api/admin/domain-settings/preview")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .param("days", "7")
                        .param("domains", "NETWORK,,OS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        List<Map<String, Object>> cells = (List<Map<String, Object>>) response.get("data");
        assertThat(cells).hasSize(7);
        assertThat(cells).extracting(c -> c.get("domain"))
                .as("빈 조각은 '아무 분야도 아님'이므로 결과에도 나오면 안 된다")
                .allMatch(d -> d.equals("NETWORK") || d.equals("OS"));
    }

    /** {@code keep} 하나만 켜진 상태를 만든다. 순서가 중요하다 — 먼저 켜 둬야 나머지를 끌 때 "마지막"에 안 걸린다. */
    private void leaveOnlyEnabled(DomainCode keep) {
        domainSettingService.findAll().stream()
                .filter(s -> s.getDomain().equals(keep))
                .forEach(s -> domainSettingService.edit(keep,
                        new AdminDomainSettingRequest(true, s.getDisplayName(), s.getHint())));
        domainSettingService.findAll().stream()
                .filter(s -> !s.getDomain().equals(keep) && s.isEnabled())
                .forEach(s -> domainSettingService.edit(s.getDomain(),
                        new AdminDomainSettingRequest(false, s.getDisplayName(), s.getHint())));
    }

    private String bearer() {
        return bearer(Role.ADMIN);
    }

    private String bearer(Role role) {
        User user = userRepository.save(User.builder()
                .username("domainsetting" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(role)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), role);
    }
}
