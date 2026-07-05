package project.study.study_project.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급/로그아웃 요청 바디 — refresh 토큰이 곧 자격 증명이다.
 * (Authorization 헤더의 access 토큰은 만료됐을 수 있으므로 여기에 의존하지 않는다)
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken은 필수입니다.")
        String refreshToken
) {
}
