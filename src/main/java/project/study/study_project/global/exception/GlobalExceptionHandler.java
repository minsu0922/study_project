package project.study.study_project.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import project.study.study_project.global.response.ApiError;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.FieldError;

import java.util.List;

/**
 * 전역 예외 → 공통 응답 envelope 변환 — 문서 04-response-format 기준.
 * <p>Security 인증/인가 실패(AUTH_003/004)는 SecurityConfig의
 * AuthenticationEntryPoint/AccessDeniedHandler에서 동일 envelope로 변환한다(Step 5).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** @Valid 바디 검증 실패 → VALIDATION_ERROR + fieldErrors */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = toFieldErrors(e.getBindingResult());
        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                fieldErrors);
        return build(ErrorCode.VALIDATION_ERROR, error);
    }

    /** @Validated 파라미터/경로변수 검증 실패 → VALIDATION_ERROR */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                fieldErrors);
        return build(ErrorCode.VALIDATION_ERROR, error);
    }

    /**
     * 쿼리/경로 파라미터 타입 변환 실패 → VALIDATION_ERROR.
     * 대표 예: enum 파라미터에 허용되지 않는 값(예 {@code ?domain=FOO}) → 400 (docs/02).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        FieldError fieldError = new FieldError(e.getName(), "허용되지 않는 값입니다: " + e.getValue());
        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                List.of(fieldError));
        return build(ErrorCode.VALIDATION_ERROR, error);
    }

    /** 요청 바디 파싱 실패(깨진 JSON, 타입 불일치 등) → COMMON_001 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return build(ErrorCode.COMMON_001, ApiError.of(
                ErrorCode.COMMON_001.getCode(), ErrorCode.COMMON_001.getDefaultMessage()));
    }

    /** 정적/미매핑 경로 → COMMON_404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return build(ErrorCode.COMMON_404, ApiError.of(
                ErrorCode.COMMON_404.getCode(), ErrorCode.COMMON_404.getDefaultMessage()));
    }

    /** 비즈니스 예외 → 해당 ErrorCode의 code/status/message */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return build(code, ApiError.of(code.getCode(), e.getMessage()));
    }

    /** 미처리 예외 → COMMON_500 (스택트레이스는 로깅만, 응답엔 미노출) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(ErrorCode.COMMON_500, ApiError.of(
                ErrorCode.COMMON_500.getCode(), ErrorCode.COMMON_500.getDefaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, ApiError error) {
        return ResponseEntity.status(code.getHttpStatus()).body(ApiResponse.fail(error));
    }

    private List<FieldError> toFieldErrors(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
    }

    /** "signup.email" 같은 경로에서 마지막 노드(email)만 필드명으로 사용. */
    private String lastNode(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return idx >= 0 ? propertyPath.substring(idx + 1) : propertyPath;
    }
}
