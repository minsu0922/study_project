package project.study.study_project.dailyquiz.dto;

import project.study.study_project.dailyquiz.domain.DailyQuiz;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 퀴즈 세트 응답 — API 스펙(docs/12 GET /api/me/daily-quiz).
 *
 * <h2>스트릭을 뺐다 (2026-08-29)</h2>
 *
 * <p>연속 완료 일수를 함께 내려주고 있었다. 뺀 이유는 기술이 아니라 <b>제품 판단</b>이다 —
 * 이 도구는 1인용이라 비교할 상대가 없고, 목표 페이스가 주 3회다. 주 3회를 지켜도 스트릭은
 * 매주 끊기므로, 잘하고 있는데 실패한 것처럼 보이는 숫자가 된다. <b>끊기는 순간이 곧 그만두는
 * 순간</b>이라는 것이 게임화 요소의 알려진 부작용이고, 여기서는 얻을 것이 없다.
 *
 * <p>계산 로직(calcStreak)과 그것이 쓰던 조회(findCompletedDatesDesc)까지 함께 지웠다.
 * 화면에서만 감추면 세트를 열 때마다 아무도 안 보는 값을 위해 조회가 한 번 더 나간다.
 */
public record DailyQuizResponse(
        LocalDate quizDate,
        boolean completed,
        Progress progress,
        List<DailyQuizItemResponse> items
) {
    /** 진행률 — solved는 저장된 플래그가 아니라 submission 연결 여부에서 파생된 개수다. */
    public record Progress(int total, int solved) {
    }

    public static DailyQuizResponse from(DailyQuiz quiz) {
        List<DailyQuizItemResponse> items = quiz.getItems().stream()
                .map(DailyQuizItemResponse::from)
                .toList();
        int solved = (int) items.stream().filter(DailyQuizItemResponse::solved).count();
        return new DailyQuizResponse(
                quiz.getQuizDate(),
                quiz.isCompleted(),
                new Progress(items.size(), solved),
                items
        );
    }
}
