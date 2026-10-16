package project.study.study_project.admin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 통합 검색 — 숫자는 번호로, 글자는 제목·지문·사용자 이름으로 찾는다. */
class AdminLookupControllerTest {

    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final GeneratedProblemDraftRepository drafts = mock(GeneratedProblemDraftRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AdminLookupController controller = new AdminLookupController(problems, drafts, users);

    @Test
    @DisplayName("#번호는 같은 번호의 문제와 코드 초안을 함께 돌려주고, 글자 검색은 하지 않는다")
    void findsBothById() {
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(7L);
        when(problem.getTitle()).thenReturn("문제 제목");
        GeneratedProblemDraft draft = mock(GeneratedProblemDraft.class);
        when(draft.getId()).thenReturn(7L);
        when(draft.getStatus()).thenReturn(DraftStatus.PENDING);
        when(draft.getCodeKind()).thenReturn(CodeProblem.Kind.CODE_FIX);
        when(draft.getTitle()).thenReturn("초안 제목");
        when(problems.findById(7L)).thenReturn(Optional.of(problem));
        when(drafts.findById(7L)).thenReturn(Optional.of(draft));

        AdminLookup r = controller.lookup(" #7 ").data();

        assertThat(r.problems()).containsExactly(new AdminLookup.Problem(7L, "문제 제목"));
        assertThat(r.drafts()).containsExactly(new AdminLookup.Draft(7L, "PENDING", true, "초안 제목"));
        assertThat(r.users()).isEmpty();
        verify(problems, never()).searchForAdmin(any(), any());
    }

    @Test
    @DisplayName("글자는 이스케이프한 포함 패턴으로 세 곳을 찾고, 제목 없는 문제는 지문 앞부분을 보인다")
    void searchesText() {
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(3L);
        when(problem.getQuestion()).thenReturn("TCP  3-way\nhandshake에서 SYN 다음에 오는 것은?");
        User user = mock(User.class);
        when(user.getId()).thenReturn(11L);
        when(user.getUsername()).thenReturn("tcpfan");
        when(user.getNickname()).thenReturn("티씨피");
        when(problems.searchForAdmin(eq("%tcp!_%"), any(Pageable.class))).thenReturn(List.of(problem));
        when(drafts.searchForAdmin(eq("%tcp!_%"), any(Pageable.class))).thenReturn(List.of());
        when(users.search(eq("%tcp!_%"), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        AdminLookup r = controller.lookup("tcp_").data();

        assertThat(r.problems()).containsExactly(
                new AdminLookup.Problem(3L, "TCP 3-way handshake에서 SYN 다음에 오는 것은?"));
        assertThat(r.drafts()).isEmpty();
        assertThat(r.users()).containsExactly(new AdminLookup.User(11L, "tcpfan", "티씨피"));
    }

    @Test
    @DisplayName("없는 번호는 모두 빈 목록이다")
    void findsNothing() {
        when(problems.findById(9L)).thenReturn(Optional.empty());
        when(drafts.findById(9L)).thenReturn(Optional.empty());

        AdminLookup r = controller.lookup("9").data();

        assertThat(r.problems()).isEmpty();
        assertThat(r.drafts()).isEmpty();
    }

    @Test
    @DisplayName("긴 지문은 40자에서 자르고 말줄임표를 붙인다")
    void labelTruncates() {
        assertThat(AdminLookupController.label(null, "가".repeat(45))).isEqualTo("가".repeat(40) + "…");
        assertThat(AdminLookupController.label("  ", "짧은 지문")).isEqualTo("짧은 지문");
    }
}
