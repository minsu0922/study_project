package project.study.study_project.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import project.study.study_project.global.response.ApiError;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.FieldError;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 전역 예외 → 공통 응답 envelope 변환 — 문서 04-response-format 기준.
 * <p>Security 인증/인가 실패(AUTH_003/004)는 SecurityConfig의
 * AuthenticationEntryPoint/AccessDeniedHandler에서 동일 envelope로 변환한다(Step 5).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Resource NOT_FOUND_PAGE = new ClassPathResource("static/404.html");
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

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

    /** 필수 쿼리 파라미터 누락 → VALIDATION_ERROR. 어느 칸이 빠졌는지 fieldErrors로 알려 준다. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        FieldError fieldError = new FieldError(e.getParameterName(), "필수 값입니다.");
        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                List.of(fieldError));
        return build(ErrorCode.VALIDATION_ERROR, error);
    }

    /** 그 주소가 받지 않는 메서드(GET 전용에 POST 등) → COMMON_405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.COMMON_405, ApiError.of(
                ErrorCode.COMMON_405.getCode(), ErrorCode.COMMON_405.getDefaultMessage()));
    }

    /** 받지 않는 Content-Type(JSON 자리에 text/plain 등) → COMMON_415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(ErrorCode.COMMON_415, ApiError.of(
                ErrorCode.COMMON_415.getCode(), ErrorCode.COMMON_415.getDefaultMessage()));
    }

    /** 업로드가 application.yml의 multipart 상한을 넘음 → COMMON_413 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return build(ErrorCode.COMMON_413, ApiError.of(
                ErrorCode.COMMON_413.getCode(), ErrorCode.COMMON_413.getDefaultMessage()));
    }

    /**
     * 정적/미매핑 경로 → COMMON_404. 단, 사람이 브라우저로 연 화면 주소면 JSON 대신 안내 화면을 준다.
     * <p>리다이렉트하지 않고 그 주소 그대로 404로 답한다 — 302로 보내면 주소창에서 잘못 친
     * 주소가 사라지고, 검색 로봇은 없는 주소를 있는 것으로 읽는다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        if (isPageRequest(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML_UTF8)
                    .body(NOT_FOUND_PAGE);
        }
        return build(ErrorCode.COMMON_404, ApiError.of(
                ErrorCode.COMMON_404.getCode(), ErrorCode.COMMON_404.getDefaultMessage()));
    }

    /**
     * 주소창에 친 요청인가. fetch는 Accept에 text/html을 싣지 않아 여기 걸리지 않는다.
     * /api/ 아래는 Accept와 상관없이 JSON이다 — 봉투 계약을 헤더 하나로 깨지 않는다.
     */
    private boolean isPageRequest(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return "GET".equals(request.getMethod())
                && !request.getRequestURI().startsWith("/api/")
                && accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
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
        // Content-Type을 못 박는다. 비워 두면 Accept가 text/html뿐인 요청에는 JSON을 쓸 수 없다고
        // 판단해 본문 없는 응답이 나간다.
        return ResponseEntity.status(code.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(error));
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
