package project.study.study_project.global.exception;

/**
 * 비즈니스 규칙 위반 예외. {@link ErrorCode}를 담아 {@code GlobalExceptionHandler}에서
 * 해당 HTTP 상태/코드로 변환한다.
 * <p>기본 메시지 대신 상황별 메시지를 주고 싶으면 두 번째 인자로 전달한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
