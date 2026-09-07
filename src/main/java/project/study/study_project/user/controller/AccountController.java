package project.study.study_project.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.user.dto.ChangePasswordRequest;
import project.study.study_project.user.dto.WithdrawRequest;
import project.study.study_project.user.service.AccountService;

/**
 * 내 계정 — 비밀번호 변경과 탈퇴 (2026-09-08).
 *
 * <p>경로가 {@code /api/me}인 것은 이 프로젝트의 기존 규칙을 따른 것이다. 내 것을 다루는
 * 것은 전부 {@code /api/me/**}에 있고(복습·오답노트·데일리·요약), 그쪽은 보호 경로다.
 * {@code /api/auth/**}는 <b>로그인하지 않은 사람이 두드리는 문</b>이라 성격이 다르다 —
 * 가입·로그인·재발급이 거기 있는 이유이고, 계정 관리가 거기 있으면 안 되는 이유다.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** 비밀번호 변경. 지금 비밀번호를 함께 받는다(DTO 주석 참고). */
    @PatchMapping("/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(userId, request);
        return ApiResponse.ok(null);
    }

    /**
     * 탈퇴 — 계정과 학습 기록을 지운다. <b>되돌릴 수 없다.</b>
     *
     * <p>DELETE에 본문을 싣는다. 규격이 금지하지는 않지만 흔한 모양은 아니라, 굳이 그렇게
     * 한 이유를 적어 둔다 — 비밀번호를 <b>쿼리스트링에 둘 수 없기</b> 때문이다.
     * 주소는 브라우저 기록·서버 접근 로그·리퍼러에 그대로 남는다.
     */
    @DeleteMapping
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody WithdrawRequest request) {
        accountService.withdraw(userId, request);
        return ApiResponse.ok(null);
    }
}
