package project.study.study_project.global.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import project.study.study_project.global.exception.ErrorCode;

import java.io.IOException;

/**
 * 필터 단계에서 공통 응답 봉투(docs/04)를 응답에 직접 쓴다.
 *
 * <p>필터는 컨트롤러보다 앞이라 {@code @RestControllerAdvice}가 못 잡는다. 그래서 인증 실패·
 * 권한 부족·요청 제한은 JSON을 손수 써야 하는데, 그 코드를 각자 두면 봉투 모양을 바꿀 때
 * 한 곳이 빠진다.
 */
public final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    /** 헤더(Retry-After 등)는 부르기 전에 붙인다 — 본문을 쓰면 응답이 확정될 수 있다. */
    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(ApiError.of(code.getCode(), code.getDefaultMessage()));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
