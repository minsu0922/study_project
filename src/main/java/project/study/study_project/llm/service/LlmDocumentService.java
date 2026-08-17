package project.study.study_project.llm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.dto.LlmDocumentDraftResponse;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.support.DocumentCheck;
import project.study.study_project.llm.support.DocumentDraftValidator;

import java.util.List;

/**
 * 개념 문서 초안 검수 서비스 — docs/15 (1단계-B).
 *
 * <p>전체 흐름: 흡수({@link DocumentImportService}가 커밋된 JSON을 PENDING 초안으로 저장) →
 * 검수(자동 검증 결과를 보고 관리자가 승인/거절) → 승인 시 정식 {@code document} 등록.
 *
 * <p><b>가장 중요한 결정 — 승인은 {@link AdminDocumentService#create}를 재사용</b>한다.
 * 문제 쪽({@link LlmProblemService})과 완전히 같은 원칙이다. 문서 등록에는 slug 중복 검사,
 * 태그 find-or-create, 캐시 무효화가 딸려 있는데, 여기서 문서를 직접 저장하면 그 셋을 전부
 * 다시 구현해야 한다. 그리고 언젠가 한쪽만 고쳐진다 — 예를 들어 나중에 문서 등록에
 * 검색 색인 갱신이 추가되면, <b>AI가 승인한 문서만 검색에 안 잡히는</b> 버그가 된다.
 *
 * <p><b>왜 문제 서비스와 합치지 않았나.</b> 둘은 "초안을 승인한다"는 겉모습만 같고 알맹이가
 * 다르다 — 문제는 유형별 보기 규약을 검증하고, 문서는 절 구성과 HTML 태그를 본다.
 * 공통 부모로 묶으면 상속 계층은 생기는데 정작 공유되는 코드는 상태 전이 서너 줄뿐이라
 * 얻는 것보다 읽기 어려워지는 쪽이 크다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDocumentService {

    private final GeneratedDocumentDraftRepository draftRepository;
    private final AdminDocumentService adminDocumentService;
    private final ObjectMapper objectMapper;

    /** 검수가 끝났음을 알린다 — 스냅샷 내보내기가 커밋 뒤에 듣는다({@link ReviewCompleted}). */
    private final ApplicationEventPublisher events;

    /* ── 흡수(저장) ───────────────────────────────────────────── */

    /**
     * 생성된 문서 하나를 PENDING 초안으로 저장한다 — <b>초안이 들어오는 유일한 입구</b>.
     *
     * <p>여기서 검증을 하지 <b>않는</b> 것이 핵심 판단이다. 문제 쪽({@code saveDrafts})은 규약을
     * 어긴 항목을 저장 단계에서 버리는데, 문서는 반대로 <b>일단 다 받아 둔다</b>. 이유는 셋이다.
     * <ul>
     *   <li>문서는 하루 한 편이라, 버리면 그날 결과가 <b>0건</b>이 된다. 5개 중 1개를 버리는
     *       문제와는 손실의 크기가 다르다.
     *   <li>검증에 걸린 문서도 <b>왜 걸렸는지 보는 것 자체가 정보</b>다 — 프롬프트를 어떻게
     *       고쳐야 할지는 걸린 실물을 봐야 알 수 있다. 조용히 버리면 그 신호가 사라진다.
     *   <li>검증 규칙은 아직 실물 두 편을 보고 만든 초기 버전이다. 규칙이 틀렸을 때
     *       저장 단계에서 버렸으면 <b>되돌릴 원본이 없다</b>(파일은 남지만 이미 흡수 완료 도장이 찍힌다).
     * </ul>
     * 대신 승인 시점에 BLOCKING을 막으므로, 잘못된 문서가 정식으로 올라가는 일은 없다.
     *
     * @param model 초안에 기록할 모델 ID — 파일에 적힌 <b>생성 당시</b>의 값을 넘긴다
     *              (현재 설정값을 쓰면 모델 교체 후 흡수한 옛 파일이 새 모델로 둔갑한다)
     */
    @Transactional
    public GeneratedDocumentDraft saveDraft(Domain domain, GeneratedDocumentItem item, String model) {
        GeneratedDocumentDraft draft = GeneratedDocumentDraft.pending(
                domain,
                truncate(trimToEmpty(item.title()), 200),
                truncate(trimToEmpty(item.slug()), 150),
                item.contentMd(),
                writeTagsJson(item.tags()),
                model);

        // 걸린 항목을 흡수 시점에 로그로 남긴다 — 관리자 화면을 열기 전에도
        // "오늘 것은 뭔가 이상하다"를 알 수 있어야 프롬프트 개선이 미뤄지지 않는다.
        List<DocumentCheck> checks =
                DocumentDraftValidator.validate(draft.getTitle(), draft.getSlug(), draft.getContentMd());
        if (!checks.isEmpty()) {
            log.warn("문서 초안 검증 지적 {}건 — \"{}\": {}", checks.size(), draft.getTitle(),
                    checks.stream().map(DocumentCheck::message).toList());
        }
        return draftRepository.save(draft);
    }

    /* ── 검수(목록·승인·거절) ─────────────────────────────────── */

    /** 초안 목록 — 기본 PENDING, 오래된 순. 각 건에 자동 검증 결과가 함께 실린다. */
    @Transactional(readOnly = true)
    public PageResponse<LlmDocumentDraftResponse> getDrafts(DraftStatus status, Pageable pageable) {
        DraftStatus target = status != null ? status : DraftStatus.PENDING;
        return PageResponse.from(
                draftRepository.findByStatusOrderByCreatedAtAsc(target, pageable).map(this::toResponse));
    }

    /** 관리자 대시보드 배지용 — 문서 검수 대기 건수. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return draftRepository.countByStatus(DraftStatus.PENDING);
    }

    /**
     * 승인 — 초안을 정식 문서로 등록한다.
     *
     * <p><b>검증을 승인 시점에 다시 돌리는 이유</b>: 흡수 때 계산한 결과를 저장해 두지 않기
     * 때문이다(엔티티 주석 참고). 규칙을 고치면 이미 대기 중인 초안에도 <b>즉시</b> 새 규칙이
     * 적용되어야 하는데, 저장해 뒀다면 옛 판정이 그대로 남아 "화면엔 경고가 없는데 승인은
     * 막히는" 어긋남이 생긴다.
     *
     * <p>slug 중복(DOC_002)은 여기서 검사하지 않는다 — {@code AdminDocumentService.create}가
     * 이미 검사하고 409를 던진다. 두 곳에서 보면 언젠가 한쪽만 고쳐진다.
     */
    @Transactional
    public DocumentDetailResponse approve(Long draftId) {
        GeneratedDocumentDraft draft = findDraft(draftId);
        // 상태 검사를 등록 "전"에 한다 — 등록부터 하면 승인 버튼 두 번 누르기가
        // 문서를 이중 등록한 뒤에야 실패한다(같은 트랜잭션이라 롤백되지만 순서가 어색하다).
        if (draft.getStatus() != DraftStatus.PENDING) {
            throw new BusinessException(ErrorCode.LLM_002);
        }

        List<DocumentCheck> checks =
                DocumentDraftValidator.validate(draft.getTitle(), draft.getSlug(), draft.getContentMd());
        if (DocumentDraftValidator.hasBlocking(checks)) {
            // 무엇에 걸렸는지 메시지로 함께 준다 — "승인할 수 없습니다"만 나오면
            // 검수자는 화면을 새로고침해 가며 이유를 찾아야 한다.
            String reasons = checks.stream()
                    .filter(DocumentCheck::isBlocking)
                    .map(DocumentCheck::message)
                    .reduce((a, b) -> a + " / " + b)
                    .orElse("");
            throw new BusinessException(ErrorCode.LLM_005, "승인 불가: " + reasons);
        }

        DocumentDetailResponse created = adminDocumentService.create(new AdminDocumentRequest(
                draft.getDomain(), draft.getTitle(), draft.getSlug(), draft.getContentMd(),
                // source는 비워 둔다. AI에게 출처를 적게 하면 그럴듯한 가짜 URL을 지어내기
                // 때문에 애초에 생성 스키마에 넣지 않았다(GeneratedDocumentItem 주석).
                null,
                readTags(draft.getTagsJson())));

        draft.approve(created.id()); // 엔티티도 같은 규칙을 방어(이중 안전장치)
        log.info("문서 초안 승인: \"{}\" → document#{}", draft.getTitle(), created.id());
        events.publishEvent(ReviewCompleted.document());
        return created;
    }

    @Transactional
    public void reject(Long draftId, String reason) {
        findDraft(draftId).reject(truncate(trimToNull(reason), 500));
        // 거절하면 배치의 "쓰지 마라" 목록(rejectedSlugs)이 늘어난다 — 스냅샷을 다시 찍어야
        // 그 문서를 근거로 사흘 치 문제가 만들어지는 것을 막을 수 있다.
        events.publishEvent(ReviewCompleted.document());
    }

    /**
     * 복구 — 거절한 문서 초안을 검수 대기로 되돌린다.
     *
     * <p>규칙은 엔티티에 있다({@link GeneratedDocumentDraft#restore()}). 문서 쪽에서 한 가지 더
     * 기억할 것: 복구하면 배치의 "쓰지 마라" 목록({@code rejectedSlugs})에서도 빠지므로
     * <b>그 문서로 다시 문제가 만들어질 수 있다</b>. 스냅샷 파일은 이 트랜잭션이 커밋된 직후
     * 갱신되고({@link ReviewCompleted}), 사용자가 커밋해야 배치에 반영된다.
     */
    @Transactional
    public void restore(Long draftId) {
        GeneratedDocumentDraft draft = findDraft(draftId);
        draft.restore();
        log.info("문서 초안 복구: \"{}\" — 검수 대기로 되돌림 (배치가 다시 근거로 쓸 수 있게 됨)",
                draft.getTitle());
        events.publishEvent(ReviewCompleted.document());
    }

    private GeneratedDocumentDraft findDraft(Long id) {
        return draftRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.LLM_001));
    }

    /* ── 변환 ─────────────────────────────────────────────────── */

    /**
     * 초안 → 응답 DTO.
     *
     * <p><b>자동 검증은 PENDING일 때만 돌린다.</b> 검증의 목적은 오직 하나 —
     * "이걸 승인해도 되는가"를 사람보다 먼저 판정하는 것이다. 이미 승인/거절이 끝난 초안에는
     * 판정할 것이 없다. 그런데도 매번 다시 검증하면 <b>과거 기록에 지금 기준을 소급 적용</b>하게 된다.
     *
     * <p>실제로 그 일이 났다(2026-08-15). 문서 프롬프트를 고치면서 필수 절에
     * {@code ## 무엇인가}·{@code ## 언제 깨지는가}를 추가했더니, 그 전에 <b>이미 승인해서
     * 사이트에 정상적으로 올라가 있는</b> 초안 2편이 검수 화면에서 빨간 "승인 불가"를 달고 나왔다.
     * 승인 버튼도 없는 카드에 승인 불가가 떠 있으니 읽을 수 있는 뜻이 없고, 빨간 상자가 늘
     * 떠 있으면 <b>진짜 차단이 왔을 때도 그러려니 하게 된다</b> — 경고의 값어치가 0이 되는 길이다.
     *
     * <p>대안으로 초안 본문을 새 기준에 맞게 고쳐 쓰는 것도 검토했지만 버렸다. 초안 테이블은
     * <b>모델이 그날 실제로 내놓은 것의 기록</b>이라, 나중에 "프롬프트를 바꿨더니 결과가 어떻게
     * 달라졌나"를 되짚는 유일한 근거다. 거절 사유를 지우지 않고 남겨 두는 것과 같은 이유다.
     */
    private LlmDocumentDraftResponse toResponse(GeneratedDocumentDraft d) {
        List<DocumentCheck> checks = d.getStatus() == DraftStatus.PENDING
                ? DocumentDraftValidator.validate(d.getTitle(), d.getSlug(), d.getContentMd())
                : List.of();
        return new LlmDocumentDraftResponse(
                d.getId(), d.getDomain(), d.getDomain().getDisplayName(),
                d.getTitle(), d.getSlug(), d.getContentMd(), readTags(d.getTagsJson()),
                d.getContentMd() == null ? 0 : d.getContentMd().length(),
                d.getStatus(), d.getModel(), d.getRejectReason(), d.getApprovedDocumentId(),
                d.getCreatedAt(), d.getReviewedAt(),
                checks, DocumentDraftValidator.hasBlocking(checks));
    }

    private String writeTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            // 문자열 목록 직렬화는 실패할 수 없는 경로다 — 도달하면 버그
            throw new IllegalStateException("태그 JSON 직렬화 실패", e);
        }
    }

    /**
     * 태그 JSON을 목록으로. 깨진 값이면 <b>예외 대신 빈 목록</b>을 준다.
     *
     * <p>여기서 예외를 던지면 초안 하나가 목록 조회 전체를 500으로 만든다 — 태그는 부가
     * 정보인데 그것 때문에 검수 화면이 통째로 안 열리는 것은 손해가 너무 크다.
     * 태그가 비면 검수자가 승인 후 문서 수정 화면에서 붙이면 된다.
     */
    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("태그 JSON을 읽지 못해 빈 목록으로 처리합니다: {}", json);
            return List.of();
        }
    }

    /* ── 문자열 도우미 ───────────────────────────────────────── */

    /**
     * 컬럼 길이를 넘는 값을 잘라 낸다.
     *
     * <p>제목 200자·slug 150자는 스키마 제약이라 넘치면 DB가 예외를 던지는데, 그 예외는
     * 파일 흡수 전체를 실패시킨다. 모델이 제목을 길게 쓴 것은 <b>검수자가 보고 고치면 되는</b>
     * 수준의 문제라, 통째로 버리는 것보다 잘라서라도 대기함에 올리는 편이 낫다.
     * (잘린 제목은 본문 H1과 달라지므로 제목 불일치 경고가 자동으로 뜬다 — 티가 난다.)
     */
    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }

    private String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
