package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.TestDomains;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.dto.LlmDocumentBulkApproveResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 문서 일괄 승인 — 한 건이 막혀도 나머지는 승인하고, 결과를 건별로 돌려준다. */
@ExtendWith(MockitoExtension.class)
class LlmDocumentBulkApproverTest {

    @Mock
    private LlmDocumentService llmDocumentService;

    private LlmDocumentBulkApprover approver() {
        return new LlmDocumentBulkApprover(llmDocumentService);
    }

    private DocumentDetailResponse doc(long id, String slug) {
        return new DocumentDetailResponse(id, TestDomains.NETWORK, "네트워크", "제목", slug, "본문",
                null, List.of(), null, null, null, null);
    }

    @Test
    @DisplayName("slug 중복으로 한 건이 막혀도 나머지는 승인된다")
    void partialSuccess() {
        when(llmDocumentService.approve(1L)).thenReturn(doc(11L, "tcp"));
        when(llmDocumentService.approve(2L)).thenThrow(new BusinessException(ErrorCode.DOC_002));
        when(llmDocumentService.approve(3L)).thenReturn(doc(13L, "tcp-advanced"));

        LlmDocumentBulkApproveResponse result = approver().approveAll(List.of(1L, 2L, 3L));

        assertThat(result.approved()).containsExactly(
                new LlmDocumentBulkApproveResponse.Approved(1L, 11L, "tcp"),
                new LlmDocumentBulkApproveResponse.Approved(3L, 13L, "tcp-advanced"));
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).draftId()).isEqualTo(2L);
        assertThat(result.failed().get(0).message()).isEqualTo("이미 사용 중인 slug입니다.");
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    @DisplayName("받은 순서대로 승인한다")
    void keepsRequestedOrder() {
        when(llmDocumentService.approve(5L)).thenReturn(doc(15L, "a"));
        when(llmDocumentService.approve(3L)).thenReturn(doc(13L, "b"));

        LlmDocumentBulkApproveResponse result = approver().approveAll(List.of(5L, 3L));

        InOrder order = inOrder(llmDocumentService);
        order.verify(llmDocumentService).approve(5L);
        order.verify(llmDocumentService).approve(3L);
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    @DisplayName("시스템 예외는 삼키지 않고 던진다 — 뒤 건은 시도하지 않는다")
    void systemFailurePropagates() {
        when(llmDocumentService.approve(1L)).thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatThrownBy(() -> approver().approveAll(List.of(1L, 2L)))
                .isInstanceOf(IllegalStateException.class);

        verify(llmDocumentService, never()).approve(2L);
    }
}
