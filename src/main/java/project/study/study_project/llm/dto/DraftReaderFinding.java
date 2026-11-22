package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import project.study.study_project.llm.client.ReaderFinding;
import project.study.study_project.llm.support.FindingList;

/**
 * 문서 초안에 저장하는 독자 점검 지적 한 건 — {@code generated_document_draft.reader_findings_json}의 원소(V49).
 *
 * @param finding    배치가 받은 지적 그대로
 * @param resolution 검수자가 처리한 기록(수락·넘김). 아직 안 봤으면 {@code null}
 */
public record DraftReaderFinding(ReaderFinding finding,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String resolution)
        implements FindingList.Resolvable<DraftReaderFinding> {

    public static final String ACCEPTED_PREFIX = "수락: ";
    public static final String EDITED_PREFIX = "직접 수정: ";
    public static final String DISMISSED_PREFIX = "넘김";

    public static DraftReaderFinding of(ReaderFinding f) {
        return new DraftReaderFinding(f, null);
    }

    @Override
    public DraftReaderFinding resolvedWith(String resolution) {
        return new DraftReaderFinding(finding, resolution);
    }

    /** 처리 상태. 사실 검수와 달리 OPEN이 남아도 승인된다. */
    public enum Status { OPEN, ACCEPTED, EDITED, DISMISSED }

    /** 메서드 이름이 getter 꼴이 아니라 JSON에는 실리지 않는다. */
    public Status status() {
        if (resolution == null) {
            return Status.OPEN;
        }
        if (resolution.startsWith(EDITED_PREFIX)) {
            return Status.EDITED;
        }
        return resolution.startsWith(ACCEPTED_PREFIX) ? Status.ACCEPTED : Status.DISMISSED;
    }
}
