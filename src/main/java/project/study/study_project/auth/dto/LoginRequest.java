package project.study.study_project.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 바디 — API 스펙(docs/03).
 *
 * <p>로그인에서는 <b>아이디 형식도, 비밀번호 형식도 검사하지 않는다</b>. 형식 검증은 가입
 * 시점의 몫이고, 로그인은 "값이 비었는지"만 보고 실제 일치 여부는 서비스가 판단한다
 * (불일치 → AUTH_002). 형식 오류를 로그인에서 친절히 알려주면 공격자에게 힌트가 된다 —
 * "이 아이디는 형식이 맞다"는 사실만으로도 후보를 좁혀 준다.
 *
 * <p>V12에서 {@code email} → {@code username}으로 바뀌었다.
 */
public record LoginRequest(

        @NotBlank(message = "아이디는 필수입니다.")
        String username,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
