package project.study.study_project.llm.client;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.dto.LlmDocumentDraftResponse;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.llm.service.LlmDocumentService;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.llm.service.TopicQueueService;
import project.study.study_project.quiz.dto.ProblemListItem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.StudySummaryResponse;
import project.study.study_project.quiz.service.ProblemListService;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.review.dto.ReviewListItem;
import project.study.study_project.review.dto.ReviewTodayItem;
import project.study.study_project.review.service.ReviewService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분야 이름을 바꾸면 "화면·프롬프트마다 이름이 갈리던" 버그의 회귀 테스트(스펙 6절, Task 4).
 *
 * <h2>이 테스트가 지키는 것</h2>
 *
 * <p>관리자가 "분야 설정" 화면에서 분야 이름을 고치면, 그 이름을 보여주는 <b>모든 자리</b>가
 * 같은 순간에 새 이름으로 바뀌어야 한다. 고치기 전에는 필터 목록 하나만 바뀌고 나머지
 * 열두어 곳(프롬프트 포함)은 옛 이름을 그대로 썼다 — 특히 <b>모델에게 나가는 프롬프트</b>가
 * 옛 이름을 쓰는 것이 가장 조용하고 비싼 증상이었다(힌트는 새것인데 분야 이름만 옛것인
 * 프롬프트가 유료 API로 나간다).
 *
 * <h2>왜 스프링이 만든 빈을 그대로 쓰는가</h2>
 *
 * <p>{@code ClaudeProblemGenerator}·{@code ClaudeDocumentGenerator}를 여기서 {@code new}로
 * 만들면 이 버그를 다시 통과시킨다 — {@code new}는 항상 {@code (String)} 한 인자 생성자로 빠져
 * {@code DefaultDomains}의 고정 카탈로그를 쓰므로, 관리자가 화면에서 이름을 고쳐도 프롬프트가
 * 바뀔 수가 없다. 그래서 {@code @Autowired}로 <b>스프링이 실제로 조립한 빈</b>을 받는다 —
 * {@code ClaudeProblemGeneratorBeanHintTest}가 힌트에 대해 지키는 것과 같은 원칙을 이름에도 적용한다.
 *
 * <h2>패키지를 {@code llm.client}에 둔 이유</h2>
 *
 * <p>{@code buildPrompt}·{@code buildAdvancedPrompt}는 패키지 전용(package-private)이다 —
 * 프롬프트 조립은 생성기 내부 구현이라 공개 API로 열어 두지 않았다({@code ClaudeProblemGeneratorPromptTest}·
 * {@code ClaudeDocumentGeneratorTest}·{@code ClaudeProblemGeneratorBeanHintTest}가 전부 이 패키지에
 * 있는 이유와 같다). {@code llm} 패키지에 두면 이 메서드들을 호출할 수 없어 컴파일이 안 된다.
 *
 * <h2>테스트 데이터를 직접 만드는 이유</h2>
 *
 * <p>로컬 DB에 이미 있는 문제·문서·복습 항목에 기대면, 그 행이 우연히 마침 확인하려는
 * 분야가 아니거나 다른 테스트가 먼저 지워 버릴 수 있다. 각 테스트가 필요한 데이터를 직접
 * 만들고, 클래스 {@code @Transactional}이 끝난 뒤 전부 되돌린다.
 *
 * <h2>수정 1차 — 관리자 검수 화면 셋을 추가로 덮는다(코드 리뷰 지적)</h2>
 *
 * <p>1차 코드 리뷰에서 13곳 중 넷이 이 테스트 없이 고쳐졌다는 지적을 받았다 — 문제 초안·
 * 문서 초안 검수 화면(관리자가 매일 보는 화면)과 주제 대기열 화면이다. 그 넷은 나머지와
 * 위험이 다르다: 나머지는 학습자가 매일 보는 화면이라 이름이 틀리면 바로 눈에 띄지만,
 * 이 셋은 관리자만 보는 화면이라 되돌림 회귀가 한참 동안 아무에게도 안 보일 수 있다.
 * {@code DomainTitle.labeled}는 이 클래스에 없다 — 유일한 호출부(
 * {@code ExistingDocumentsExporter})가 다른 패키지의 패키지 전용 메서드를 거쳐야 해서
 * {@code DomainRenameDocumentTitleIntegrationTest}(같은 디렉터리, {@code llm.service} 패키지)로
 * 따로 뺐다 — 이유는 이 클래스가 {@code llm.client}에 있는 이유(위 문단)와 같다.
 */
