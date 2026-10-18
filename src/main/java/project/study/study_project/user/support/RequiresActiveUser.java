package project.study.study_project.user.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 정지된 사용자는 이 메서드를 부를 수 없다. 글·댓글·신고·추천 같은 쓰기 경로에 붙인다.
 *
 * <p>메서드에 {@code Long userId} 파라미터가 있어야 한다. {@link SuspensionAspect}가 그 값으로 사용자를 찾는다.
 * 메서드 본문보다 먼저 검사하므로, 정지된 사람은 없는 글을 고쳐도 404가 아니라 DISCUSSION_013을 받는다.
 * 같은 클래스 안에서 부르면(자기 호출) 프록시를 거치지 않아 검사가 빠진다. 컨트롤러가 부르는 public 메서드에만 붙인다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresActiveUser {
}
