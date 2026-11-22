package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.DedupRemoval;
import project.study.study_project.llm.support.DedupGuard;

import java.util.List;

/**
 * 한 편의 중복 제거 결과.
 *
 * @param chosen       이후 단계가 쓸 편. 적용하지 않았으면 원본
 * @param applied      제거본을 채택했는지
 * @param reason       채택하지 않은 까닭. 채택했으면 {@code null}
 * @param removals     지운 문장. 채택하지 않았으면 빈 목록
 * @param removedChars 지운 글자 수
 * @param rejected     코드 검사에서 버린 후보
 */
record DedupOutcome(GeneratedDocumentItem chosen, boolean applied, String reason, List<DedupRemoval> removals,
                    int removedChars, List<DedupGuard.Rejected> rejected) {
}
