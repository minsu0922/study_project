package project.study.study_project.admin;

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
import project.study.study_project.admin.dto.AdminDomainCreateRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분야 등록부 추가·삭제 API 통합 테스트 — 6번 작업.
 *
 * <p>{@link AdminDomainSettingIntegrationTest}(Task 9)가 이미 수정·순서 이동·미리보기를
 * 본다. 여기서는 그 위에 새로 생긴 두 동작, <b>추가({@link #createdDomainIsOffAtTheEnd},
 * {@link #refusesDuplicateCode})와 삭제({@link #refusesDeleteWhenInUse},
 * {@link #cannotDeleteLastEnabledDomainByDeleting})</b>, 그리고 등록부가 사람 손으로 관리되는
 * 것으로 바뀌면서 가장 위험해진 회귀 — <b>{@link #seedDoesNotDeleteUserDomains}</b> —를 본다.
 * 이 마지막 것이 가장 중요하다: 옛 {@code syncWithDefaults}였다면 관리자가 추가한 분야를
 * "기본 목록에 없다"는 이유로 다음 기동에 지워 버렸다. 그 규정이 조용히 되살아나면 안 된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminDomainRegistryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DomainSettingService domainSettingService;
    @Autowired
    private TopicQueueItemRepository topicQueueItemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static final DomainCode MESSAGING = DomainCode.of("MESSAGING");

    @Test
    @DisplayName("추가한 분야는 꺼진 채 맨 끝에 생긴다")
    void createdDomainIsOffAtTheEnd() throws Exception {
        String body = mockMvc.perform(post("/api/admin/domain-settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"MESSAGING","displayName":"메시징·비동기","hint":"카프카·RabbitMQ 등"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"MESSAGING\"").contains("\"enabled\":false");

        // findAll()은 sortOrder 순 전체다 — 방금 만든 행이 맨 끝(가장 큰 sortOrder)에 있어야 한다.
        List<DomainSetting> all = domainSettingService.findAll();
        assertThat(all).isNotEmpty();
        DomainSetting last = all.get(all.size() - 1);
        assertThat(last.getDomain()).isEqualTo(MESSAGING);
        assertThat(last.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("이미 쓰는 코드로 또 등록하면 400(DOMAIN_004)")
    void refusesDuplicateCode() throws Exception {
        String body = mockMvc.perform(post("/api/admin/domain-settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"NETWORK","displayName":"네트워크 둘째","hint":null}"""))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("DOMAIN_004");
        // 원래 NETWORK 행은 그대로다 — 덮어써지거나 둘이 되지 않는다.
        assertThat(domainSettingService.findAll())
                .filteredOn(s -> s.getDomain().equals(TestDomains.NETWORK)).hasSize(1);
    }

    /**
     * 브리핑 핵심 테스트 1 — 내용이 있으면 지울 수 없고, 메시지가 어느 표에 몇 건인지 말해 준다.
     */
    @Test
    @DisplayName("내용이 있으면 지울 수 없다 — 어느 표에 몇 건인지 알려 준다")
    void refusesDeleteWhenInUse() throws Exception {
        domainSettingService.create(new AdminDomainCreateRequest(MESSAGING, "메시징·비동기", null));
        int nextOrder = topicQueueItemRepository.findMaxSortOrder() + 1;
        topicQueueItemRepository.save(TopicQueueItem.fresh(MESSAGING, "카프카 컨슈머 그룹", null, nextOrder));

        String body = mockMvc.perform(delete("/api/admin/domain-settings/MESSAGING")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("DOMAIN_005").contains("주제 대기열 1건");
        // 실제로 지워지지 않았다 — 행이 여전히 있다.
        assertThat(domainSettingService.findAll()).anyMatch(s -> s.getDomain().equals(MESSAGING));
    }

    @Test
    @DisplayName("마지막으로 켜진 분야는 삭제도 막는다(DOMAIN_002)")
    void cannotDeleteLastEnabledDomainByDeleting() throws Exception {
        leaveOnlyEnabled(TestDomains.OS);

        String body = mockMvc.perform(delete("/api/admin/domain-settings/OS")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("DOMAIN_002");
        assertThat(domainSettingService.findAll()).anyMatch(s -> s.getDomain().equals(TestDomains.OS));
    }

    /**
     * 브리핑 핵심 테스트 2 — 관리자가 추가한 분야는 재시작(=시드 재실행)에도 남는다.
     *
     * <p>옛 {@code syncWithDefaults}라면 {@code MESSAGING}이 "기본 목록에 없다"는 이유로
     * {@code seedIfEmpty} 재실행에서 지워졌을 것이다. 이 테스트가 그 회귀를 막는다 — 조용히
     * 되살아나면 안 되는 동작이라 이름 그대로 지켜야 한다.
     */
    @Test
    @DisplayName("사람이 추가한 분야는 시드를 다시 돌려도 남는다")
    void seedDoesNotDeleteUserDomains() {
        domainSettingService.create(new AdminDomainCreateRequest(MESSAGING, "메시징·비동기", null));

        domainSettingService.seedIfEmpty();

        assertThat(domainSettingService.findAll()).anyMatch(s -> s.getDomain().equals(MESSAGING));
    }

    /** {@code keep} 하나만 켜진 상태를 만든다({@code AdminDomainSettingIntegrationTest}와 같은 헬퍼). */
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
        User user = userRepository.save(User.builder()
                .username("domainregistry" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), Role.ADMIN);
    }
}
