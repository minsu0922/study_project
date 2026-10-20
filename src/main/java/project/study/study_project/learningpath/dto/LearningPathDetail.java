package project.study.study_project.learningpath.dto;

import project.study.study_project.global.common.Difficulty;

import java.util.List;

/** 로드맵 상세 — 코스 머리말과 단계별 내 진도. */
public record LearningPathDetail(
        LearningPathSummary summary,
        List<StepItem> steps
) {

    /**
     * @param documentSlug  문서 단계면 차 있다. 화면이 [문서 읽기] 링크를 건다
     * @param documentTitle 문서 단계의 문서 제목(= 단계 이름)
     * @param difficulty    코드 단계면 차 있다
     * @param done          단계 문제를 전부 맞혔다
     */
    public record StepItem(
            int seq,
            String title,
            String edition,
            String documentSlug,
            Difficulty difficulty,
            long total,
            long attempted,
            long solved,
            boolean done
    ) {
    }
}
