package project.study.study_project.llm.dto;

import project.study.study_project.llm.client.ProblemReview;

/**
 * 초안에 저장하는 AI 검수 지적 한 건 — {@code generated_problem_draft.ai_findings_json}의 원소(V29).
 *
 * <p>배치 파일의 {@link ProblemReview.Finding}에서 {@code problemIndex}만 뺀 모양이다.
 * 그 번호는 파일 안 위치라, 초안 한 건에 붙은 뒤에는 뜻이 없다.
 */
public record DraftAiFinding(ProblemReview.FindingType type, String message) {
}
