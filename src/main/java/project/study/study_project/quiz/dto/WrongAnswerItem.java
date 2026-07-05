package project.study.study_project.quiz.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.time.LocalDateTime;

/**
 * 오답노트 항목 — API 스펙(docs/03 GET /api/me/wrong-answers).
 *
 * <p>오답노트는 "복습"이 목적이라 정답·해설을 <b>바로</b> 보여준다(채점을 이미 치른 문제이므로).
 *
 * <p>{@code myAnswer}/{@code correctAnswer}는 저장 원문이 아니라 <b>표시용 문자열</b>이다 —
 * 객관식 저장값은 choiceId(예 "2")인데 그대로 보여주면 무의미해서, 서비스가
 * AnswerDisplay 규칙으로 보기 text("물리 계층")로 바꿔서 넣는다. 변환에 도메인 규칙이
 * 필요해서 DTO 자체 from() 대신 서비스에서 조립한다.
 *
 * @param lastSubmittedAt 이 문제를 마지막으로 틀린 시각(재도전 이력 중 최신 오답 기준)
 */
public record WrongAnswerItem(
        Long problemId,
        Domain domain,
        Difficulty difficulty,
        ProblemType type,
        String question,
        String myAnswer,
        String correctAnswer,
        String explanation,
        LocalDateTime lastSubmittedAt
) {
}
