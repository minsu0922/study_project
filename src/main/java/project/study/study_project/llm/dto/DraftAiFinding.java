package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import project.study.study_project.llm.client.ProblemReview;

/**
 * 초안에 저장하는 AI 검수 지적 한 건 — {@code generated_problem_draft.ai_findings_json}의 원소(V29).
 *
 * <p>배치 파일의 {@link ProblemReview.Finding}에서 {@code problemIndex}만 뺀 모양이다.
 * 그 번호는 파일 안 위치라, 초안 한 건에 붙은 뒤에는 뜻이 없다.
 *
 * @param detail     근거(여러 줄). 없으면 {@code null}
 * @param suggestion 고친 문장 제안. 없거나 이미 적용했으면 {@code null}
 */
public record DraftAiFinding(ProblemReview.FindingType type, String message, String detail,
                             // 제안은 드물다. 없는 지적마다 null을 적으면 저장된 JSON이 칸 이름으로 채워진다
                             @JsonInclude(JsonInclude.Include.NON_NULL) ProblemReview.Suggestion suggestion) {

    public DraftAiFinding(ProblemReview.FindingType type, String message, String detail) {
        this(type, message, detail, null);
    }
}
