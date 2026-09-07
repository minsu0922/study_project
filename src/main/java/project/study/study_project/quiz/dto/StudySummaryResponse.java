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
 * @param domains 분야별 진척 — 맞힌 개수와 그 분야의 전체 문제 수(분모).
 *
 *                <p><b>분모는 원래 일부러 주지 않았다.</b> 배치가 매일 문제를 더해 분모가
 *                커지므로, 어제 40%가 오늘 37%가 되면 아무것도 잘못하지 않았는데
 *                뒷걸음질친 것처럼 보이기 때문이다.
 *
 *                <p><b>2026-09-06 화면 개편에서 뒤집었다.</b> 분야별 진도를 막대로 보여
 *                주기로 했는데, 막대는 분모 없이는 그릴 수 없다. 숫자만 늘어놓으면
 *                40개 중 31개와 33개 중 9개가 같은 무게로 읽힌다.
 *
 *                <p>위 걱정은 여전히 맞아서 <b>화면에서</b> 눌렀다 — 주인공은 절대값
 *                (31 / 40)이고 막대는 그 아래 얇은 보조이며, 퍼센트 숫자는 아예 쓰지 않는다.
 *                문제 목록 개편에서 정한 "게이지 → 절대값"과 같은 방향이다.
 */
public record StudySummaryResponse(
        Stats stats,
        List<DomainProgress> domains
) {

    /**
     * <h2>attemptedTotal과 solvedTotal은 <b>다른 질문</b>에 답한다 (2026-09-07)</h2>
     *
     * <p>"얼마나 했나"와 "얼마나 아나"다. 예전에는 뒤의 값 하나뿐이었고, 화면이 그것을
     * "푼 문제"라는 <b>앞의 이름</b>으로 쓰고 있었다. 그래서 한 문제를 풀고 틀린 사람에게
     * 오늘의 퀴즈는 "1 / 10"이라 하고 홈은 "첫 문제를 풀어볼까요"라 했다.
     * 틀린 것이 안 푼 것이 되어, 방금 한 일이 없던 일이 됐다.
     *
     * <p>값이 틀렸던 것이 아니라 이름이 두 뜻을 감당하고 있었다. 하나를 더 만들어 가른다.
     *
     * @param attemptedTotal 제출한 적 있는 문제 수. <b>맞았든 틀렸든</b> 센다 —
     *                       "얼마나 했나"에 답하는 값이라 채점 결과와 무관하다
     * @param solvedTotal    맞힌 적 있는 문제 수(제출 건수가 아니라 문제 수).
     *                       분야별 진도가 쓰는 값이다
     * @param correctRate    전체 제출 중 정답 비율(0~100). <b>제출이 없으면 null</b> —
     *                       0%(다 틀렸다)와 "아직 안 풀었다"는 정반대 신호다
     * @param solvedThisWeek 이번 주(월요일 0시부터) 맞힌 문제 수. 스트릭을 대신하는 값이다
     * @param reviewDue      지금 복습할 차례인 문제 수
     */
    public record Stats(
            long attemptedTotal,
            long solvedTotal,
            Integer correctRate,
            long solvedThisWeek,
            long reviewDue
    ) {
    }

    /**
     * 분야 한 줄. {@code label}을 서버가 함께 주는 것은 이 프로젝트의 기존 규칙이다.
     *
     * @param solved 내가 맞힌 적 있는 문제 수
     * @param total  그 분야의 전체 문제 수. 배치가 문제를 더하면 <b>커진다</b> —
     *               그래서 화면은 이 둘로 퍼센트를 만들지 않는다(위 주석 참고)
     */
    public record DomainProgress(Domain domain, String label, long solved, long total) {
    }
}
