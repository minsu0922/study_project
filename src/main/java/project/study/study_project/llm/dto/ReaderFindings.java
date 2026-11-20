package project.study.study_project.llm.dto;

import project.study.study_project.llm.client.ReaderFinding;

import java.util.List;

/**
 * 배치 파일의 독자 점검 칸. 사실 검수와 달리 편마다 따로 읽으므로 섞지 않고 편별로 둔다.
 *
 * @param beginner 입문편 지적. 그 편 점검을 못 돌렸으면 {@code null}
 * @param advanced 심화편 지적. 심화편이 없거나 점검을 못 돌렸으면 {@code null}
 */
public record ReaderFindings(List<ReaderFinding> beginner, List<ReaderFinding> advanced) {
}
