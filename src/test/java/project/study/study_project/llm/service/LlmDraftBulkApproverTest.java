package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.dto.LlmBulkApproveResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일괄 승인 단위 테스트 — 이 기능의 존재 이유인 <b>부분 성공</b>을 지킨다.
 *
 * <p>여기서 트랜잭션 경계 자체는 검증하지 못한다(단위 테스트에는 트랜잭션이 없다).
 * 검증하는 것은 그 위에 얹힌 계약이다 — "한 건이 실패해도 나머지는 계속 시도하고,
 * 결과를 건별로 돌려준다". 트랜잭션을 건별로 끊는 진짜 이유와 그 근거는
 * {@link LlmDraftBulkApprover} 클래스 주석에 적혀 있다.
 */
@ExtendWith(MockitoExtension.class)
class LlmDraftBulkApproverTest {

    @Mock
    private LlmProblemService llmProblemService;

    private LlmDraftBulkApprover approver() {
        return new LlmDraftBulkApprover(llmProblemService);
    }

    /** 승인 결과 상세 — 이 테스트가 보는 것은 id뿐이라 나머지는 최소로 채운다. */
    private AdminProblemDetail detail(long problemId) {
        return new AdminProblemDetail(problemId, Domain.NETWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, "지문", null, "해설", null, List.of());
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 승인된다 — 부분 성공")
    void partialSuccess() {
        // 2번만 "이미 처리된 초안"으로 막힌다(LLM_002)
        when(llmProblemService.approve(1L)).thenReturn(detail(101L));
        when(llmProblemService.approve(2L)).thenThrow(new BusinessException(ErrorCode.LLM_002));
        when(llmProblemService.approve(3L)).thenReturn(detail(103L));

        LlmBulkApproveResponse result = approver().approveAll(List.of(1L, 2L, 3L));

        assertThat(result.approved())
                .containsExactly(new LlmBulkApproveResponse.Approved(1L, 101L),
                        new LlmBulkApproveResponse.Approved(3L, 103L));
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).draftId()).isEqualTo(2L);
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    @DisplayName("실패 사유는 예외 메시지를 그대로 옮긴다 — 사람이 읽고 손볼 수 있어야 한다")
    void failureCarriesMessage() {
        when(llmProblemService.approve(7L))
                .thenThrow(new BusinessException(ErrorCode.QUIZ_004, "객관식은 정답 보기가 정확히 1개여야 합니다."));

        LlmBulkApproveResponse result = approver().approveAll(List.of(7L));

        assertThat(result.approved()).isEmpty();
        assertThat(result.failed().get(0).message()).isEqualTo("객관식은 정답 보기가 정확히 1개여야 합니다.");
    }

    @Test
    @DisplayName("전부 성공하면 실패 목록이 비고 allSucceeded가 참")
    void allSucceeded() {
        when(llmProblemService.approve(1L)).thenReturn(detail(101L));
        when(llmProblemService.approve(2L)).thenReturn(detail(102L));

        LlmBulkApproveResponse result = approver().approveAll(List.of(1L, 2L));

        assertThat(result.failed()).isEmpty();
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    @DisplayName("받은 순서대로 승인한다 — 화면에서 본 순서와 결과 순서가 같아야 한다")
    void keepsRequestedOrder() {
        when(llmProblemService.approve(5L)).thenReturn(detail(105L));
        when(llmProblemService.approve(3L)).thenReturn(detail(103L));

        approver().approveAll(List.of(5L, 3L));

        InOrder order = inOrder(llmProblemService);
        order.verify(llmProblemService).approve(5L);
        order.verify(llmProblemService).approve(3L);
    }

    /**
     * 규칙 위반(BusinessException)과 시스템 고장은 다루는 방식이 달라야 한다.
     * 전자는 그 건만 건너뛰지만, 후자는 나머지도 어차피 실패하므로 그대로 터뜨려
     * 관리자가 "12건 중 11건 실패" 같은 무의미한 목록 대신 진짜 원인을 보게 한다.
     */
    @Test
    @DisplayName("시스템 예외는 삼키지 않고 그대로 던진다 — 이후 건도 시도하지 않는다")
    void systemFailurePropagates() {
        when(llmProblemService.approve(1L)).thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatThrownBy(() -> approver().approveAll(List.of(1L, 2L)))
                .isInstanceOf(IllegalStateException.class);

        verify(llmProblemService, never()).approve(2L);
    }
}
