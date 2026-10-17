package project.study.study_project.admin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.admin.dto.AdminUserItem;
import project.study.study_project.admin.service.AdminUserService;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.service.AdminDocumentService;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.AdminProblemDetail;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.AdminProblemService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 통합 검색 — 숫자는 번호로, 글자는 각 목록 화면과 같은 서비스로 찾는다. */
class AdminLookupControllerTest {

    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final GeneratedProblemDraftRepository drafts = mock(GeneratedProblemDraftRepository.class);
    private final AdminProblemService problemService = mock(AdminProblemService.class);
    private final AdminDocumentService documentService = mock(AdminDocumentService.class);
    private final AdminUserService userService = mock(AdminUserService.class);
    private final AdminLookupController controller =
            new AdminLookupController(problems, drafts, problemService, documentService, userService);

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

        assertThat(r.problems().items()).containsExactly(new AdminLookup.Problem(7L, "문제 제목"));
        assertThat(r.drafts().items()).containsExactly(new AdminLookup.Draft(7L, "PENDING", true, "초안 제목"));
        assertThat(r.documents().items()).isEmpty();
        assertThat(r.users().items()).isEmpty();
        verify(problemService, never()).getProblems(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("글자는 목록 화면의 서비스에 검색어를 그대로 넘기고, 초안은 승인 아닌 것만 이스케이프한 패턴으로 찾는다")
    void searchesText() {
        AdminProblemDetail problem = mock(AdminProblemDetail.class);
        when(problem.id()).thenReturn(3L);
        when(problem.question()).thenReturn("TCP  3-way\nhandshake에서 SYN 다음에 오는 것은?");
        when(problemService.getProblems(isNull(), isNull(), isNull(), isNull(), eq("tcp_"), any(Pageable.class)))
                .thenReturn(page(List.of(problem), 12));
        when(drafts.findForReview(eq(List.of(DraftStatus.PENDING, DraftStatus.REJECTED)), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq("%tcp!_%"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        DocumentListItem doc = mock(DocumentListItem.class);
        when(doc.id()).thenReturn(21L);
        when(doc.title()).thenReturn("TCP 연결 종료");
        when(doc.hidden()).thenReturn(true);
        when(documentService.list(eq("tcp_"), any(Pageable.class))).thenReturn(page(List.of(doc), 1));
        AdminUserItem user = mock(AdminUserItem.class);
        when(user.id()).thenReturn(11L);
        when(user.username()).thenReturn("tcpfan");
        when(user.nickname()).thenReturn("티씨피");
        when(userService.search(eq("tcp_"), eq(false), any(Pageable.class))).thenReturn(page(List.of(user), 1));

        AdminLookup r = controller.lookup("tcp_").data();

        assertThat(r.problems().items()).containsExactly(
                new AdminLookup.Problem(3L, "TCP 3-way handshake에서 SYN 다음에 오는 것은?"));
        assertThat(r.problems().total()).isEqualTo(12);
        assertThat(r.drafts().items()).isEmpty();
        assertThat(r.documents().items()).containsExactly(new AdminLookup.Document(21L, "TCP 연결 종료", true));
        assertThat(r.users().items()).containsExactly(new AdminLookup.User(11L, "tcpfan", "티씨피"));
    }

    @Test
    @DisplayName("없는 번호는 모두 빈 목록이다")
    void findsNothing() {
        when(problems.findById(9L)).thenReturn(Optional.empty());
        when(drafts.findById(9L)).thenReturn(Optional.empty());

        AdminLookup r = controller.lookup("9").data();

        assertThat(r.problems().items()).isEmpty();
        assertThat(r.drafts().items()).isEmpty();
    }

    @Test
    @DisplayName("긴 지문은 40자에서 자르고 말줄임표를 붙인다")
    void labelTruncates() {
        assertThat(AdminLookupController.label(null, "가".repeat(45))).isEqualTo("가".repeat(40) + "…");
        assertThat(AdminLookupController.label("  ", "짧은 지문")).isEqualTo("짧은 지문");
    }

    private static <T> PageResponse<T> page(List<T> items, long total) {
        return PageResponse.from(new PageImpl<>(items, PageRequest.of(0, 5), total));
    }
}
