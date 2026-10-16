package project.study.study_project.admin.dto;

import java.util.List;

/**
 * 상단 띠의 통합 검색 결과 — {@code GET /api/admin/lookup?q=}의 응답.
 *
 * <p>숫자면 그 번호의 문제와 문제 초안을 찾는다. 둘은 번호 체계가 따로라 같은 번호가 둘 다 있을 수 있다.
 * 글자면 문제·초안의 제목과 지문, 사용자의 아이디와 닉네임에서 찾는다. 종류마다 최근 것부터 몇 개만 준다.
 */
public record AdminLookup(List<Problem> problems, List<Draft> drafts, List<User> users) {

    public record Problem(long id, String title) {
    }

    /** @param code 코드 문제 초안인가. 검수함이 어느 탭으로 열지 정한다 */
    public record Draft(long id, String status, boolean code, String title) {
    }

    public record User(long id, String username, String nickname) {
    }
}
