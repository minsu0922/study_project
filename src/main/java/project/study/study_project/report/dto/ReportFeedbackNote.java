package project.study.study_project.report.dto;

/**
 * 인정된 제보 한 건을 "지문 + 사유" 짝으로 줄인 것 — 생성 프롬프트로 되먹일 재료.
 *
 * <p><b>왜 {@code llm.client.RejectionNote}를 그대로 쓰지 않나.</b> 모양은 같지만 주인이 다르다.
 * 그 타입은 프롬프트에 실려 가는 형식이고 그 형식은 {@code llm} 패키지의 사정이다. 이 패키지가
 * 그것을 직접 만들면 <b>제보 기능이 프롬프트 형식을 알게 되어</b>, 프롬프트가 바뀔 때 여기까지
 * 따라 바뀐다. 여기서는 "무엇을 넘길지"만 정하고 "어떤 문장이 될지"는 {@code LlmProblemService}가
 * 정한다(되먹임 문구의 접두사도 거기서 붙는다).
 *
 * @param question 제보당한 문제의 지문
 * @param reason   사유 코드의 문구 + 제보자가 덧붙인 상세(있으면)
 */
public record ReportFeedbackNote(String question, String reason) {
}
