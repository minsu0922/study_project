package project.study.study_project.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import project.study.study_project.auth.jwt.JwtAuthenticationFilter;
import project.study.study_project.auth.jwt.JwtTokenProvider;

/**
 * Spring Security 설정 — 경로별 접근 규칙과 JWT 필터/실패 처리기를 조립한다. 설계는 docs/06.
 *
 * <p>핵심 결정:
 * <ul>
 *   <li><b>Stateless</b>: 서버가 세션을 만들지 않는다. 매 요청의 JWT만으로 인증을 판단한다.
 *   <li><b>CSRF 비활성화</b>: CSRF는 브라우저가 쿠키/세션을 자동 전송해서 생기는 공격인데,
 *       우리는 세션 쿠키가 아니라 Authorization 헤더의 토큰을 쓰므로 공격 표면 자체가 없다.
 *   <li><b>폼로그인/httpBasic 비활성화</b>: 로그인은 JSON API(/api/auth/login)로만 한다.
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint; // 미인증 → AUTH_003
    private final JwtAccessDeniedHandler accessDeniedHandler;           // 권한부족 → AUTH_004

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // REST + 토큰 기반이라 불필요한 기본 기능들을 끈다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 세션을 아예 만들지 않는다(토큰만으로 판단).
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 경로별 접근 권한 (docs/06 표와 일치)
                .authorizeHttpRequests(auth -> auth
                        // 공개: 프론트 정적 파일(HTML/CSS/JS). "화면은 누구나 열 수 있고,
                        // 화면 속 데이터(API)만 토큰으로 보호한다" — 페이지를 잠그면 로그인
                        // 화면 자체도 못 여는 모순이 생기므로 정적 리소스는 전부 연다.
                        .requestMatchers("/", "/*.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        // 공개: 회원가입/로그인
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                        // 공개: 문서/퀴즈 읽기
                        .requestMatchers(HttpMethod.GET, "/api/documents/**", "/api/quiz").permitAll()
                        // 공개: API 문서(Swagger)
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 보호: 답안 제출 / 내 정보(오답노트 등)
                        .requestMatchers(HttpMethod.POST, "/api/quiz/submit").authenticated()
                        .requestMatchers("/api/me/**").authenticated()
                        // 관리자 전용: 콘텐츠 등록/수정/삭제·대시보드. hasRole("ADMIN")은
                        // JWT의 role 클레임으로 만든 "ROLE_ADMIN" 권한(JwtTokenProvider)과 대응된다.
                        // 경로 한 곳에서 일괄 통제 — admin 컨트롤러에 API를 추가해도 권한이 자동 적용된다.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 나머지는 기본적으로 인증 요구
                        .anyRequest().authenticated())
                // 인증/인가 실패를 공통 응답 봉투로 변환
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // JWT 필터를 아이디/비번 인증 필터 자리 앞에 끼워 넣는다(토큰을 먼저 해석).
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 비밀번호 해시 인코더. BCrypt는 일부러 느리게 설계돼 무차별 대입에 강하고,
     * 매번 다른 salt가 자동으로 붙어 같은 비밀번호도 저장 해시가 달라진다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
