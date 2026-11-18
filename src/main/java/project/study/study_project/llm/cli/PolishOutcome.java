package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.GeneratedDocumentItem;

/**
 * 한 편의 윤문 결과.
 *
 * @param chosen  저장할 편. 채택했으면 윤문본, 아니면 원본 그대로
 * @param applied 윤문본을 채택했는지
 * @param reason  원본을 쓴 이유. 채택했으면 {@code null}
 */
record PolishOutcome(GeneratedDocumentItem chosen, boolean applied, String reason) {
}
