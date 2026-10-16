package project.study.study_project.admin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 번호로 이동 — 문제와 초안은 번호 체계가 따로라 둘 다 찾아 돌려준다. */
class AdminLookupControllerTest {

    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final GeneratedProblemDraftRepository drafts = mock(GeneratedProblemDraftRepository.class);
    private final AdminLookupController controller = new AdminLookupController(problems, drafts);

    @Test
    @DisplayName("같은 번호의 문제와 코드 초안을 함께 돌려주고, 초안은 상태와 코드 여부를 싣는다")
    void findsBoth() {
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

        AdminLookup r = controller.lookup(7L).data();

        assertThat(r.problem()).isEqualTo(new AdminLookup.Problem(7L, "문제 제목"));
        assertThat(r.draft()).isEqualTo(new AdminLookup.Draft(7L, "PENDING", true, "초안 제목"));
    }

    @Test
    @DisplayName("없는 번호는 둘 다 null이다")
    void findsNothing() {
        when(problems.findById(9L)).thenReturn(Optional.empty());
        when(drafts.findById(9L)).thenReturn(Optional.empty());

        AdminLookup r = controller.lookup(9L).data();

        assertThat(r.problem()).isNull();
        assertThat(r.draft()).isNull();
    }
}
