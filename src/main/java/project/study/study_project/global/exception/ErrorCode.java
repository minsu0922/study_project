package project.study.study_project.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 체계 — 문서 04-response-format의 에러표 기준.
 * <p>형식: {@code {도메인}_{식별자}}. 클라이언트는 {@link #getCode()}로 분기,
 * {@link #getDefaultMessage()}는 사용자 표시용(한글).
 * 새 에러는 문서 04 표에 추가한 뒤 여기에 상수를 더한다.
 */
public enum ErrorCode {

    // 공통
    COMMON_001("COMMON_001", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다."),
    COMMON_404("COMMON_404", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    COMMON_500("COMMON_500", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 인증/인가
    AUTH_001("AUTH_001", HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    AUTH_002("AUTH_002", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_003("AUTH_003", HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다."),
    AUTH_004("AUTH_004", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 퀴즈
    QUIZ_001("QUIZ_001", HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다."),
    QUIZ_002("QUIZ_002", HttpStatus.BAD_REQUEST, "지원하지 않는 문제 타입입니다."),
    // 관리자 문제 관리(콘텐츠 등록 기능)에서 사용
    QUIZ_003("QUIZ_003", HttpStatus.CONFLICT, "제출 이력이 있는 문제는 삭제할 수 없습니다."),
    QUIZ_004("QUIZ_004", HttpStatus.BAD_REQUEST, "문제 유형별 입력 규칙에 맞지 않습니다."),

    // 문서
    DOC_001("DOC_001", HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),
    DOC_002("DOC_002", HttpStatus.CONFLICT, "이미 사용 중인 slug입니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
