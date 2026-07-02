package project.study.study_project.auth.service;

import lombok.RequiredArgsConstructor;
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

/**
 * 인증(회원가입·로그인) 비즈니스 로직 — 설계는 docs/06.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

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
     * 로그인 → access 토큰 발급. 이메일이 없거나 비밀번호가 틀리면 둘 다
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

        String accessToken = jwtTokenProvider.createToken(user.getId(), user.getRole());
        return LoginResponse.of(accessToken, jwtTokenProvider.getValiditySeconds());
    }
}
