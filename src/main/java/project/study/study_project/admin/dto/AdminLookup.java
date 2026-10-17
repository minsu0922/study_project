package project.study.study_project.admin.dto;

import java.util.List;

/**
 * 상단 띠의 통합 검색 결과 — {@code GET /api/admin/lookup?q=}의 응답.
 *
 * <p>숫자면 그 번호의 문제와 문제 초안을 찾는다. 둘은 번호 체계가 따로라 같은 번호가 둘 다 있을 수 있다.
 * 글자면 각 목록 화면과 같은 조건으로 찾아 종류마다 앞의 몇 개만 준다. total은 그 목록 화면에서
 * 같은 검색어로 보게 될 전체 수다.
 */
public record AdminLookup(Section<Problem> problems, Section<Draft> drafts,
                          Section<Document> documents, Section<User> users) {

    public record Section<T>(List<T> items, long total) {

        public static <T> Section<T> empty() {
            return new Section<>(List.of(), 0);
        }
    }

    public record Problem(long id, String title) {
    }

    /** @param code 코드 문제 초안인가. 검수함이 어느 탭으로 열지 정한다 */
    public record Draft(long id, String status, boolean code, String title) {
    }

    /** @param hidden 내려 둔 문서인가. 관리 목록은 내려 둔 문서도 싣는다 */
    public record Document(long id, String title, boolean hidden) {
    }

    public record User(long id, String username, String nickname) {
    }
}
