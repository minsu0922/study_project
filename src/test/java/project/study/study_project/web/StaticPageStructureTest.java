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
 * 사용자 화면 HTML의 <b>구조</b>를 지키는 스모크 테스트 (2026-09-06 화면 개편에서 신설).
 *
 * <h2>왜 이 테스트가 있나</h2>
 *
 * <p>이번 개편에서 사용자 화면 HTML을 <b>전부</b> 건드린다. 이런 작업에서 가장 흔한 사고는
 * 논리 오류가 아니라 <b>한 파일을 빠뜨리는 것</b>이다. script 태그 하나가 없으면 그 화면만
 * 조용히 죽는데, 나머지가 멀쩡하니 눈으로는 좀처럼 안 걸린다 — 열 개를 열어 보고 열한 번째를
 * 안 열어 보는 것이 사람이다.
 *
 * <p>가상의 걱정이 아니다. 이 저장소에서 포커스 링 규칙이 <b>문제 목록 화면에만</b> 있던 일이
 * 있었다(docs/19). 스펙에 "포커스 링을 지우지 않는다"고 적어 놓고, 정작 다른 화면에는 그
 * 규칙이 없다는 것을 아무도 보지 않았다. 사람이 매번 세는 대신 이 파일이 센다.
 *
 * <h2>왜 스프링도 브라우저도 안 띄우나</h2>
 *
 * <p>확인하려는 것이 <b>파일의 내용</b>이지 실행 결과가 아니다. 톰캣을 띄우면 몇 초가 들고
 * 브라우저를 띄우면 의존성과 CI 시간이 붙는데, 잡는 결함은 똑같다. 그래서 파일을 읽는다 —
 * 컨텍스트 없이 밀리초 단위로 끝난다.
 *
 * <p>{@code AdminGateRealServerTest}가 진짜 서버를 띄우면서 <b>"이 방식을 다른 테스트로
 * 넓히지 않는다"</b>고 적어 둔 것도 같은 판단이다. 그쪽은 서블릿의 ERROR 재진입을 봐야 해서
 * 컨테이너가 <em>원리상</em> 필요했다. 여기는 그런 이유가 없다.
 *
 * <p>대신 <b>못 잡는 것</b>이 있다는 점을 분명히 해 둔다: 실행 중에 나는 JS 오류, 레이아웃
 * 깨짐, 대비 미달. 그건 개발 중에 앱을 띄워 콘솔과 눈으로 본다. 이 테스트가 답하는 질문은
 * 하나다 — <b>"재료가 다 들어갔나"</b>.
 *
 * <h2>목록을 손으로 적고 <em>동시에</em> 검사한다</h2>
 *
 * <p>{@link #USER_PAGES}는 사람이 적는 목록이다. 그런데 목록만 있으면 화면을 새로 만들고
 * 목록에 안 넣는 순간 그 화면은 영원히 검사 밖이 된다 — 정작 새 화면이 규칙을 빠뜨리기
 * 가장 쉬운 곳인데. 그래서 {@link #everyPageIsListed()}가 <b>폴더를 훑어 목록과 대조</b>한다.
 * 새 HTML을 만들면 그 테스트가 먼저 빨개져서 목록에 넣으라고 말한다.
 *
 * <p>자동으로 폴더만 훑고 목록을 아예 없앨 수도 있었다. 그러지 않은 이유는 <b>파일이
 * 사라지는 것</b>도 사고이기 때문이다. 목록이 있으면 "있어야 할 화면이 없다"도 잡힌다.
 *
 * <h2>규칙은 나눠서 붙인다</h2>
 *
 * <p>지금 통과하는 규칙만 들어 있다. 셸(#shell)이나 테마 스크립트처럼 아직 만들지 않은 것의
 * 규칙은 <b>그것을 만드는 단계에서</b> 이 파일에 이어 붙인다. 미리 넣으면 빌드가 여러 커밋
 * 동안 빨갛고, 그러면 빨간불이 신호가 아니라 소음이 된다 — 소음이 된 빨간불은 아무도 안 본다.
 */
class StaticPageStructureTest {

    /**
     * 테스트의 작업 디렉터리는 Gradle 기본값인 프로젝트 루트다.
     * 절대 경로를 쓰지 않는 이유: 이 저장소는 CI(리눅스)에서도 돌고, 로컬 경로에 한글이 있다.
     */
    private static final Path STATIC_DIR = Path.of("src", "main", "resources", "static");

    /**
     * 검사 대상 — <b>사용자 화면만</b>이다.
     *
     * <p>관리 콘솔({@code admin/})은 개편 2단계라 아직 규칙이 다르다. 지금 함께 넣으면
     * 두 영역의 규칙이 섞여, 정작 콘솔을 옮길 때 "이 규칙이 누구 것이었나"를 되짚게 된다.
     */
    private static final List<String> USER_PAGES = List.of(
            "index.html",
            "daily.html",
            "document.html",
            "documents.html",
            "login.html",
            "problems.html",
            "quiz.html",
            "review.html",
            "signup.html",
            "wrong-answers.html");

    @Test
    @DisplayName("모든 사용자 화면이 한국어·모바일 대응·제목·공용 스타일을 갖춘다")
    void everyPageHasTheBasics() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            // lang이 없으면 스크린 리더가 이 화면을 영어로 읽는다. 한국어 화면에서 치명적이고,
            // 눈으로 보는 사람에게는 아무 티도 안 나서 영영 안 걸리는 종류의 결함이다.
            assertThat(html).as("%s: lang=\"ko\"가 없다", page)
                    .contains("lang=\"ko\"");

            // 이게 없으면 폰이 화면을 데스크톱 폭으로 그린 뒤 통째로 축소한다 —
            // 글자가 개미만 해지고, 모바일 우선으로 짠 CSS가 하나도 적용되지 않는다.
            assertThat(html).as("%s: viewport meta가 없다", page)
                    .contains("name=\"viewport\"");

            // 탭 제목. 여러 탭을 띄워 두고 오가는 화면이라 "무엇의 화면인지"가 제목에 있어야 한다.
            assertThat(html).as("%s: <title>이 없다", page)
                    .contains("<title>");

            // 공용 스타일을 안 물면 그 화면만 디자인 토큰 밖에 남는다 —
            // 다크 모드를 켰을 때 그 화면만 하얗게 뜬다.
            assertThat(html).as("%s: /css/style.css를 안 문다", page)
                    .contains("/css/style.css");
        }
    }

    @Test
    @DisplayName("모든 사용자 화면이 api.js를 싣고, 그 뒤에 shell.js를 싣는다")
    void everyPageLoadsApiThenShell() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            int api = html.indexOf("/js/api.js");
            int shell = html.indexOf("/js/shell.js");

            // 토큰 보관과 HTTP 호출이 여기 있다. 빠지면 그 화면은 로그인 상태조차 모른다.
            assertThat(api).as("%s: /js/api.js를 안 싣는다", page).isNotNegative();

            // 내비게이션이 여기 있다. 빠지면 그 화면만 메뉴가 통째로 사라진다.
            assertThat(shell).as("%s: /js/shell.js를 안 싣는다", page).isNotNegative();

            // 순서가 곧 의존성이다. 번들러가 없어 전역 함수로 이어 붙이는 구조라,
            // shell.js가 먼저 실행되면 그 안에서 부르는 escapeHtml·isAdmin·api가 아직 없다.
            // 사람이 지켜야 하는 규칙은 언젠가 깨지므로 이 줄이 대신 지킨다.
            assertThat(shell).as("%s: shell.js는 api.js 뒤에 와야 한다", page)
                    .isGreaterThan(api);
        }
    }

    /**
     * 폴더에 있는데 목록에 없는 화면(그리고 그 반대)을 잡는다.
     *
     * <p><b>이 테스트가 이 클래스의 핵심이다.</b> 위의 규칙들은 목록에 든 것만 검사하므로,
     * 목록을 갱신하지 않으면 새 화면이 조용히 빠져나간다. 새 화면이야말로 규칙을 빠뜨리기
     * 가장 쉬운 곳이다 — 기존 화면은 복사해 만들지만 새 화면은 맨손으로 만든다.
     */
    @Test
    @DisplayName("static 폴더의 모든 화면이 검사 목록에 들어 있다")
    void everyPageIsListed() throws IOException {
        try (Stream<Path> files = Files.list(STATIC_DIR)) {
            List<String> found = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".html"))
                    .sorted()
                    .toList();

            assertThat(found)
                    .as("화면을 새로 만들었다면 USER_PAGES에도 넣어야 위의 규칙 검사를 받는다")
                    .containsExactlyInAnyOrderElementsOf(USER_PAGES);
        }
    }

    /** 파일이 없으면 "내용이 안 맞다"가 아니라 "파일이 없다"고 말하게 한다 — 원인이 다르다. */
    private String read(String page) throws IOException {
        Path path = STATIC_DIR.resolve(page);
        assertThat(Files.exists(path)).as("%s 파일이 있어야 한다", page).isTrue();
        return Files.readString(path);
    }
}
