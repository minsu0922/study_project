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

    /** 처리 상태(2026-11-13). 미처리가 하나라도 남은 초안은 승인하지 않는다. */
    public enum Status { OPEN, FIXED, FALSE_ALARM }

    /** 헛경보 처리 기록의 머리말. 이유가 이 뒤에 붙는다. */
    public static final String FALSE_ALARM_PREFIX = "헛경보: ";

    /**
     * 처리 기록에서 상태를 읽는다. "고치지 않음"은 이유 칸이 생기기 전(11-13 이전)의 헛경보 기록이다.
     * 메서드 이름이 getter 꼴이 아니라 JSON에는 실리지 않는다.
     */
    public Status status() {
        if (resolution == null) {
            return Status.OPEN;
        }
        return resolution.startsWith(FALSE_ALARM_PREFIX) || resolution.equals("고치지 않음")
                ? Status.FALSE_ALARM : Status.FIXED;
    }
}
