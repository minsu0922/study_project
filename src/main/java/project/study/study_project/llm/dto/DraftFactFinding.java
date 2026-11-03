package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.support.FindingList;

/**
 * 문서 초안에 저장하는 사실 검수 지적 한 건 — {@code generated_document_draft.fact_findings_json}의 원소(V45).
 *
 * <p>{@link FactCheckFinding}과 칸 이름이 같아, 처리 칸이 생기기 전에 저장한 JSON도 그대로 읽힌다.
 *
 * @param resolution 검수자가 처리한 기록(직접 수정·고치지 않음). 아직 안 봤으면 {@code null}
 */
public record DraftFactFinding(String quote, FactCheckFinding.Kind kind, String reason, String correction,
                               String sourceUrl, FactCheckFinding.Confidence confidence,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String resolution)
        implements FindingList.Resolvable<DraftFactFinding> {

    public static DraftFactFinding of(FactCheckFinding f) {
        return new DraftFactFinding(f.quote(), f.kind(), f.reason(), f.correction(), f.sourceUrl(),
                f.confidence(), null);
    }

    public DraftFactFinding resolvedWith(String resolution) {
        return new DraftFactFinding(quote, kind, reason, correction, sourceUrl, confidence, resolution);
    }
}
