package project.study.study_project.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영 점검 창구(Actuator)의 <b>접근 규칙</b>을 못 박는 테스트. 설정은 application.yml의
 * {@code management} 절, 경로 규칙은 {@link SecurityConfig}.
 *
 * <p><b>왜 테스트가 필요한가.</b> 이 기능의 설정은 세 파일에 흩어져 있고(의존성 · 노출 목록 ·
 * 시큐리티 경로), 어긋났을 때의 증상이 둘 다 <b>조용하다</b>.
 * <ul>
 *   <li>너무 잠그면 — 도커 HEALTHCHECK가 401만 받아 <b>멀쩡한 컨테이너가 계속 재시작</b>된다.
 *       배포하기 전에는 아무도 모른다.
 *   <li>너무 열면 — {@code /actuator/env}로 환경변수(=DB 비밀번호·서명 키)가 통째로 나간다.
 *       화면에는 아무 변화가 없어 더 오래 모른다.
 * </ul>
 * 그래서 "무엇이 열려 있는가"를 사람의 기억이 아니라 여기서 지킨다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제).
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ActuatorEndpointTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 도커 HEALTHCHECK가 실제로 부르는 주소다. 이 한 건이 깨지면 배포된 컨테이너가
     * 30초마다 unhealthy 판정을 받는다.
     *
     * <p>여기만 200을 <b>단정</b>할 수 있는 이유: essential 그룹은 db와 ping만 본다.
     * 테스트가 도는 환경에는 MySQL이 반드시 떠 있으므로 결과가 흔들리지 않는다.
     */
    @Test
    @DisplayName("essential 상태 점검은 인증 없이 200 — 오케스트레이터에는 줄 토큰이 없다")
    void essentialHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/essential"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * 기본 집계는 Redis·디스크까지 본다. 이 프로젝트에서 Redis는 <b>꺼져 있어도 정상</b>인
     * 부품이라(fail-open) 그때 이 주소는 503을 준다 — 그것 자체는 결함이 아니다.
     * 그래서 여기서 재는 것은 상태값이 아니라 <b>인증 없이 닿는가</b>이다.
     *
     * <p>이 구분이 이 테스트의 핵심이다. 200을 단정했다면 Redis를 끄고 돌리는 개발자의
     * 화면에서만 빨간 줄이 뜨고, 그 사람은 멀쩡한 코드를 의심하게 된다.
     */
    @Test
    @DisplayName("기본 상태 점검도 인증 없이 닿는다 — Redis가 꺼져 있으면 503이지만 401은 아니다")
    void aggregateHealthIsReachableWithoutAuth() throws Exception {
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("200(전부 UP) 또는 503(Redis 등이 DOWN)이어야 한다. 401/403이면 경로가 잠긴 것이다")
                .isIn(200, 503);
    }

    /**
     * 경로는 열되 내용은 잠근다 — 어느 부품이 죽었는지는 공격자에게 유용한 단서다
     * (DB가 내려간 순간을 밖에서 알 수 있으면 그때를 노릴 수 있다).
     */
    @Test
    @DisplayName("비로그인에게는 상태만, 부품 목록은 안 보인다")
    void hidesComponentsFromAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health/essential"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("관리자에게는 부품별 상태가 보인다 — 사람이 원인을 찾는 자리")
    void showsComponentsToAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.components.db").exists());
    }

    @Test
    @DisplayName("info는 관리자만 — 빌드 버전도 굳이 밖에 알릴 것이 아니다")
    void infoRequiresAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /**
     * <b>가장 중요한 한 건.</b> {@code /actuator/env}는 환경변수를 그대로 돌려주는
     * 엔드포인트라, 열리는 순간 {@code JWT_SECRET}·{@code DB_PASSWORD}가 함께 나간다.
     *
     * <p>관리자 토큰으로 시험하는 이유: 비로그인으로 401을 확인하면 "시큐리티가 막았다"만
     * 보이고 <b>노출 목록이 좁다는 것은 확인되지 않는다</b>. 통과할 수 있는 사람에게도
     * 404여야 비로소 "애초에 켜지지 않았다"가 증명된다.
     */
    @Test
    @DisplayName("노출 목록에 없는 엔드포인트는 관리자에게도 없다 — env로 비밀이 새지 않는다")
    void unexposedEndpointsStayOff() throws Exception {
        mockMvc.perform(get("/actuator/env").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    /** 그 역할로 서명한 토큰의 Authorization 헤더 값. */
    private String bearer(Role role) {
        User user = userRepository.save(User.builder()
                // 아이디는 30자 제한이라 UUID 앞 8자만 딴다(AdminGateIntegrationTest와 같은 방식)
                .username("act" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), role);
    }
}
