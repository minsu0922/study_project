package project.study.study_project.llm.client;

import java.util.List;

/**
 * 객관식 문제 검수 입구 — 배치(DraftGeneratorCli)와 관리 화면(LlmProblemService)이 함께 쓴다.
 * 구현은 {@code llm.review.ProblemReviewPipeline}이다(풀이 → 편집 → 고친 글 넣기 → 문서 지적 연결).
 */
public interface ProblemReviewer {

    /**
     * @param facts 근거 문서 파일에 실린 사실 검수 지적. 없으면 {@code null}
     * @throws RuntimeException 풀이 호출이 실패하면. 부르는 쪽이 "검수 실패"로 처리한다
     */
    Reviewed review(List<GeneratedProblemItem> problems, SourceDocument source, List<FactCheckFinding> facts);

    /**
     * @param problems 고친 해설·오답 설명이 들어간 문제 목록. 순서와 질문 글은 받은 그대로다
     * @param findings 지적. 번호는 위 목록 안의 위치다
     */
    record Reviewed(List<GeneratedProblemItem> problems, List<ProblemReview.Finding> findings) {
    }
}
