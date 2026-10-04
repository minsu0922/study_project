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
    private User userWithId(Long id, String username, String passwordHash, Role role) {
        User user = User.builder().username(username).passwordHash(passwordHash).role(role).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("이미 있는 아이디면 AUTH_001, 저장은 시도하지 않는다")
        void duplicateUsernameFails() {
            when(userRepository.existsByUsername("tester")).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(new SignupRequest("tester", "password1", "테스터")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_001);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 쓰는 닉네임이면 DISCUSSION_004, 저장은 시도하지 않는다")
        void duplicateNicknameFails() {
            when(userRepository.existsByUsername("tester")).thenReturn(false);
            when(userRepository.existsByNickname("테스터")).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(new SignupRequest("tester", "password1", "테스터")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DISCUSSION_004);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("성공하면 비밀번호는 해시로 저장되고, 응답엔 원문 대신 id/username/role만 담긴다")
        void success() {
            when(userRepository.existsByUsername("tester")).thenReturn(false);
            when(passwordEncoder.encode("password1")).thenReturn("hashed");
            org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
            when(userRepository.saveAndFlush(saved.capture()))
                    .thenAnswer(inv -> userWithId(1L, "tester", "hashed", Role.USER));
            when(jwtTokenProvider.createToken(1L, Role.USER)).thenReturn("access-token");
            when(refreshTokenStore.issue(eq(1L), any(Duration.class))).thenReturn("refresh-token");

            SignupResponse response = authService.signup(new SignupRequest("tester", "password1", "테스터"));

            assertThat(saved.getValue().getNickname()).as("가입할 때 받은 닉네임을 저장한다").isEqualTo("테스터");
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.username()).isEqualTo("tester");
            assertThat(response.role()).isEqualTo(Role.USER);
            // 가입 응답이 토큰을 함께 준다 — 화면이 로그인을 한 번 더 부르면 요청 제한(분당 5회)을
            // 하나 더 쓰고, 닉네임이 겹쳐 몇 번 실패한 사람은 가입 직후 로그인이 막힌다.
            assertThat(response.tokens().accessToken()).isEqualTo("access-token");
            assertThat(response.tokens().refreshToken()).isEqualTo("refresh-token");
        }

        @Test
        @DisplayName("운영진으로 보이는 닉네임이면 DISCUSSION_010, 저장은 시도하지 않는다")
        void reservedNicknameFails() {
            when(userRepository.existsByUsername("tester")).thenReturn(false);

            assertThatThrownBy(() -> authService.signup(new SignupRequest("tester", "password1", "관리자")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DISCUSSION_010);
            verify(userRepository, never()).saveAndFlush(any());
        }

        /**
         * 중복 검사와 저장 사이에 같은 값의 가입이 끼어들면 유일 제약이 막는다. 그 예외를 그대로
         * 두면 500이 나가서, 같은 상황이 어떨 땐 안내이고 어떨 땐 서버 오류가 된다.
         */
        @Test
        @DisplayName("저장 순간 닉네임이 겹치면 DISCUSSION_004로 바꾼다 — 500이 아니라")
        void nicknameRaceBecomesConflict() {
            when(userRepository.existsByUsername("tester")).thenReturn(false);
            when(userRepository.existsByNickname("테스터")).thenReturn(false);
            when(passwordEncoder.encode("password1")).thenReturn("hashed");
            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_user_nickname"));

            assertThatThrownBy(() -> authService.signup(new SignupRequest("tester", "password1", "테스터")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DISCUSSION_004);
        }

        @Test
        @DisplayName("저장 순간 아이디가 겹치면 AUTH_001로 바꾼다")
        void usernameRaceBecomesConflict() {
            // 검사 때는 없었는데 저장에서 아이디 유일 제약(uk_user_username)에 걸린다 — 그 사이 들어왔다.
            when(userRepository.existsByUsername("tester")).thenReturn(false);
            when(userRepository.existsByNickname("테스터")).thenReturn(false);
            when(passwordEncoder.encode("password1")).thenReturn("hashed");
            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_user_username"));

            assertThatThrownBy(() -> authService.signup(new SignupRequest("tester", "password1", "테스터")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_001);
        }
    }

    @Nested
    @DisplayName("로그인 — 아이디 없음과 비밀번호 불일치는 '같은' 코드로 응답한다(어느 쪽이 틀렸는지 숨김)")
    class Login {

        @Test
        @DisplayName("존재하지 않는 아이디 → AUTH_002")
        void usernameNotFound() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "whatever1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_002);
        }

        @Test
        @DisplayName("비밀번호 불일치 → AUTH_002 (아이디 없음과 동일한 코드)")
        void wrongPassword() {
            User existing = userWithId(1L, "tester", "hashed", Role.USER);
            when(userRepository.findByUsername("tester")).thenReturn(Optional.of(existing));
            when(passwordEncoder.matches("wrong-pw1", "hashed")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("tester", "wrong-pw1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_002);
        }

        @Test
        @DisplayName("성공하면 access + refresh 토큰 한 세트를 발급한다")
        void success() {
            User existing = userWithId(1L, "tester", "hashed", Role.USER);
            when(userRepository.findByUsername("tester")).thenReturn(Optional.of(existing));
            when(passwordEncoder.matches("correct1", "hashed")).thenReturn(true);
            when(jwtTokenProvider.createToken(1L, Role.USER)).thenReturn("access-token");
            when(jwtTokenProvider.getValiditySeconds()).thenReturn(3600L);
            when(refreshTokenStore.issue(eq(1L), any(Duration.class))).thenReturn("refresh-token");

            LoginResponse response = authService.login(new LoginRequest("tester", "correct1"));

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
            User existing = userWithId(1L, "tester", "hashed", Role.USER);
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
