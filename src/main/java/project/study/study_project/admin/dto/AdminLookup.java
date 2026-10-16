package project.study.study_project.admin.dto;

/**
 * 상단 띠의 "번호로 이동" 결과 — {@code GET /api/admin/lookup?id=}의 응답(2026-10-16).
 *
 * <p>문제와 문제 초안은 번호 체계가 따로라 같은 번호가 둘 다 있을 수 있다. 그래서 둘을 다 찾아
 * 돌려주고, 둘 다 있으면 화면이 고르게 한다.
 *
 * @param problem 그 번호의 정식 문제. 없으면 {@code null}
 * @param draft   그 번호의 문제 초안. 없으면 {@code null}
 */
public record AdminLookup(Problem problem, Draft draft) {

    public record Problem(long id, String title) {
    }

    /** @param code 코드 문제 초안인가. 검수함이 어느 탭으로 열지 정한다 */
    public record Draft(long id, String status, boolean code, String title) {
    }
}
