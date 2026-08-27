package project.study.study_project.quiz.dto;

import java.util.List;

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
 * @param choices       보기별 정답 여부와 오답 설명 — 객관식에만 채워지고 그 외 유형은 빈 목록.
 *                      화면이 이걸로 "오답 분석"을 그린다({@code QuizChoiceResult}).
 *                      <p>{@code explanation}과 <b>역할이 갈린다</b>: 해설은 "왜 정답인가",
 *                      여기 실린 것은 "왜 그 오답이 틀렸는가"다. 옛 문제는 오답 설명이 통짜
 *                      해설 안에 녹아 있어 {@code rationale}이 전부 {@code null}로 오고,
 *                      화면은 그것을 보고 옛 형식으로 그린다(V15)
 */
public record QuizSubmitResponse(
        Long problemId,
        boolean correct,
        String correctAnswer,
        String explanation,
        Long submissionId,
        String documentSlug,
        List<QuizChoiceResult> choices
) {
}
