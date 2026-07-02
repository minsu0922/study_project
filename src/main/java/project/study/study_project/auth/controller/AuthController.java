package project.study.study_project.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.auth.dto.LoginRequest;
import project.study.study_project.auth.dto.LoginResponse;
import project.study.study_project.auth.dto.SignupRequest;
import project.study.study_project.auth.dto.SignupResponse;
import project.study.study_project.auth.service.AuthService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 인증 API — 회원가입/로그인. 명세는 docs/03-api-spec.
 *
 * <p>컨트롤러는 얇게 유지한다: 검증(@Valid)과 응답 포장(ApiResponse)만 하고, 실제 로직은 서비스에 위임.
 * 반환 타입을 {@code ApiResponse}로 통일해 모든 응답이 같은 봉투를 쓰도록 한다(docs/04).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 회원가입. 성공 시 201 Created + 생성된 회원 정보. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    /** 로그인. 성공 시 200 + access 토큰. */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
