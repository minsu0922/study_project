package project.study.study_project.auth.gate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 관리 화면(정적 파일) 출입 검사 — 출입증 쿠키가 없으면 <b>404</b>.
 *
 * <h2>무엇을 막는가</h2>
 *
 * <p>지금까지 {@code admin.html}은 누구나 내려받을 수 있었다. API는 {@code hasRole(ADMIN)}이
 * 막고 있었으니 데이터가 새지는 않았지만, <b>관리 기능이 어떻게 생겼고 어떤 API를 쓰는지</b>가
 * 그대로 드러났다. 그건 공격자에게 지도를 주는 것과 같다 — 어디를 두드려야 할지 알려 준다.
 *
 * <h2>왜 401이 아니라 404인가</h2>
 *
 * <p>401은 "여기 뭔가 있는데 너는 못 본다"는 뜻이라 <b>존재를 알려 준다</b>. 404는 아무것도
 * 알려 주지 않는다. 관리 화면은 애초에 나 혼자 쓰는 것이라, 남에게는 없는 편이 낫다.
 *
 * <p>단점도 있다: 쿠키가 만료된 관리자도 404를 본다. 로그인하면 쿠키가 새로 붙으므로
 * "다시 로그인"이 답인데, 그 사실을 화면이 알려 줄 수 없다(화면 자체가 안 열리므로).
 * 이건 감추기를 택한 대가다 — 사용자가 나 혼자라 감수할 만하다고 판단했다.
 *
 * <h2>API 경로는 건드리지 않는다</h2>
 *
 * <p>{@code /api/admin/**}은 이 필터의 관심사가 아니다({@code startsWith("/admin")}에 걸리지
 * 않는다). 그쪽은 헤더의 JWT로 {@code SecurityConfig}가 막는다. 한 요청을 두 장치가 각자
 * 막으면 어느 쪽이 거절했는지 알기 어려워지므로 <b>경계를 겹치지 않게</b> 둔다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminGateFilter extends OncePerRequestFilter {

    private final AdminGateCookie gateCookie;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (gateCookie.isValid(request)) {
            chain.doFilter(request, response);
            return;
        }

        // 누가 두드렸는지는 남긴다 — 감추는 것과 모르는 것은 다르다.
        // 경로만 적고 쿠키 값은 절대 남기지 않는다(로그에 토큰이 남으면 그 자체가 유출이다).
        log.info("관리 화면 접근 거부(출입증 없음): {}", request.getRequestURI());
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * 관리 화면 경로에만 적용한다.
     *
     * <p>{@code /admin}(끝에 슬래시 없음)도 포함해야 한다 — 그 주소는 {@code /admin/}으로
     * 넘어가는데, 넘어가기 전에 걸러야 "리다이렉트가 되는 것"만으로 존재가 드러나지 않는다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/admin") || path.startsWith("/admin/"));
    }
}
