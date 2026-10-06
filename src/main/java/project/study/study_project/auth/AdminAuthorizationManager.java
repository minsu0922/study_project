package project.study.study_project.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.repository.UserRepository;

import java.util.function.Supplier;

/**
 * 관리자 경로의 문지기 — 토큰에 적힌 권한에 더해 <b>지금 DB의 권한</b>을 본다.
 *
 * <p>토큰은 발급한 순간의 권한을 만료될 때까지(1시간) 들고 다닌다. 토큰만 보면 관리자에서 내린
 * 사람이나 지운 계정이 그동안 관리 API를 계속 쓸 수 있다. 그래서 관리자 경로에서만 한 번 더 묻는다.
 * 요청마다 조회가 하나 늘지만 관리 API는 부르는 사람이 몇 명뿐이다. 모든 경로에 걸지 않는 이유는
 * 반대다 — 학습 API는 요청이 많고, 거기서는 권한이 바뀌어도 잃을 것이 없다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final String ADMIN_AUTHORITY = "ROLE_" + Role.ADMIN.name();

    private final UserRepository userRepository;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        // 토큰부터 본다. 관리자 토큰이 아니면 DB까지 갈 일이 없다.
        boolean tokenSaysAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
        if (!tokenSaysAdmin || !(auth.getPrincipal() instanceof Long userId)) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(userRepository.findById(userId)
                .map(user -> user.getRole() == Role.ADMIN)
                .orElse(false));
    }
}
