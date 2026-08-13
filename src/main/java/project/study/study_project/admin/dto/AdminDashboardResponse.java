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
 * @param llmReview     AI 초안의 모델별 승인율 — "모델·프롬프트를 바꿨더니 나아졌나"를 보는 저울
 */
public record AdminDashboardResponse(
        Totals totals,
        List<MatrixCell> problemMatrix,
        List<DomainStat> domainStats,
        List<ProblemStat> problemStats,
        LlmReview llmReview
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

    /**
     * AI 초안 검수 현황 — 문제와 문서를 <b>나눠서</b> 담는다.
     *
     * <p>둘은 생성 빈도가 다르다(문제 하루 5개 vs 문서 나흘에 1편). 합치면 표본이 큰 문제 쪽
     * 숫자에 문서가 묻혀서, "모델을 바꿨더니 문서만 나빠졌다" 같은 판단을 할 수 없다.
     *
     * @param problems  문제 초안 — 모델별
     * @param documents 개념 문서 초안 — 모델별
     */
    public record LlmReview(List<ModelStat> problems, List<ModelStat> documents) {
    }

    /**
     * 한 모델이 만든 초안의 검수 결과.
     *
     * <p><b>이 표가 존재하는 이유</b>: {@code model} 컬럼은 처음부터 "모델을 바꿨을 때 품질이
     * 올랐는지 비교하려고" 기록해 왔고 설계 문서에도 <b>"측정 없이 바꾸지 않는다"</b>고 적어
     * 두었는데, 정작 승인율을 볼 수단이 없었다. 재료만 모으고 저울이 없던 셈이다.
     *
     * @param approvalRate 승인율(%) = 승인 ÷ (승인 + 거절). <b>아직 검수한 게 없으면 null</b> —
     *                     "0%(전부 거절당함)"와 "아직 아무도 안 봄"은 정반대 신호인데 0으로
     *                     뭉개면 구분할 수 없다({@link ProblemStat#correctRate}와 같은 규칙).
     *                     검수 대기(pending)는 분모에서 뺀다: 아직 판정이 안 난 것을
     *                     실패로 세면 부지런히 만들수록 승인율이 떨어지는 이상한 지표가 된다
     */
    public record ModelStat(String model, long pending, long approved, long rejected,
                            Integer approvalRate) {
    }
}
