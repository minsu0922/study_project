package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.ProblemListItem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.service.ProblemListService;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 문제·문서 비공개 전환(V30) — 내린 것이 학습자 경로에서 빠지고, 올리면 돌아오는지. */
@SpringBootTest
@Transactional
class ContentHiddenIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private ProblemListService problemListService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private DocumentService documentService;

    @Test
    @DisplayName("내린 문제는 목록·단건·채점에서 빠진다")
    void hiddenProblemDisappearsFromLearnerPaths() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);

        adminProblemService.setHidden(problem.getId(), true);

        assertThat(listedIds(user)).doesNotContain(problem.getId());
        assertThatThrownBy(() -> quizService.getOne(problem.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_001));
        assertThatThrownBy(() -> quizService.submit(user.getId(),
                new QuizSubmitRequest(problem.getId(), "O")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> quizService.check(problem.getId(), "O"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다시 올리면 목록에 돌아온다")
    void shownProblemComesBack() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        adminProblemService.setHidden(problem.getId(), true);

        adminProblemService.setHidden(problem.getId(), false);

        assertThat(listedIds(user)).contains(problem.getId());
        assertThat(quizService.getOne(problem.getId()).problems()).hasSize(1);
    }

    @Test
    @DisplayName("제출 이력이 있어 삭제가 막힌 문제도 내릴 수 있다")
    void problemWithSubmissionsCanBeHidden() {
        Problem problem = fixtures.problem();
        fixtures.solver(problem);
        assertThatThrownBy(() -> adminProblemService.delete(problem.getId()))
                .isInstanceOf(BusinessException.class);

        adminProblemService.setHidden(problem.getId(), true);

        assertThat(adminProblemService.getProblem(problem.getId()).hidden()).isTrue();
    }

    @Test
    @DisplayName("내린 문서는 공개 목록·단건에서 빠지고 관리 목록에는 남는다")
    void hiddenDocumentStaysOnlyInAdminList() {
        String slug = "hidden-test-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = adminDocumentService.create(new AdminDocumentRequest(
                TestDomains.DATABASE, "비공개 테스트 문서", slug, "본문", null, List.of())).id();

        adminDocumentService.setHidden(id, true);

        assertThatThrownBy(() -> documentService.getDocument(slug))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOC_001));
        assertThat(documentService.getDocuments(null, null, "비공개 테스트 문서", PageRequest.of(0, 50))
                .content()).extracting(DocumentListItem::slug).doesNotContain(slug);
        assertThat(adminDocumentService.list(PageRequest.of(0, 200)).content())
                .filteredOn(d -> d.slug().equals(slug))
                .singleElement()
                .satisfies(d -> assertThat(d.hidden()).isTrue());

        adminDocumentService.setHidden(id, false);

        assertThat(documentService.getDocument(slug).slug()).isEqualTo(slug);
    }

    private List<Long> listedIds(User user) {
        return problemListService.getList(user.getId(), TestDomains.NETWORK, null, null, false,
                        "TCP 연결은 3번의 패킷", null, PageRequest.of(0, 200))
                .content().stream().map(ProblemListItem::id).toList();
    }
}
