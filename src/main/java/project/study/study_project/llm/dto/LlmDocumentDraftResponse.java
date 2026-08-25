package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.support.DraftCheck;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문서 초안 검수 화면용 응답 — docs/15.
 *
 * <p>{@link LlmDraftResponse}(문제)와 달리 <b>검증 결과({@code checks}, {@code blocked})를
 * 함께 싣는다</b>. 화면이 스스로 판단하게 두면 규칙이 자바와 자바스크립트 두 곳에 생기고,
 * 그때부터는 "화면은 통과라는데 서버는 거부하는" 상태가 언제든 만들어진다.
 * 판정은 서버에서 한 번만 하고 화면은 받아서 그리기만 한다.
 *
 * @param contentLength 본문 글자 수 — 검수자가 분량 감각을 잡는 데 쓴다. 화면에서
 *                      {@code contentMd.length}로 세도 되지만, 경고 기준(3,500자)을
 *                      정하는 쪽과 세는 쪽이 같아야 숫자가 어긋나지 않는다
 * @param checks        자동 검증에서 걸린 항목 전부(없으면 빈 목록)
 * @param blocked       {@code checks}에 BLOCKING이 하나라도 있어 승인이 막힌 상태인지
 */
public record LlmDocumentDraftResponse(
        Long id,
        Domain domain,
        String domainName,
        String title,
        String slug,
        String contentMd,
        List<String> tags,
        int contentLength,
        DraftStatus status,
        String model,
        String rejectReason,
        Long approvedDocumentId,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        List<DraftCheck> checks,
        boolean blocked
) {
}
