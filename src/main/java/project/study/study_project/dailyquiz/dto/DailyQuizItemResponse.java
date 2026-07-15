package project.study.study_project.dailyquiz.dto;

import project.study.study_project.dailyquiz.domain.DailyQuizItem;
import project.study.study_project.dailyquiz.domain.DailyQuizSource;
import project.study.study_project.quiz.dto.QuizProblemItem;

/**
 * 세트 항목 응답 — API 스펙(docs/12 GET /api/me/daily-quiz).
 *
 * @param correct 푼 항목만 값이 있고 미풀이면 null — "이어서 풀기" 화면에서 앞에서 뭘
 *                틀렸는지 표시용. boolean이 아니라 Boolean인 이유가 바로 이 3상태(정답/오답/미풀이).
 * @param problem 퀴즈 API의 {@link QuizProblemItem}을 그대로 재사용 — 정답·해설을 절대 포함하지
 *                않는 원칙(docs/03)도 함께 상속된다. 정답은 채점 API 응답에만 존재한다.
 */
public record DailyQuizItemResponse(
        int seq,
        DailyQuizSource source,
        boolean solved,
        Boolean correct,
        QuizProblemItem problem
) {
    public static DailyQuizItemResponse from(DailyQuizItem item) {
        boolean solved = item.isSolved();
        return new DailyQuizItemResponse(
                item.getSeq(),
                item.getSource(),
                solved,
                // LAZY인 submission은 풀린 항목만 접근한다(불필요한 로딩 방지 — QuizProblemItem.from의 choices와 같은 판단)
                solved ? item.getSubmission().isCorrect() : null,
                QuizProblemItem.from(item.getProblem())
        );
    }
}
