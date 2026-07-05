package project.study.study_project.quiz.dto;

/**
 * 답안 제출(채점) 응답 — API 스펙(docs/03 POST /api/quiz/submit).
 *
 * <p>여기서 처음으로 정답·해설이 노출된다. 풀이용 조회(GET /api/quiz)에서는 절대 반환하지 않고,
 * "제출이라는 대가를 치른 뒤에만" 보여주는 것이 퀴즈의 규칙.
 *
 * @param correctAnswer 사람이 읽는 정답 표기 — 객관식=정답 보기의 text, OX="O"/"X",
 *                      단답형=대표 정답(복수 정답 중 첫 {@code |} 토큰) (docs/03)
 * @param submissionId  저장된 제출 이력 id — 오답노트의 원천 데이터(ADR-0002)
 */
public record QuizSubmitResponse(
        Long problemId,
        boolean correct,
        String correctAnswer,
        String explanation,
        Long submissionId
) {
}
