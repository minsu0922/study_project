package project.study.study_project.dailyquiz.domain;

/**
 * 세트 항목이 어느 배합 칸으로 뽑혔는지 — {@code daily_quiz_item.source} 컬럼(docs/12).
 *
 * <p>저장하는 이유: 이 값은 조회 시점에 재계산할 수 없다 — "그날의 사다리 상태·정답률
 * 스냅숏"으로 뽑힌 결과이기 때문. 또한 나중에 "복습 문제를 잘 맞히나, 새 문제를 잘
 * 맞히나" 같은 배합 효과 분석의 재료가 된다(docs/12 재검토 트리거).
 */
public enum DailyQuizSource {
    /** 복습 칸 — 간격 사다리(docs/10)에서 오늘이 예정일인 문제. */
    REVIEW,
    /** 취약 칸 — 내 정답률이 낮은 도메인에서 뽑은 문제. */
    WEAK,
    /** 새 문제 칸 — 아직 한 번도 안 푼 문제. 앞 칸들의 부족분도 이 칸이 흡수한다. */
    NEW,
    /** 채움 칸 — 새 문제까지 바닥났을 때(문제 풀 고갈) 이미 푼 문제 중 무작위. */
    GENERAL
}
