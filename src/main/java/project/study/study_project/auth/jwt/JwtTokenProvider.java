package project.study.study_project.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import project.study.study_project.user.domain.Role;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 발급·검증 담당 — 설계 계약은 docs/06-security-jwt.
 *
 * <p>토큰에 담는 정보(claims):
 * <ul>
 *   <li>{@code sub}(subject) = 사용자 id. 이메일이 아니라 id를 쓰는 이유: id는 불변이고,
 *       매 요청마다 DB를 다시 조회하지 않아도 되기 때문.
 *   <li>{@code role} = 권한(USER/ADMIN). 관리자 검사를 DB 없이 처리하기 위함.
 * </ul>
 *
 * <p>서명: HS256(대칭키). 비밀키는 {@code jwt.secret}에서 읽어 오며 256비트(32바이트) 이상이어야 한다.
 * jjwt 0.12.x API({@code signWith(key)}, {@code verifyWith(key)})를 사용한다 — 구버전과 메서드가 다르니 주의.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long validitySeconds;

    /**
     * @param secret          HS256 서명용 비밀키 문자열(application.yml {@code jwt.secret})
     * @param validitySeconds access 토큰 유효기간(초) (application.yml {@code jwt.access-token-validity-seconds})
     */
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-seconds}") long validitySeconds
    ) {
        // 문자열 비밀키를 HMAC-SHA 키 객체로 변환. 길이가 256비트 미만이면 여기서 예외가 나므로
        // 설정이 잘못됐을 때 부팅 단계에서 바로 드러난다(빠른 실패).
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validitySeconds = validitySeconds;
    }

    /** 로그인 응답의 expiresIn에 그대로 내려 줄 값. */
    public long getValiditySeconds() {
        return validitySeconds;
    }

    /** 사용자 id와 권한으로 서명된 access 토큰을 만든다. */
    public String createToken(Long userId, Role role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validitySeconds * 1000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))   // sub = userId
                .claim("role", role.name())        // role = "USER"/"ADMIN"
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)                     // 키 길이에 맞춰 HS256 자동 선택
                .compact();
    }

    /**
     * 서명·만료를 검증한다. 유효하면 true, 위조·만료·형식오류면 false.
     * (실패를 예외로 던지지 않고 boolean으로 돌려, 필터에서 "인증 안 함"으로 조용히 넘기기 위함)
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 JWT: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 검증된 토큰에서 인증 정보를 만든다. principal에는 사용자 id(Long)를 담아,
     * 이후 컨트롤러가 "요청 본문의 값"이 아니라 "토큰에서 꺼낸 id"를 신뢰하도록 한다.
     * 권한은 Spring Security 관례에 따라 {@code ROLE_} 접두어를 붙인다.
     */
    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        String role = claims.get("role", String.class);
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new UsernamePasswordAuthenticationToken(userId, null, authorities);
    }
}
