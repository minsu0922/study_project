package project.study.study_project.user.service;

/** 계정이 지워졌다(본인 탈퇴·강제 탈퇴). 인증 쪽이 듣고 그 사용자의 토큰을 끊는다. 이벤트인 이유는 {@link PasswordChanged}와 같다. */
public record AccountDeleted(Long userId) {
}
