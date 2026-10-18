package project.study.study_project.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiresActiveUserTest {

    @Test
    @DisplayName("애노테이션을 붙인 메서드는 모두 public이고 Long userId를 받는다. 어기면 애스펙트가 요청 때 터진다")
    void everyAnnotatedMethodHasUserId() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        var names = new DefaultParameterNameDiscoverer();
        List<String> found = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("project.study.study_project")) {
            for (Method m : Class.forName(bean.getBeanClassName()).getDeclaredMethods()) {
                if (!m.isAnnotationPresent(RequiresActiveUser.class)) {
                    continue;
                }
                assertThat(Modifier.isPublic(m.getModifiers())).as(m.toString()).isTrue();
                SuspensionAspect.userIdIndex(m, names.getParameterNames(m));
                found.add(m.getDeclaringClass().getSimpleName() + "." + m.getName());
            }
        }
        assertThat(found).contains(
                "PostService.write", "PostService.edit", "CommentService.write", "CommentService.edit",
                "CommentReportService.report", "CommentReportService.reportPost", "PostLikeService.like");
    }

    @Test
    @DisplayName("userId가 없으면 메서드 이름을 담아 실패한다")
    void missingUserId() throws Exception {
        Method m = Sample.class.getDeclaredMethod("noUser", Long.class);
        assertThatThrownBy(() -> SuspensionAspect.userIdIndex(m, new String[]{"postId"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("noUser");
    }

    static class Sample {
        void noUser(Long postId) {
        }
    }
}
