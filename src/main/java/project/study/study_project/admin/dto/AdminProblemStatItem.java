package project.study.study_project.admin.dto;

/**
 * 학습 현황의 문제별 정답률 한 줄.
 *
 * @param correctRate 정답률(%). 제출이 0건이면 null — "0%"(전부 틀림)와 "아무도 안 풀었다"는
 *                    정반대 신호라 0으로 뭉개지 않는다
 */
public record AdminProblemStatItem(Long problemId, String domain, String difficulty, String type,
                                   String title, String question, boolean hidden,
                                   long attempts, long correctCount, Integer correctRate) {
}
