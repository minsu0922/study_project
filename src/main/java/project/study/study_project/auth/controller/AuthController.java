package project.study.study_project.auth.controller;

import project.study.study_project.auth.dto.PasswordResetRequest;
import project.study.study_project.user.dto.RecoveryCodeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.auth.cookie.RefreshTokenCookie;
import project.study.study_project.auth.dto.AvailabilityResponse;
import project.study.study_project.auth.dto.LoginRequest;
import project.study.study_project.auth.dto.LoginResponse;
import project.study.study_project.auth.dto.RefreshRequest;
import project.study.study_project.auth.dto.SignupRequest;
import project.study.study_project.auth.dto.SignupResponse;
import project.study.study_project.auth.gate.AdminGateCookie;
import project.study.study_project.auth.service.AuthService;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
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

    /**
     * 관리 화면 출입증 쿠키 — 로그인·재발급에 붙이고 로그아웃에 지운다.
     *
     * <p><b>왜 서비스가 아니라 컨트롤러가 다루나.</b> 쿠키는 HTTP의 물건이지 인증 로직의
     * 물건이 아니다. {@code AuthService}는 지금 "누구인지 확인하고 토큰을 만든다"만 알면
     * 되는데, 여기에 {@code HttpServletResponse}를 들여보내면 서비스가 웹 계층에 묶여
     * 테스트도 어려워진다. 컨트롤러가 <b>토큰을 쿠키로 옮겨 담는</b> 일만 한다.
     */
    private final AdminGateCookie adminGateCookie;

    /** refresh 토큰은 응답 본문이 아니라 이 쿠키로 나간다. 다루는 자리가 컨트롤러인 이유는 위와 같다. */
    private final RefreshTokenCookie refreshTokenCookie;

    /**
     * 아이디나 닉네임을 쓸 수 있는지 — 가입 화면이 입력 도중에 묻는다.
     * 예: {@code GET /api/auth/availability?username=minsu_01}. 둘 중 하나만 준다.
     *
     * <p>GET이라 인증 경로의 엄격한 요청 제한(분당 5회)에 들지 않는다. 글자를 칠 때마다 묻는
     * 화면이라 그 한도면 가입 자체를 못 한다. 일반 한도(분당 60회)를 쓴다.
     */
    @GetMapping("/availability")
    public ApiResponse<AvailabilityResponse> availability(@RequestParam(required = false) String username,
                                                          @RequestParam(required = false) String nickname) {
        if ((username == null) == (nickname == null)) {
            throw new BusinessException(ErrorCode.COMMON_001, "username과 nickname 가운데 하나만 보내 주세요.");
        }
        return ApiResponse.ok(username != null
                ? authService.checkUsername(username)
                : authService.checkNickname(nickname));
    }

    /**
     * 회원가입. 성공 시 201 Created + 생성된 회원 정보와 로그인 토큰.
     *
     * <p>출입증 쿠키도 로그인과 같게 다룬다. 새 계정은 관리자가 아니므로 옛 쿠키가 지워진다 —
     * 관리자로 쓰던 브라우저에서 새 계정을 만들었을 때 관리 화면이 계속 열리지 않게 한다.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        SignupResponse response = authService.signup(request);
        adminGateCookie.issue(httpRequest, httpResponse, response.tokens().accessToken());
        refreshTokenCookie.issue(httpRequest, httpResponse, response.tokens().refreshToken());
        return ApiResponse.ok(response);
    }

    /**
     * 로그인. 성공 시 200 + access 토큰. refresh 토큰은 HttpOnly 쿠키로 나간다.
     *
     * <p>관리자면 <b>출입증 쿠키가 함께 내려간다</b>. 화면 코드는 이 쿠키를 몰라도 되고
     * (HttpOnly라 읽을 수도 없다), 브라우저가 {@code /admin/**} 요청에 알아서 실어 보낸다.
     * 관리자가 아니면 옛 쿠키를 지운다 — 같은 브라우저에서 계정을 바꿔 로그인했을 때
     * "권한은 내려갔는데 관리 화면은 계속 열리는" 상태를 막는다.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        LoginResponse response = authService.login(request);
        adminGateCookie.issue(httpRequest, httpResponse, response.accessToken());
        refreshTokenCookie.issue(httpRequest, httpResponse, response.refreshToken());
        return ApiResponse.ok(response);
    }

    /**
     * access 토큰 재발급(로드맵 2). 쿠키의 refresh 토큰이 자격 증명이며 응답에서 <b>새 refresh로
     * 교체(회전)</b>된다 — 이전 refresh는 이 순간부터 무효. 없거나 무효면 401 AUTH_005.
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        String refreshToken = refreshTokenOf(httpRequest, request);
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.AUTH_005);
        }
        LoginResponse response = authService.refresh(refreshToken);
        refreshTokenCookie.issue(httpRequest, httpResponse, response.refreshToken());
        // 출입증도 함께 갱신한다. 안 하면 access 토큰 수명(1시간)이 지나는 순간 관리 화면이
        // 404가 되는데, 정작 API는 재발급으로 멀쩡히 돈다 — 원인을 짐작하기 어려운 상태다.
        adminGateCookie.issue(httpRequest, httpResponse, response.accessToken());
        return ApiResponse.ok(response);
    }

    /**
     * 복구 코드로 비밀번호 재설정(V32). 로그인하지 못하는 사람이 부르는 경로라 공개다.
     * 응답의 새 복구 코드는 이때 한 번만 볼 수 있다.
     */
    @PostMapping("/password-reset")
    public ApiResponse<RecoveryCodeResponse> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        return ApiResponse.ok(authService.resetPassword(request));
    }

    /**
     * 로그아웃(로드맵 2) — refresh 토큰 폐기. 이미 무효여도 200(멱등: 몇 번 눌러도 같은 결과).
     *
     * <p>출입증도 함께 지운다. 남겨 두면 로그아웃한 브라우저에서 관리 화면이 그대로 열린다 —
     * API는 401이라 데이터는 안 보이지만, 감추려던 화면 구성이 노출된 채로 남는다.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshRequest request,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        String refreshToken = refreshTokenOf(httpRequest, request);
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        adminGateCookie.clear(httpRequest, httpResponse);
        refreshTokenCookie.clear(httpRequest, httpResponse);
        return ApiResponse.ok();
    }

    /** 쿠키가 먼저다. 바디는 쿠키로 옮기기 전에 로그인한 브라우저만 쓴다(RefreshRequest 주석). */
    private String refreshTokenOf(HttpServletRequest httpRequest, RefreshRequest body) {
        return refreshTokenCookie.read(httpRequest)
                .orElseGet(() -> body == null || body.refreshToken() == null || body.refreshToken().isBlank()
                        ? null : body.refreshToken());
    }
}
