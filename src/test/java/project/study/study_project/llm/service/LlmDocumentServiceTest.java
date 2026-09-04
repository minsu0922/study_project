package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.dto.LlmDocumentDraftResponse;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 문서 초안 검수(승인·거절) 테스트 — docs/15.
 *
 * <p>가장 중요한 검증은 <b>"승인이 AdminDocumentService.create를 거친다"</b>는 것이다.
 * 이 서비스가 문서를 직접 저장하기 시작하면 slug 중복 검사·태그 find-or-create·캐시 무효화가
 * 통째로 빠지고, 그 사실은 한참 뒤 "AI 문서만 태그가 없다" 같은 증상으로 드러난다.
 * 그래서 호출 여부와 <b>넘긴 내용</b>까지 잡아 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class LlmDocumentServiceTest {

    @Mock
    private GeneratedDocumentDraftRepository draftRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private AdminDocumentService adminDocumentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmDocumentService service;

    @BeforeEach
    void setUp() {
        service = new LlmDocumentService(
                draftRepository, documentRepository, adminDocumentService, objectMapper, event -> { });
        lenient().when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 기본값은 "짝이 없다". 2026-09-03 이전 문서는 한 편짜리이므로 이쪽이 보통이고,
        // 짝을 보는 테스트만 아래에서 덮어쓴다. 안 두면 목록을 훑는 다른 테스트가
        // Optional 자리에 null을 받아 NPE로 죽는다.
        lenient().when(documentRepository.findTitleBySlug(any())).thenReturn(Optional.empty());
        lenient().when(draftRepository.findPairableTitlesBySlug(any())).thenReturn(List.of());
    }

    /* ── 승인 ────────────────────────────────────────────────── */

    @Test
    @DisplayName("승인하면 관리자 문서 등록 경로를 그대로 탄다 — 직접 저장하면 slug 검사·태그·캐시가 통째로 빠진다")
    void approveReusesAdminDocumentCreate() {
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략"));
        givenDraft(1L, draft);
        when(adminDocumentService.create(any())).thenReturn(detail(42L));

        DocumentDetailResponse created = service.approve(1L);

        ArgumentCaptor<AdminDocumentRequest> captor = ArgumentCaptor.forClass(AdminDocumentRequest.class);
        verify(adminDocumentService).create(captor.capture());
        AdminDocumentRequest request = captor.getValue();
        assertThat(request.title()).isEqualTo("캐시 전략");
        assertThat(request.slug()).isEqualTo("cache-strategy");
        assertThat(request.domain()).isEqualTo(Domain.SYSTEM_DESIGN);
        assertThat(request.tags()).containsExactly("cache", "performance");
        assertThat(request.source()).as("AI가 지어낸 가짜 출처를 넣지 않기 위해 비운다").isNull();

        assertThat(created.id()).isEqualTo(42L);
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.APPROVED);
        assertThat(draft.getApprovedDocumentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("차단 항목이 있으면 승인이 막히고 문서는 만들어지지 않는다")
    void approveBlockedWhenValidationFails() {
        // 필수 절이 하나도 없는 문서
        givenDraft(1L, pendingDraft("엉망", "bad-doc", "# 엉망\n\n아무 절도 없다."));

        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("## 왜 필요한가");

        verify(adminDocumentService, never()).create(any());
    }

    /**
     * 분량 초과는 <b>경고</b>다. 실측 2편이 모두 30% 넘게 넘겼고 프롬프트로는 못 잡는다는 것이
     * 확인됐는데, 이걸 차단으로 두면 잘 쓴 문서가 매일 승인 불가로 쌓인다.
     */
    @Test
    @DisplayName("경고만 있으면 승인된다 — 분량 초과로 좋은 문서를 막지 않는다")
    void approveAllowedWithWarningsOnly() {
        // 숫자를 직접 적지 않고 상수에 묶는다 — 권장 분량을 올린 날 이 테스트가 "경고 없는 문서로
        // 경고를 검사하는" 상태가 되어도 통과해 버려서, 무의미해진 것을 아무도 모른다.
        String content = validContent("캐시 전략")
                .replace("내용.", "가".repeat(DocumentDraftValidator.WARN_LENGTH + 1));
        givenDraft(1L, pendingDraft("캐시 전략", "cache-strategy", content));
        when(adminDocumentService.create(any())).thenReturn(detail(7L));

        assertThat(service.approve(1L).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("이미 처리된 초안은 다시 승인되지 않는다 — 승인 버튼 두 번 클릭이 문서를 이중 등록하면 안 된다")
    void approveTwiceFails() {
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략"));
        draft.approve(42L);
        givenDraft(1L, draft);

        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.LLM_002.getDefaultMessage());

        verify(adminDocumentService, never()).create(any());
    }

    @Test
    @DisplayName("없는 초안을 승인하면 LLM_001")
    void approveMissingDraft() {
        when(draftRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.LLM_001.getDefaultMessage());
    }

    /* ── 거절 ────────────────────────────────────────────────── */

    @Test
    @DisplayName("거절하면 사유가 남는다 — 나중에 프롬프트를 고칠 단서가 된다")
    void rejectKeepsReason() {
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략"));
        givenDraft(1L, draft);

        service.reject(1L, "  캐시 무효화 설명이 사실과 다름  ");

        assertThat(draft.getStatus()).isEqualTo(DraftStatus.REJECTED);
        assertThat(draft.getRejectReason()).isEqualTo("캐시 무효화 설명이 사실과 다름");
        assertThat(draft.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("검증에 걸린 초안도 거절은 된다 — 막아야 하는 건 승인이지 처리 자체가 아니다")
    void rejectWorksEvenWhenBlocked() {
        GeneratedDocumentDraft draft = pendingDraft("엉망", "bad-doc", "# 엉망\n\n절 없음");
        givenDraft(1L, draft);

        service.reject(1L, "구조가 무너졌다");

        assertThat(draft.getStatus()).isEqualTo(DraftStatus.REJECTED);
    }

    /* ── 복구 ────────────────────────────────────────────────── */

    /**
     * 문서 복구에는 검수함 밖으로 미치는 효과가 하나 있다: 거절된 문서의 slug는 배치가 읽는
     * "쓰지 마라" 목록으로 나가는데, 그 조회가 {@code status='REJECTED'}로 걸러지므로
     * 복구하면 저절로 빠진다 — <b>그 문서로 다시 문제를 만들 수 있게 된다</b>.
     */
    @Test
    @DisplayName("거절한 문서를 되돌리면 검수 대기로 돌아간다 — 배치가 다시 근거로 쓸 수 있게 된다")
    void restoreRejectedDraft() {
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략"));
        draft.reject("실수로 거절");
        givenDraft(1L, draft);

        service.restore(1L);

        assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING);
        assertThat(draft.getReviewedAt()).isNull();
        assertThat(draft.getRejectReason()).as("왜 거절했었는지는 다음 판단의 재료다")
                .isEqualTo("실수로 거절");
    }

    @Test
    @DisplayName("승인된 문서는 되돌릴 수 없다 — 이미 정식 문서가 만들어졌다")
    void cannotRestoreApprovedDraft() {
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략"));
        draft.approve(42L);
        givenDraft(1L, draft);

        assertThatThrownBy(() -> service.restore(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("거절된 초안만");

        assertThat(draft.getStatus()).isEqualTo(DraftStatus.APPROVED);
    }

    @Test
    @DisplayName("복구한 뒤에는 다시 승인할 수 있다")
    void canApproveAfterRestore() {
        String slug = "cache-strategy";
        GeneratedDocumentDraft draft = pendingDraft("캐시 전략", slug, validContent("캐시 전략"));
        draft.reject("실수로 거절");
        givenDraft(1L, draft);
        when(adminDocumentService.create(any())).thenReturn(detail(7L));

        service.restore(1L);

        assertThat(service.approve(1L).id()).isEqualTo(7L);
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.APPROVED);
    }

    /* ── 목록(검증 결과 표시) ────────────────────────────────── */

    /**
     * 자동 검증은 "승인해도 되는가"를 판정하는 장치라, 판정이 끝난 초안에는 쓸모가 없다.
     *
     * <p>이 두 테스트가 지키는 실제 사고(2026-08-15): 문서 프롬프트에 필수 절 두 개를 추가하자
     * <b>이미 승인해서 사이트에 잘 올라가 있던</b> 옛 초안들이 검수 화면에서 빨간 "승인 불가"를
     * 달고 나왔다. 승인 버튼조차 없는 카드라 고칠 방법도 없고, 빨간 상자가 상시로 떠 있으면
     * 정작 진짜 차단이 왔을 때 눈이 그냥 지나친다.
     */
    @Test
    @DisplayName("검수 대기 초안에는 검증 결과가 실린다")
    void pendingDraftCarriesChecks() {
        givenDraftPage(DraftStatus.PENDING, pendingDraft("엉망", "bad-doc", "# 엉망\n\n절 없음"));

        LlmDocumentDraftResponse response =
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0);

        assertThat(response.checks()).isNotEmpty();
        assertThat(response.blocked()).isTrue();
    }

    @Test
    @DisplayName("이미 승인·거절된 초안은 다시 검증하지 않는다 — 옛 기록에 새 기준을 소급하면 상시 빨간 화면이 된다")
    void decidedDraftIsNotRevalidated() {
        // 지금 기준으로는 차단감인 본문. 그래도 이미 처리된 건이면 판정할 것이 없다.
        GeneratedDocumentDraft draft = pendingDraft("엉망", "bad-doc", "# 엉망\n\n절 없음");
        draft.approve(42L);
        givenDraftPage(DraftStatus.APPROVED, draft);

        LlmDocumentDraftResponse response =
                service.getDrafts(DraftStatus.APPROVED, Pageable.unpaged()).content().get(0);

        assertThat(response.checks()).isEmpty();
        assertThat(response.blocked()).isFalse();
    }

    /* ── 짝 편 제목 대조 ─────────────────────────────────────── */

    /**
     * <b>두 편은 제목이 글자까지 같아야 한다</b>(2026-09-04).
     *
     * <p>화면에서 편을 가르는 것은 제목이 아니라 배지다. 제목이 갈리면 목록에서 두 편이
     * <b>남남으로 보인다</b> — 같은 주제의 1부·2부라는 사실이 독자에게 전달되지 않는다.
     * 프롬프트와 user 메시지가 둘 다 "그대로 옮겨 적으라"고 시키지만 <b>지시일 뿐</b>이라,
     * 어겨도 예외도 로그도 나지 않는다. 실제로 2026-09-07 한 쌍이 제목이 다른 채
     * 검수함에 들어와 있었고 화면에는 아무 표시도 없었다.
     */
    @Test
    @DisplayName("짝 편과 제목이 다르면 경고가 뜬다 — 어긋나도 예외가 안 나던 자리다")
    void warnsWhenPairedEditionHasDifferentTitle() {
        when(documentRepository.findTitleBySlug("cache-strategy" + DocumentEditions.ADVANCED_SUFFIX))
                .thenReturn(Optional.of("캐시를 언제 비울 것인가"));
        givenDraftPage(DraftStatus.PENDING,
                pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략")));

        LlmDocumentDraftResponse response =
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0);

        assertThat(pairingMessages(response))
                .as("어느 쪽 제목에 맞춰야 하는지 알려면 짝의 제목이 메시지에 있어야 한다")
                .singleElement(as(STRING))
                .contains("심화편")
                .contains("캐시를 언제 비울 것인가");
        assertThat(response.blocked())
                .as("제목이 달라도 글은 멀쩡히 읽힌다 — 사람이 고칠 수 있는 것은 경고다")
                .isFalse();
    }

    /**
     * 두 편은 대개 <b>같은 날 함께 들어와 둘 다 PENDING</b>이다. 정식 {@code document} 테이블만
     * 보면 그때는 짝을 못 찾는다 — 아직 아무것도 승인되지 않았기 때문이다. 이 경로가 빠지면
     * 경고가 뜨는 때는 "한쪽을 먼저 승인한 뒤"뿐인데, 그건 이미 늦다.
     */
    @Test
    @DisplayName("짝이 아직 검수 대기 초안이어도 찾아낸다 — 두 편은 대개 같은 날 함께 들어온다")
    void findsCounterpartAmongPendingDraftsToo() {
        when(draftRepository.findPairableTitlesBySlug("cache-strategy"))
                .thenReturn(List.of("캐시를 언제 비울 것인가"));
        givenDraftPage(DraftStatus.PENDING, pendingDraft(
                "캐시 전략", "cache-strategy" + DocumentEditions.ADVANCED_SUFFIX, advancedContent("캐시 전략")));

        assertThat(pairingMessages(
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0)))
                .singleElement(as(STRING))
                .contains("입문편")
                .contains("캐시를 언제 비울 것인가");
    }

    @Test
    @DisplayName("제목이 같으면 조용하다 — 정상인 쌍에 경고가 뜨면 경고 전체가 값어치를 잃는다")
    void staysQuietWhenPairedTitlesMatch() {
        when(documentRepository.findTitleBySlug("cache-strategy")).thenReturn(Optional.of("캐시 전략"));
        givenDraftPage(DraftStatus.PENDING, pendingDraft(
                "캐시 전략", "cache-strategy" + DocumentEditions.ADVANCED_SUFFIX, advancedContent("캐시 전략")));

        assertThat(pairingMessages(
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0))).isEmpty();
    }

    /**
     * 2026-09-03 이전 문서는 한 편짜리다. 없는 짝을 두고 "제목이 다르다"고 할 수는 없다 —
     * {@code DocumentEditions}가 배지를 안 다는 것과 같은 판단이다.
     */
    @Test
    @DisplayName("짝이 없으면 조용하다 — 한 편짜리 문서가 대부분이라 여기서 새면 상시 경고가 된다")
    void staysQuietWhenThereIsNoCounterpart() {
        givenDraftPage(DraftStatus.PENDING,
                pendingDraft("캐시 전략", "cache-strategy", validContent("캐시 전략")));

        assertThat(pairingMessages(
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0))).isEmpty();
    }

    /**
     * 승인된 쪽이 <b>이미 사이트에 떠 있는 제목</b>이라, 맞춰야 할 대상은 그쪽이다.
     * 초안 제목을 집으면 아직 아무도 못 본 제목을 기준으로 삼게 된다.
     */
    @Test
    @DisplayName("정식 문서가 있으면 그 제목을 기준으로 삼는다 — 초안이 아니라 사이트에 떠 있는 쪽이 기준이다")
    void prefersApprovedDocumentTitleOverDraft() {
        when(documentRepository.findTitleBySlug("cache-strategy")).thenReturn(Optional.of("사이트에 뜬 제목"));
        lenient().when(draftRepository.findPairableTitlesBySlug("cache-strategy"))
                .thenReturn(List.of("초안 제목"));
        givenDraftPage(DraftStatus.PENDING, pendingDraft(
                "캐시 전략", "cache-strategy" + DocumentEditions.ADVANCED_SUFFIX, advancedContent("캐시 전략")));

        assertThat(pairingMessages(
                service.getDrafts(DraftStatus.PENDING, Pageable.unpaged()).content().get(0)))
                .singleElement(as(STRING))
                .contains("사이트에 뜬 제목")
                .doesNotContain("초안 제목");
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /**
     * 짝 대조 경고만 골라낸다.
     *
     * <p>본문에 걸린 다른 지적(분량·절 구성)까지 함께 세면, 검증 규칙을 하나 늘린 날
     * 이 테스트가 <b>엉뚱한 이유로</b> 깨진다. 여기서 보고 싶은 것은 짝 대조 하나뿐이다.
     */
    private List<String> pairingMessages(LlmDocumentDraftResponse response) {
        return response.checks().stream()
                .map(DraftCheck::message)
                .filter(m -> m.contains("제목이 다릅니다"))
                .toList();
    }

    /** 심화편으로 판정되는 최소 본문 — 편 판정 근거는 {@code ## 언제 깨지는가} 하나다. */
    private String advancedContent(String heading) {
        return """
                # %s

                ## 핵심 요약
                - **요점** — 결론 한 문장.

                ## 이 글을 읽기 전에
                - 입문편에서 정의를 다뤘다.

                ## 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 요점 | 한 줄 뜻. | 이럴 때. |

                ## 언제 깨지는가
                **첫 번째 조건**
                이럴 때 터진다. 이래서 그렇게 된다. 이렇게 대응한다.

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가**
                요점 두 문장.

                ## 면접 한 줄 요약
                "한 줄."
                """.formatted(heading);
    }

    private void givenDraftPage(DraftStatus status, GeneratedDocumentDraft draft) {
        when(draftRepository.findByStatusOrderByCreatedAtAsc(eq(status), any()))
                .thenReturn(new PageImpl<>(List.of(draft)));
    }

    private void givenDraft(Long id, GeneratedDocumentDraft draft) {
        when(draftRepository.findById(id)).thenReturn(Optional.of(draft));
    }

    private GeneratedDocumentDraft pendingDraft(String title, String slug, String content) {
        // 흡수 경로와 같은 입구를 쓴다 — 테스트가 엔티티를 직접 만들면 태그 JSON 형식이
        // 실제와 달라져 "테스트만 통과하는" 상태가 된다.
        return service.saveDraft(Domain.SYSTEM_DESIGN,
                new GeneratedDocumentItem(title, slug, content, List.of("cache", "performance")),
                "claude-opus-5");
    }

    private String validContent(String heading) {
        return """
                # %s

                ## 핵심 요약
                - **요점** — 결론 한 문장.

                ## 바탕이 되는 개념
                상위 개념을 처음부터 설명한다.

                ## 무엇인가
                한 문장 정의.

                ## 왜 필요한가
                내용.

                ## 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 요점 | 한 줄 뜻. | 이럴 때. |

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 자주 하는 오해
                **"믿기 쉬운 문장이다"**
                그렇지 않다. 맞는 사실은 이것이다.

                ## 한 줄로 정리하면
                "한 줄."
                """.formatted(heading);
    }

    private DocumentDetailResponse detail(Long id) {
        return new DocumentDetailResponse(id, Domain.SYSTEM_DESIGN, "시스템 설계",
                "캐시 전략", "cache-strategy", "본문", null, List.of("cache"),
                LocalDateTime.now(), LocalDateTime.now(), null, null);
    }
}
