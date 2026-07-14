package project.study.study_project.admin.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * 관리자 대시보드 응답 — 화면 한 번에 필요한 것을 하나의 API(/api/admin/dashboard)로 묶었다.
 * (현황판·통계를 API 3개로 쪼개면 화면이 요청 3번을 조립해야 해서, 사용처가 하나뿐인 MVP에선 과분리)
 *
 * @param totals        전체 개수 요약(회원/문서/문제/제출)
 * @param problemMatrix 도메인×난이도별 문제 수 — "어느 칸이 비었나"를 보는 출제 현황판
 * @param domainStats   도메인별 정답률 — DB 뷰 domain_stats(R__domain_stats_view.sql) 조회 결과.
 *                      문제 단위(problemStats)보다 한 단계 넓은 시야: "어느 영역이 약한가"
 * @param problemStats  문제별 제출 수·정답률 — 너무 어렵거나(정답률↓) 오류인 문제 발견용
 */
public record AdminDashboardResponse(
        Totals totals,
        List<MatrixCell> problemMatrix,
        List<DomainStat> domainStats,
        List<ProblemStat> problemStats
) {
    public record Totals(long users, long documents, long problems, long submissions) {
    }

    public record MatrixCell(Domain domain, Difficulty difficulty, long count) {
    }

    /**
     * 도메인별 정답률 — 뷰 domain_stats의 한 행을 그대로 옮긴 것.
     * 집계 로직(JOIN·GROUP BY·정답률 계산)은 전부 뷰가 갖고 있고 자바는 매핑만 한다 —
     * 같은 통계를 앱/DB콘솔/관리도구 어디서든 동일하게 보기 위한 설계(뷰 파일 주석 참고).
     *
     * <p>제출이 0건인 도메인은 뷰(INNER JOIN)에 아예 등장하지 않는다 — problemStats의
     * "0%와 데이터 없음 구분" 문제가 여기선 행의 부재로 자연히 표현된다.
     *
     * @param accuracyPct 정답률(%) — 뷰에서 ROUND(AVG(is_correct)*100, 1)로 계산된 값
     */
    public record DomainStat(String domain, long submissionCount, long correctCount,
                             double accuracyPct, long attemptedProblemCount, long solverCount) {
    }

    /**
     * @param correctRate 정답률(%). 제출이 0건이면 null — "0%"(전부 틀림)와 "데이터 없음"은
     *                    다른 의미인데 0으로 뭉개면 구분할 수 없어서 null로 남긴다.
     */
    public record ProblemStat(Long problemId, String domain, String type, String question,
                              long attempts, long correctCount, Integer correctRate) {
    }
}
