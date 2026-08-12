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
 * @param documentSlug  이 문제의 근거가 된 개념 문서 slug — 화면이 "개념 문서 읽기" 링크를 건다(docs/15 3단계).
 *                      <b>실제로 존재하는 문서일 때만</b> 채워진다. 문제의 document_slug가 있어도
 *                      그 문서가 아직 검수 대기면 {@code null}이다 — 죽은 링크를 보여주지 않기 위해.
 *                      <p>해설과 <b>같은 자리</b>에 실리는 것이 중요하다. 이 문서는 문제의 출제 근거라
 *                      풀기 전에 보여주면 답을 알려주는 꼴이 된다(docs/03의 "해설은 채점 후에만" 규칙과 같은 이유)
 */
public record QuizSubmitResponse(
        Long problemId,
        boolean correct,
        String correctAnswer,
        String explanation,
        Long submissionId,
        String documentSlug
) {
}
