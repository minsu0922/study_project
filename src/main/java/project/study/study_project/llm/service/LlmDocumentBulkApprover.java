package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.dto.LlmBulkApproveResponse;
import project.study.study_project.llm.dto.LlmDocumentBulkApproveResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서 초안 일괄 승인. 별도 클래스로 둔 이유는 {@link LlmDraftBulkApprover}와 같다.
 * 밖에서 {@link LlmDocumentService#approve}를 불러야 건마다 트랜잭션이 따로 커밋된다.
 * 한 트랜잭션으로 묶으면 한 건의 실패(slug 중복 등)가 성공한 건까지 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDocumentBulkApprover {

    private final LlmDocumentService llmDocumentService;

    /**
     * 받은 순서대로 승인한다. 여기에 {@code @Transactional}을 붙이면 건별 커밋이 깨진다.
     * 규칙 위반(BusinessException)만 결과에 담고, 시스템 예외는 그대로 던진다.
     */
    public LlmDocumentBulkApproveResponse approveAll(List<Long> draftIds) {
        List<LlmDocumentBulkApproveResponse.Approved> approved = new ArrayList<>();
        List<LlmBulkApproveResponse.Failed> failed = new ArrayList<>();

        for (Long id : draftIds) {
            try {
                DocumentDetailResponse created = llmDocumentService.approve(id);
                approved.add(new LlmDocumentBulkApproveResponse.Approved(id, created.id(), created.slug()));
            } catch (BusinessException e) {
                // 이미 처리된 초안(LLM_002), 차단 항목(LLM_005), slug 중복(DOC_002) 등
                log.info("문서 일괄 승인 건너뜀: 초안 #{} — {}", id, e.getMessage());
                failed.add(new LlmBulkApproveResponse.Failed(id, e.getMessage()));
            }
        }

        log.info("문서 일괄 승인 완료: 요청 {}건 → 성공 {}건, 실패 {}건",
                draftIds.size(), approved.size(), failed.size());
        return new LlmDocumentBulkApproveResponse(approved, failed);
    }
}
