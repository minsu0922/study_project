package project.study.study_project.quiz.dto;

import project.study.study_project.quiz.domain.Choice;

/**
 * 풀이용 보기 항목 — API 스펙(docs/03 GET /api/quiz).
 *
 * <p><b>{@code isCorrect}(정답 여부)는 의도적으로 뺐다.</b> 엔티티를 그대로 직렬화하면
 * 정답이 응답에 실려 퀴즈가 무의미해진다 — "엔티티와 API 응답을 DTO로 분리하는" 대표적인 이유.
 *
 * @param id  채점 제출 시 사용자가 고른 보기를 식별하는 값(userAnswer로 보냄)
 * @param seq 화면 표시 순서(1..N)
 */
public record QuizChoiceItem(
        Long id,
        int seq,
        String text
) {
    public static QuizChoiceItem from(Choice c) {
        return new QuizChoiceItem(c.getId(), c.getSeq(), c.getText());
    }
}
