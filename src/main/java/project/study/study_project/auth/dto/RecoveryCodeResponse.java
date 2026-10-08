package project.study.study_project.auth.dto;

/** 새로 발급한 복구 코드 원문. 서버는 해시만 가지므로 이 응답이 원문을 볼 수 있는 유일한 기회다. */
public record RecoveryCodeResponse(String recoveryCode) {
}
