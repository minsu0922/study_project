package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminProblemStatItem;
import project.study.study_project.admin.service.AdminProblemStatsService;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 통합 검색과 문제 관리 목록이 쓰는 검색 쿼리 — JPQL 조건은 실제 DB에서만 확인된다. MySQL이 필요하다. */
@SpringBootTest(properties = "llm.import.enabled=false")
@Transactional
class AdminSearchQueriesIntegrationTest {

    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private GeneratedProblemDraftRepository draftRepository;
    @Autowired
    private GeneratedDocumentDraftRepository documentDraftRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private AdminProblemStatsService statsService;

    @Test
    @DisplayName("문제 관리 목록의 검색어는 제목과 지문을 함께 보고, 대소문자를 가리지 않는다")
    void problemKeywordMatchesTitleOrQuestion() {
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        Problem byTitle = save(word + " 제목", "지문");
        Problem byQuestion = save(null, "지문 안의 " + word.toLowerCase());
        save("상관없는 제목", "상관없는 지문");

        Page<Problem> page = problemRepository.findForAdmin(null, null, null, null,
                SearchKeyword.likePattern(word.toUpperCase()), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Problem::getId)
                .containsExactly(byQuestion.getId(), byTitle.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("검색어가 번호면 문제·초안·정답률 목록 모두 id로도 찾는다")
    void numberKeywordMatchesId() {
        Problem problem = save("번호 검색 제목", "번호 검색 지문");
        String q = "#" + problem.getId();

        Page<Problem> page = problemRepository.findForAdmin(null, null, null, null,
                SearchKeyword.likePattern(q), SearchKeyword.idOf(q), PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(Problem::getId).containsExactly(problem.getId());

        assertThat(statsService.search(new AdminProblemStatsService.Filter(
                String.valueOf(problem.getId()), null, null, null, null, null, null, null), 0, 20).content())
                .extracting(AdminProblemStatItem::problemId).contains(problem.getId());

        GeneratedProblemDraft draft = saveDraft("번호 검색 초안");
        String draftQ = String.valueOf(draft.getId());
        Page<GeneratedProblemDraft> found = draftRepository.findForReview(List.of(DraftStatus.PENDING),
                null, null, null, null, null, SearchKeyword.likePattern(draftQ), SearchKeyword.idOf(draftQ),
                PageRequest.of(0, 20));
        assertThat(found.getContent()).extracting(GeneratedProblemDraft::getId).contains(draft.getId());
    }

    @Test
    @DisplayName("초안 검색은 승인된 초안을 뺀다 — 같은 제목의 문제가 따로 있다")
    void draftSearchSkipsApproved() {
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        GeneratedProblemDraft pending = saveDraft(word + " 대기");
        GeneratedProblemDraft approved = saveDraft(word + " 승인");
        approved.approve(save("승인으로 생긴 문제", "지문").getId());
        GeneratedProblemDraft rejected = saveDraft(word + " 거절");
        rejected.reject("테스트");
        draftRepository.flush();

        Page<GeneratedProblemDraft> found = draftRepository.findForReview(
                List.of(DraftStatus.PENDING, DraftStatus.REJECTED), null, null, null, null, null,
                SearchKeyword.likePattern(word), null, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(found.getContent()).extracting(GeneratedProblemDraft::getId)
                .containsExactly(rejected.getId(), pending.getId());
    }

    @Test
    @DisplayName("문서 초안 검색은 제목만 보고 상태 하나 안에서 찾는다")
    void documentDraftSearchByTitle() {
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        GeneratedDocumentDraft hit = documentDraftRepository.save(GeneratedDocumentDraft.pending(
                TestDomains.NETWORK, word + " 문서", "s-" + word, "본문", "[]", "test-model"));
        documentDraftRepository.save(GeneratedDocumentDraft.pending(
                TestDomains.NETWORK, "다른 제목", "t-" + word, "본문에만 " + word, "[]", "test-model"));

        Page<GeneratedDocumentDraft> found = documentDraftRepository.findForReview(
                DraftStatus.PENDING, SearchKeyword.likePattern(word), PageRequest.of(0, 5));

        assertThat(found.getContent()).extracting(GeneratedDocumentDraft::getId).containsExactly(hit.getId());
    }

    @Test
    @DisplayName("문서 관리 목록 — 상태는 공개만·내림만·둘 다로, 태그와 분야로도 거른다")
    void documentAdminFilters() {
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        String tagName = word.toLowerCase();
        Tag tag = tagRepository.save(Tag.of(tagName));
        Document shown = documentRepository.save(Document.create(
                TestDomains.NETWORK, word + " 공개", "a-" + tagName, "본문", null, Set.of(tag)));
        Document hidden = documentRepository.save(Document.create(
                TestDomains.SECURITY, word + " 내림", "b-" + tagName, "본문", null, Set.of()));
        hidden.hide();
        documentRepository.flush();
        PageRequest page = PageRequest.of(0, 20);

        assertThat(ids(documentService.getDocumentsForAdmin(null, null, word, null, page)))
                .containsExactlyInAnyOrder(shown.getId(), hidden.getId());
        assertThat(ids(documentService.getDocumentsForAdmin(null, null, word, true, page)))
                .containsExactly(hidden.getId());
        assertThat(ids(documentService.getDocumentsForAdmin(null, null, word, false, page)))
                .containsExactly(shown.getId());
        assertThat(ids(documentService.getDocumentsForAdmin(null, tagName, word, null, page)))
                .containsExactly(shown.getId());
        assertThat(ids(documentService.getDocumentsForAdmin(TestDomains.SECURITY, null, word, null, page)))
                .containsExactly(hidden.getId());
        assertThat(documentRepository.findUsedTagNames()).contains(tagName);
    }

    private static List<Long> ids(PageResponse<DocumentListItem> page) {
        return page.content().stream().map(DocumentListItem::id).toList();
    }

    private Problem save(String title, String question) {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX, title, question, "O", "해설", null));
    }

    private GeneratedProblemDraft saveDraft(String title) {
        return draftRepository.save(GeneratedProblemDraft.pending(
                TestDomains.NETWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                title, "검색 테스트용 지문 " + UUID.randomUUID(),
                null, "해설입니다.", "[{\"text\":\"보기1\",\"correct\":true}]", "test-model", null, null, null));
    }
}
