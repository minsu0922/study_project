package project.study.study_project.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * 인증(회원가입·로그인·토큰 재발급·로그아웃) 비즈니스 로직 — 설계는 docs/06 + 로드맵 2.
 *
 * <p>토큰 이원 체계(로드맵 2에서 완성):
 * <ul>
 *   <li><b>access(JWT, 1시간)</b> — 매 요청의 신분증. 서버 무상태 검증(서명만 확인).
 *   <li><b>refresh(불투명 토큰, 14일, Redis)</b> — access 재발급 전용. 서버가 회수 가능해서
 *       "로그아웃"과 "탈취 대응"이 실제로 동작한다. 한 번 쓰면 새것으로 교체(회전)된다.
 * </ul>
 * 왜 이렇게 나누는지·왜 refresh는 JWT가 아닌지는 RefreshTokenStore 주석 참고.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    @Value("${jwt.refresh-token-validity-seconds}")
    private long refreshValiditySeconds;

    /**
     * 회원가입. 이메일이 이미 있으면 {@link ErrorCode#AUTH_001}(409).
     * 비밀번호는 <b>BCrypt 해시로만</b> 저장한다(원문은 어디에도 남기지 않음).
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.AUTH_001);
        }
        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password())) // 단방향 해시
                .role(Role.USER)
                .build();
        return SignupResponse.from(userRepository.save(user));
    }

    /**
     * 로그인 → access + refresh 발급. 이메일이 없거나 비밀번호가 틀리면 둘 다
     * {@link ErrorCode#AUTH_002}(401)로 <b>동일하게</b> 응답한다.
     * (어느 쪽이 틀렸는지 알려주면 "이 이메일이 가입돼 있다"는 정보가 새어 나가므로 일부러 구분하지 않음)
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_002));

        // 입력 원문 비번을 저장된 해시와 대조(matches 내부에서 해시하여 비교)
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }
        return issueTokens(user);
    }

    /**
     * access 토큰 재발급 — refresh 토큰을 소비하고 <b>새 access + 새 refresh</b>를 준다(회전).
     *
     * <p>무효한(만료·이미 사용·위조) refresh면 {@link ErrorCode#AUTH_005}(401) — 클라이언트는
     * 이 코드를 받으면 재로그인으로 보낸다. DB에서 사용자를 다시 읽는 이유: 14일 사이에
     * 권한(role)이 바뀌었거나 계정이 사라졌을 수 있어서, 토큰 발급 시점의 최신 상태를 반영한다.
     */
    @Transactional(readOnly = true)
    public LoginResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.consume(refreshToken); // 검증 + 폐기(회전)를 원자적으로
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_005);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_005));
        return issueTokens(user);
    }

    /**
     * 로그아웃 — refresh 토큰을 저장소에서 폐기한다. 이후 이 토큰으로는 재발급이 불가능하다.
     *
     * <p>이미 발급된 access 토큰은 만료(최대 1시간)까지는 유효하다는 한계가 있다 —
     * JWT는 회수가 안 되기 때문(ADR-0001). 완전 즉시 차단이 필요하면 access 블랙리스트를
     * Redis에 추가하는 방법이 있지만, 매 요청 Redis 조회가 생겨 무상태의 이점이 줄어드는
     * 트레이드오프라 MVP+에서는 "짧은 access 수명"으로 갈음한다.
     */
    public void logout(String refreshToken) {
        refreshTokenStore.revoke(refreshToken); // 없어도 조용히 성공(멱등)
    }

    /** access + refresh 한 세트 발급 (로그인·재발급 공용). */
    private LoginResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createToken(user.getId(), user.getRole());
        String refreshToken = refreshTokenStore.issue(user.getId(), Duration.ofSeconds(refreshValiditySeconds));
        return LoginResponse.of(accessToken, refreshToken, jwtTokenProvider.getValiditySeconds());
    }
}
