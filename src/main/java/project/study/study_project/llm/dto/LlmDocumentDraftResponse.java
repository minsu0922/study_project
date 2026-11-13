package project.study.study_project.llm.dto;

import project.study.study_project.global.common.DomainCode;
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
 * @param factFindings  AI 사실 검수 지적. 검수를 안 돌렸으면 {@code null}, 돌렸는데 없으면 빈 목록
 */
public record LlmDocumentDraftResponse(
        Long id,
        DomainCode domain,
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
        boolean blocked,
        List<FactFinding> factFindings
) {

    /**
     * @param kind       FACT_ERROR·INTERNAL_MISMATCH·UNDEFINED_TERM
     * @param label      화면 표기용 한글 이름
     * @param confident  검수기가 틀렸다고 확신하면 true, 의심만 하면 false
     * @param quote      문서에서 그대로 옮긴 문장
     * @param sourceUrl  확인한 공식 문서 주소. 없으면 빈 문자열
     * @param paragraph  지적된 문장이 든 문단(마크다운 원문). 고치기 칸에 채운다. 처리했거나 본문에서 사라졌으면 {@code null}
     * @param resolution 검수자가 처리한 기록. 아직 안 봤으면 {@code null}
     */
    /** @param status OPEN(미처리)·FIXED(고침)·FALSE_ALARM(헛경보). 미처리가 남으면 승인되지 않는다 */
    public record FactFinding(String kind, String label, boolean confident, String quote, String reason,
                              String correction, String sourceUrl, String paragraph, String resolution,
                              String status) {
    }
}
