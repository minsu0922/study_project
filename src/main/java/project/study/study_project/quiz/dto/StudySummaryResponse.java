package project.study.study_project.quiz.dto;

import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * 문제 목록 화면의 통계 카드 + 사이드바 진척 — 한 번에 내려준다(docs/18, 2026-08-29).
 *
 * <h2>왜 API를 둘로 쪼개지 않나</h2>
 *
 * <p>통계 카드와 사이드바는 <b>항상 함께</b> 뜬다. 화면을 여는 순간이 곧 둘 다 필요한 순간이라,
 * 나누면 클라이언트가 호출 두 개를 조립하고 로딩 상태도 두 벌 관리해야 한다. 재료도 같다 —
 * 둘 다 내 제출 이력에서 나온다.
 *
 * <p>목록({@code GET /api/problems})과는 갈라 둔다. 그쪽은 필터·쪽을 바꿀 때마다 다시 부르는데
 * 통계는 그때마다 바뀌지 않는다. 합쳐 두면 필터를 만질 때마다 집계 쿼리 넷이 같이 돈다.
 *
 * @param stats   통계 카드 넷
 * @param domains 분야별 진척 — <b>맞힌 개수만</b> 준다. 전체 문제 수(분모)를 함께 주지 않는
 *                이유는 배치가 매일 문제를 더해 분모가 커지기 때문이다. 어제 40%가 오늘 37%가
 *                되면 아무것도 잘못하지 않았는데 뒷걸음질친 것처럼 보인다
 */
public record StudySummaryResponse(
        Stats stats,
        List<DomainProgress> domains
) {

    /**
     * @param solvedTotal    맞힌 적 있는 문제 수(제출 건수가 아니라 문제 수)
     * @param correctRate    전체 제출 중 정답 비율(0~100). <b>제출이 없으면 null</b> —
     *                       0%(다 틀렸다)와 "아직 안 풀었다"는 정반대 신호다
     * @param solvedThisWeek 이번 주(월요일 0시부터) 맞힌 문제 수. 스트릭을 대신하는 값이다
     * @param reviewDue      지금 복습할 차례인 문제 수
     */
    public record Stats(
            long solvedTotal,
            Integer correctRate,
            long solvedThisWeek,
            long reviewDue
    ) {
    }

    /** 분야 한 줄. {@code label}을 서버가 함께 주는 것은 이 프로젝트의 기존 규칙이다. */
    public record DomainProgress(Domain domain, String label, long solved) {
    }
}
