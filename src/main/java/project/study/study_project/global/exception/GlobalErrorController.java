package project.study.study_project.global.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiError;
import project.study.study_project.global.response.ApiResponse;

/**
 * {@code /error} — 전역 예외처리가 못 받는 오류의 마지막 출구.
 *
 * <p>여기로 오는 길은 둘이다. 서블릿 컨테이너의 ERROR 디스패치(톰캣이 직접 거절한 요청,
 * 누군가 부른 {@code sendError})와, 주소창에 {@code /error}를 직접 친 경우다.
 * 이 클래스가 없으면 스프링 기본 처리기가 Whitelabel 화면이나 제 형식의 JSON을 내보내
 * 공통 봉투 밖의 응답이 생긴다.
 *
 * <p>{@link ErrorController}를 구현한 빈이 있으면 스프링 기본 처리기는 등록되지 않는다.
 */
@RestController
public class GlobalErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<?> handle(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object uriAttr = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // 속성이 없으면 디스패치가 아니라 직접 연 것이다. 보여 줄 오류가 없으니 없는 주소로 답한다.
        HttpStatus status = statusAttr instanceof Integer code && HttpStatus.resolve(code) != null
                ? HttpStatus.valueOf(code) : HttpStatus.NOT_FOUND;
        String path = uriAttr instanceof String uri ? uri : request.getRequestURI();

        if (status == HttpStatus.NOT_FOUND && GlobalExceptionHandler.isPageRequest(request, path)) {
            return GlobalExceptionHandler.notFoundPage();
        }
        ErrorCode code = toErrorCode(status);
        // 상태는 컨테이너가 정한 것을 그대로 둔다. 코드만 가장 가까운 것을 고른다.
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(ApiError.of(code.getCode(), code.getDefaultMessage())));
    }

    private ErrorCode toErrorCode(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorCode.COMMON_404;
            case METHOD_NOT_ALLOWED -> ErrorCode.COMMON_405;
            case PAYLOAD_TOO_LARGE -> ErrorCode.COMMON_413;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.COMMON_415;
            default -> status.is4xxClientError() ? ErrorCode.COMMON_001 : ErrorCode.COMMON_500;
        };
    }
}
