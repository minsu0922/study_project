package project.study.study_project.learningpath.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리 콘솔의 코스 한 벌. 목록에서는 {@code steps}를 비워 보낸다.
 *
 * @param visibleStepCount 사용자에게 보이는 단계 수 — 숨긴 문서의 단계는 빠진다
 */
public record AdminLearningPathItem(
        Long id,
        String title,
        String description,
        int sortOrder,
        boolean published,
        int stepCount,
        int visibleStepCount,
        LocalDateTime updatedAt,
        List<Step> steps
) {

    /** @param hidden 문서를 내렸다. 사용자에게는 이 단계가 보이지 않는다 */
    public record Step(int seq, Long documentId, String slug, String title, String domain,
                       boolean hidden, long problemCount) {
    }
}
