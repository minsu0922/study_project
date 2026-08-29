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
 * <h2>이 결정이 한동안 무효였다 (2026-08-29)</h2>
 *
 * <p>{@code sendError(404)}를 부르고 있었는데, 실제로 나가는 응답은 <b>401 JSON</b>이었다.
 * 서블릿의 ERROR 디스패치가 {@code /error}로 다시 들어가고 그 경로가 인증을 요구해서,
 * 이 필터가 정한 404를 인증 엔트리포인트가 덮어쓴 것이다. 감추려던 것을 도리어
 * "여기 뭔가 있다"고 알려 주는 상태였다.
 *
 * <p><b>테스트는 초록불이었다.</b> MockMvc는 ERROR 디스패치를 재현하지 않아서
 * {@code status().isNotFound()}가 그대로 통과했다. 그래서 이 회귀는 <b>진짜 컨테이너를
 * 띄우는 테스트</b>로만 잡을 수 있다({@code AdminGateRealServerTest}).
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

        // sendError를 쓰지 않는다(2026-08-29에 고침). sendError는 서블릿 컨테이너의
        // <b>ERROR 디스패치</b>를 일으켜 요청이 /error로 다시 들어가는데, 그 경로는
        // SecurityConfig의 permitAll 목록에 없어 anyRequest().authenticated()에 걸린다.
        // 결과적으로 여기서 정한 404가 엔트리포인트의 <b>401 JSON으로 덮여</b> 나갔다 —
        // "존재를 알려 주지 않는다"는 이 클래스의 결정이 통째로 무효였고, 주소창에
        // /admin을 친 사람은 {"code":"AUTH_003"} 원문을 봤다.
        //
        // 상태 코드를 직접 쓰면 디스패치 자체가 없다. 본문도 비운다 — 우리가 문구를 넣으면
        // 그 문구가 곧 "여기 뭔가 있다"는 단서가 된다. 비워 두면 브라우저가 자기 기본 404
        // 화면을 띄우고, 그건 없는 주소를 쳤을 때와 <b>정확히 같은 모습</b>이다.
        //
        // flushBuffer로 응답을 확정한다. 스택이 되감기면서 앞선 필터가 이 응답에 손대는 일을
        // 막는다 — 이 사고가 정확히 "누군가 나중에 덮어쓴" 것이었다.
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentLength(0);
        response.flushBuffer();
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
