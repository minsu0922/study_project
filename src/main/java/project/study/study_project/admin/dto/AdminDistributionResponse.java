package project.study.study_project.admin.dto;

import java.util.List;

/**
 * 출제 분포 화면 — 학습자에게 <b>보이는</b> 문제 기준으로 센다. 내린 문제는 따로 센다.
 *
 * @param batchDomains           배치가 채우는 분야. 이 밖의 분야는 빈 칸으로 세지 않는다
 * @param plannedCells           배치가 채우는 칸 수 = 배치 분야 수 × 난이도 3
 * @param emptyCells             그중 공개 문제가 하나도 없는 칸 수
 * @param recentDays             {@link Cell#recent}를 센 기간(일)
 * @param cells                  분야 × 난이도 칸
 * @param types                  분야 × 유형별 공개 문제 수
 * @param documents              문서마다 그 문서를 근거로 한 문제 수. 공개 문제가 적은 순
 * @param noDocumentProblems     근거 문서가 없는 공개 문제 수(코드 문제·직접 등록 등). 이상이 아니다
 * @param missingDocumentProblems 근거 slug가 있는데 그 문서가 없는 공개 문제 수. 문서 slug를 바꾸면 생긴다
 */
public record AdminDistributionResponse(
        List<String> batchDomains,
        int plannedCells,
        int emptyCells,
        int recentDays,
        List<Cell> cells,
        List<TypeCount> types,
        List<DocumentCount> documents,
        long noDocumentProblems,
        long missingDocumentProblems
) {

    /** @param recent 최근 {@code recentDays}일 안에 등록된 공개 문제 수 */
    public record Cell(String domain, String difficulty, long visible, long hidden, long recent) {
    }

    public record TypeCount(String domain, String type, long count) {
    }

    /** @param documentHidden 문서 자체를 내렸는지 */
    public record DocumentCount(String slug, String title, String domain, boolean documentHidden,
                                long visible, long hidden) {
    }
}
