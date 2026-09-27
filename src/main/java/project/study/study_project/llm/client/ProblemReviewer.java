package project.study.study_project.llm.client;

import project.study.study_project.global.common.Difficulty;

import java.util.List;

/**
 * 객관식 문제 검수 — 지적만 하고 문제는 고치지 않는다(docs/22 §3.2~3.4).
 * 보기 4개·정답 1개가 아닌 문제는 건너뛴다.
 */
public interface ProblemReviewer {

    List<ProblemReview.Finding> review(List<GeneratedProblemItem> problems, Difficulty labeled, SourceDocument source);
}
