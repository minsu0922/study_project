package project.study.study_project.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import project.study.study_project.auth.dto.RefreshRequest;
import project.study.study_project.auth.dto.SignupRequest;
import project.study.study_project.auth.dto.SignupResponse;
import project.study.study_project.auth.gate.AdminGateCookie;
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

    /**
     * 관리 화면 출입증 쿠키 — 로그인·재발급에 붙이고 로그아웃에 지운다.
     *
     * <p><b>왜 서비스가 아니라 컨트롤러가 다루나.</b> 쿠키는 HTTP의 물건이지 인증 로직의
     * 물건이 아니다. {@code AuthService}는 지금 "누구인지 확인하고 토큰을 만든다"만 알면
     * 되는데, 여기에 {@code HttpServletResponse}를 들여보내면 서비스가 웹 계층에 묶여
     * 테스트도 어려워진다. 컨트롤러가 <b>토큰을 쿠키로 옮겨 담는</b> 일만 한다.
     */
    private final AdminGateCookie adminGateCookie;

    /** 회원가입. 성공 시 201 Created + 생성된 회원 정보. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    /**
     * 로그인. 성공 시 200 + access/refresh 토큰 묶음(로드맵 2).
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
        return ApiResponse.ok(response);
    }

    /**
     * access 토큰 재발급(로드맵 2). refresh 토큰이 자격 증명이며 응답에서 <b>새 refresh로
     * 교체(회전)</b>된다 — 이전 refresh는 이 순간부터 무효. 무효 토큰이면 401 AUTH_005.
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        LoginResponse response = authService.refresh(request.refreshToken());
        // 출입증도 함께 갱신한다. 안 하면 access 토큰 수명(1시간)이 지나는 순간 관리 화면이
        // 404가 되는데, 정작 API는 재발급으로 멀쩡히 돈다 — 원인을 짐작하기 어려운 상태다.
        adminGateCookie.issue(httpRequest, httpResponse, response.accessToken());
        return ApiResponse.ok(response);
    }

    /**
     * 로그아웃(로드맵 2) — refresh 토큰 폐기. 이미 무효여도 200(멱등: 몇 번 눌러도 같은 결과).
     *
     * <p>출입증도 함께 지운다. 남겨 두면 로그아웃한 브라우저에서 관리 화면이 그대로 열린다 —
     * API는 401이라 데이터는 안 보이지만, 감추려던 화면 구성이 노출된 채로 남는다.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        authService.logout(request.refreshToken());
        adminGateCookie.clear(httpRequest, httpResponse);
        return ApiResponse.ok();
    }
}
