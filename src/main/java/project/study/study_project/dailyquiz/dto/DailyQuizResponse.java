package project.study.study_project.dailyquiz.dto;

import project.study.study_project.dailyquiz.domain.DailyQuiz;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 퀴즈 세트 응답 — API 스펙(docs/12 GET /api/me/daily-quiz).
 *
 * <p>스트릭이 세트 응답에 포함되는 이유: 홈 카드가 "3/10 완료 · 🔥 5일째"를 한 화면에
 * 그리는데, 이걸 API 2개로 쪼개면 클라이언트가 호출을 조립해야 한다. 세트를 조회하는
 * 순간이 곧 스트릭이 필요한 순간이라 함께 내려준다(별도 스트릭 API 없음).
 *
 * @param streak 연속 완료 일수. 오늘 미완료여도 어제까지 이어져 있으면 살아 있는 값(docs/12)
 */
public record DailyQuizResponse(
        LocalDate quizDate,
        boolean completed,
        int streak,
        Progress progress,
        List<DailyQuizItemResponse> items
) {
    /** 진행률 — solved는 저장된 플래그가 아니라 submission 연결 여부에서 파생된 개수다. */
    public record Progress(int total, int solved) {
    }

    public static DailyQuizResponse from(DailyQuiz quiz, int streak) {
        List<DailyQuizItemResponse> items = quiz.getItems().stream()
                .map(DailyQuizItemResponse::from)
                .toList();
        int solved = (int) items.stream().filter(DailyQuizItemResponse::solved).count();
        return new DailyQuizResponse(
                quiz.getQuizDate(),
                quiz.isCompleted(),
                streak,
                new Progress(items.size(), solved),
                items
        );
    }
}
