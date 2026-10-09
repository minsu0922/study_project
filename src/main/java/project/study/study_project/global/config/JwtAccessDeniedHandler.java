package project.study.study_project.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ErrorResponseWriter;

import java.io.IOException;

/**
 * <b>인증은 됐지만 권한이 부족한</b> 요청일 때 호출된다 → 403 {@code AUTH_004}.
 * (예: USER가 ADMIN 전용 리소스에 접근)
 *
 * <p>{@link JwtAuthenticationEntryPoint}와 마찬가지로 필터 단계라 전역 예외처리가 못 잡으므로
 * 동일 응답 봉투(docs/04)를 직접 만들어 내려 준다.
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ErrorResponseWriter.write(response, objectMapper, ErrorCode.AUTH_004);
    }
}
