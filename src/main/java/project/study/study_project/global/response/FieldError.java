package project.study.study_project.global.response;

/**
 * 검증 실패한 개별 필드 정보 — 문서 04-response-format 기준.
 */
public record FieldError(String field, String reason) {
}
