package project.study.study_project.auth.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import project.study.study_project.user.domain.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 단위 테스트 — 발급·검증 로직만 본다.
 *
 * <p>스프링 빈이지만 의존성이 생성자 인자(secret, validitySeconds)뿐이라 스프링 컨텍스트 없이
 * {@code new}로 바로 만들 수 있다 — 굳이 무거운 @SpringBootTest를 쓸 이유가 없다.
 */
class JwtTokenProviderTest {

    // HS256은 256비트(32바이트) 이상 키가 필요하다(JwtTokenProvider 생성자 주석 참고). 49바이트로 여유 있게.
    private static final String SECRET = "test-secret-key-for-jwt-unit-test-32bytes-minimum";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3600L);
    }

    @Test
    @DisplayName("발급한 토큰은 검증을 통과한다")
    void createdTokenIsValid() {
        String token = provider.createToken(1L, Role.USER);
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("토큰에서 꺼낸 인증 정보에 사용자 id(principal)와 ROLE_ 권한이 담긴다")
    void authenticationCarriesUserIdAndRole() {
        String token = provider.createToken(42L, Role.ADMIN);
        Authentication authentication = provider.getAuthentication(token);

        assertThat(authentication.getPrincipal()).isEqualTo(42L);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");
    }

    @Test
    @DisplayName("getValiditySeconds는 생성자로 받은 만료 시간을 그대로 돌려준다 — 로그인 응답의 expiresIn이 이 값이다")
    void returnsConfiguredValiditySeconds() {
        assertThat(provider.getValiditySeconds()).isEqualTo(3600L);
    }

    @Nested
    @DisplayName("검증 실패 케이스 — 전부 예외 대신 false로 조용히 처리된다(필터에서 '미인증'으로 넘기기 위함)")
    class InvalidToken {

        @Test
        @DisplayName("null 토큰은 false")
        void nullToken() {
            assertThat(provider.validateToken(null)).isFalse();
        }

        @Test
        @DisplayName("JWT 형식이 아닌 문자열은 false")
        void garbageToken() {
            assertThat(provider.validateToken("this-is-not-a-jwt")).isFalse();
        }

        @Test
        @DisplayName("서명이 위조된(끝 글자를 바꾼) 토큰은 false")
        void tamperedSignature() {
            String token = provider.createToken(1L, Role.USER);
            char last = token.charAt(token.length() - 1);
            String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');

            assertThat(provider.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("만료된 토큰은 false")
        void expiredToken() {
            // 유효기간을 음수로 준 provider로 발급 → 발급 시각(now) + (-10초) = 이미 과거인 만료시각이 찍힌다.
            JwtTokenProvider expiredIssuer = new JwtTokenProvider(SECRET, -10L);
            String expired = expiredIssuer.createToken(1L, Role.USER);

            assertThat(provider.validateToken(expired)).isFalse();
        }

        @Test
        @DisplayName("다른 비밀키로 서명된 토큰은 false — secret이 달라지면 위조와 구별할 수 없어야 한다")
        void differentSecret() {
            JwtTokenProvider otherSecretIssuer =
                    new JwtTokenProvider("completely-different-secret-key-32-bytes-or-more!!", 3600L);
            String tokenFromOther = otherSecretIssuer.createToken(1L, Role.USER);

            assertThat(provider.validateToken(tokenFromOther)).isFalse();
        }
    }
}
