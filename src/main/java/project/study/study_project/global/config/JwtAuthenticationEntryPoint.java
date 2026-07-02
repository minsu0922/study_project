package project.study.study_project.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ApiError;
import project.study.study_project.global.response.ApiResponse;

import java.io.IOException;

/**
 * <b>미인증</b> 요청(토큰 없음/만료/위조)이 보호 리소스에 접근했을 때 호출된다 → 401 {@code AUTH_003}.
 *
 * <p>왜 여기서 직접 JSON을 쓰나: 이 지점은 컨트롤러 이전의 <b>필터 단계</b>라
 * {@code @RestControllerAdvice}(전역 예외처리)가 잡지 못한다. 그래서 나머지 API와 동일한
 * 응답 봉투(docs/04)를 여기서 손수 만들어 내려 준다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorCode code = ErrorCode.AUTH_003;
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(ApiError.of(code.getCode(), code.getDefaultMessage()));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
