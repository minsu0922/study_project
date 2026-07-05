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
 * @param problemStats  문제별 제출 수·정답률 — 너무 어렵거나(정답률↓) 오류인 문제 발견용
 */
public record AdminDashboardResponse(
        Totals totals,
        List<MatrixCell> problemMatrix,
        List<ProblemStat> problemStats
) {
    public record Totals(long users, long documents, long problems, long submissions) {
    }

    public record MatrixCell(Domain domain, Difficulty difficulty, long count) {
    }

    /**
     * @param correctRate 정답률(%). 제출이 0건이면 null — "0%"(전부 틀림)와 "데이터 없음"은
     *                    다른 의미인데 0으로 뭉개면 구분할 수 없어서 null로 남긴다.
     */
    public record ProblemStat(Long problemId, String domain, String type, String question,
                              long attempts, long correctCount, Integer correctRate) {
    }
}
