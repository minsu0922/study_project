package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.GeneratedDocumentItem;

import java.util.List;

/**
 * 한 편의 윤문 결과.
 *
 * @param chosen  저장할 편. 채택했으면 윤문본(일부 덩어리는 원고일 수 있다), 아니면 원본 그대로
 * @param applied 윤문본을 한 덩어리라도 채택했는지
 * @param reason  편 전체를 원본으로 둔 이유. 채택했으면 {@code null}
 * @param kept    채택했지만 원고로 둔 덩어리마다 이유와 자리. 없으면 빈 목록
 */
record PolishOutcome(GeneratedDocumentItem chosen, boolean applied, String reason, List<String> kept) {

    PolishOutcome(GeneratedDocumentItem chosen, boolean applied, String reason) {
        this(chosen, applied, reason, List.of());
    }
}
