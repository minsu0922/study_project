package project.study.study_project.global;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.exception.GlobalExceptionHandler;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스프링이 컨트롤러에 닿기 전에 던지는 예외가 COMMON_500으로 뭉치지 않는지,
 * 없는 주소가 부른 쪽(사람/스크립트)에 맞는 모양으로 나가는지 본다.
 *
 * <p>통합 테스트인 이유: 이 예외들은 DispatcherServlet이 던진다. 처리기 메서드만 따로 부르면
 * "그 예외가 실제로 이 처리기에 닿는가"를 못 본다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로 롤백된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private GlobalExceptionHandler handler;

    @Test
    @DisplayName("받지 않는 메서드는 500이 아니라 405 COMMON_405다")
    void methodNotSupported() throws Exception {
        mockMvc.perform(post("/api/me/bookmarks/state").header("Authorization", bearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_405"));
    }

    @Test
    @DisplayName("필수 파라미터가 빠지면 400이고 어느 칸인지 알려 준다")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/api/me/bookmarks").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("type"));
    }

    @Test
    @DisplayName("JSON 자리에 다른 형식을 보내면 415 COMMON_415다")
    void mediaTypeNotSupported() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("COMMON_415"));
    }

    /**
     * 이것만 처리기를 직접 부른다. MockMvc의 multipart 요청은 톰캣을 거치지 않아
     * application.yml의 상한이 적용되지 않는다 — 큰 파일을 실어도 예외가 나지 않는다.
     */
    @Test
    @DisplayName("업로드 상한 초과는 413 COMMON_413으로 바뀐다")
    void maxUploadSize() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error().code()).isEqualTo("COMMON_413");
    }

    /* ── 없는 주소 ───────────────────────────────────────────── */

    @Test
    @DisplayName("브라우저로 없는 화면을 열면 JSON이 아니라 404 안내 화면이 나간다")
    void missingPageGetsHtml() throws Exception {
        mockMvc.perform(get("/no-such-page.html").accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("없는 주소입니다")));
    }

    /** 확장자 없는 주소는 예전에 인증 규칙에 먼저 걸려 401 JSON이 나갔다(SecurityConfig의 anyRequest). */
    @Test
    @DisplayName("확장자 없는 없는 주소도 401이 아니라 404 안내 화면이다")
    void missingPathIsNotUnauthorized() throws Exception {
        mockMvc.perform(get("/no/such/path").accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    @DisplayName("스크립트가 부른 없는 파일은 그대로 COMMON_404 JSON이다")
    void missingFileForFetchStaysJson() throws Exception {
        mockMvc.perform(get("/js/no-such.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMON_404"));
    }

    @Test
    @DisplayName("없는 API 주소는 Accept가 text/html이어도 JSON이고, 비로그인이면 여전히 401이다")
    void missingApiStaysJsonAndLocked() throws Exception {
        mockMvc.perform(get("/api/no-such").accept("text/html"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));

        mockMvc.perform(get("/api/no-such").accept("text/html").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMON_404"));
    }

    /** /api/ 밖을 열면서 함께 열리면 안 되는 곳. 이 규칙은 anyRequest보다 위에 따로 적혀 있다. */
    @Test
    @DisplayName("/api/ 밖을 열어도 actuator는 잠긴 채다")
    void actuatorStaysLocked() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    /* ── /error ─────────────────────────────────────────────── */

    @Test
    @DisplayName("/error를 직접 열면 Whitelabel이 아니라 404 안내 화면이다")
    void errorPathOpenedDirectly() throws Exception {
        mockMvc.perform(get("/error").accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("없는 주소입니다")));
    }

    /** MockMvc는 ERROR 디스패치를 재현하지 않으므로, 컨테이너가 넣어 주는 속성을 손으로 넣는다. */
    @Test
    @DisplayName("컨테이너가 넘긴 오류는 상태를 그대로 두고 공통 봉투로 나간다")
    void errorDispatchKeepsStatusInEnvelope() throws Exception {
        mockMvc.perform(post("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/admin/llm-problems/generate-from-document"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_413"));

        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/login.html")
                        .accept("text/html"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_500"));
    }

    private String bearer() {
        User user = userRepository.save(User.builder()
                .username("exh" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), Role.USER);
    }
}