@SpringBootTest
@Transactional
class DomainRenameIntegrationTest {

    @Autowired
    private DomainSettingService settings;
    @Autowired
    private ClaudeProblemGenerator problemGenerator;   // 스프링이 만든 빈 — new로 만들면 이 경로를 못 밟는다
    @Autowired
    private ClaudeDocumentGenerator documentGenerator;
    @Autowired
    private ProblemListService problemListService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private QuizService quizService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TopicQueueService topicQueueService;
    @Autowired
    private LlmDocumentService llmDocumentService;
    @Autowired
    private LlmProblemService llmProblemService;
    @Autowired
    private GeneratedProblemDraftRepository problemDraftRepository;
    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("이름을 바꾸면 모델 프롬프트의 '분야:' 줄이 새 이름이다")
    void promptUsesRenamedDomain() {
        rename(TestDomains.NETWORK, "네트워크 기초");

        String prompt = problemGenerator.buildPrompt(TestDomains.NETWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 1, List.of(), List.of(), null, null);

        assertThat(prompt).contains("분야: 네트워크 기초").doesNotContain("분야: 네트워크\n");
    }

    @Test
    @DisplayName("이름을 바꾸면 문서 생성기 입문편 프롬프트의 '분야:' 줄도 새 이름이다")
    void documentBeginnerPromptUsesRenamedDomain() {
        rename(TestDomains.DATABASE, "데이터베이스 기초");

        String prompt = documentGenerator.buildPrompt(TestDomains.DATABASE, null, List.of(), List.of());

        assertThat(prompt).contains("분야: 데이터베이스 기초").doesNotContain("분야: 데이터베이스\n");
    }

    @Test
    @DisplayName("이름을 바꾸면 문서 생성기 심화편 프롬프트의 '분야:' 줄도 새 이름이다")
    void documentAdvancedPromptUsesRenamedDomain() {
        rename(TestDomains.DATABASE, "데이터베이스 기초");
        // buildAdvancedPrompt는 입문편 결과(제목·slug·본문)를 함께 요구한다 — 프롬프트 조립
        // 그 자체가 검증 대상이 아니므로 최소한의 값만 채운다(ClaudeDocumentGeneratorTest와 같은 값 모양).
        GeneratedDocumentItem beginner = new GeneratedDocumentItem(
                "임시 제목", "temp-slug", "# 임시 제목\n\n## 무엇인가\n정의.", List.of("database"));

        String prompt = documentGenerator.buildAdvancedPrompt(TestDomains.DATABASE, beginner, List.of());

        assertThat(prompt).contains("분야: 데이터베이스 기초").doesNotContain("분야: 데이터베이스\n");
    }

