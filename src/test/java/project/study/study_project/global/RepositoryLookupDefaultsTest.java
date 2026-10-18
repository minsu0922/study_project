package project.study.study_project.global;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 저장소 default 조회 메서드가 서비스에 있던 private 메서드와 같은 오류 코드를 내는지 본다. */
class RepositoryLookupDefaultsTest {

    @Test
    @DisplayName("getAuthenticated: 있으면 돌려주고, 없으면 AUTH_003")
    void authenticatedUser() {
        UserRepository repo = mock(UserRepository.class, CALLS_REAL_METHODS);
        User user = mock(User.class);
        when(repo.findById(1L)).thenReturn(Optional.of(user));
        when(repo.findById(2L)).thenReturn(Optional.empty());

        assertThat(repo.getAuthenticated(1L)).isSameAs(user);
        assertCode(() -> repo.getAuthenticated(2L), ErrorCode.AUTH_003);
    }

    @Test
    @DisplayName("getAlive: 지운 글과 없는 글은 둘 다 DISCUSSION_011")
    void alivePost() {
        PostRepository repo = mock(PostRepository.class, CALLS_REAL_METHODS);
        Post alive = mock(Post.class);
        Post deleted = mock(Post.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(alive));
        when(repo.findById(2L)).thenReturn(Optional.of(deleted));
        when(repo.findById(3L)).thenReturn(Optional.empty());

        assertThat(repo.getAlive(1L)).isSameAs(alive);
        assertCode(() -> repo.getAlive(2L), ErrorCode.DISCUSSION_011);
        assertCode(() -> repo.getAlive(3L), ErrorCode.DISCUSSION_011);
    }

    @Test
    @DisplayName("getVisible: 숨긴 문제와 없는 문제는 둘 다 QUIZ_001")
    void visibleProblem() {
        ProblemRepository repo = mock(ProblemRepository.class, CALLS_REAL_METHODS);
        Problem visible = mock(Problem.class);
        Problem hidden = mock(Problem.class);
        when(hidden.isHidden()).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(visible));
        when(repo.findById(2L)).thenReturn(Optional.of(hidden));
        when(repo.findById(3L)).thenReturn(Optional.empty());

        assertThat(repo.getVisible(1L)).isSameAs(visible);
        assertCode(() -> repo.getVisible(2L), ErrorCode.QUIZ_001);
        assertCode(() -> repo.getVisible(3L), ErrorCode.QUIZ_001);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }
}
