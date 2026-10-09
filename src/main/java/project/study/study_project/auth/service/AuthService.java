package project.study.study_project.auth.service;

import project.study.study_project.auth.dto.PasswordResetRequest;
import project.study.study_project.auth.dto.RecoveryCodeResponse;
import project.study.study_project.user.support.RecoveryCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.dto.AvailabilityResponse;
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
import project.study.study_project.user.support.NicknameRule;

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
@Slf4j
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
     * 가입 전에 아이디를 쓸 수 있는지 답한다 — 가입 화면이 입력 도중에 묻는다.
     *
     * <p>가입과 <b>같은 규칙, 같은 문구</b>로 답한다. 여기서 된다고 한 값을 가입이 거절하면
     * 화면이 거짓말을 한 셈이 된다. 그래서 형식에 안 맞는 값도 오류가 아니라 "불가 + 이유"다.
     *
     * <p>아이디가 있는지를 누구에게나 알려 주는 창구이긴 하다. 다만 가입 요청이 이미 같은 것을
     * 알려 준다(AUTH_001) — 새로 새는 정보는 없고, 요청 제한(분당 60회)이 훑기를 늦춘다.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse checkUsername(String raw) {
        String username = normalize(raw);
        if (!username.matches(SignupRequest.USERNAME_PATTERN)) {
            return AvailabilityResponse.no(SignupRequest.USERNAME_MESSAGE);
        }
        return userRepository.existsByUsername(username)
                ? AvailabilityResponse.no(ErrorCode.AUTH_001.getDefaultMessage())
                : AvailabilityResponse.ok();
    }

    /** 닉네임을 쓸 수 있는지. 판단 순서는 가입({@link #signup})과 같다 — 형식, 운영진으로 보이는 말, 중복. */
    @Transactional(readOnly = true)
    public AvailabilityResponse checkNickname(String raw) {
        String nickname = raw == null ? "" : raw.trim();
        if (!nickname.matches(SignupRequest.NICKNAME_PATTERN)) {
            return AvailabilityResponse.no(SignupRequest.NICKNAME_MESSAGE);
        }
        if (NicknameRule.isReserved(nickname)) {
            return AvailabilityResponse.no(ErrorCode.DISCUSSION_010.getDefaultMessage());
        }
        return userRepository.existsByNickname(nickname)
                ? AvailabilityResponse.no(ErrorCode.DISCUSSION_004.getDefaultMessage())
                : AvailabilityResponse.ok();
    }

    /**
     * 회원가입. 아이디가 이미 있으면 {@link ErrorCode#AUTH_001}(409).
     * 비밀번호는 <b>BCrypt 해시로만</b> 저장한다(원문은 어디에도 남기지 않음).
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String username = normalize(request.username());
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.AUTH_001);
        }
        if (NicknameRule.isReserved(request.nickname())) {
            throw new BusinessException(ErrorCode.DISCUSSION_010);
        }
        // 닉네임도 유일해야 한다 — 토론에서 두 사람이 같은 이름으로 보이면 구분할 수 없다.
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.DISCUSSION_004);
        }
        User user = User.builder()
                .username(username)
                // email은 넘기지 않는다 — 이 서비스는 메일을 보내지 않아 받을 이유가 없다(V12).
                .passwordHash(passwordEncoder.encode(request.password())) // 단방향 해시
                .role(Role.USER)
                .build();
        user.changeNickname(request.nickname());
        String recoveryCode = RecoveryCode.generate();
        user.changeRecoveryCodeHash(passwordEncoder.encode(RecoveryCode.normalize(recoveryCode)));

        User saved;
        try {
            // 여기서 바로 내려보낸다. 위 검사와 저장 사이에 같은 값의 가입이 끼어들면 유일 제약이
            // 막는데, 그 예외가 메서드 밖(커밋 시점)에서 터지면 500으로 나간다.
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 어느 제약에 걸렸는지는 DB가 준 문구로 가른다. 다시 조회하지 않는 이유: 제약 위반이 난
            // 뒤의 영속성 컨텍스트는 믿을 수 없다. 제약 이름은 V12(uk_user_username)·V21(uk_user_nickname).
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new BusinessException(cause.contains("uk_user_username")
                    ? ErrorCode.AUTH_001 : ErrorCode.DISCUSSION_004);
        }
        return SignupResponse.of(saved, issueTokens(saved), recoveryCode);
    }

    /**
     * 복구 코드로 비밀번호를 다시 정한다(V32).
     *
     * <p>쓴 코드는 버리고 새 코드를 내준다. 한 번 쓴 코드가 계속 통하면,
     * 재설정 화면을 어깨 너머로 본 사람이 나중에 다시 계정을 가져갈 수 있다.
     *
     * @return 새 복구 코드 원문
     * @throws BusinessException 아이디가 없거나, 코드를 발급받은 적이 없거나, 코드가 틀리면 AUTH_006
     */
    @Transactional
    public RecoveryCodeResponse resetPassword(PasswordResetRequest request) {
        User user = userRepository.findByUsername(normalize(request.username()))
                .filter(u -> u.getRecoveryCodeHash() != null)
                .filter(u -> passwordEncoder.matches(
                        RecoveryCode.normalize(request.recoveryCode()), u.getRecoveryCodeHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_006));

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        refreshTokenStore.revokeAll(user.getId());
        String next = RecoveryCode.generate();
        user.changeRecoveryCodeHash(passwordEncoder.encode(RecoveryCode.normalize(next)));
        log.info("복구 코드로 비밀번호 재설정: userId={}", user.getId());   // 값은 남기지 않는다
        return new RecoveryCodeResponse(next);
    }

    /**
     * 로그인 → access + refresh 발급. 아이디가 없거나 비밀번호가 틀리면 둘 다
     * {@link ErrorCode#AUTH_002}(401)로 <b>동일하게</b> 응답한다.
     * (어느 쪽이 틀렸는지 알려주면 "이 아이디가 가입돼 있다"는 정보가 새어 나가므로 일부러 구분하지 않음)
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(normalize(request.username()))
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

    /**
     * 아이디 정규화 — 앞뒤 공백을 떼고 소문자로 낮춘다. <b>가입과 로그인이 같은 함수를 쓴다</b>.
     *
     * <p>한쪽만 정규화하면 "Minsu로 가입했는데 minsu로는 로그인이 안 되는" 상태가 된다.
     * 그리고 그 증상은 대문자를 쓴 사람에게만 나타나서 좀처럼 재현되지 않는다.
     *
     * <p><b>DB collation에 기대지 않는 이유</b>: 지금 테이블은 {@code utf8mb4_0900_ai_ci}라
     * 대소문자를 구분하지 않아 UNIQUE 제약만으로도 중복이 막힌다. 하지만 그건 <b>설정</b>이고,
     * 언젠가 바뀌면 조용히 깨진다 — 규칙은 코드에 적어 둔다.
     */
    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** access + refresh 한 세트 발급 (로그인·재발급 공용). */
    private LoginResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createToken(user.getId(), user.getRole());
        String refreshToken = refreshTokenStore.issue(user.getId(), Duration.ofSeconds(refreshValiditySeconds));
        return LoginResponse.of(accessToken, refreshToken, jwtTokenProvider.getValiditySeconds());
    }
}