    @Test
    @DisplayName("이름을 바꾸면 문제 목록의 항목 라벨과 사이드바 진척 이름도 새 이름이다")
    void problemListShowsRenamedDomain() {
        rename(TestDomains.OS, "운영체제 원리");
        Long userId = newUser("plist");
        AdminProblemDetail created = adminProblemService.create(new AdminProblemRequest(
                TestDomains.OS, Difficulty.BEGINNER, ProblemType.OX, "제목",
                "지문 " + UUID.randomUUID(), "O", "해설입니다.", null, null));

        PageResponse<ProblemListItem> list = problemListService.getList(
                userId, TestDomains.OS, null, null, false, null, null, PageRequest.of(0, 10));
        ProblemListItem item = list.content().stream()
                .filter(i -> i.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(item.domainLabel()).isEqualTo("운영체제 원리");

        StudySummaryResponse summary = problemListService.getSummary(userId);
        StudySummaryResponse.DomainProgress progress = summary.domains().stream()
                .filter(d -> d.domain().equals(TestDomains.OS)).findFirst().orElseThrow();
        assertThat(progress.label()).isEqualTo("운영체제 원리");
    }

    @Test
    @DisplayName("이름을 바꾸면 복습 현황 목록 항목의 분야 라벨도 새 이름이다")
    void reviewListShowsRenamedDomain() {
        rename(TestDomains.SECURITY, "보안 실무");
        Long userId = newUser("review-list");
        AdminProblemDetail created = adminProblemService.create(new AdminProblemRequest(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX, "제목",
                "지문 " + UUID.randomUUID(), "O", "해설입니다.", null, null));
        // 오답 제출 → 복습 사다리에 오른다(ReviewService.onSubmission이 QuizService.submit에서 불린다)
        quizService.submit(userId, new QuizSubmitRequest(created.id(), "X"));

        PageResponse<ReviewListItem> myReviews =
                reviewService.getMyReviews(userId, null, null, PageRequest.of(0, 10));

        assertThat(myReviews.content()).hasSize(1);
        assertThat(myReviews.content().get(0).domainLabel()).isEqualTo("보안 실무");
    }

    @Test
    @DisplayName("이름을 바꾸면 오늘의 복습 항목의 분야 라벨도 새 이름이다")
    void reviewTodayShowsRenamedDomain() {
        rename(TestDomains.SECURITY, "보안 실무");
        Long userId = newUser("review-today");
        AdminProblemDetail created = adminProblemService.create(new AdminProblemRequest(
                TestDomains.SECURITY, Difficulty.BEGINNER, ProblemType.OX, "제목",
                "지문 " + UUID.randomUUID(), "O", "해설입니다.", null, null));
        quizService.submit(userId, new QuizSubmitRequest(created.id(), "X"));
        timeTravelToDue(userId); // 오답 직후 예정일은 내일이라, 오늘의 복습에 태우려면 시간을 당겨야 한다

        PageResponse<ReviewTodayItem> today =
                reviewService.getTodayReviews(userId, PageRequest.of(0, 10));

        assertThat(today.content()).hasSize(1);
        assertThat(today.content().get(0).domainLabel()).isEqualTo("보안 실무");
    }

    @Test
    @DisplayName("이름을 바꾸면 문서 상세·목록의 분야 라벨도 새 이름이다")
    void documentShowsRenamedDomain() {
        rename(TestDomains.SYSTEM_DESIGN, "시스템설계 실전");
        String slug = "domain-rename-" + UUID.randomUUID().toString().substring(0, 8);
        adminDocumentService.create(new AdminDocumentRequest(
                TestDomains.SYSTEM_DESIGN, "제목", slug, "본문입니다.", null, List.of()));

        DocumentDetailResponse detail = documentService.getDocument(slug);
        assertThat(detail.domainLabel()).isEqualTo("시스템설계 실전");

        PageResponse<DocumentListItem> list = documentService.getDocuments(
                TestDomains.SYSTEM_DESIGN, null, null, PageRequest.of(0, 10));
        DocumentListItem item = list.content().stream()
                .filter(i -> i.slug().equals(slug)).findFirst().orElseThrow();
        assertThat(item.domainLabel()).isEqualTo("시스템설계 실전");
    }

    /**
     * 관리자 "주제 대기열" 화면 — {@link TopicQueueService#add}가 곧 그 화면의 등록 버튼이 부르는
     * 자리다. 응답을 별도 조회 없이 그 자리에서 돌려주므로, 등록 직후 화면에 뜨는 라벨이 바로
     * 이 값이다.
     */
    @Test
    @DisplayName("이름을 바꾸면 주제 대기열 항목의 분야 라벨도 새 이름이다")
    void topicQueueShowsRenamedDomain() {
        rename(TestDomains.CLOUD_INFRA, "클라우드 실무");

        TopicQueueItemResponse created = topicQueueService.add(
                new AdminTopicQueueRequest(TestDomains.CLOUD_INFRA, "컨테이너 오케스트레이션", null));

        assertThat(created.domainLabel()).isEqualTo("클라우드 실무");
    }

    /**
     * 관리자 "문서 검수" 화면 — {@link LlmDocumentService#getDrafts}가 그 화면의 목록 조회다.
     * {@link LlmDocumentService#saveDraft}로 PENDING 초안 하나를 만들어 실제 흡수 경로를 태운다.
     */
    @Test
    @DisplayName("이름을 바꾸면 문서 검수 화면 초안의 분야 라벨도 새 이름이다")
    void documentDraftReviewShowsRenamedDomain() {
        rename(TestDomains.SOFTWARE_ENGINEERING, "소프트웨어공학 실무");
        String slug = "domain-rename-draft-" + UUID.randomUUID().toString().substring(0, 8);
        llmDocumentService.saveDraft(TestDomains.SOFTWARE_ENGINEERING,
                new GeneratedDocumentItem("제목", slug, "# 제목\n\n## 무엇인가\n정의.", List.of()),
                "test-model");

        PageResponse<LlmDocumentDraftResponse> drafts =
                llmDocumentService.getDrafts(DraftStatus.PENDING, PageRequest.of(0, 200));
        LlmDocumentDraftResponse draft = drafts.content().stream()
                .filter(d -> d.slug().equals(slug)).findFirst().orElseThrow();

        assertThat(draft.domainName()).isEqualTo("소프트웨어공학 실무");
    }

    /**
     * 관리자 "문제 검수" 화면 — {@link LlmProblemService#getDrafts}가 그 화면의 목록 조회다.
     * 초안은 저장소에 바로 넣는다 — {@link LlmProblemService#saveDrafts}는 품질 규칙
     * ({@code ProblemItemRule})에 걸리면 조용히 건너뛰므로, 이름 표기만 확인하는 이 테스트에서는
     * 그 판정이 결과를 흔들 이유가 없다.
     */
    @Test
    @DisplayName("이름을 바꾸면 문제 검수 화면 초안의 분야 라벨도 새 이름이다")
    void problemDraftReviewShowsRenamedDomain() {
        rename(TestDomains.LANGUAGE_RUNTIME, "언어·런타임 실무");
        GeneratedProblemDraft draft = problemDraftRepository.save(GeneratedProblemDraft.pending(
                TestDomains.LANGUAGE_RUNTIME, Difficulty.BEGINNER, ProblemType.OX,
                "제목", "지문 " + UUID.randomUUID(), "O", "해설입니다.", null,
                "test-model", null, null, null));

        PageResponse<LlmDraftResponse> drafts = llmProblemService.getDrafts(
                DraftStatus.PENDING, TestDomains.LANGUAGE_RUNTIME, null, null, PageRequest.of(0, 200));
        LlmDraftResponse response = drafts.content().stream()
                .filter(d -> d.id().equals(draft.getId())).findFirst().orElseThrow();

        assertThat(response.domainLabel()).isEqualTo("언어·런타임 실무");
    }

    /* ── 도우미 ─────────────────────────────────────────────── */

    /** 켜짐·힌트는 그대로 두고 이름만 바꾼다 — 화면의 "저장" 버튼과 같은 경로. */
    private void rename(DomainCode code, String name) {
        DomainSetting s = settings.findAll().stream()
                .filter(r -> r.getDomain().equals(code)).findFirst().orElseThrow();
        settings.edit(code, new AdminDomainSettingRequest(s.isEnabled(), name, s.getHint()));
    }

    private Long newUser(String prefix) {
        User user = userRepository.save(User.builder()
                .username(prefix + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build());
        return user.getId();
    }

    /** 오답 직후의 예정일(내일)을 과거로 당긴다 — ReviewFlowIntegrationTest와 같은 시간 여행 방식. */
    private void timeTravelToDue(Long userId) {
        em.flush();
        em.createQuery("update ReviewItem r set r.nextReviewAt = :past where r.userId = :userId")
                .setParameter("past", LocalDateTime.now().minusMinutes(1))
                .setParameter("userId", userId)
                .executeUpdate();
        em.clear();
    }
}
