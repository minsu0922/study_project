package project.study.study_project.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리 콘솔 화면의 <b>구조</b>를 지키는 스모크 테스트 (2026-09-06 콘솔 개편에서 신설).
 *
 * <p>사용자 화면 쪽 {@link StaticPageStructureTest}와 같은 장치를 콘솔에도 둔다.
 * 그쪽에서 이 테스트가 실제로 세 번 사고를 잡았다 — 스크립트 로드 순서, 목록에 안 넣은
 * 새 화면, 테마 스크립트가 빠진 화면 하나. 콘솔도 화면 일곱을 전부 건드리므로 같은
 * 종류의 사고가 난다.
 *
 * <p><b>왜 파일만 읽나.</b> 확인하려는 것이 파일의 내용이지 실행 결과가 아니다.
 * 게다가 콘솔은 <b>서버가 출입증 쿠키로 막고 있어</b>({@code AdminGateFilter}) HTTP로
 * 열려면 로그인부터 해야 한다 — 구조를 보자고 그 절차를 태울 이유가 없다.
 *
 * <p><b>{@code topics.html}은 목록에 없다.</b> 그 화면은 meta refresh로 넘겨주기만 하는
 * 열몇 줄짜리 파일이고 스크립트가 없다. 규칙을 들이대면 전부 실패하는데, 실패가 맞는
 * 것이 아니라 <b>다른 종류의 파일</b>이다.
 *
 * <p><b>못 잡는 것</b>은 분명히 해 둔다: 실행 중 JS 오류, 레이아웃 깨짐, 대비 미달.
 * 그건 콘솔을 띄워 눈으로 본다. 이 테스트가 답하는 질문은 하나다 — "재료가 다 들어갔나".
 */
class AdminPageStructureTest {

    private static final Path ADMIN_DIR =
            Path.of("src", "main", "resources", "static", "admin");

    /** 넘겨주기 전용이라 규칙 밖에 둔다(위 주석 참고). */
    private static final String REDIRECT_ONLY = "topics.html";

    private static final List<String> ADMIN_PAGES = List.of(
            "index.html",
            "batch.html",
            "documents.html",
            "generate.html",
            "llm.html",
            "problems.html",
            "reports.html");

    @Test
    @DisplayName("모든 콘솔 화면이 한국어·모바일 대응·제목·공용 스타일을 갖춘다")
    void everyPageHasTheBasics() throws IOException {
        for (String page : ADMIN_PAGES) {
            String html = read(page);

            // lang이 없으면 스크린 리더가 이 화면을 영어로 읽는다
            assertThat(html).as("%s: lang=\"ko\"가 없다", page).contains("lang=\"ko\"");

            // 콘솔은 데스크톱에서 주로 쓰지만, 없으면 폰에서 데스크톱 폭으로 그린 뒤
            // 통째로 축소해 아예 못 쓰는 화면이 된다
            assertThat(html).as("%s: viewport meta가 없다", page).contains("name=\"viewport\"");

            assertThat(html).as("%s: <title>이 없다", page).contains("<title>");

            // 공용 스타일을 안 물면 그 화면만 디자인 토큰 밖에 남는다
            assertThat(html).as("%s: /css/style.css를 안 문다", page).contains("/css/style.css");
        }
    }

    @Test
    @DisplayName("모든 콘솔 화면이 api.js → shell.js → admin-common.js 순으로 싣는다")
    void scriptOrderIsKept() throws IOException {
        for (String page : ADMIN_PAGES) {
            String html = read(page);

            int api = html.indexOf("/js/api.js");
            int shell = html.indexOf("/js/shell.js");
            int common = html.indexOf("/admin/js/admin-common.js");

            assertThat(api).as("%s: api.js를 안 싣는다", page).isNotNegative();
            assertThat(shell).as("%s: shell.js를 안 싣는다", page).isNotNegative();
            assertThat(common).as("%s: admin-common.js를 안 싣는다", page).isNotNegative();

            // 순서가 곧 의존성이다. 번들러가 없어 전역 함수로 이어 붙이는 구조라
            // 사람이 지켜야 하는데, 사람이 지키는 규칙은 언젠가 깨진다.
            assertThat(shell).as("%s: shell.js는 api.js 뒤에 와야 한다", page).isGreaterThan(api);
            assertThat(common).as("%s: admin-common.js는 shell.js 뒤에 와야 한다", page)
                    .isGreaterThan(shell);
        }
    }

    /**
     * 폴더에 있는데 목록에 없는 화면(그리고 그 반대)을 잡는다.
     *
     * <p>규칙 검사는 목록에 든 것만 보므로, 목록을 갱신하지 않으면 새 화면이 조용히
     * 빠져나간다. 새 화면이야말로 규칙을 빠뜨리기 가장 쉬운 곳이다 — 기존 화면은
     * 복사해 만들지만 새 화면은 맨손으로 만든다.
     */
    @Test
    @DisplayName("admin 폴더의 모든 화면이 검사 목록에 들어 있다")
    void everyPageIsListed() throws IOException {
        try (Stream<Path> files = Files.list(ADMIN_DIR)) {
            List<String> found = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".html"))
                    .filter(name -> !name.equals(REDIRECT_ONLY))
                    .sorted()
                    .toList();

            assertThat(found)
                    .as("콘솔 화면을 새로 만들었다면 ADMIN_PAGES에도 넣어야 검사를 받는다")
                    .containsExactlyInAnyOrderElementsOf(ADMIN_PAGES);
        }
    }

    /** 파일이 없으면 "내용이 안 맞다"가 아니라 "파일이 없다"고 말하게 한다 — 원인이 다르다. */
    private String read(String page) throws IOException {
        Path path = ADMIN_DIR.resolve(page);
        assertThat(Files.exists(path)).as("%s 파일이 있어야 한다", page).isTrue();
        return Files.readString(path);
    }
}
