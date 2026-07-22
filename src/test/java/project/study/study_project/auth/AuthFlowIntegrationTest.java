package project.study.study_project.auth;

import com.jayway.jsonpath.JsonPath;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증(회원가입·로그인·토큰 재발급·로그아웃) 통합 테스트 — 실제 HTTP 요청으로
 * SecurityConfig의 필터 체인(JwtAuthenticationFilter → 인가 규칙 → 예외 처리기)까지 전부 태운다.
 *
 * <p>단위 테스트(JwtTokenProviderTest, AuthServiceTest)가 로직 조각을 봤다면, 여기서는
 * 단위 테스트가 못 보는 것들을 본다: 실제 Authorization 헤더 파싱, 경로별 접근 규칙
 * (공개/인증필요/ADMIN 전용)이 진짜로 적용되는지, 인증·인가 실패가 공통 응답 봉투(ApiResponse)로
 * 나오는지, refresh 토큰의 1회성(회전)이 Redis까지 포함해 실제로 지켜지는지.
 *
 * <p>MySQL·Redis가 필요하다(RefreshTokenStore가 진짜 Redis를 쓴다) — CI의 서비스 컨테이너,
 * 로컬은 docker-compose로 뜬 것을 그대로 쓴다(MySQL 미가동이면 컨텍스트 로드 단계에서 실패,
 * ReviewFlowIntegrationTest와 같은 전제). DB는 클래스의 {@code @Transactional}로 테스트마다
 * 롤백되지만, Redis의 refresh 토큰 키는 롤백 대상이 아니다 — 다만 랜덤 값이라 테스트끼리
 * 절대 충돌하지 않고 TTL(14일)로 알아서 청소되므로, 기존 TokenBucketRateLimiterTest와
 * 같은 방식으로 그냥 둔다(별도 정리 코드를 두지 않음).
 *
 * <p><b>요청 제한(로드맵 3)은 이 클래스에서만 끈다</b>: {@code /api/auth/login|signup|refresh}는
 * IP당 분당 5회로 엄격히 제한되는데(RateLimitFilter), 이 클래스는 그 세 경로를 테스트
 * 메서드마다 여러 번 호출해서 한 번에 20회 넘게 부른다 — 같은 "IP"(MockMvc는 전부
 * 127.0.0.1)에서 오는 정상적인 시나리오 검증인데 방화벽(요청 제한)에 걸려 429가 섞여
 * 나오면 무엇을 검증하는 테스트인지 흐려진다. 요청 제한 자체의 동작은 이미
 * RateLimitFilterTest·TokenBucketRateLimiterTest가 전담해서 보고 있으므로 여기선 끈다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** 테스트마다 겹치지 않는 이메일 — 회원가입 중복 검사에 서로 영향을 주지 않기 위함. */
    private String freshEmail() {
        return "auth-test-" + UUID.randomUUID() + "@test.local";
    }

    private MvcResult signup(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andReturn();
    }

    private String field(MvcResult result, String jsonPathExpr) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPathExpr);
    }

    /** 관리자 권한 토큰이 필요한 테스트용 — 회원가입 API로는 ADMIN을 만들 수 없으므로 직접 저장한다. */
    private String issueAdminToken() {
        User admin = userRepository.save(User.builder()
                .email(freshEmail())
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }

    @Test
    @DisplayName("회원가입 → 로그인 → 토큰으로 보호 API 접근까지 정상 흐름")
    void signupLoginAndAccessProtectedResource() throws Exception {
        String email = freshEmail();

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("USER"));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn();
        String accessToken = field(loginResult, "$.data.accessToken");

        // 보호 리소스(/api/me/**)는 토큰만 있으면 통과 — 여기선 401/403이 아니면 충분.
        mockMvc.perform(get("/api/me/reviews/today")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이미 가입된 이메일로 다시 가입하면 409 AUTH_001")
    void signupDuplicateEmailFails() throws Exception {
        String email = freshEmail();
        signup(email, "password1"); // 1차 가입(성공 전제)

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    @DisplayName("영문+숫자 8자 미만 비밀번호로 가입하면 400 VALIDATION_ERROR")
    void signupWeakPasswordFails() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"short1"}""".formatted(freshEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 AUTH_002 — 존재하지 않는 이메일과 같은 코드")
    void loginWrongPasswordFails() throws Exception {
        String email = freshEmail();
        signup(email, "password1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-pw1"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_002"));
    }

    @Test
    @DisplayName("토큰 없이 보호 API에 접근하면 401 AUTH_003")
    void accessProtectedResourceWithoutTokenFails() throws Exception {
        mockMvc.perform(get("/api/me/reviews/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    @Test
    @DisplayName("위조된 토큰으로 보호 API에 접근해도 401 AUTH_003 — 필터가 조용히 무시하고 인가 단계에서 걸린다")
    void accessProtectedResourceWithTamperedTokenFails() throws Exception {
        mockMvc.perform(get("/api/me/reviews/today")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    @Test
    @DisplayName("일반 사용자 토큰으로 관리자 API에 접근하면 403 AUTH_004")
    void accessAdminResourceAsNormalUserFails() throws Exception {
        String email = freshEmail();
        signup(email, "password1");
        String accessToken = field(login(email, "password1"), "$.data.accessToken");

        mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_004"));
    }

    @Test
    @DisplayName("관리자 토큰이면 관리자 API에 접근할 수 있다")
    void accessAdminResourceAsAdminSucceeds() throws Exception {
        String adminToken = issueAdminToken();

        mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("refresh 토큰은 1회용이다 — 재발급에 쓰고 나면 같은 토큰으로 또 재발급받을 수 없다(회전)")
    void refreshTokenRotatesAndOldOneIsRejected() throws Exception {
        String email = freshEmail();
        signup(email, "password1");
        String oldRefreshToken = field(login(email, "password1"), "$.data.refreshToken");

        // 1차 재발급 — 새 토큰 세트를 받는다
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(oldRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn();
        String newRefreshToken = field(refreshResult, "$.data.refreshToken");
        Assertions.assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

        // 옛 refresh를 다시 쓰면 이미 소비돼서(GETDEL) 401 AUTH_005
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(oldRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_005"));
    }

    @Test
    @DisplayName("로그아웃하면 그 refresh 토큰으로는 더 이상 재발급받을 수 없다")
    void logoutRevokesRefreshToken() throws Exception {
        String email = freshEmail();
        signup(email, "password1");
        String refreshToken = field(login(email, "password1"), "$.data.refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_005"));
    }
}
