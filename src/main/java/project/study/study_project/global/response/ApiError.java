package project.study.study_project.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 실패 응답의 error 필드 — 문서 04-response-format 기준.
 * <p>{@code fieldErrors}는 검증 오류일 때만 채워지고, 그 외엔 응답에서 생략된다(NON_NULL).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(code, message, fieldErrors);
    }
}
