package project.study.study_project.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.gate.AdminGateCookie;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 화면 출입증 통합 테스트 — 정적 파일을 쿠키로 막는 자리.
 *
 * <p><b>왜 통합 테스트인가.</b> 이 기능은 조각 셋이 맞물려야 동작하고, 하나만 어긋나도
 * <b>증상이 정반대로</b> 나타난다.
 * <ol>
 *   <li>{@code SecurityConfig}가 {@code /admin/**}을 열어 두지 않으면 — 관리자도 401을 받는다
 *       (필터까지 가지도 못한다)
 *   <li>필터의 경로 판정이 틀리면 — 모든 페이지가 404가 되거나, 관리 화면이 그냥 뚫린다
 *   <li>로그인 응답에 쿠키가 안 붙으면 — 로그인해도 화면이 안 열린다
 * </ol>
 * 셋 다 단위 테스트로는 볼 수 없다. 실제 요청이 필터 사슬을 지나야 드러난다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로 롤백된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminGateIntegrationTest {

    /** 실제로 존재하는 관리 화면 하나. 없는 파일로 시험하면 404가 게이트 때문인지 알 수 없다. */
    private static final String ADMIN_PAGE = "/admin/index.html";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("출입증이 없으면 관리 화면은 404 — 401이면 '뭔가 있다'를 알려 주는 셈이다")
    void hidesAdminPageWithoutCookie() throws Exception {
        mockMvc.perform(get(ADMIN_PAGE))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("관리자 쿠키가 있으면 관리 화면이 열린다")
    void servesAdminPageWithAdminCookie() throws Exception {
        mockMvc.perform(get(ADMIN_PAGE).cookie(gateCookie(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /**
     * 가장 중요한 검증이다. 쿠키가 <b>있기만 하면</b> 통과시키는 구현이었다면 일반 사용자도
     * 관리 화면을 열게 되는데, 그 상태는 겉보기에 정상이라(관리자에게는 잘 열리므로)
     * 눈치채기 어렵다.
     */
    @Test
    @DisplayName("일반 사용자 토큰이 든 쿠키로는 열리지 않는다 — 쿠키의 존재가 아니라 역할을 본다")
    void rejectsNonAdminCookie() throws Exception {
        mockMvc.perform(get(ADMIN_PAGE).cookie(gateCookie(Role.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("깨진 쿠키는 없는 것과 같이 취급한다 — 서명 검증에서 걸린다")
    void rejectsTamperedCookie() throws Exception {
        mockMvc.perform(get(ADMIN_PAGE).cookie(new Cookie(AdminGateCookie.NAME, "not-a-jwt")))
                .andExpect(status().isNotFound());
    }

    /**
     * 주소창에 {@code /admin}만 쳐 보는 것은 사람이 가장 먼저 하는 일이다. 정적 리소스 처리기는
     * 디렉터리를 {@code index.html}로 바꿔 주지 않으므로 보정이 필요하고(WebMvcConfig),
     * 그 보정이 <b>게이트를 건너뛰지 않는지</b>도 함께 못 박는다.
     */
    @Test
    @DisplayName("/admin 짧은 주소도 대시보드로 이어지되, 출입증이 없으면 여전히 404다")
    void shortAdminPathGoesThroughTheGate() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/")).andExpect(status().isNotFound());

        mockMvc.perform(get("/admin").cookie(gateCookie(Role.ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get("/admin/").cookie(gateCookie(Role.ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("사용자 화면은 그대로 공개다 — 관리 화면을 잠그면서 로그인 페이지까지 잠그면 안 된다")
    void keepsPublicPagesOpen() throws Exception {
        mockMvc.perform(get("/login.html")).andExpect(status().isOk());
        mockMvc.perform(get("/js/api.js")).andExpect(status().isOk());
    }

    /**
     * 관리자로 로그인하면 응답에 출입증이 실려야 한다. 이게 빠지면 "로그인은 되는데 관리
     * 화면은 404"라는, 원인을 짐작하기 가장 어려운 상태가 된다.
     */
    @Test
    @DisplayName("관리자 로그인 응답에 출입증 쿠키가 실린다 — HttpOnly이고 /admin 경로 전용이다")
    void issuesGateCookieOnAdminLogin() throws Exception {
        String email = createUser(Role.ADMIN);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(AdminGateCookie.NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).as("화면 코드가 읽을 일이 없다 — XSS 한 방에 새어 나가면 안 된다").isTrue();
        assertThat(cookie.getPath()).isEqualTo(AdminGateCookie.PATH);
        assertThat(cookie.getMaxAge()).as("토큰 수명과 맞춰야 '화면은 열리는데 API는 401'이 안 생긴다")
                .isEqualTo((int) jwtTokenProvider.getValiditySeconds());
    }

    /**
     * 같은 브라우저에서 관리자로 쓰다가 일반 계정으로 로그인하는 경우다. 옛 쿠키를 안 지우면
     * <b>권한은 내려갔는데 관리 화면은 계속 열린다</b>.
     */
    @Test
    @DisplayName("일반 사용자로 로그인하면 남아 있던 출입증을 지운다")
    void clearsGateCookieOnNonAdminLogin() throws Exception {
        String email = createUser(Role.USER);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(AdminGateCookie.NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).as("수명 0으로 덮어써야 브라우저가 지운다").isZero();
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    /** 그 역할의 토큰을 담은 출입증 쿠키. 로그인 흐름을 거치지 않고 필터만 시험할 때 쓴다. */
    private Cookie gateCookie(Role role) {
        User user = save(role, "gate-" + UUID.randomUUID() + "@test.local");
        return new Cookie(AdminGateCookie.NAME, jwtTokenProvider.createToken(user.getId(), role));
    }

    /** 로그인 흐름까지 시험할 때 쓰는 계정. 비밀번호는 아래 요청 본문과 같아야 한다. */
    private String createUser(Role role) {
        return save(role, "gate-" + UUID.randomUUID() + "@test.local").getEmail();
    }

    private User save(Role role, String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build());
    }
}
