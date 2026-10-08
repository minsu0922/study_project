package project.study.study_project.bookmark;

import jakarta.persistence.EntityManager;
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
import project.study.study_project.bookmark.dto.BookmarkItem;
import project.study.study_project.bookmark.dto.BookmarkState;
import project.study.study_project.bookmark.dto.BookmarkTarget;
import project.study.study_project.bookmark.service.BookmarkService;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 북마크(V33) — 담기·빼기가 몇 번을 불러도 같고, 남의 것과 섞이지 않는지. */
@SpringBootTest
@Transactional
class BookmarkIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private BookmarkService bookmarkService;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("문제를 담으면 내 목록에 뜨고, 두 번 담아도 한 건이다")
    void addProblemIsIdempotent() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);

        bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, problem.getId());
        bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, problem.getId());

        List<BookmarkItem> items = problemsOf(user);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).targetId()).isEqualTo(problem.getId());
        assertThat(items.get(0).title()).isEqualTo("TCP 3-way handshake");
    }

    @Test
    @DisplayName("빼면 목록에서 사라지고, 담지 않은 것을 빼도 오류가 아니다")
    void removeIsIdempotent() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, problem.getId());

        bookmarkService.remove(user.getId(), BookmarkTarget.PROBLEM, problem.getId());
        bookmarkService.remove(user.getId(), BookmarkTarget.PROBLEM, problem.getId());

        em.clear();
        assertThat(problemsOf(user)).isEmpty();
    }

    @Test
    @DisplayName("남이 담은 것은 내 목록과 상태에 보이지 않는다")
    void bookmarksArePerUser() {
        Problem problem = fixtures.problem();
        User mine = fixtures.user(Role.USER);
        User other = fixtures.user(Role.USER);
        bookmarkService.add(other.getId(), BookmarkTarget.PROBLEM, problem.getId());

        assertThat(problemsOf(mine)).isEmpty();
        BookmarkState state = bookmarkService.state(mine.getId(), List.of(problem.getId()), List.of());
        assertThat(state.problemIds()).isEmpty();
        assertThat(bookmarkService.state(other.getId(), List.of(problem.getId()), List.of()).problemIds())
                .containsExactly(problem.getId());
    }

    @Test
    @DisplayName("문서도 담을 수 있고 목록이 slug를 준다")
    void addDocument() {
        String slug = "bookmark-test-" + UUID.randomUUID().toString().substring(0, 8);
        Long docId = adminDocumentService.create(new AdminDocumentRequest(
                TestDomains.DATABASE, "북마크 테스트 문서", slug, "본문", null, List.of())).id();
        User user = fixtures.user(Role.USER);

        bookmarkService.add(user.getId(), BookmarkTarget.DOCUMENT, docId);

        List<BookmarkItem> items = bookmarkService.list(user.getId(), BookmarkTarget.DOCUMENT,
                PageRequest.of(0, 20)).content();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).slug()).isEqualTo(slug);
        assertThat(problemsOf(user)).isEmpty();
    }

    @Test
    @DisplayName("내려 둔 문제는 담을 수 없고, 담아 둔 문제가 내려가면 목록에서 빠진다")
    void hiddenProblemIsExcluded() {
        Problem hidden = fixtures.problem();
        Problem later = fixtures.problem();
        User user = fixtures.user(Role.USER);
        adminProblemService.setHidden(hidden.getId(), true);

        assertThatThrownBy(() -> bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, hidden.getId()))
                .isInstanceOf(BusinessException.class);

        bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, later.getId());
        adminProblemService.setHidden(later.getId(), true);
        em.flush();
        em.clear();

        assertThat(problemsOf(user)).isEmpty();
    }

    @Test
    @DisplayName("없는 문제는 담을 수 없다")
    void unknownTargetIsRejected() {
        User user = fixtures.user(Role.USER);

        assertThatThrownBy(() -> bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, -1L))
                .isInstanceOf(BusinessException.class);
    }

    private List<BookmarkItem> problemsOf(User user) {
        return bookmarkService.list(user.getId(), BookmarkTarget.PROBLEM, PageRequest.of(0, 20)).content();
    }
}
