package project.study.study_project.user.support;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import project.study.study_project.user.repository.UserRepository;

import java.lang.reflect.Method;

/** {@link RequiresActiveUser}가 붙은 메서드 앞에서 정지 여부를 본다. 거절 문구는 {@link SuspensionGuard}가 만든다. */
@Aspect
@Component
public class SuspensionAspect {

    static final String USER_ID_PARAM = "userId";

    private final UserRepository userRepository;

    public SuspensionAspect(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Before("@annotation(project.study.study_project.user.support.RequiresActiveUser)")
    public void rejectSuspended(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        int index = userIdIndex(signature.getMethod(), signature.getParameterNames());
        Long userId = (Long) joinPoint.getArgs()[index];
        SuspensionGuard.requireNotSuspended(userRepository.getAuthenticated(userId));
    }

    /** 파라미터 이름은 컴파일 옵션 -parameters로 남는다(Spring Boot Gradle 플러그인 기본값). */
    static int userIdIndex(Method method, String[] names) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < names.length; i++) {
            if (USER_ID_PARAM.equals(names[i]) && types[i] == Long.class) {
                return i;
            }
        }
        throw new IllegalStateException("@RequiresActiveUser 메서드에 Long userId 파라미터가 없습니다: " + method);
    }
}
