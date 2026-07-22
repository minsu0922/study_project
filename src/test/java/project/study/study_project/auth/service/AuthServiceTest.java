package project.study.study_project.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.auth.dto.LoginRequest;
import project.study.study_project.auth.dto.LoginResponse;
import project.study.study_project.auth.dto.SignupRequest;
import project.study.study_project.auth.dto.SignupResponse;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 단위 테스트 — 회원가입·로그인·재발급·로그아웃의 성공/실패 분기를
 * 가짜 저장소(UserRepository)·가짜 Redis(RefreshTokenStore)로 검증한다.
 *
 * <p>실제 DB/Redis·HTTP까지 다 통하는지는 AuthFlowIntegrationTest가 본다 — 여기서는
 * "어떤 입력이 어떤 ErrorCode로 이어지는지" 서비스의 분기 로직만 빠르게 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenStore refreshTokenStore;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider, refreshTokenStore);
        // refreshValiditySeconds는 생성자 인자가 아니라 @Value 필드 주입(application.yml)이라
        // 스프링 컨텍스트 없는 단위 테스트에서는 리플렉션으로 직접 채워 넣는다.
        ReflectionTestUtils.setField(authService, "refreshValiditySeconds", 1_209_600L);
    }

    /** id까지 채워진 User를 흉내 낸다 — id는 @GeneratedValue라 빌더로는 못 주므로 리플렉션으로 넣는다. */
    private User userWithId(Long id, String email, String passwordHash, Role role) {
        User user = User.builder().email(email).passwordHash(passwordHash).role(role).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("이미 있는 이메일이면 AUTH_001, 저장은 시도하지 않는다")
        void duplicateEmailFails() {
            when(userRepository.existsByEmail("a@test.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(new SignupRequest("a@test.com", "password1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_001);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("성공하면 비밀번호는 해시로 저장되고, 응답엔 원문 대신 id/email/role만 담긴다")
        void success() {
            when(userRepository.existsByEmail("a@test.com")).thenReturn(false);
            when(passwordEncoder.encode("password1")).thenReturn("hashed");
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> userWithId(1L, "a@test.com", "hashed", Role.USER));

            SignupResponse response = authService.signup(new SignupRequest("a@test.com", "password1"));

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("a@test.com");
            assertThat(response.role()).isEqualTo(Role.USER);
        }
    }

    @Nested
    @DisplayName("로그인 — 이메일 없음과 비밀번호 불일치는 '같은' 코드로 응답한다(어느 쪽이 틀렸는지 숨김)")
    class Login {

        @Test
        @DisplayName("존재하지 않는 이메일 → AUTH_002")
        void emailNotFound() {
            when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@test.com", "whatever1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_002);
        }

        @Test
        @DisplayName("비밀번호 불일치 → AUTH_002 (이메일 없음과 동일한 코드)")
        void wrongPassword() {
            User existing = userWithId(1L, "a@test.com", "hashed", Role.USER);
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(existing));
            when(passwordEncoder.matches("wrong-pw1", "hashed")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("a@test.com", "wrong-pw1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_002);
        }

        @Test
        @DisplayName("성공하면 access + refresh 토큰 한 세트를 발급한다")
        void success() {
            User existing = userWithId(1L, "a@test.com", "hashed", Role.USER);
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(existing));
            when(passwordEncoder.matches("correct1", "hashed")).thenReturn(true);
            when(jwtTokenProvider.createToken(1L, Role.USER)).thenReturn("access-token");
            when(jwtTokenProvider.getValiditySeconds()).thenReturn(3600L);
            when(refreshTokenStore.issue(eq(1L), any(Duration.class))).thenReturn("refresh-token");

            LoginResponse response = authService.login(new LoginRequest("a@test.com", "correct1"));

            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(3600L);
        }
    }

    @Nested
    @DisplayName("토큰 재발급 — refresh는 1회용(회전)이라 소비 실패는 전부 재로그인 요구로 귀결된다")
    class Refresh {

        @Test
        @DisplayName("무효한(만료·이미 사용된·위조) refresh 토큰 → AUTH_005")
        void invalidRefreshToken() {
            when(refreshTokenStore.consume("bad-token")).thenReturn(null);

            assertThatThrownBy(() -> authService.refresh("bad-token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_005);
        }

        @Test
        @DisplayName("토큰은 유효했지만 그 사이 사용자가 사라졌으면 AUTH_005")
        void userNoLongerExists() {
            when(refreshTokenStore.consume("token")).thenReturn(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_005);
        }

        @Test
        @DisplayName("성공하면 새 access + 새 refresh를 받는다")
        void success() {
            User existing = userWithId(1L, "a@test.com", "hashed", Role.USER);
            when(refreshTokenStore.consume("old-refresh")).thenReturn(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(jwtTokenProvider.createToken(1L, Role.USER)).thenReturn("new-access");
            when(jwtTokenProvider.getValiditySeconds()).thenReturn(3600L);
            when(refreshTokenStore.issue(eq(1L), any(Duration.class))).thenReturn("new-refresh");

            LoginResponse response = authService.refresh("old-refresh");

            assertThat(response.accessToken()).isEqualTo("new-access");
            assertThat(response.refreshToken()).isEqualTo("new-refresh");
        }
    }

    @Test
    @DisplayName("로그아웃은 refresh 토큰을 저장소에서 폐기한다(멱등 — 이미 없어도 예외를 던지지 않음)")
    void logoutRevokesRefreshToken() {
        authService.logout("some-token");

        verify(refreshTokenStore).revoke("some-token");
    }
}
