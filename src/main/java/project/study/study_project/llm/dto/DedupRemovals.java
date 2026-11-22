package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 배치 파일의 중복 제거 기록. 편마다 따로 읽으므로 편별로 둔다.
 *
 * @param beginner 입문편에서 지운 문장. 그 편을 원본으로 두었으면 {@code null}
 * @param advanced 심화편에서 지운 문장. 심화편이 없거나 원본으로 두었으면 {@code null}
 */
public record DedupRemovals(List<DedupRemoval> beginner, List<DedupRemoval> advanced) {
}
