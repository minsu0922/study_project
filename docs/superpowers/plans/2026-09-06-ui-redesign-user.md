# 화면 전면 개편 — 사용자 화면 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** csquiz의 사용자 화면 13개를 새 셸(사이드바/탭바)·새 토큰(라이트+다크)로 다시 짓고, 없던 화면 셋(랜딩·내 기록·설정)을 만든다.

**Architecture:** 빌드 도구 없는 정적 HTML/JS를 유지한다. 셸 마크업은 `js/shell.js` 하나가 그리고, 각 HTML은 본문만 쓴다. 색·타이포·간격은 전부 CSS 변수로 두고 라이트/다크 두 벌을 선언한다. 화면이 안 바뀌는 작업(토큰 선언, 코드 분리, 서버 필드)을 먼저 쌓고 그 위에서 화면을 하나씩 옮긴다.

**Tech Stack:** Spring Boot 3.4.1 / Java / 순수 CSS / 바닐라 JS / Gradle (`.\gradlew.bat`)

**Spec:** `docs/superpowers/specs/2026-09-06-ui-redesign-design.md`

## Global Constraints

- **프런트엔드 테스트 도구를 새로 들이지 않는다.** `package.json`도 JS 테스트 러너도 없고, 넣으면 "빌드 도구 없는 정적 HTML"이라는 전제가 흔들린다. 대신 세 겹으로 본다.
  1. **서버를 건드리는 Task 4·5는 TDD** — 기존 JUnit·MockMvc 그대로
  2. **화면 구조는 스모크 테스트(Task 1B)** — 스프링도 브라우저도 안 띄우고 HTML 파일을 읽어 규칙을 확인한다. 의존성 0개. HTML 13개를 전부 건드리는 작업이라 script 태그 하나 빠뜨린 화면이 조용히 죽는 것을 막는다
  3. **눈으로 보는 것은 앱을 띄워서** — 375/768/1280 세 폭. 브라우저 자동화 도구가 붙어 있으면 스크린샷과 콘솔 오류 확인에 쓴다(저장소에는 안 남긴다)
- **시각 회귀 테스트(스크린샷 비교)는 넣지 않는다.** 모든 화면이 바뀌는 개편이라 기준 이미지를 매 커밋 갱신하게 되고, 그러면 잡아주는 것이 없다.
- 화면 확인 폭은 **375px · 768px · 1280px** 셋. 매 화면 작업마다 셋 다 본다.
- **브라우저 콘솔 오류 0**을 유지한다. 지금 0인 상태다.
- 대비는 **WCAG AA** — 본문 4.5:1, 큰 글자(18.66px 이상 또는 굵은 14px 이상) 3:1. 라이트·다크 양쪽에서 잰다.
- **클래스 이름을 바꾸지 않는다.** 기존 `.card` `.badge` `.grid` `.meta` `.btn` 등은 그대로 두고 값만 바꾼다. 이름을 바꾸면 HTML 20개를 같이 고쳐야 한다.
- 그림자는 카드에 쓰지 않는다(docs/19 결정). `--overlay-shadow`만 진짜 떠 있는 것에.
- 색만으로 정보를 전하지 않는다. 난이도 배지는 글자를, 정답/오답은 ✓/✕를 함께 둔다.
- 주석은 **"왜 이렇게 했는지"**를 남긴다. 설계 의도·트레이드오프·버린 대안. 프로젝트 규칙이다.
- 커밋 메시지 끝에 다음 두 줄을 붙인다.
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
  ```
- **`git push`는 하지 않는다.** 커밋까지만.

## 앱 띄우고 확인하는 법 (모든 화면 Task에서 씀)

```powershell
.\gradlew.bat bootRun --console=plain
```

브라우저에서 `http://localhost:8080`. 확인이 끝나면 Ctrl+C로 끈다.
DevTools를 열고 **Console 탭에 빨간 줄이 없는지**, **Toggle device toolbar(Ctrl+Shift+M)로 375 / 768 / 1280** 세 폭을 본다.

로그인이 필요하면 관리자 계정 `admin`으로 들어간다.

## 파일 구조

| 파일 | 책임 | 상태 |
| --- | --- | --- |
| `static/css/style.css` | 토큰 두 벌 + 공통 컴포넌트 + 셸 | 수정 |
| `static/js/api.js` | 토큰 보관 · HTTP · 응답 봉투 · 라벨/포맷 헬퍼 **만** | 수정(화면 코드 빠짐) |
| `static/js/shell.js` | 셸 마크업 · 활성 메뉴 · 테마 · 복습 배지 · 로그아웃 | **신규** |
| `static/js/player.js` | 문제 풀이 진행 | 수정 |
| `static/index.html` | 오늘(로그인) + 랜딩(비로그인) | 수정 |
| `static/me.html` | 내 기록 | **신규** |
| `static/settings.html` | 설정 | **신규** |
| `quiz/dto/StudySummaryResponse.java` | 요약 응답 | 수정(`total` 추가) |
| `quiz/service/ProblemListService.java` | 요약 집계 | 수정 |
| `quiz/repository/ProblemRepository.java` | 분야별 전체 개수 쿼리 | 수정 |
| `quiz/controller/PublicStatsController.java` | 공개 집계 | **신규** |
| `quiz/dto/PublicStatsResponse.java` | 공개 집계 응답 | **신규** |
| `test/.../web/StaticPageStructureTest.java` | 화면 구조 스모크 테스트 | **신규** |

---

## Task 1: 토큰 두 벌과 눈금 선언

값을 선언만 하고 아무도 안 쓴다. **이 Task가 끝나도 화면은 1픽셀도 안 바뀐다.**
먼저 이렇게 하는 이유: 뒤에서 화면이 깨졌을 때 "토큰 탓인가 마크업 탓인가"를 안 물어도 되게.

**Files:**
- Modify: `src/main/resources/static/css/style.css` (`:root` 블록, 파일 32~110줄 부근)

**Interfaces:**
- Consumes: 없음
- Produces: CSS 변수 — `--bg --card --fill --border --border-strong --text --text-2 --muted --primary --primary-dark --primary-soft --primary-border --review --review-soft --correct --wrong --correct-bg --wrong-bg --lv1-bg --lv1-fg --lv1-solid --lv2-* --lv3-* --radius --radius-lg --overlay-shadow` + 신규 `--fs-1..6 --sp-1..6`

- [ ] **Step 1: 기존 `:root` 뒤에 다크 블록과 눈금을 더한다**

기존 `:root` 안의 값은 **손대지 않는다**. 아래 두 가지만 더한다.
(1) `:root` 끝에 `--primary-border`와 눈금 변수, (2) `:root` 블록 **뒤에** 다크 두 블록.

`:root` 블록 끝(`--radius-lg: 12px;` 다음 줄)에 이어 붙인다:

```css
  /* 옅은 강조 판의 테두리. 홈 히어로가 --primary-soft 바탕 + 이 테두리로 선다.
     지금까지는 이 자리에 쓸 이름이 없어 컴포넌트마다 #c7d2fe를 직접 적었다 —
     이름이 없으면 다크에서 고칠 자리를 찾지 못한다. */
  --primary-border: #c7d2fe;

  /* ── 타이포 눈금 6단 ────────────────────────────────────────────────
   * 지금까지는 화면마다 임의값(14·16·18·22…)이 섞여 있었다. 눈금이 있으면
   * "이건 왜 16인가"를 매번 정하지 않아도 되고, 다른 화면과 저절로 맞는다.
   * 6단인 이유: 보조글자 / 본문 / 조금 큰 본문 / 소제목 / 화면 제목 / 랜딩 제목.
   * 일곱 번째가 필요해지면 그건 대개 눈금이 모자란 게 아니라 위계가 꼬인 것이다. */
  --fs-1: 11px;   /* 보조 설명, 메타 */
  --fs-2: 13px;   /* 본문 */
  --fs-3: 15px;   /* 조금 큰 본문, 목록 제목 */
  --fs-4: 17px;   /* 문제 지문, 소제목 */
  --fs-5: 21px;   /* 화면 제목 */
  --fs-6: 30px;   /* 랜딩 큰 제목 */

  /* ── 간격 눈금 6단 (4의 배수) ──────────────────────────────────────
   * 4의 배수로 묶는 이유: 값이 자유로우면 6과 7이 섞여 눈으로는 안 보이는데
   * 코드에서는 다른 값이 된다. 나중에 "이 둘을 맞춰라"가 불가능해진다. */
  --sp-1: 4px;  --sp-2: 8px;  --sp-3: 12px;
  --sp-4: 16px; --sp-5: 24px; --sp-6: 40px;
```

그리고 `:root` 블록이 **닫힌 뒤**에 다크 두 벌을 붙인다:

```css
/* ═════════════════════════════════════════════════════════════════════
 * 다크 모드 — 라이트의 색을 뒤집는 게 아니라 <따로 정한 한 벌>이다.
 *
 * [왜 두 블록인가] 테마는 세 상태다.
 *   1) 기기 설정을 따름(기본)  2) 라이트 고정  3) 다크 고정
 * 아래 첫 블록이 1번, 둘째 블록이 3번을 맡는다. 2번은 :root의 원래 값이 그대로 이긴다 —
 * 첫 블록에 :not([data-theme="light"])가 붙어 있어서다. 이게 없으면 기기가 다크인 사람이
 * "라이트"를 골라도 다크가 이긴다.
 *
 * [바탕이 왜 순수한 검정이 아닌가] #0b1120은 파란빛이 도는 어둠이다. 라이트의 중립색이
 * 이미 slate(파란빛 회색) 계열이라 같은 눈금을 아래로 이은 것이고, 브랜드 인디고와 온도가
 * 같아야 카드 위의 버튼이 화면에서 떠 보이지 않는다(라이트에서 이미 한 판단).
 *
 * [--primary가 밝아지는 것이 핵심] #4f46e5를 어두운 바탕에 그대로 쓰면 대비가 3:1도
 * 안 나와 버튼 글자가 안 읽힌다. 다크의 주 버튼은 <밝은 인디고 바탕 + 어두운 글자>다.
 *
 * [보조 글자 둘은 값이 다르다] 라이트의 --muted(#64748b)를 #0b1120 위에 쓰면 3.96:1로
 * AA 미달이다. 실제로 재 보고 --text-2는 #cbd5e1(12.8:1), --muted는 #94a3b8(7.4:1)로
 * 올렸다. 역할 이름은 같고 값만 다르다 — docs/19에서 스펙 값을 그대로 옮겼다가
 * 같은 자리에서 한 번 걸렸다.
 * ═══════════════════════════════════════════════════════════════════ */
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #0b1120;
    --card: #151d2e;
    --fill: #1e293b;
    --border: #293548;
    --border-strong: #3b4a63;
    --text: #e2e8f0;
    --text-2: #cbd5e1;
    --muted: #94a3b8;

    --primary: #818cf8;
    --primary-dark: #a5b4fc;
    --primary-soft: #1e1b4b;
    --primary-border: #3730a3;

    --review: #fbbf24;
    --review-soft: #78350f;

    --correct: #34d399;
    --wrong: #f87171;
    --correct-bg: #064e3b;
    --wrong-bg: #7f1d1d;

    --lv1-bg: #064e3b; --lv1-fg: #34d399; --lv1-solid: #34d399;
    --lv2-bg: #78350f; --lv2-fg: #fbbf24; --lv2-solid: #fbbf24;
    --lv3-bg: #7f1d1d; --lv3-fg: #f87171; --lv3-solid: #f87171;

    --overlay-shadow: 0 8px 24px rgba(0, 0, 0, .45);
  }
}

/* 수동으로 다크를 고른 경우 — 기기 설정이 라이트여도 이긴다.
   위 블록과 값이 같지만 합칠 수 없다: 미디어 쿼리 안에 있으면 기기가 라이트인 사람에게
   영원히 적용되지 않는다. 값이 두 벌이 되는 대가로 세 상태가 정확해진다. */
:root[data-theme="dark"] {
  --bg: #0b1120;
  --card: #151d2e;
  --fill: #1e293b;
  --border: #293548;
  --border-strong: #3b4a63;
  --text: #e2e8f0;
  --text-2: #cbd5e1;
  --muted: #94a3b8;

  --primary: #818cf8;
  --primary-dark: #a5b4fc;
  --primary-soft: #1e1b4b;
  --primary-border: #3730a3;

  --review: #fbbf24;
  --review-soft: #78350f;

  --correct: #34d399;
  --wrong: #f87171;
  --correct-bg: #064e3b;
  --wrong-bg: #7f1d1d;

  --lv1-bg: #064e3b; --lv1-fg: #34d399; --lv1-solid: #34d399;
  --lv2-bg: #78350f; --lv2-fg: #fbbf24; --lv2-solid: #fbbf24;
  --lv3-bg: #7f1d1d; --lv3-fg: #f87171; --lv3-solid: #f87171;

  --overlay-shadow: 0 8px 24px rgba(0, 0, 0, .45);
}

/* 색 구성표를 브라우저에 알린다 — 스크롤바·기본 폼 컨트롤이 같이 어두워진다.
   이게 없으면 다크 화면에 흰 스크롤바가 남는다. */
:root { color-scheme: light; }
:root[data-theme="dark"] { color-scheme: dark; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) { color-scheme: dark; }
}
```

- [ ] **Step 2: 화면이 안 바뀌었는지 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

`http://localhost:8080` 을 열고 홈·문제 목록·퀴즈를 훑는다.
**기대: 지금과 똑같다.** 아직 아무도 새 변수를 안 쓰고, 다크 블록은 `data-theme`이 없고
기기 설정이 라이트면 적용되지 않는다.

기기가 다크 모드라면 화면 색이 바뀌어 보일 수 있다 — 그건 정상이고 다음 Task들에서
컴포넌트가 변수를 쓰게 되면서 맞춰진다. 확인만 하고 넘어간다.

- [ ] **Step 3: 커밋**

```powershell
git add src/main/resources/static/css/style.css
git commit -m @'
style(tokens): 다크 한 벌과 타이포·간격 눈금을 선언한다

값을 선언만 하고 아직 아무도 쓰지 않는다. 화면은 1픽셀도 안 바뀐다.
docs/19에서 "화면이 변하지 않는 것부터 쌓는다"가 통했던 순서를 따른다 —
뒤에서 화면이 깨졌을 때 토큰 탓인지 마크업 탓인지 묻지 않아도 된다.

다크는 라이트를 뒤집은 게 아니라 따로 정한 한 벌이다. --primary가
밝아지는 것이 핵심인데, #4f46e5를 어두운 바탕에 그대로 쓰면 대비가
3:1도 안 나와 버튼 글자가 안 읽힌다.

보조 글자색 둘은 실제로 재 보고 올렸다. 라이트의 #64748b는 #0b1120
위에서 3.96:1로 AA 미달이다. docs/19에서 스펙 값을 그대로 옮겼다가
같은 자리에서 한 번 걸린 적이 있다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 1B: 화면 구조 스모크 테스트

**번호가 1B인 이유:** 뒤 Task들의 번호를 그대로 두려는 것이다. 이 Task는 나중에 끼워
넣기로 한 것이라, 2~12를 한 칸씩 밀면 문서 곳곳의 "Task 5(랜딩)" 같은 참조가 전부
어긋난다. 순서상 두 번째로 한다.

**지금 통과하는 규칙만 넣는다.** 셸·테마처럼 아직 안 만든 것의 규칙은 그것을 만드는
Task에서 이 테스트에 이어 붙인다 — 그래야 빌드가 여러 커밋 동안 빨갛지 않다.

**Files:**
- Create: `src/test/java/project/study/study_project/web/StaticPageStructureTest.java`

**Interfaces:**
- Consumes: 없음 (스프링 컨텍스트를 안 띄운다)
- Produces: `USER_PAGES` 상수 — Task 2·3·12가 규칙을 더할 때 이 목록을 그대로 쓴다

- [ ] **Step 1: 테스트를 쓴다**

```java
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
 * 사용자 화면 HTML의 <b>구조</b>를 지키는 스모크 테스트.
 *
 * <h2>왜 이 테스트가 있나</h2>
 *
 * <p>2026-09-06 화면 개편에서 HTML 13개를 <b>전부</b> 건드린다. 이런 작업에서 가장 흔한
 * 사고는 논리 오류가 아니라 <b>한 파일을 빠뜨리는 것</b>이다 — script 태그 하나가 없으면
 * 그 화면만 조용히 죽는데, 나머지 열두 개가 멀쩡하니 눈으로는 좀처럼 안 걸린다.
 * 실제로 이 저장소에서 포커스 링 규칙이 한 화면에만 있던 일이 있었다(docs/19).
 *
 * <h2>왜 스프링도 브라우저도 안 띄우나</h2>
 *
 * <p>확인하려는 것이 <b>파일의 내용</b>이지 실행 결과가 아니다. 톰캣을 띄우면 몇 초가 들고,
 * 브라우저를 띄우면 의존성과 CI 시간이 붙는데, 잡는 결함은 똑같다.
 * {@code AdminGateRealServerTest}가 진짜 서버를 띄우면서 "이 방식을 다른 테스트로
 * 넓히지 않는다"고 적어 둔 것도 같은 판단이다.
 *
 * <p>대신 <b>못 잡는 것</b>이 있다: 실행 중에 나는 JS 오류. 그건 개발 중에 앱을 띄워
 * 콘솔로 본다. 이 테스트는 "재료가 다 들어갔나"만 본다.
 *
 * <h2>목록을 손으로 적고 <em>동시에</em> 검사한다</h2>
 *
 * <p>{@link #USER_PAGES}는 사람이 적는 목록이다. 그런데 목록만 있으면 화면을 새로
 * 만들고 목록에 안 넣는 순간 그 화면은 영원히 검사 밖이 된다 — 정작 새 화면이 가장
 * 위험한데. 그래서 {@link #everyPageIsListed()}가 <b>폴더를 훑어 목록과 대조</b>한다.
 * 새 HTML을 만들면 그 테스트가 먼저 빨개져서 목록에 넣으라고 말한다.
 */
class StaticPageStructureTest {

    private static final Path STATIC_DIR = Path.of("src", "main", "resources", "static");

    /** 사용자 화면. 관리 콘솔(admin/)은 개편 2단계라 여기 없다. */
    private static final List<String> USER_PAGES = List.of(
            "index.html", "daily.html", "document.html", "documents.html",
            "login.html", "problems.html", "quiz.html", "review.html",
            "signup.html", "wrong-answers.html");

    @Test
    @DisplayName("모든 사용자 화면이 한국어·모바일 대응·제목·공용 스타일을 갖춘다")
    void everyPageHasTheBasics() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            // lang이 없으면 스크린 리더가 영어로 읽는다. 한국어 화면에서 치명적이다
            assertThat(html).as("%s: lang=\"ko\"", page).contains("lang=\"ko\"");

            // 이게 없으면 폰이 데스크톱 폭으로 그린 뒤 축소한다 — 모바일 우선 설계가 통째로 무의미해진다
            assertThat(html).as("%s: viewport meta", page)
                    .contains("name=\"viewport\"");

            // 탭 제목. 여러 탭을 열어 두는 화면이라 "무엇의 화면인지"가 제목에 있어야 한다
            assertThat(html).as("%s: <title>", page).contains("<title>");

            // 공용 스타일을 안 물면 그 화면만 토큰 밖에 남는다
            assertThat(html).as("%s: style.css", page).contains("/css/style.css");
        }
    }

    @Test
    @DisplayName("모든 사용자 화면이 api.js를 싣는다")
    void everyPageLoadsApiJs() throws IOException {
        for (String page : USER_PAGES) {
            assertThat(read(page)).as("%s: api.js", page).contains("/js/api.js");
        }
    }

    /**
     * 폴더에 있는데 목록에 없는 화면을 잡는다.
     *
     * <p>이 테스트가 이 클래스의 핵심이다. 위의 규칙들은 목록에 든 것만 검사하므로,
     * 목록을 갱신하지 않으면 새 화면이 조용히 빠져나간다. 새 화면이야말로 규칙을
     * 빠뜨리기 가장 쉬운 곳이다.
     */
    @Test
    @DisplayName("static 폴더의 모든 화면이 검사 목록에 들어 있다")
    void everyPageIsListed() throws IOException {
        try (Stream<Path> files = Files.list(STATIC_DIR)) {
            List<String> found = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".html"))
                    .sorted()
                    .toList();

            assertThat(found)
                    .as("새 화면을 만들었다면 USER_PAGES에도 넣어야 이 검사를 받는다")
                    .containsExactlyInAnyOrderElementsOf(USER_PAGES);
        }
    }

    private String read(String page) throws IOException {
        Path path = STATIC_DIR.resolve(page);
        assertThat(Files.exists(path)).as("%s 파일이 있어야 한다", page).isTrue();
        return Files.readString(path);
    }
}
```

- [ ] **Step 2: 테스트를 돌려 통과를 확인한다**

```powershell
.\gradlew.bat test --tests "*StaticPageStructureTest*" --console=plain
```

기대: **PASS 3건.** 지금 상태를 그대로 적은 규칙이라 처음부터 초록이다.

빨간불이 나오면 그건 **테스트가 틀린 게 아니라 화면 하나가 이미 규칙을 어기고 있는 것**이다.
어느 화면인지 메시지에 나온다 — 그 화면을 고친다(규칙을 낮추지 않는다).

- [ ] **Step 3: 일부러 깨뜨려 본다**

테스트가 진짜 잡는지 확인한다. `login.html`에서 `<script src="/js/api.js"></script>` 줄을
잠깐 지우고:

```powershell
.\gradlew.bat test --tests "*StaticPageStructureTest*" --console=plain
```

기대: **실패**, 메시지에 `login.html: api.js`가 뜬다.
**확인했으면 지운 줄을 되돌린다.**

- [ ] **Step 4: 전체 테스트**

```powershell
.\gradlew.bat test --console=plain
```

기대: 전부 통과.

- [ ] **Step 5: 커밋**

```powershell
git add src/test/java
git commit -m @'
test(web): 화면 구조 스모크 테스트를 둔다

이번 개편에서 HTML 13개를 전부 건드린다. 이런 작업에서 가장 흔한 사고는
논리 오류가 아니라 한 파일을 빠뜨리는 것이다 — script 태그 하나가 없으면
그 화면만 조용히 죽는데, 나머지 열두 개가 멀쩡하니 눈으로는 안 걸린다.
docs/19에서 포커스 링 규칙이 한 화면에만 있던 일이 실제로 있었다.

스프링도 브라우저도 안 띄운다. 확인하려는 것이 파일의 내용이지 실행
결과가 아니다. AdminGateRealServerTest가 진짜 서버를 띄우면서 "이 방식을
다른 테스트로 넓히지 않는다"고 적어 둔 것과 같은 판단이다.

핵심은 폴더를 훑어 목록과 대조하는 세 번째 테스트다. 규칙 검사는 목록에
든 것만 보므로, 목록을 갱신하지 않으면 새 화면이 조용히 빠져나간다.
새 화면이야말로 규칙을 빠뜨리기 가장 쉬운 곳이다.

지금 통과하는 규칙만 넣었다. 셸·테마의 규칙은 그것을 만드는 단계에서
이어 붙인다 — 그래야 빌드가 여러 커밋 동안 빨갛지 않다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 2: `shell.js`를 떼어낸다 (동작은 그대로)

`api.js`가 지금 인증·통신·화면을 다 하고 있다(430줄). 화면 쪽을 새 파일로 옮긴다.
**이 Task가 끝나도 화면은 안 바뀐다** — 같은 상단 바를 다른 파일이 그릴 뿐이다.

**Files:**
- Create: `src/main/resources/static/js/shell.js`
- Modify: `src/main/resources/static/js/api.js` (295~430줄의 `ROLE_RANK`·`currentRole`·`hasRole`·`MENUS`·`renderNav`·`authAreaHtml`·`wireLogout`·`loadReviewBadge`를 잘라낸다)
- Modify: 사용자 HTML 10개 — `js/api.js` 다음 줄에 `<script src="/js/shell.js"></script>` 추가
- Modify: `src/main/resources/static/admin/js/admin-common.js` — `authAreaHtml`·`wireLogout`를 계속 쓴다(이제 shell.js에 있음)
- Modify: 관리자 HTML 7개 — 같은 script 태그 추가

**Interfaces:**
- Consumes: `api.js`의 `isLoggedIn()` `getRole()` `escapeHtml()` `api()` `USERNAME_KEY` `REFRESH_KEY` `clearLogin()`
- Produces: `shell.js` 전역 함수 — `hasRole(need)` `renderNav(active)` `authAreaHtml()` `wireLogout()` `loadReviewBadge()` `currentRole()`, 상수 `MENUS` `ROLE_RANK`

- [ ] **Step 1: 잘라낼 범위를 확인한다**

```powershell
Get-Content src/main/resources/static/js/api.js | Select-Object -Skip 285 -First 150
```

`ROLE_RANK` 선언부터 파일 끝(`loadReviewBadge`의 닫는 괄호)까지가 옮길 범위다.
**주석을 통째로 가져간다** — 메뉴를 넷으로 줄인 판단이 그 주석에 적혀 있다.

- [ ] **Step 2: `shell.js`를 만들고 잘라낸 코드를 붙인다**

파일 맨 위에 이 머리말을 두고, 그 아래에 Step 1에서 확인한 범위를 **주석까지 그대로** 옮긴다.

```javascript
/* =====================================================================
 * shell.js — 모든 화면을 감싸는 껍데기(내비게이션·테마·로그인 표시)
 * ---------------------------------------------------------------------
 * [왜 api.js에서 떼어냈나]
 * api.js는 토큰 보관·HTTP 호출·응답 봉투 해석이 일이다. 거기에 화면을 그리는
 * 코드가 얹혀 430줄이 됐고, "토큰 만료를 고치러 들어갔다가 메뉴 배열을 지나치는"
 * 상태가 됐다. 두 파일은 바뀌는 이유가 다르다 — 메뉴가 하나 늘 때 인증 코드를
 * 다시 읽을 이유가 없다.
 *
 * [의존 방향은 한쪽이다] shell.js → api.js. 반대는 없다.
 * api.js는 화면이 있는지도 모르는 채로 동작해야 한다(테스트·관리 콘솔에서 재사용).
 *
 * [로드 순서] HTML에서 api.js 다음에 온다. 전역 함수를 쓰는 구조라
 * 순서가 곧 의존성이다 — 번들러가 없으니 이 규칙을 사람이 지킨다.
 * ===================================================================== */
```

- [ ] **Step 3: `api.js`에서 옮긴 부분을 지우고, 남은 자리에 안내를 적는다**

`api.js` 맨 위 주석의 `3) renderNav(): 모든 페이지 공통 상단 메뉴를 그린다` 줄을 지우고,
파일 끝에 이렇게 남긴다:

```javascript
/* 내비게이션·테마·로그인 표시는 js/shell.js로 옮겼다(2026-09-06 화면 개편).
   이 파일은 토큰·HTTP·응답 봉투와 라벨/포맷 헬퍼만 맡는다. */
```

- [ ] **Step 4: HTML 17개에 script 태그를 넣는다**

사용자 화면 10개(`index` `daily` `document` `documents` `login` `problems` `quiz` `review`
`signup` `wrong-answers`)와 관리자 7개(`admin/index` `admin/batch` `admin/documents`
`admin/generate` `admin/llm` `admin/problems` `admin/reports`).

각 파일에서 이 줄을

```html
<script src="/js/api.js"></script>
```

이렇게 바꾼다(관리자 화면은 경로 앞에 `/`가 이미 붙어 있어 그대로 쓴다):

```html
<script src="/js/api.js"></script>
<script src="/js/shell.js"></script>
```

**`admin/topics.html`(35줄)도 확인한다** — 다른 화면으로 넘기기만 하는 파일이면 script가
없을 수 있다. 없으면 넣지 않는다.

- [ ] **Step 5: 앱을 띄워 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

확인할 것:
- 홈·문제·복습·개념 문서 상단 바가 **지금과 똑같이** 뜬다
- 복습 배지 숫자가 뜬다
- 로그아웃이 동작한다(누르면 첫 화면으로 가고 다시 로그인이 필요하다)
- 관리 콘솔(`/admin/index.html`) 상단 바와 로그아웃도 동작한다
- **콘솔에 `renderNav is not defined` 같은 빨간 줄이 없다** — 있으면 script 태그를 빠뜨린 파일이다

- [ ] **Step 6: 스모크 테스트에 규칙을 이어 붙인다**

`StaticPageStructureTest`의 `everyPageLoadsApiJs`를 이렇게 넓힌다:

```java
    @Test
    @DisplayName("모든 사용자 화면이 api.js를 싣고, 그 뒤에 shell.js를 싣는다")
    void everyPageLoadsApiThenShell() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            int api = html.indexOf("/js/api.js");
            int shell = html.indexOf("/js/shell.js");

            assertThat(api).as("%s: api.js", page).isNotNegative();
            assertThat(shell).as("%s: shell.js", page).isNotNegative();

            // 순서가 곧 의존성이다. 번들러가 없어 전역 함수로 이어 붙이는 구조라,
            // shell.js가 먼저 실행되면 그 안에서 부르는 escapeHtml·api가 아직 없다.
            // 사람이 지켜야 하는 규칙이므로 사람 대신 이 줄이 지킨다.
            assertThat(shell).as("%s: shell.js는 api.js 뒤에 와야 한다", page)
                    .isGreaterThan(api);
        }
    }
```

돌려서 통과를 확인한다:

```powershell
.\gradlew.bat test --tests "*StaticPageStructureTest*" --console=plain
```

- [ ] **Step 7: 커밋**

```powershell
git add src/main/resources/static src/test/java
git commit -m @'
refactor(shell): 내비게이션을 api.js에서 shell.js로 떼어낸다

동작은 하나도 안 바뀐다. 같은 상단 바를 다른 파일이 그린다.

api.js는 토큰 보관·HTTP·응답 봉투가 일인데 화면 코드가 얹혀 430줄이
됐다. 두 파일은 바뀌는 이유가 다르다 — 메뉴가 하나 늘 때 인증 코드를
다시 읽을 이유가 없다.

다음 단계에서 셸을 사이드바로 바꾸는데, 그 마크업은 지금 상단 바보다
크고 본문을 감싼다. 먼저 자리를 만들어 두고 옮긴다.

의존 방향은 shell.js → api.js 한쪽이다. api.js는 화면이 있는지도
모르는 채로 동작해야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 3: 셸을 사이드바 + 탭바로 바꾼다

여기서 화면이 크게 바뀐다. 본문은 아직 옛 모습이고 **껍데기만** 새것이다.

**Files:**
- Modify: `src/main/resources/static/js/shell.js` (`renderShell` 추가, `MENUS` 확장)
- Modify: `src/main/resources/static/css/style.css` (셸 스타일 추가)
- Modify: 사용자 HTML 10개 (`<header id="nav">` → `<div id="shell">`, 본문을 `<main>` 안으로)

**Interfaces:**
- Consumes: Task 2의 `hasRole` `authAreaHtml` `wireLogout` `loadReviewBadge`
- Produces: `renderShell({ active, title })` — `active`는 `MENUS`의 `key`, `title`은 폰 상단 바에 뜨는 화면 이름. 각 HTML은 `renderNav(...)` 대신 이것을 부른다. `renderNav`는 관리 콘솔이 아직 쓰므로 **남겨 둔다**.

- [ ] **Step 1: `MENUS`에 새 화면 둘을 더한다**

`shell.js`의 `MENUS` 배열을 이렇게 바꾼다:

```javascript
/* 사이드바·탭바가 함께 읽는 한 벌. tab이 있는 것만 폰 탭바에 오른다.
 *
 * [왜 탭이 다섯인가] 폰 가로 375px에서 글자가 안 줄어드는 상한이다. 여섯을 넣으면
 * "개념 문서"가 두 줄이 되거나 잘린다. 그래서 내 기록·설정은 탭 하나("나")로 묶고,
 * 설정 입구는 내 기록 화면 위쪽에 둔다.
 *
 * [관리 콘솔에 tab이 없는 이유] 콘솔은 다른 영역이다. 폰 탭바에 넣으면 학습하다
 * 잘못 눌러 넘어간다 — admin-common.js가 같은 이유로 콘솔에 학습 메뉴를 안 둔다. */
const MENUS = [
  { key: "today",    label: "오늘",       href: "/",                     need: "public", tab: "☀️" },
  { key: "problems", label: "문제",       href: "/problems.html",        need: "user",   tab: "🗂️" },
  { key: "review",   label: "복습",       href: "/review.html",          need: "user",   tab: "🔁", badge: "reviewBadge" },
  { key: "docs",     label: "개념 문서",  href: "/documents.html",       need: "public", tab: "📚" },
  { key: "me",       label: "내 기록",    href: "/me.html",              need: "user",   tab: "🙂", tabLabel: "나" },
  { key: "settings", label: "설정",       href: "/settings.html",        need: "user" },
  { key: "admin",    label: "관리 콘솔 ↗", href: "/admin/index.html",    need: "admin" },
];
```

- [ ] **Step 2: `renderShell`을 쓴다**

`shell.js`에 더한다. `renderNav`는 지우지 않는다 — 관리 콘솔이 Task 12까지 쓴다.

```javascript
/**
 * 화면을 감싸는 껍데기를 그린다 — 넓으면 왼쪽 기둥, 좁으면 아래 탭바.
 *
 * <p>[왜 HTML마다 안 적고 JS가 그리나] 이 프로젝트는 빌드 도구도, head를 공유하는 틀도
 * 없다. 사이드바 마크업을 HTML 13개에 복붙하면 화면이 하나 늘 때마다 <b>빠뜨릴 자리가
 * 같이 는다</b>. 실제로 포커스 링이 한 화면에만 있던 일이 있었다(docs/19).
 *
 * <p>[한 벌로 둘을 그린다] 사이드바와 탭바를 <b>같은 MENUS</b>에서 만든다. 둘을 따로
 * 적으면 메뉴를 하나 더할 때 한쪽만 고치고 넘어가게 된다.
 *
 * <p>[본문을 감싸지 않는다] 셸이 본문을 innerHTML로 감싸면 각 HTML이 이미 잡아 둔
 * DOM 참조가 끊긴다. 대신 <b>셸을 본문 옆에 그리고</b> CSS grid로 나란히 세운다 —
 * 마크업 순서와 화면 배치를 분리하는 것이 grid를 쓰는 이유다.
 *
 * @param active MENUS의 key. 지금 화면을 굵게 표시한다
 * @param title  폰 상단 바에 뜨는 화면 이름. 좁은 화면에는 사이드바가 없어
 *               "지금 어디인지"를 말해 줄 자리가 여기밖에 없다
 */
function renderShell({ active, title = "" }) {
  applyStoredTheme();

  const el = document.getElementById("shell");
  if (!el) return;

  const visible = MENUS.filter(m => hasRole(m.need));
  const tabs = visible.filter(m => m.tab).slice(0, 5);

  el.innerHTML = `
    <header class="shell-top">
      <a class="brand" href="/">csquiz</a>
      <span class="shell-title">${escapeHtml(title)}</span>
      <span class="spacer"></span>
      ${authAreaHtml()}
    </header>

    <nav class="shell-side" aria-label="주 메뉴">
      <a class="brand" href="/">csquiz</a>
      ${sideLinks(visible.filter(m => m.key !== "settings" && m.key !== "admin"), active)}
      ${visible.some(m => m.key === "settings")
        ? `<div class="shell-rule"></div>${sideLinks(visible.filter(m => m.key === "settings"), active)}` : ""}
      <span class="spacer"></span>
      ${visible.some(m => m.key === "admin")
        ? sideLinks(visible.filter(m => m.key === "admin"), active) : ""}
    </nav>

    <nav class="shell-tabs" aria-label="주 메뉴">
      ${tabs.map(m => `
        <a class="shell-tab${m.key === active ? " active" : ""}" href="${m.href}"
           ${m.key === active ? 'aria-current="page"' : ""}>
          <span class="ic" aria-hidden="true">${m.tab}</span>${escapeHtml(m.tabLabel || m.label)}
          ${m.badge ? `<span id="${m.badge}-tab"></span>` : ""}
        </a>`).join("")}
    </nav>`;

  loadReviewBadge();
  wireLogout();
}

/** 사이드바 링크 한 묶음. 사이드바와 탭바가 같은 MENUS를 읽되 모양만 다르다. */
function sideLinks(items, active) {
  return items.map(m =>
    `<a class="shell-link${m.key === active ? " active" : ""}" href="${m.href}"
        ${m.key === active ? 'aria-current="page"' : ""}>
       <span class="ic" aria-hidden="true">${m.tab || "🛠"}</span>
       <span>${escapeHtml(m.label)}</span>
       ${m.badge ? `<span id="${m.badge}"></span>` : ""}
     </a>`).join("");
}

/* 테마 — 저장된 값을 <html>에 붙인다.
 * 실제 전환 UI는 설정 화면(Task 11)에서 만든다. 여기서는 붙이기만 한다.
 *
 * [왜 여기서도 부르나] 첫 그림이 라이트로 번쩍이는 것(FOUC)을 막는 진짜 장치는
 * 각 HTML의 <head>에 넣는 인라인 한 줄이다(Task 12). 이 함수는 그 뒤에 한 번 더
 * 부르는 안전망이다 — head 한 줄을 빠뜨린 화면이 생겨도 늦게나마 맞는다. */
function applyStoredTheme() {
  try {
    const t = localStorage.getItem("csquiz_theme");
    if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme; // "auto" = 기기 설정을 따름
  } catch (e) { /* 시크릿 모드 등에서 localStorage가 막힐 수 있다 — 기본(자동)으로 둔다 */ }
}
```

`loadReviewBadge`는 지금 `reviewBadge` 하나만 채운다. 탭바에도 배지가 있으니
그 함수 안의 채우는 줄을 이렇게 바꾼다:

```javascript
    if (data.totalElements > 0) {
      const html = `<span class="nav-badge">${data.totalElements}</span>`;
      // 사이드바와 탭바 둘 다 — 같은 정보를 두 자리에서 보여 준다
      ["reviewBadge", "reviewBadge-tab"].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.innerHTML = html;
      });
    }
```

- [ ] **Step 3: 셸 CSS를 쓴다**

`style.css`에 더한다. 기존 `.nav` 규칙은 **지우지 않는다** — 관리 콘솔이 아직 쓴다.

```css
/* ═════════════════════════════════════════════════════════════════════
 * 셸 — 넓으면 왼쪽 기둥, 좁으면 아래 탭바
 *
 * [왜 grid인가] 셸(#shell)과 본문(main)은 마크업에서 형제다. 셸이 본문을 감싸면
 * 각 화면이 이미 잡아 둔 DOM 참조가 끊긴다. grid-template-areas를 쓰면
 * <b>마크업 순서와 화면 배치를 떼어놓을 수 있다</b> — 좁을 때는 위/아래, 넓을 때는
 * 왼쪽/오른쪽으로 같은 요소가 자리를 옮긴다.
 *
 * [모바일 우선] 기본 규칙이 폰이고, min-width로 넓은 화면을 덧쓴다. 반대로 짜면
 * 폰에서 데스크톱 규칙을 하나씩 되돌리게 되고, 되돌리기를 빠뜨린 자리가 곧 버그다.
 * ═══════════════════════════════════════════════════════════════════ */
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  display: grid;
  grid-template-areas: "top" "main" "tabs";
  grid-template-rows: auto 1fr auto;
  min-height: 100vh;
}
#shell { display: contents; }   /* 자식 셋이 직접 grid 칸에 앉는다 */

.shell-top  { grid-area: top; }
.shell-side { grid-area: side; display: none; }
.shell-tabs { grid-area: tabs; }
main        { grid-area: main; min-width: 0; }   /* min-width:0이 없으면 긴 표가 grid를 늘린다 */

/* ── 폰 상단 바 ── */
.shell-top {
  display: flex; align-items: center; gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-4);
  background: var(--card); border-bottom: 1px solid var(--border);
  position: sticky; top: 0; z-index: 10;
}
.shell-title { color: var(--muted); font-size: var(--fs-2); }

/* ── 폰 하단 탭바 ── */
.shell-tabs {
  display: flex;
  background: var(--card); border-top: 1px solid var(--border);
  position: sticky; bottom: 0; z-index: 10;
  /* 아이폰 홈 인디케이터에 탭이 가리지 않게 */
  padding-bottom: env(safe-area-inset-bottom, 0);
}
.shell-tab {
  flex: 1; text-align: center; text-decoration: none;
  color: var(--muted); font-size: 10px; line-height: 1.5;
  /* 44px은 손가락 표적의 하한이다 — 이보다 작으면 옆 탭이 눌린다 */
  min-height: 44px; padding: 6px 0 7px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.shell-tab .ic { font-size: 17px; line-height: 1; margin-bottom: 2px; }
.shell-tab.active { color: var(--primary); font-weight: 700; }

/* ── 넓은 화면: 기둥이 서고 탭바가 사라진다 ── */
@media (min-width: 768px) {
  body {
    grid-template-areas: "side main";
    grid-template-columns: 208px 1fr;
    grid-template-rows: 1fr;
  }
  .shell-top, .shell-tabs { display: none; }

  .shell-side {
    display: flex; flex-direction: column; gap: 2px;
    padding: var(--sp-3) var(--sp-2);
    background: var(--card); border-right: 1px solid var(--border);
    position: sticky; top: 0; height: 100vh; overflow-y: auto;
  }
  .shell-side .brand {
    padding: var(--sp-1) var(--sp-2) var(--sp-4);
    font-size: var(--fs-4); font-weight: 800;
    color: var(--primary); text-decoration: none; letter-spacing: -.02em;
  }
  .shell-link {
    display: flex; align-items: center; gap: var(--sp-2);
    padding: var(--sp-2); border-radius: var(--radius);
    color: var(--text-2); text-decoration: none; font-size: var(--fs-2);
    min-height: 36px;
  }
  .shell-link:hover { background: var(--fill); }
  .shell-link.active { background: var(--primary-soft); color: var(--primary); font-weight: 700; }
  .shell-link .ic { flex: none; }
  .shell-rule { border-top: 1px solid var(--border); margin: var(--sp-2) 0; }

  main { padding: var(--sp-5) var(--sp-5) var(--sp-6); }
  main > .container { max-width: 880px; margin: 0 auto; padding: 0; }
}

@media (max-width: 767px) {
  main > .container { padding: var(--sp-4); }
}
```

- [ ] **Step 4: 사용자 HTML 10개를 바꾼다**

각 파일에서

```html
<header id="nav"></header>
```

를

```html
<div id="shell"></div>
```

로 바꾸고, 페이지 스크립트의 `renderNav("...")` 를 `renderShell({ active: "...", title: "..." })`
로 바꾼다. `active`는 지금 쓰던 값을 그대로 쓰고, `title`은 화면 이름이다.

| 파일 | 바꿀 호출 |
| --- | --- |
| `index.html` | `renderShell({ active: "today", title: "오늘" })` |
| `daily.html` | `renderShell({ active: "today", title: "오늘의 퀴즈" })` |
| `problems.html` | `renderShell({ active: "problems", title: "문제" })` |
| `quiz.html` | `renderShell({ active: "problems", title: "자유 퀴즈" })` |
| `review.html` | `renderShell({ active: "review", title: "복습" })` |
| `wrong-answers.html` | `renderShell({ active: "review", title: "오답노트" })` |
| `documents.html` | `renderShell({ active: "docs", title: "개념 문서" })` |
| `document.html` | `renderShell({ active: "docs", title: "개념 문서" })` |
| `login.html` | `renderShell({ active: "", title: "로그인" })` |
| `signup.html` | `renderShell({ active: "", title: "회원가입" })` |

- [ ] **Step 5: 앱을 띄워 세 폭에서 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

| 폭 | 기대 |
| --- | --- |
| 375px | 위에 얇은 바(브랜드·화면 이름·로그인), 아래에 탭 5칸. 사이드바 없음 |
| 768px | 왼쪽 기둥 208px, 탭바 없음 |
| 1280px | 같음. 본문이 880px에서 멈추고 가운데 정렬 |

그리고:
- 지금 화면의 탭이 굵게 표시된다
- 복습 배지가 **사이드바와 탭바 양쪽**에 뜬다
- 비로그인으로 열면 "문제·복습·내 기록·설정"이 안 보이고 탭이 셋만 뜬다
- 가로 스크롤바가 생기지 않는다 — 생기면 `main`의 `min-width: 0`이 빠진 것이다
- **Tab 키로 훑으면 사이드바·탭바 링크에 포커스 링이 보인다.** `style.css`의 전역
  `:focus-visible` 규칙이 새 컴포넌트에도 걸리는지 보는 것이다 — 그 규칙은 원래
  한 화면에만 있었고, docs/19에서 전역으로 올렸다. 새로 만드는 컴포넌트마다
  이걸 확인하지 않으면 같은 일이 되풀이된다
- 콘솔 빨간 줄 0

- [ ] **Step 6: 스모크 테스트에 셸 규칙을 더한다**

`StaticPageStructureTest`에 테스트를 하나 더한다:

```java
    @Test
    @DisplayName("모든 사용자 화면이 셸 자리를 두고, 옛 내비 자리는 남기지 않는다")
    void everyPageHasShellMount() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            // renderShell이 채울 자리. 없으면 그 화면만 메뉴가 통째로 사라진다
            assertThat(html).as("%s: <div id=\"shell\">", page)
                    .contains("id=\"shell\"");

            // 옛 자리가 남아 있으면 빈 <header>가 화면 맨 위에 여백으로 남는다.
            // 눈에 잘 안 띄는 종류의 잔재라 사람보다 이 줄이 낫다.
            assertThat(html).as("%s: 옛 id=\"nav\"가 남아 있다", page)
                    .doesNotContain("id=\"nav\"");
        }
    }
```

돌려서 통과를 확인한다:

```powershell
.\gradlew.bat test --tests "*StaticPageStructureTest*" --console=plain
```

- [ ] **Step 7: 커밋**

```powershell
git add src/main/resources/static src/test/java
git commit -m @'
feat(shell): 상단 가로바를 사이드바와 하단 탭바로 바꾼다

껍데기만 바뀌고 본문은 아직 옛 모습이다.

화면이 10개에서 13개로 느는데 가로바에는 넣을 자리가 없었다. 기둥에는
이름째로 들어가고, 폰에서는 아래쪽이 엄지에 닿는다.

사이드바와 탭바를 같은 MENUS에서 그린다. 둘을 따로 적으면 메뉴를 하나
더할 때 한쪽만 고치고 넘어가게 된다.

셸이 본문을 감싸지 않는 것이 설계의 핵심이다. innerHTML로 감싸면 각
화면이 이미 잡아 둔 DOM 참조가 끊긴다. 대신 형제로 두고 grid의
template-areas로 자리를 옮긴다 — 마크업 순서와 화면 배치를 뗀다.

탭을 다섯으로 묶은 것은 375px에서 글자가 안 줄어드는 상한이라서다.
내 기록과 설정은 "나" 하나로 접고, 설정 입구는 내 기록 화면에 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 4: 분야별 전체 문제 수를 응답에 더한다 (TDD)

진도 막대는 분모가 있어야 그려진다. **이건 기존 판단을 뒤집는 것이라** 주석에
그 이야기를 남기는 것까지가 이 Task다.

**Files:**
- Modify: `src/main/java/project/study/study_project/quiz/repository/ProblemRepository.java`
- Modify: `src/main/java/project/study/study_project/quiz/dto/StudySummaryResponse.java`
- Modify: `src/main/java/project/study/study_project/quiz/service/ProblemListService.java`
- Test: `src/test/java/project/study/study_project/quiz/StudySummaryTotalIntegrationTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces: `StudySummaryResponse.DomainProgress(Domain domain, String label, long solved, long total)` — JSON 키 `total`. Task 6·10이 읽는다.
  `ProblemRepository.countGroupByDomain()` → `List<DomainCount>` (`getDomain()` `getCnt()`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/quiz/StudySummaryTotalIntegrationTest.java`:

```java
package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분야별 진척의 <b>분모</b> 통합 테스트.
 *
 * <p><b>왜 이 필드가 뒤늦게 생겼나.</b> 예전에는 일부러 안 줬다. 그 DTO 주석이 이유를
 * 적어 두었다 — 배치가 매일 문제를 더해 분모가 커지므로 "어제 40%가 오늘 37%"가 되고,
 * 아무것도 잘못하지 않았는데 뒷걸음질친 것처럼 보인다는 것이다.
 *
 * <p>화면 개편에서 분야별 진도를 <b>막대</b>로 보여 주기로 하면서 분모가 필요해졌다.
 * 숫자만 늘어놓으면 40개 중 31개와 33개 중 9개가 같은 무게로 읽힌다. 대신 화면에서는
 * 절대값(31 / 40)을 크게 두고 퍼센트를 아예 쓰지 않아 뒷걸음질이 눈에 덜 띄게 한다.
 *
 * <p><b>왜 통합 테스트인가.</b> 조용히 고장 날 자리가 둘이고 단위 테스트로는 못 본다.
 * <ol>
 *   <li>{@code group by}가 빠진 분야를 아예 안 주는 성질 — 문제가 0개인 분야가
 *       응답에서 <b>사라지면</b> 화면에서 그 분야가 통째로 없어진다
 *   <li>프로젝션 인터페이스의 별칭이 게터 이름과 어긋나면 런타임에만 터진다
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudySummaryTotalIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProblemRepository problemRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("분야별 진척에 그 분야의 전체 문제 수가 함께 온다")
    void domainProgressCarriesTotal() throws Exception {
        User user = userRepository.save(User.create(
                "u" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("pw12345!"), Role.USER));
        String token = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());

        // 네트워크에 2개, 데이터베이스에 1개. 나머지 분야는 0개다.
        problemRepository.save(problem(Domain.NETWORK, "네트워크 문제 1"));
        problemRepository.save(problem(Domain.NETWORK, "네트워크 문제 2"));
        problemRepository.save(problem(Domain.DATABASE, "DB 문제 1"));

        mockMvc.perform(get("/api/me/study-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains[?(@.domain == 'NETWORK')].total").value(2))
                .andExpect(jsonPath("$.data.domains[?(@.domain == 'DATABASE')].total").value(1));
    }

    @Test
    @DisplayName("문제가 하나도 없는 분야도 total 0으로 응답에 남는다")
    void emptyDomainStaysWithZero() throws Exception {
        User user = userRepository.save(User.create(
                "u" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("pw12345!"), Role.USER));
        String token = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());

        // 이 테스트는 아무 문제도 만들지 않는다. group by는 없는 분야를 안 주는데,
        // 그대로 내려보내면 화면에서 손대지 않은 분야가 사라진다 — "여기부터 해 볼까"의
        // 후보가 안 보이게 된다. 서비스가 Domain.values()로 빈 칸을 채우는지 본다.
        mockMvc.perform(get("/api/me/study-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains.length()").value(Domain.values().length))
                .andExpect(jsonPath("$.data.domains[0].total").exists());
    }

    private Problem problem(Domain domain, String title) {
        return Problem.create(domain, Difficulty.BEGINNER, ProblemType.OX,
                title, title + " 지문", "O", "해설", null);
    }
}
```

**`Problem.create(...)`의 실제 시그니처를 먼저 확인한다.** 위 호출이 안 맞으면 컴파일이
안 된다 — 다른 테스트가 `Problem`을 어떻게 만드는지 보고 맞춘다:

```powershell
Select-String -Path src/test/java -Pattern "Problem.create" -Recurse | Select-Object -First 3
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*StudySummaryTotalIntegrationTest*" --console=plain
```

기대: **실패.** `total` 필드가 없으므로 `jsonPath ... .total` 이 값을 못 찾는다.
(컴파일 오류가 나면 `Problem.create` 시그니처를 안 맞춘 것이니 Step 1로 돌아간다.)

- [ ] **Step 3: 리포지토리에 분야별 개수 쿼리를 더한다**

`ProblemRepository.java`의 `countGroupByDomainAndDifficulty` 아래에 붙인다:

```java
    /**
     * 분야별 전체 문제 수 — 진도 막대의 분모.
     *
     * <p>난이도까지 묶는 {@link #countGroupByDomainAndDifficulty}가 이미 있지만 그것을
     * 재사용하지 않는다. 쓰는 쪽이 난이도 축을 합치는 코드를 들고 있어야 하고, 그 합치는
     * 코드가 <b>화면 두 곳(홈·내 기록)에 각각</b> 생긴다. 쿼리 한 줄이 더 싸다.
     *
     * <p>Problem 테이블에는 <b>승인된 문제만</b> 있다 — AI 초안은 GeneratedProblemDraft라는
     * 다른 테이블에 산다. 그래서 상태 조건이 없어도 "풀 수 있는 문제"만 세어진다.
     */
    @Query("""
            select p.domain as domain, count(p) as cnt
            from Problem p
            group by p.domain
            """)
    List<DomainCount> countGroupByDomain();

    /** {@link #countGroupByDomain} 결과 행 — select 별칭과 게터 이름이 매핑 규약이다. */
    interface DomainCount {
        Domain getDomain();
        long getCnt();
    }
```

- [ ] **Step 4: DTO에 `total`을 더하고 주석을 잇는다**

`StudySummaryResponse.java`의 클래스 주석에서 `@param domains` 설명을 **지우지 말고
이어서 적는다**:

```java
 * @param domains 분야별 진척 — 맞힌 개수와 그 분야의 전체 문제 수(분모).
 *                <p><b>분모는 원래 일부러 주지 않았다.</b> 배치가 매일 문제를 더해 분모가
 *                커지므로, 어제 40%가 오늘 37%가 되면 아무것도 잘못하지 않았는데
 *                뒷걸음질친 것처럼 보이기 때문이다.
 *                <p><b>2026-09-06 화면 개편에서 뒤집었다.</b> 분야별 진도를 막대로 보여
 *                주기로 했는데, 막대는 분모 없이는 그릴 수 없다. 숫자만 늘어놓으면
 *                40개 중 31개와 33개 중 9개가 같은 무게로 읽힌다.
 *                <p>대신 <b>화면에서</b> 뒷걸음질을 눌렀다 — 주인공은 절대값(31 / 40)이고
 *                막대는 그 아래 보조로 얇게 둔다. 퍼센트 숫자는 아예 쓰지 않는다.
 *                문제 목록 개편에서 정한 "게이지 → 절대값"과 같은 방향이다.
```

레코드를 고친다:

```java
    /**
     * 분야 한 줄. {@code label}을 서버가 함께 주는 것은 이 프로젝트의 기존 규칙이다.
     *
     * @param solved 내가 맞힌 적 있는 문제 수
     * @param total  그 분야의 전체 문제 수. 배치가 문제를 더하면 커진다(위 주석 참고)
     */
    public record DomainProgress(Domain domain, String label, long solved, long total) {
    }
```

- [ ] **Step 5: 서비스가 두 집계를 합치게 한다**

`ProblemListService.java`의 `domainProgress`를 바꾼다:

```java
    /**
     * 분야별 진척 — <b>모든 분야를 0으로라도 채워</b> 돌려준다.
     *
     * <p>집계 쿼리는 해당 행이 없는 분야를 아예 안 준다(GROUP BY의 성질). 그대로
     * 내려보내면 화면에서 <b>손대지 않은 분야가 사라져</b>, 정작 "여기부터 해 볼까"의
     * 후보가 안 보인다. 빠진 분야를 만들어 내려면 Domain 목록이 필요한데 그건 자바가
     * 아는 것이라 여기서 채운다.
     *
     * <p><b>맵을 두 개 만들어 합친다.</b> 분자(내가 맞힌 수)와 분모(전체 문제 수)는
     * 출처가 다르다 — 앞은 내 제출 이력, 뒤는 문제 테이블. 조인 한 방으로 묶을 수도
     * 있지만 그러면 "제출이 0건인 분야"가 조인에서 떨어져 나가 위의 빈 칸 채우기가
     * 다시 깨진다. 각자 세고 자바에서 합치는 편이 안전하다.
     */
    private List<StudySummaryResponse.DomainProgress> domainProgress(Long userId) {
        Map<Domain, Long> solved = new EnumMap<>(Domain.class);
        submissionRepository.countSolvedByDomain(userId)
                .forEach(row -> solved.put(row.getDomain(), row.getSolved()));

        Map<Domain, Long> total = new EnumMap<>(Domain.class);
        problemRepository.countGroupByDomain()
                .forEach(row -> total.put(row.getDomain(), row.getCnt()));

        return java.util.Arrays.stream(Domain.values())
                .map(domain -> new StudySummaryResponse.DomainProgress(
                        domain,
                        domain.getDisplayName(),
                        solved.getOrDefault(domain, 0L),
                        total.getOrDefault(domain, 0L)))
                .toList();
    }
```

`ProblemListService`에 `ProblemRepository`가 이미 주입돼 있는지 확인한다. 없으면
**생성자 주입**으로 더한다(필드 주입 금지 — 프로젝트 규칙).

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```powershell
.\gradlew.bat test --tests "*StudySummaryTotalIntegrationTest*" --console=plain
```

기대: **PASS 2건.**

- [ ] **Step 7: 전체 테스트가 안 깨졌는지 본다**

```powershell
.\gradlew.bat test --console=plain
```

기대: 전부 통과. `DomainProgress` 생성자를 쓰던 다른 테스트가 있으면 인자 하나를 더한다.

- [ ] **Step 8: 커밋**

```powershell
git add src/main/java src/test/java
git commit -m @'
feat(summary): 분야별 진척에 분모를 더한다 — 뒤집은 결정이다

예전에는 일부러 안 줬다. DTO 주석이 이유를 적어 두었다 — 배치가 매일
문제를 더해 분모가 커지므로 어제 40%가 오늘 37%가 되고, 아무것도
잘못하지 않았는데 뒷걸음질친 것처럼 보인다는 것이다.

화면 개편에서 분야별 진도를 막대로 보여 주기로 하면서 분모가 필요해졌다.
숫자만 늘어놓으면 40개 중 31개와 33개 중 9개가 같은 무게로 읽힌다.

걱정 자체는 그대로 맞아서, 화면에서 눌렀다. 주인공은 절대값(31 / 40)이고
막대는 그 아래 보조로 얇게 둔다. 퍼센트 숫자는 아예 쓰지 않는다.

옛 주석을 지우지 않고 이어서 적었다. "왜 그때는 안 줬고 지금은 주는가"가
다음 사람이 읽어야 할 이야기다.

분자와 분모를 조인 한 방으로 묶지 않고 각자 세어 자바에서 합친다.
조인하면 제출이 0건인 분야가 떨어져 나가, 손대지 않은 분야를 0으로라도
채워 두는 기존 장치가 다시 깨진다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 5: 공개 집계 엔드포인트 (TDD)

랜딩의 "문제 296개 · 개념 문서 24편 · 8개 분야"를 위해서다.
숫자를 HTML에 글로 박으면 곧 거짓말이 된다.

**Files:**
- Create: `src/main/java/project/study/study_project/quiz/dto/PublicStatsResponse.java`
- Create: `src/main/java/project/study/study_project/quiz/controller/PublicStatsController.java`
- Test: `src/test/java/project/study/study_project/quiz/PublicStatsIntegrationTest.java` (신규)
- Modify: 보안 설정 — `/api/stats`를 인증 없이 열어 준다

**Interfaces:**
- Consumes: `ProblemRepository.count()`, 문서 리포지토리의 개수 세기
- Produces: `GET /api/stats` → `{ success, data: { problemCount, documentCount, domainCount } }`. Task 9(랜딩)가 읽는다.

- [ ] **Step 1: 보안 설정에서 공개 경로가 어떻게 열리는지 확인한다**

```powershell
Select-String -Path src/main/java -Pattern "permitAll|requestMatchers" -Recurse | Select-Object -First 20
```

`/api/documents` 같은 공개 API가 어떻게 열려 있는지 보고 **같은 방식**으로 `/api/stats`를 연다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/quiz/PublicStatsIntegrationTest.java`:

```java
package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 랜딩용 공개 집계 통합 테스트.
 *
 * <p><b>왜 API를 새로 만드나.</b> 랜딩에 "문제 296개"를 글로 박으면 배치가 도는 다음 날
 * 곧 거짓말이 된다. 숫자를 말하려면 세어서 말해야 한다.
 *
 * <p><b>왜 통합 테스트인가.</b> 이 엔드포인트가 조용히 고장 날 자리는 <b>보안 설정</b>이다.
 * 컨트롤러는 멀쩡한데 SecurityConfig에서 안 열어 주면 401이 나가고, 랜딩은 로그인하지
 * 않은 사람이 보는 화면이라 <b>아무도 못 본다</b>. 단위 테스트로는 이 자리를 못 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicStatsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProblemRepository problemRepository;

    @Test
    @DisplayName("로그인하지 않아도 집계를 볼 수 있다")
    void openToAnonymous() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problemCount").exists())
                .andExpect(jsonPath("$.data.documentCount").exists())
                .andExpect(jsonPath("$.data.domainCount").value(Domain.values().length));
    }

    @Test
    @DisplayName("문제를 더하면 집계도 따라 는다")
    void countsFollowData() throws Exception {
        String before = mockMvc.perform(get("/api/stats"))
                .andReturn().getResponse().getContentAsString();
        long baseline = com.jayway.jsonpath.JsonPath.parse(before).read("$.data.problemCount",
                Integer.class).longValue();

        problemRepository.save(Problem.create(Domain.NETWORK, Difficulty.BEGINNER,
                ProblemType.OX, "집계용", "집계용 지문", "O", "해설", null));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problemCount").value((int) baseline + 1));
    }
}
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*PublicStatsIntegrationTest*" --console=plain
```

기대: **실패** — 404 또는 401.

- [ ] **Step 4: 응답 DTO를 만든다**

`src/main/java/project/study/study_project/quiz/dto/PublicStatsResponse.java`:

```java
package project.study.study_project.quiz.dto;

/**
 * 랜딩 화면의 집계 한 줄 — "문제 296개 · 개념 문서 24편 · 8개 분야".
 *
 * <p><b>왜 서버가 세나.</b> HTML에 숫자를 글로 박으면 배치가 도는 다음 날 거짓말이 된다.
 * 이 앱은 매일 문제를 더하는 것이 기능이라, 정적인 숫자가 특히 빨리 낡는다.
 *
 * <p><b>왜 인증이 없나.</b> 이 숫자를 보는 사람은 아직 가입하지 않은 사람이다.
 * 공개해도 새는 정보가 없다 — 개별 문제 내용이 아니라 개수뿐이다.
 *
 * @param problemCount  풀 수 있는 문제 수. Problem 테이블에는 승인된 것만 있다
 *                      (AI 초안은 GeneratedProblemDraft라는 다른 테이블)
 * @param documentCount 공개된 개념 문서 편수
 * @param domainCount   분야 수. Domain enum의 크기라 DB를 안 본다
 */
public record PublicStatsResponse(
        long problemCount,
        long documentCount,
        int domainCount
) {
}
```

- [ ] **Step 5: 컨트롤러를 만든다**

`src/main/java/project/study/study_project/quiz/controller/PublicStatsController.java`:

```java
package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.ApiResponse;
import project.study.study_project.global.common.Domain;
import project.study.study_project.quiz.dto.PublicStatsResponse;
import project.study.study_project.quiz.repository.ProblemRepository;

/**
 * 랜딩용 공개 집계 — 인증이 필요 없는 유일한 통계 엔드포인트.
 *
 * <p><b>왜 서비스 계층이 없나.</b> 하는 일이 리포지토리 count 둘과 enum 길이 하나를
 * 담는 것뿐이다. 도메인 규칙이 하나도 없는데 계층을 만들면 통과만 하는 파일이 는다.
 * 규칙(예: "최근 30일 것만 센다")이 생기면 그때 서비스로 내린다.
 *
 * <p><b>요청 제한</b>은 api 버킷(60/분)을 그대로 탄다 — 공개 API지만 예외를 두지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class PublicStatsController {

    private final ProblemRepository problemRepository;
    private final DocumentRepository documentRepository;

    /** 랜딩 화면이 한 번 부른다. 캐시는 두지 않는다 — 하루에 몇 번 불릴 값이다. */
    @GetMapping("/api/stats")
    public ApiResponse<PublicStatsResponse> stats() {
        return ApiResponse.ok(new PublicStatsResponse(
                problemRepository.count(),
                documentRepository.count(),
                Domain.values().length));
    }
}
```

**문서 리포지토리의 실제 이름과 패키지를 먼저 확인한다:**

```powershell
Get-ChildItem -Recurse -Filter "*Repository.java" src/main/java/project/study/study_project/document
```

이름이 다르면 import와 필드 타입을 맞춘다. 개념 문서에 "공개/비공개" 상태가 있으면
`count()` 대신 공개된 것만 세는 메서드를 쓴다 — 있는지 확인하고 없으면 `count()` 그대로 둔다.

- [ ] **Step 6: 보안 설정에서 `/api/stats`를 연다**

Step 1에서 본 방식 그대로, 공개 경로 목록에 `/api/stats`를 더한다. 예를 들어
`requestMatchers(...).permitAll()` 목록에 있는 형태라면 거기에 넣는다.

주석을 함께 남긴다:

```java
// 랜딩(비로그인 첫 화면)이 부르는 집계다. 여기가 막히면 401이 나가고
// 정작 로그인하지 않은 사람이 아무 숫자도 못 본다 — 통합 테스트가 이 자리를 본다.
```

- [ ] **Step 7: 테스트를 돌려 통과를 확인한다**

```powershell
.\gradlew.bat test --tests "*PublicStatsIntegrationTest*" --console=plain
```

기대: **PASS 2건.**

- [ ] **Step 8: 전체 테스트**

```powershell
.\gradlew.bat test --console=plain
```

기대: 전부 통과.

- [ ] **Step 9: 커밋**

```powershell
git add src/main/java src/test/java
git commit -m @'
feat(stats): 랜딩용 공개 집계 엔드포인트를 만든다

랜딩에 "문제 296개"를 글로 박으면 배치가 도는 다음 날 거짓말이 된다.
이 앱은 매일 문제를 더하는 것이 기능이라 정적인 숫자가 특히 빨리 낡는다.

서비스 계층을 두지 않았다. 하는 일이 count 둘과 enum 길이 하나를 담는
것뿐인데 계층을 만들면 통과만 하는 파일이 는다. 규칙이 생기면 그때 내린다.

통합 테스트가 보는 자리는 보안 설정이다. 컨트롤러가 멀쩡해도 SecurityConfig에서
안 열어 주면 401이 나가고, 랜딩은 로그인하지 않은 사람이 보는 화면이라
아무도 못 본다. 단위 테스트로는 이 자리를 못 본다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 6: 홈("오늘") 개편

**Files:**
- Modify: `src/main/resources/static/index.html` (로그인 쪽 `#today` 섹션과 그 스크립트)
- Modify: `src/main/resources/static/css/style.css` (히어로·통계 타일·진도 막대)

**Interfaces:**
- Consumes: `renderShell`, `GET /api/me/daily-quiz/today`, `GET /api/me/study-summary`(이제 `total` 포함)
- Produces: CSS 클래스 `.hero-card` `.hero-card.done` `.hero-card.first` `.stat-row` `.stat-tile` `.progress-grid` `.progress-item` `.bar`

- [ ] **Step 1: 히어로·통계·진도 CSS를 쓴다**

```css
/* ── 오늘 히어로 ────────────────────────────────────────────────────
 * [왜 옅은 판인가] 진한 그라데이션 판도 후보였고 처음 오는 사람에게는 그쪽이 명확하다.
 * 하지만 이 앱은 <매일 오는 사람>의 화면이고, index.html의 기존 주석이 이미
 * "매일 오는 사람에게 매일 같은 광고를 보이지 않는다"를 결정으로 적어 두었다.
 * 진한 판은 몇 주면 배경이 된다 — 그때는 크기만 차지하고 아무 말도 하지 않는다.
 *
 * [왼쪽 4px 띠] 옅은 판은 배경에 묻히기 쉬워 시작 지점을 표시할 것이 필요하다.
 * 테두리를 통째로 진하게 하는 대신 한 변만 굵히면, 판을 세게 만들지 않고도
 * "여기부터 오늘 할 일"이 읽힌다.
 *
 * [세 얼굴] 상황에 따라 색과 문구가 바뀐다. 할 일 있음(인디고) / 끝(초록) / 첫날(인디고).
 * 색만으로 알리지 않는다 — 끝난 상태에는 ✓가 문구에 함께 있다. */
.hero-card {
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-left: 4px solid var(--primary);
  border-radius: var(--radius-lg);
  padding: var(--sp-4) var(--sp-5);
}
.hero-card .eyebrow {
  color: var(--primary); font-size: var(--fs-1); font-weight: 700;
  letter-spacing: .08em; text-transform: uppercase;
}
.hero-card h1 {
  font-size: var(--fs-5); font-weight: 800; letter-spacing: -.02em;
  margin: var(--sp-2) 0 var(--sp-1);
}
.hero-card .lead { color: var(--text-2); margin: 0 0 var(--sp-3); }
.hero-card .actions { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-3); }

/* 오늘 몫을 끝낸 날. 초록으로 바뀌는 것만으로 알리지 않고 문구에 ✓를 함께 둔다 */
.hero-card.done { background: var(--correct-bg); border-color: var(--correct); border-left-color: var(--correct); }
.hero-card.done .eyebrow { color: var(--correct); }

/* ── 통계 타일 ──
 * 채움색이 --card인 이유: --bg를 쓰면 페이지 배경과 같은 색이라 타일이 통째로 사라진다.
 * docs/19에서 실제로 한 번 겪었다. */
.stat-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--sp-2); margin-top: var(--sp-4); }
@media (min-width: 640px) { .stat-row { grid-template-columns: repeat(4, 1fr); } }
.stat-tile {
  background: var(--card); border: 1px solid var(--border);
  border-radius: var(--radius); padding: var(--sp-2) var(--sp-3);
}
.stat-tile .k { color: var(--muted); font-size: var(--fs-1); }
.stat-tile .v { font-size: var(--fs-5); font-weight: 800; letter-spacing: -.02em; }

/* ── 분야별 진도 ──
 * [절대값이 주인공이다] "31 / 40"을 글자로 크게 두고 막대는 그 아래 7px로 얇게 깐다.
 * 퍼센트 숫자는 아예 쓰지 않는다 — 배치가 매일 문제를 더해 분모가 커지므로
 * 퍼센트는 가만히 있어도 줄어든다. 절대값은 줄지 않는다(맞힌 수는 안 준다).
 * 문제 목록 개편에서 정한 "게이지 → 절대값"과 같은 방향이다. */
.progress-grid { display: grid; grid-template-columns: 1fr; gap: var(--sp-3) var(--sp-5); margin-top: var(--sp-2); }
@media (min-width: 640px) { .progress-grid { grid-template-columns: 1fr 1fr; } }
.progress-item .row { display: flex; justify-content: space-between; align-items: baseline; gap: var(--sp-2); }
.progress-item .name { font-size: var(--fs-2); }
.progress-item .num { font-size: var(--fs-2); font-weight: 700; color: var(--text); }
.bar { height: 7px; border-radius: 99px; background: var(--fill); overflow: hidden; margin-top: var(--sp-1); }
.bar > i { display: block; height: 100%; background: var(--primary); }

.section-label {
  display: flex; justify-content: space-between; align-items: center;
  color: var(--muted); font-size: var(--fs-1); font-weight: 700;
  letter-spacing: .09em; text-transform: uppercase;
  margin: var(--sp-5) 0 var(--sp-2);
}
.section-label a { text-transform: none; letter-spacing: 0; font-weight: 700; }
```

- [ ] **Step 2: `#today` 섹션 마크업을 바꾼다**

`index.html`의 `<section id="today" hidden>` 안을 이렇게 바꾼다:

```html
  <section id="today" hidden>
    <!-- 히어로는 상황에 따라 세 얼굴을 갖는다(할 일 있음 / 끝 / 첫날). JS가 통째로 채운다 -->
    <div id="hero"></div>

    <div class="stat-row" id="stats"></div>

    <div class="section-label" id="progLabel" hidden>
      <span>분야별 진도</span>
      <a href="/me.html">전체 보기 →</a>
    </div>
    <div class="progress-grid" id="progList" hidden></div>

    <div class="section-label" id="docLabel" hidden><span>최근 개념 문서</span></div>
    <div id="docList"></div>
  </section>
```

기존의 `#onboard` `#tasks` `#domLabel` `#domList` `.quick-grid`는 지운다 —
할 일은 히어로가, 분야 추천은 진도표가 물려받고, 바로가기 카드 셋은 사이드바가 대신한다.

- [ ] **Step 3: 히어로를 그리는 함수를 쓴다**

`index.html`의 스크립트에 더한다:

```javascript
/* 히어로 세 얼굴 — 오늘의 퀴즈 상태가 문구·색·버튼을 다 정한다.
 *
 * [왜 한 함수가 셋을 다 그리나] 세 상태는 <같은 자리에 번갈아> 뜬다. 함수를 셋으로
 * 나누면 "지금 어느 것이 떠 있나"를 부르는 쪽이 관리하게 되고, 둘이 동시에 뜨는
 * 버그가 생길 자리가 만들어진다. 한 함수가 innerHTML을 통째로 쓰면 그럴 수 없다.
 *
 * [실패해도 화면은 남는다] 이 호출이 실패해도 통계·진도는 그린다. 첫 화면이 통째로
 * 비면 앱이 죽은 것처럼 보인다 — 기존 코드가 이미 지키던 규칙이다. */
async function loadHero() {
  const el = document.getElementById("hero");
  try {
    const daily = await api("/api/me/daily-quiz/today");
    const done = daily.solvedCount ?? 0;
    const total = daily.totalCount ?? 0;
    const left = Math.max(total - done, 0);

    if (total === 0) {
      // 첫날 — 아직 오늘 세트가 없다. 무엇을 하는 곳인지부터 말한다
      el.innerHTML = heroHtml({
        cls: "first",
        eyebrow: today(),
        title: "첫 문제를 풀어볼까요",
        lead: "매일 아침 10문제가 준비돼요. 틀린 문제는 잊어버릴 때쯤 다시 나와요.",
        bar: null,
        actions: `<a class="btn" href="/daily.html">오늘의 퀴즈 시작</a>`,
      });
    } else if (left === 0) {
      el.innerHTML = heroHtml({
        cls: "done",
        eyebrow: today(),
        title: "오늘 몫은 끝났어요 ✓",
        lead: `${total}문제 전부 · 내일 아침에 새 문제가 준비돼요`,
        bar: null,
        actions: `<a class="btn btn-outline" href="/quiz.html">더 풀어보기</a>`,
      });
    } else {
      el.innerHTML = heroHtml({
        cls: "",
        eyebrow: `${today()} · 오늘의 퀴즈`,
        title: `${left}문제 남았어요`,
        lead: "10분이면 끝나요.",
        bar: Math.round(done / total * 100),
        actions: `<a class="btn" href="/daily.html">이어서 풀기 →</a>`,
      });
    }
  } catch (e) {
    // 오늘 세트를 못 가져와도 화면은 선다. 들어갈 문은 남겨 둔다
    el.innerHTML = heroHtml({
      cls: "", eyebrow: today(), title: "오늘의 퀴즈",
      lead: "지금은 오늘 진행 상황을 불러오지 못했어요.",
      bar: null, actions: `<a class="btn" href="/daily.html">오늘의 퀴즈로</a>`,
    });
  }
}

function heroHtml({ cls, eyebrow, title, lead, bar, actions }) {
  return `<div class="hero-card ${cls}">
    <div class="eyebrow">${escapeHtml(eyebrow)}</div>
    <h1>${escapeHtml(title)}</h1>
    <p class="lead">${escapeHtml(lead)}</p>
    ${bar === null ? "" :
      `<div class="bar" role="progressbar" aria-valuenow="${bar}" aria-valuemin="0" aria-valuemax="100"
            style="max-width:320px"><i style="width:${bar}%"></i></div>`}
    <div class="actions">${actions}</div>
  </div>`;
}

/** "9월 6일 토요일" — 히어로 맨 윗줄. 매일 오는 화면이라 날짜가 바뀌는 것이 정보다. */
function today() {
  return new Date().toLocaleDateString("ko-KR",
    { month: "long", day: "numeric", weekday: "long" });
}
```

**`/api/me/daily-quiz/today` 응답의 실제 필드 이름을 확인하고 맞춘다:**

```powershell
Select-String -Path src/main/java -Pattern "class DailyQuiz.*Response|record DailyQuiz" -Recurse
```

`solvedCount` / `totalCount`가 아니면 그 이름으로 바꾼다. `daily.html`이 같은 API를
이미 쓰고 있으니 그 코드를 보는 것이 가장 빠르다.

- [ ] **Step 4: 통계와 진도를 그리는 함수를 바꾼다**

기존 `loadSummary()`를 이렇게 바꾼다:

```javascript
/* 통계 넷 + 분야별 진도 여섯.
 *
 * [왜 여섯만 두나] 분야가 여덟인데 홈에 다 깔면 스크롤 두 판이 된다. 홈이 답해야 하는
 * 질문은 "오늘 뭐부터 하지"이지 "나 얼마나 왔지"가 아니다. 뒤쪽 질문은 내 기록이 맡고,
 * 여기에는 [전체 보기 →]만 둔다.
 *
 * [왜 진도순인가] 많이 한 분야가 위로 온다. 적게 한 분야를 위에 두는 안도 있었지만
 * 그러면 매일 0에 가까운 분야가 첫 줄이라 화면이 매일 같다 — 숫자가 바뀌는 것이
 * 이 목록의 값어치인데 그게 사라진다. */
async function loadSummary() {
  try {
    const s = await api("/api/me/study-summary");

    const rate = s.stats.correctRate === null ? "–" : s.stats.correctRate + "%";
    document.getElementById("stats").innerHTML = [
      ["푼 문제", s.stats.solvedTotal],
      ["정답률", rate],
      ["이번 주", s.stats.solvedThisWeek],
      ["복습 대기", s.stats.reviewDue],
    ].map(([k, v]) =>
      `<div class="stat-tile"><div class="k">${k}</div><div class="v">${v}</div></div>`).join("");

    const top = [...s.domains].sort((a, b) => b.solved - a.solved).slice(0, 6);
    if (top.length) {
      document.getElementById("progLabel").hidden = false;
      document.getElementById("progList").hidden = false;
      document.getElementById("progList").innerHTML = top.map(d => {
        // 분모가 0이면 막대를 0으로 둔다 — 0으로 나누면 NaN이 style에 들어가 막대가 사라진다
        const pct = d.total > 0 ? Math.round(d.solved / d.total * 100) : 0;
        return `<div class="progress-item">
          <div class="row">
            <span class="name">${escapeHtml(d.label)}</span>
            <span class="num">${d.solved} <span class="meta">/ ${d.total}</span></span>
          </div>
          <div class="bar" role="progressbar" aria-label="${escapeHtml(d.label)} 진도"
               aria-valuenow="${d.solved}" aria-valuemin="0" aria-valuemax="${d.total}">
            <i style="width:${pct}%"></i>
          </div>
        </div>`;
      }).join("");
    }
  } catch (e) { /* 통계가 없어도 히어로와 문서 목록은 남는다 */ }
}
```

`loadToday()`를 부르던 자리를 `loadHero()`로 바꾼다.

- [ ] **Step 5: 앱을 띄워 세 폭에서 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

| 확인 | 기대 |
| --- | --- |
| 오늘의 퀴즈를 안 푼 상태 | 인디고 히어로, "N문제 남았어요", 진행 바 |
| 오늘 것을 다 푼 상태 | **초록** 히어로, "오늘 몫은 끝났어요 ✓" |
| 통계 4칸 | 375px에서 2×2, 640px 이상에서 1×4 |
| 진도 막대 | 6개, `31 / 40` 절대값이 크고 막대는 아래 얇게. **퍼센트 숫자 없음** |
| [전체 보기 →] | `/me.html`로 간다 (아직 404 — Task 10에서 만든다) |
| 콘솔 | 빨간 줄 0 |

"오늘 것을 다 푼 상태"를 만들려면 `/daily.html`에서 10문제를 실제로 다 풀면 된다.

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(home): 오늘 화면을 히어로·통계·진도 세 덩이로 다시 짠다

히어로가 상황에 따라 세 얼굴을 갖는다 — 할 일 있음(인디고) / 오늘 몫
끝(초록) / 첫날(안내). 한 함수가 셋을 다 그리는 이유는 셋이 같은 자리에
번갈아 뜨기 때문이다. 나누면 "지금 어느 것이 떠 있나"를 부르는 쪽이
관리하게 되고, 둘이 동시에 뜨는 버그가 생길 자리가 만들어진다.

색이 찬 진한 판도 후보였고 처음 오는 사람에게는 그쪽이 명확하다. 하지만
이 화면은 매일 오는 사람의 것이고, 이 파일의 기존 주석이 "매일 오는
사람에게 매일 같은 광고를 보이지 않는다"를 이미 결정으로 적어 두었다.

진도는 절대값이 주인공이다. 31 / 40을 크게 두고 막대는 아래 7px로
얇게 깐다. 퍼센트를 안 쓰는 이유는 배치가 매일 문제를 더해 분모가
커지기 때문이다 — 퍼센트는 가만히 있어도 줄지만 맞힌 개수는 안 준다.

분야 여덟 중 여섯만 둔다. 홈이 답할 질문은 "오늘 뭐부터 하지"이지
"나 얼마나 왔지"가 아니다. 뒤쪽은 내 기록이 맡는다.

바로가기 카드 셋을 뺐다. 사이드바가 늘 떠 있으니 같은 링크를 본문에
한 번 더 깔 이유가 없어졌다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 7: 문제 푸는 화면 셋

**Files:**
- Modify: `src/main/resources/static/js/player.js`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/main/resources/static/quiz.html` `daily.html` `review.html`

**Interfaces:**
- Consumes: `renderShell`, `player.js`의 기존 진행 로직
- Produces: CSS 클래스 `.play-rail` `.play-head` `.q-text` `.opt` `.opt.selected` `.opt.correct` `.opt.wrong` `.explain` `.score-board`

- [ ] **Step 1: 지금 플레이어가 그리는 마크업을 읽는다**

```powershell
Get-Content src/main/resources/static/js/player.js
```

바꿀 것은 **클래스와 배치뿐**이고 진행·채점 로직은 건드리지 않는다.
`.opt` `.q-text` `.explain` `.progress` `.player-top` `.score-board`가 지금 쓰는 이름이다.

- [ ] **Step 2: 플레이어 CSS를 쓴다**

```css
/* ── 문제 푸는 화면 ─────────────────────────────────────────────────
 * [셸을 유지한다] 푸는 동안 사이드바·탭바를 감추는 "집중 모드"도 후보였다. 폰에서
 * 탭바가 먹는 49px이 보기 하나 값어치라 그쪽이 화면은 넓다. 대신 <풀다가 개념 문서로
 * 바로 넘어가는 길>이 끊긴다. 이 앱은 해설에서 개념으로 이어지는 것이 핵심이라
 * 셸을 남기기로 했다.
 *
 * [대신 두 가지로 손실을 메운다]
 *  1) 진행 바를 본문 맨 위에 붙박이로 둔다 — 탭바가 있어도 "지금 몇 번째"가 늘 보인다
 *  2) 폰에서 보기 버튼의 세로 여백을 한 단계 줄인다 — 탭바가 문제를 밀어내지 않게 */
.play-rail { height: 4px; background: var(--fill); border-radius: 99px; overflow: hidden; }
.play-rail > i { display: block; height: 100%; background: var(--primary); transition: width .2s; }

.play-head {
  display: flex; align-items: center; gap: var(--sp-2);
  margin: var(--sp-3) 0 var(--sp-3);
  color: var(--muted); font-size: var(--fs-1);
}
.play-head .count { margin-left: auto; color: var(--text); font-weight: 700; font-size: var(--fs-2); }

.q-text {
  font-size: var(--fs-4); font-weight: 700; letter-spacing: -.01em;
  line-height: 1.55; margin: 0 0 var(--sp-4);
}

/* 보기 — 손가락 표적이라 44px 아래로 내려가지 않는다 */
.opt {
  display: flex; gap: var(--sp-3); align-items: flex-start;
  width: 100%; text-align: left;
  background: var(--card); border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: var(--sp-3);
  min-height: 44px; margin-bottom: var(--sp-2);
  color: var(--text); font-size: var(--fs-3); font-family: inherit;
  cursor: pointer;
}
@media (max-width: 767px) { .opt { padding: 10px var(--sp-3); font-size: var(--fs-2); } }
.opt:hover { border-color: var(--border-strong); }
.opt .key {
  flex: none; width: 22px; height: 22px; border-radius: 6px;
  background: var(--fill); color: var(--muted);
  font-size: var(--fs-1); font-weight: 700;
  display: grid; place-items: center;
}
.opt.selected { border-color: var(--primary); background: var(--primary-soft); }
.opt.selected .key { background: var(--primary); color: var(--card); }

/* 채점 뒤. 색만으로 알리지 않는다 — ✓/✕ 글자를 함께 넣는다(JS가 붙인다) */
.opt.correct { border-color: var(--correct); background: var(--correct-bg); }
.opt.correct .key { background: var(--correct); color: var(--card); }
.opt.wrong { border-color: var(--wrong); background: var(--wrong-bg); }
.opt.wrong .key { background: var(--wrong); color: var(--card); }

.explain {
  background: var(--fill); border: 1px solid var(--border);
  border-left: 3px solid var(--primary);
  border-radius: var(--radius); padding: var(--sp-3);
  font-size: var(--fs-2); margin-top: var(--sp-3);
}

.score-board { text-align: center; padding: var(--sp-5) 0 var(--sp-3); }
.score-board .score { font-size: 44px; font-weight: 800; letter-spacing: -.03em; color: var(--primary); }
.score-board .score .of { font-size: var(--fs-4); color: var(--muted); font-weight: 700; }
```

- [ ] **Step 3: `player.js`가 새 마크업을 그리게 한다**

바꿀 곳 넷이다. **진행·채점 로직은 손대지 않는다.**

아래 조각의 변수 이름(`idx` `items` `q` `correctEl` `myEl`)은 **자리를 가리키는 이름**이다.
`player.js`가 그 자리에서 실제로 쓰는 이름으로 바꿔 넣는다 — Step 1에서 읽은 것이 그 답이다.
새 변수를 만들지 말고 있는 것에 붙인다.

(1) 진행 표시를 `.play-rail` + `.play-head`로:

```javascript
  /* 진행 바를 본문 맨 위에 붙박이로 둔다.
     셸을 유지하기로 해서 화면이 좁아진 대신, "지금 몇 번째"만은 늘 보이게 한다. */
  const head = `
    <div class="play-rail" role="progressbar"
         aria-valuenow="${idx}" aria-valuemin="0" aria-valuemax="${items.length}">
      <i style="width:${Math.round(idx / items.length * 100)}%"></i>
    </div>
    <div class="play-head">
      <span>${escapeHtml(domainLabel(q.domain))} · ${escapeHtml(typeLabel(q.type))}</span>
      ${difficultyBadge(q.difficulty)}
      <span class="count">${idx + 1} / ${items.length}</span>
    </div>`;
```

(2) 보기 버튼에 번호 칩을 넣는다:

```javascript
  /* 번호 칩은 숫자키 힌트와 <같은 값>이다. 예전에는 힌트가 따로 떠 있어
     "1을 누르면 뭐가 골라지는지"를 눈으로 이어야 했다. 칩을 보기 안에 두면
     그 연결이 저절로 된다 — 힌트 줄은 데스크톱에서만 남긴다. */
  `<button class="opt" data-choice="${i}">
     <span class="key" aria-hidden="true">${i + 1}</span>
     <span>${escapeHtml(text)}</span>
   </button>`
```

(3) 채점 뒤 정답/오답 표시에 글자를 함께 붙인다:

```javascript
  /* 색만으로 알리지 않는다 — 초록·빨강이 같은 회색으로 보이는 사람에게
     테두리 색은 아무 정보도 아니다. 배지 글자가 진짜 신호다.
     .verdict를 쓰고 .meta를 안 쓰는 이유는 바로 아래 참고. */
  correctEl.classList.add("correct");
  correctEl.insertAdjacentHTML("beforeend",
    `<span class="verdict">✓ 정답</span>`);
  if (myEl && myEl !== correctEl) {
    myEl.classList.add("wrong");
    myEl.insertAdjacentHTML("beforeend",
      `<span class="verdict">✕ 내 답</span>`);
  }
```

**`.meta`(흐린 글자)를 쓰면 안 된다.** Task 1에서 실제로 재 보니 라이트에서
`--muted`를 `--wrong-bg` 위에 올리면 **4.35:1**로 AA(4.5:1)에 못 미친다.
흰 배경에서는 4.76:1로 통과하던 색이라 눈으로는 안 걸린다 — 색이 있는 판 위로
옮기면 기준을 다시 재야 한다는 사례가 하나 더 생긴 셈이다.
한 단계 진한 `--text-2`를 쓴다(라이트 7.58:1, 다크 10.01:1).

```css
/* 정답/오답 판 위에 얹히는 짧은 라벨. 흐린 글자(--muted)를 쓰면 색이 있는 판 위에서
   대비가 4.35:1로 미달한다 — 흰 배경에서 통과하던 값이라 눈으로는 안 걸린다. */
.opt .verdict { margin-left: auto; color: var(--text-2); font-size: var(--fs-1); font-weight: 700; }
```

(4) 짝짓기·순서 배열은 폰에서 1열로 세운다:

```css
.match-grid { display: grid; grid-template-columns: 1fr; gap: var(--sp-3); }
@media (min-width: 640px) { .match-grid { grid-template-columns: 1fr 1fr; } }
```

- [ ] **Step 4: 세 HTML의 본문 여백을 맞춘다**

`quiz.html` `daily.html` `review.html`의 `<main class="container">`는 그대로 두고,
Task 3에서 넣은 셸 규칙이 여백을 잡는지 확인한다. 안 맞으면 `.container`의
`max-width`가 `main > .container` 규칙과 충돌하는 것이니 기존 `.container` 규칙에서
`margin: 0 auto`만 남기고 폭은 셸 규칙에 맡긴다.

- [ ] **Step 5: 앱을 띄워 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

| 확인 | 기대 |
| --- | --- |
| `/quiz.html`에서 한 판 시작 | 진행 바가 맨 위, 보기가 번호 칩 달린 큰 버튼 |
| 보기를 고른다 | 인디고 테두리 + 옅은 바탕 |
| 답을 낸다 | 정답 초록 + "✓ 정답", 내 답 빨강 + "✕ 내 답", 해설이 아래에 펼쳐짐 |
| 숫자키 1~4 | 그대로 동작한다 |
| Tab 키로 보기를 훑는다 | 포커스 링이 보인다. `.opt`가 `<button>`이라 전역 규칙이 걸린다 |
| 375px | 탭바가 문제를 가리지 않는다. 보기가 화면에 다 보인다 |
| 짝짓기·순서 문제 | 375px에서 1열로 선다 |
| `/daily.html` `/review.html` | 같은 모습 |
| 마지막 문제 뒤 결과 화면 | 점수 큰 숫자 + 틀린 목록 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(player): 문제 푸는 화면을 새 셸과 토큰으로 다시 그린다

진행·채점 로직은 한 줄도 안 건드렸다. 클래스와 배치만 바뀐다.

셸을 유지한다. 푸는 동안 사이드바·탭바를 감추는 집중 모드도 후보였고
폰에서 탭바가 먹는 49px이 보기 하나 값어치라 화면은 그쪽이 넓다. 대신
풀다가 개념 문서로 바로 넘어가는 길이 끊긴다 — 이 앱은 해설에서 개념으로
이어지는 것이 핵심이라 셸을 남겼다.

잃은 세로 공간은 둘로 메웠다. 진행 바를 본문 맨 위에 붙박이로 둬
탭바가 있어도 "지금 몇 번째"가 늘 보이게 했고, 폰에서 보기 버튼의
세로 여백을 한 단계 줄였다.

번호 칩을 보기 안으로 옮겼다. 예전에는 숫자키 힌트가 따로 떠 있어
"1을 누르면 뭐가 골라지는지"를 눈으로 이어야 했다.

정답·오답에 ✓/✕ 글자를 함께 붙인다. 초록과 빨강이 같은 회색으로
보이는 사람에게 테두리 색은 아무 정보도 아니다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 8: 나머지 기존 화면을 새 토큰·눈금으로

기능은 그대로 두고 셸·토큰·타이포만 맞춘다.

**Files:**
- Modify: `problems.html` `documents.html` `document.html` `wrong-answers.html` `login.html` `signup.html`
- Modify: `src/main/resources/static/css/style.css` (`.card` `.btn` `.badge` `.filters` `.field` 등 공통 컴포넌트를 새 눈금으로)

**Interfaces:**
- Consumes: Task 1의 눈금 변수, Task 3의 셸
- Produces: 없음 (기존 클래스 이름 유지)

- [ ] **Step 1: 공통 컴포넌트를 눈금에 맞춘다**

`style.css`의 기존 `.card` `.btn` `.badge` `.filters` `.field` `.meta` `.alert` 규칙에서
**임의 픽셀값을 눈금 변수로 바꾼다.** 값이 눈금에 없으면 가장 가까운 눈금으로 올린다.

예:

```css
/* 바꾸기 전: padding: 18px; font-size: 14px; margin-bottom: 10px;
   바꾼 뒤 — 값을 눈금으로 올린다. 18은 16(--sp-4)으로, 14는 13(--fs-2)으로,
   10은 8(--sp-2)로. 1~2px 차이는 눈으로 안 보이지만, 눈금 밖 값이 하나 남으면
   다음 사람이 그 값을 근거 삼아 또 다른 임의값을 만든다. */
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--sp-4);
  margin-bottom: var(--sp-3);
}
```

`.btn`은 손가락 표적이라 `min-height: 44px`를 넣는다.

- [ ] **Step 2: 여섯 화면을 하나씩 띄워 보며 고친다**

한 화면씩 본다. 고칠 것은 대개 셋 중 하나다.
- 폭이 안 맞는다 → 화면 안의 `max-width`가 셸 규칙과 겹친다. 화면 쪽을 지운다
- 표가 375px에서 잘린다 → 그 표를 `overflow-x: auto` 컨테이너로 감싼다
- 색이 안 바뀐다 → 하드코딩된 hex가 남아 있다. 변수로 바꾼다

하드코딩된 색을 찾는 법:

```powershell
Select-String -Path src/main/resources/static -Pattern "#[0-9a-fA-F]{3,6}" -Recurse |
  Where-Object { $_.Path -notlike "*style.css" }
```

`style.css` 밖의 hex는 전부 변수로 바꾼다. **HTML 안의 `style="color:#..."`이 남아 있으면
다크에서 그 자리만 안 바뀐다** — 다크가 반쯤 깨져 보이는 원인이 대개 이것이다.

| 화면 | 특히 볼 것 |
| --- | --- |
| `problems.html` | **분야 필터가 좁은 폭에서 깨져 있다** — 아래 참고. 그 외 배치는 안 바꾼다(docs/18에서 다듬은 화면) |

**`problems.html`의 분야 필터 (Task 3에서 발견, 셸과 무관한 기존 결함).**
좁은 폭에서 분야 목록이 가로 스크롤 칩 줄로 바뀌게 돼 있는데(`.pl-domains`),
실제로는 칩 하나가 폭을 통째로 먹어 **"네트워크" 한 줄만 보이고 가로·세로 스크롤바가
둘 다 생긴다**. 375px와 768px에서 같다 — 셸을 넣기 전에도 같은 규칙을 타고 있었다.
`.pl-domain`이 원래 블록 요소라 `flex: none`만으로는 폭이 안 줄어드는 것이 원인으로 보인다.
이 화면을 옮길 때 실제로 띄워 확인하고 고친다.
| `documents.html` | 카드 그리드가 1열로 서는지 |
| `document.html` | 본문 글줄 길이. 읽는 화면이라 `max-width: 68ch` 정도로 잡는다 |
| `wrong-answers.html` | 목록 줄이 잘리지 않는지 |
| `login.html` `signup.html` | 셸이 뜨는지(비로그인이라 탭이 셋). 폼이 가운데 서는지 |

- [ ] **Step 3: 세 폭에서 전부 확인한다**

```powershell
.\gradlew.bat bootRun --console=plain
```

여섯 화면 × 세 폭. 가로 스크롤바가 생기는 화면이 없어야 한다.
콘솔 빨간 줄 0.

- [ ] **Step 4: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(ui): 나머지 화면 여섯을 새 눈금과 셸에 맞춘다

기능은 그대로 두고 셸·토큰·타이포만 옮겼다.

임의 픽셀값을 눈금 변수로 올렸다. 18을 16으로, 14를 13으로 바꾸는 일은
눈으로는 안 보이지만, 눈금 밖 값이 하나 남으면 다음 사람이 그 값을
근거 삼아 또 다른 임의값을 만든다.

style.css 밖에 남아 있던 하드코딩 hex를 전부 변수로 바꿨다. HTML의
인라인 style에 색이 박혀 있으면 다음 단계의 다크 모드에서 그 자리만
안 바뀐다 — 다크가 반쯤 깨져 보이는 원인이 대개 이것이다.

문제 목록은 배치를 바꾸지 않았다. docs/18에서 막 다듬은 화면이고,
이번 개편의 목적은 그 결정을 되돌리는 게 아니라 앱 전체를 거기 맞추는
것이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 9: 랜딩 (비로그인 첫 화면)

**Files:**
- Modify: `src/main/resources/static/index.html` (`#intro` 섹션)
- Modify: `src/main/resources/static/css/style.css` (랜딩 전용)

**Interfaces:**
- Consumes: `GET /api/stats`(Task 5), `GET /api/problems?size=1`
- Produces: 없음

- [ ] **Step 1: 랜딩 CSS를 쓴다**

```css
/* ── 랜딩 ──
 * [왜 문제를 하나 보여 주나] 설명 열 줄보다 문제 한 개가 낫다. 이 서비스가 뭘 주는지는
 * "CS 면접 대비"라는 말이 아니라 실제 문제의 <난이도와 결>이 말해 준다.
 * 문제 조회는 공개 API라 로그인 없이 가져올 수 있다 — 채점만 로그인이 필요하다. */
.landing { text-align: center; padding: var(--sp-6) 0 var(--sp-5); }
.landing h1 {
  font-size: var(--fs-6); font-weight: 800; letter-spacing: -.035em;
  line-height: 1.25; margin: 0 0 var(--sp-2);
}
.landing h1 .accent { color: var(--primary); }
.landing .lead { color: var(--text-2); font-size: var(--fs-3); margin: 0 0 var(--sp-4); }
.landing .cta { display: flex; gap: var(--sp-2); justify-content: center; flex-wrap: wrap; }

.landing-points { display: grid; grid-template-columns: 1fr; gap: var(--sp-2); margin-top: var(--sp-6); text-align: left; }
@media (min-width: 640px) { .landing-points { grid-template-columns: repeat(3, 1fr); } }
.landing-points .card { margin: 0; }
.landing-points .ic { font-size: 20px; line-height: 1; }

.landing-sample { text-align: left; margin-top: var(--sp-5); }
```

- [ ] **Step 2: `#intro` 섹션을 다시 쓴다**

```html
  <section class="landing" id="intro" hidden>
    <h1>CS 면접, <span class="accent">매일 조금씩</span></h1>
    <p class="lead">매일 나에게 맞춘 10문제 — 틀린 건 잊어버릴 때쯤 다시 만나요.</p>
    <div class="cta">
      <a href="/signup.html" class="btn btn-lg">무료로 시작하기</a>
      <a href="/quiz.html" class="btn btn-outline btn-lg">가입 없이 한 문제 풀어보기</a>
    </div>

    <div class="landing-points">
      <div class="card"><div class="ic">📅</div><b>매일 10문제</b>
        <p class="meta">아침마다 새로 준비됩니다. 10분이면 끝나요.</p></div>
      <div class="card"><div class="ic">🔁</div><b>잊을 때쯤 다시</b>
        <p class="meta">틀린 문제는 하루·사흘·일주일 뒤에 다시 나옵니다.</p></div>
      <div class="card"><div class="ic">📚</div><b>개념도 같이</b>
        <p class="meta">틀린 자리에서 바로 읽을 문서가 붙어 있어요.</p></div>
    </div>

    <div class="landing-sample">
      <div class="section-label"><span>이런 문제가 나옵니다</span></div>
      <div class="card" id="sampleProblem"></div>
      <p class="meta" id="statsLine" style="text-align:center"></p>
    </div>
  </section>
```

- [ ] **Step 3: 샘플 문제와 집계를 채운다**

```javascript
/* 랜딩의 샘플 문제와 집계 한 줄.
 *
 * [왜 숫자를 서버에서 받나] "문제 296개"를 HTML에 글로 박으면 배치가 도는 다음 날
 * 거짓말이 된다. 이 앱은 매일 문제를 더하는 것이 기능이라 정적인 숫자가 특히 빨리 낡는다.
 *
 * [둘 다 실패해도 랜딩은 선다] 히어로와 설명 카드 셋은 서버가 없어도 그려진다.
 * 첫 화면이 통째로 비면 서비스가 죽은 것처럼 보인다 — 정작 가입을 권해야 하는 자리다. */
async function loadLanding() {
  try {
    const page = await api("/api/problems?size=1");
    const p = (page.content || [])[0];
    if (p) {
      document.getElementById("sampleProblem").innerHTML = `
        <div class="meta">${escapeHtml(domainLabel(p.domain))} · ${escapeHtml(typeLabel(p.type))}
          ${difficultyBadge(p.difficulty)}</div>
        <div class="q-text" style="font-size:var(--fs-3);margin:var(--sp-2) 0 var(--sp-3)">
          ${escapeHtml(p.question)}</div>
        <div class="meta">가입하면 채점과 해설을 볼 수 있어요.</div>`;
    } else {
      document.getElementById("sampleProblem").hidden = true;
    }
  } catch (e) {
    document.getElementById("sampleProblem").hidden = true;
  }

  try {
    const s = await api("/api/stats");
    document.getElementById("statsLine").textContent =
      `문제 ${s.problemCount}개 · 개념 문서 ${s.documentCount}편 · ${s.domainCount}개 분야`;
  } catch (e) { /* 숫자를 못 세면 그 줄만 비운다 — 거짓 숫자를 띄우느니 안 띄운다 */ }
}
```

비로그인 분기에서 부른다:

```javascript
if (!isLoggedIn()) {
  document.getElementById("intro").hidden = false;
  loadLanding();
}
```

**`GET /api/problems`가 정말 공개인지 확인한다.** `MENUS`에서 문제 화면을 `need: "user"`로
둔 것은 화면 이야기이고 API는 다를 수 있다. 401이 오면 샘플 문제는 빼고 집계 줄만 남긴다.

```powershell
Select-String -Path src/main/java -Pattern "api/problems" -Recurse
```

- [ ] **Step 4: 앱을 띄워 확인한다**

로그아웃한 상태로 `http://localhost:8080`:

| 확인 | 기대 |
| --- | --- |
| 히어로·버튼 둘 | 뜬다 |
| 설명 카드 셋 | 375px 1열, 640px 이상 3열 |
| 샘플 문제 | 실제 문제 하나가 뜬다 |
| 집계 줄 | "문제 N개 · 개념 문서 M편 · 8개 분야". **숫자가 실제 DB와 맞는지** 확인 |
| 사이드바 | 오늘·개념 문서만 (탭바는 둘) |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 5: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(landing): 비로그인 첫 화면을 제대로 만든다

지금까지는 히어로 한 덩이가 전부였다. 무엇을 하는 곳인지 모르는 사람에게
설명 없이 "시작하기" 버튼만 보여 주고 있었다.

핵심은 실제 문제를 하나 보여 주는 것이다. 설명 열 줄보다 문제 한 개가
낫다 — 이 서비스가 뭘 주는지는 "CS 면접 대비"라는 말이 아니라 실제
문제의 난이도와 결이 말한다. 문제 조회는 공개 API라 로그인 없이 된다.

집계 숫자는 서버에서 받는다. HTML에 글로 박으면 배치가 도는 다음 날
거짓말이 된다. 못 세면 그 줄만 비운다 — 거짓 숫자를 띄우느니 안 띄운다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 10: 내 기록 (`me.html`)

**Files:**
- Create: `src/main/resources/static/me.html`
- Modify: `src/main/resources/static/css/style.css` (약한 곳 카드, 정렬 전환)

**Interfaces:**
- Consumes: `renderShell`, `GET /api/me/study-summary`(`total` 포함)
- Produces: 없음

- [ ] **Step 1: `me.html`을 만든다**

```html
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>내 기록 — csquiz</title>
<link rel="stylesheet" href="/css/style.css">
</head>
<body>
<div id="shell"></div>

<main class="container">
  <h1 id="pageTitle">내 기록</h1>
  <p class="meta" id="sinceLine"></p>

  <div class="stat-row" id="stats"></div>

  <div class="section-label">
    <span>분야별</span>
    <span class="seg" id="sortSeg">
      <button class="seg-btn active" data-sort="solved">진도순</button>
      <button class="seg-btn" data-sort="name">이름순</button>
    </span>
  </div>
  <div class="progress-grid" id="progList"></div>

  <div class="section-label"><span>약한 곳</span></div>
  <div id="weakSpot"></div>

  <div class="section-label"><span>더 보기</span></div>
  <div class="card">
    <a href="/wrong-answers.html">오답노트 — 틀린 문제와 내 답을 다시 읽기</a>
  </div>
  <div class="card">
    <a href="/settings.html">설정 — 테마·글자 크기·퀴즈 기본값</a>
  </div>
</main>

<script src="/js/api.js"></script>
<script src="/js/shell.js"></script>
<script>
/* 내 기록 — 홈이 "오늘 뭐부터 하지"라면 이 화면은 "나 얼마나 왔지"다.
 *
 * [왜 홈과 나누나] 두 질문의 답은 매일 다른 속도로 바뀐다. 오늘 할 일은 하루에
 * 여러 번 보고, 전체 진도는 일주일에 한 번 본다. 한 화면에 두면 자주 보는 것이
 * 가끔 보는 것에 밀린다.
 *
 * [활동 격자를 안 넣는다] 잔디밭 같은 것을 붙일 자리지만 뺐다. 이 앱은 이미
 * 스트릭(연속 며칠)을 안 쓰기로 정했다 — 하루 빠졌다고 벌을 주는 장치가
 * "매일 조금씩"이라는 약속과 어긋나서다. 격자도 같은 종류의 압박이다.
 *
 * [폰에서 이 화면이 "나" 탭이다] 탭이 다섯이 상한이라 설정을 따로 못 걸었다.
 * 그래서 아래 "더 보기"에 설정 입구를 둔다 — 사이드바에서는 따로 서 있다. */
if (!isLoggedIn()) {
  location.href = "/login.html";
} else {
  renderShell({ active: "me", title: "내 기록" });
  loadMe();
  document.getElementById("sortSeg").addEventListener("click", e => {
    const btn = e.target.closest(".seg-btn");
    if (!btn) return;
    document.querySelectorAll("#sortSeg .seg-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    drawProgress(btn.dataset.sort);
  });
}

let summary = null;

async function loadMe() {
  try {
    summary = await api("/api/me/study-summary");
  } catch (e) {
    document.getElementById("stats").innerHTML =
      `<p class="meta">기록을 불러오지 못했어요. 잠시 뒤 새로고침해 주세요.</p>`;
    return;
  }

  const s = summary.stats;
  const solvedAll = summary.domains.reduce((a, d) => a + d.solved, 0);
  const totalAll = summary.domains.reduce((a, d) => a + d.total, 0);

  document.getElementById("stats").innerHTML = [
    ["푼 문제", `${s.solvedTotal}<span class="meta"> / ${totalAll}</span>`],
    ["정답률", s.correctRate === null ? "–" : s.correctRate + "%"],
    ["이번 주", s.solvedThisWeek],
    ["복습 대기", s.reviewDue],
  ].map(([k, v]) =>
    `<div class="stat-tile"><div class="k">${k}</div><div class="v">${v}</div></div>`).join("");

  drawProgress("solved");
  drawWeakSpot();
}

/* 분야 여덟을 전부 그린다 — 홈은 여섯만 보여 주고 나머지를 여기로 넘긴다.
 * 정렬은 둘이다. 진도순은 "많이 한 것부터", 이름순은 "찾던 분야를 바로".
 * 정답률순은 넣지 않았다 — 서버가 분야별 정답률을 주지 않는다. 없는 데이터로
 * 만든 정렬은 화면에서만 그럴듯하고 클릭하면 아무 일도 안 일어난다. */
function drawProgress(sort) {
  const list = [...summary.domains].sort(
    sort === "solved" ? (a, b) => b.solved - a.solved
                      : (a, b) => a.label.localeCompare(b.label, "ko"));

  document.getElementById("progList").innerHTML = list.map(d => {
    const pct = d.total > 0 ? Math.round(d.solved / d.total * 100) : 0;
    return `<div class="progress-item">
      <div class="row">
        <span class="name">${escapeHtml(d.label)}</span>
        <span class="num">${d.solved} <span class="meta">/ ${d.total}</span></span>
      </div>
      <div class="bar" role="progressbar" aria-label="${escapeHtml(d.label)} 진도"
           aria-valuenow="${d.solved}" aria-valuemin="0" aria-valuemax="${d.total}">
        <i style="width:${pct}%"></i>
      </div>
    </div>`;
  }).join("");
}

/* 약한 곳 — 손을 가장 덜 댄 분야 하나.
 *
 * [왜 정답률이 아니라 진도인가] "약한 곳"의 정의로는 정답률이 더 맞지만 서버가
 * 분야별 정답률을 주지 않는다. 없는 값을 추측해서 보여 주느니, 있는 값으로
 * 정직한 말을 한다 — "가장 적게 푼 분야"라고 화면에도 그렇게 적는다.
 * (분야별 정답률이 생기면 여기부터 바꾼다.)
 *
 * [문제가 0개인 분야는 후보에서 뺀다] 아직 문제를 안 만든 분야가 늘 1등이 된다. */
function drawWeakSpot() {
  const candidates = summary.domains.filter(d => d.total > 0);
  if (!candidates.length) { document.getElementById("weakSpot").hidden = true; return; }

  const weak = candidates.reduce((a, b) =>
    (a.solved / a.total) <= (b.solved / b.total) ? a : b);

  document.getElementById("weakSpot").innerHTML = `
    <div class="card" style="display:flex;align-items:center;gap:var(--sp-3);flex-wrap:wrap">
      <div style="flex:1;min-width:180px">
        <b>${escapeHtml(weak.label)}</b>
        <div class="meta">가장 적게 푼 분야예요 — ${weak.total}문제 중 ${weak.solved}개</div>
      </div>
      <a class="btn btn-outline" href="/problems.html?domain=${encodeURIComponent(weak.domain)}">
        이 분야만 풀기</a>
    </div>`;
}
</script>
</body>
</html>
```

- [ ] **Step 2: 정렬 전환(`.seg`) CSS를 쓴다**

```css
/* 두세 갈래를 나란히 두는 전환 — 설정 화면(Task 11)도 같은 것을 쓴다.
   드롭다운 대신 이걸 쓰는 이유: 선택지가 셋 이하면 <펼치지 않고> 다 보이는 편이
   빠르고, 지금 무엇이 골라져 있는지도 한눈에 보인다. */
.seg { display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; background: var(--card); }
.seg-btn {
  padding: 6px var(--sp-3); min-height: 32px;
  background: none; border: 0; border-right: 1px solid var(--border);
  color: var(--text-2); font-family: inherit; font-size: var(--fs-1);
  font-weight: 600; cursor: pointer; text-transform: none; letter-spacing: 0;
}
.seg-btn:last-child { border-right: 0; }
.seg-btn.active { background: var(--primary); color: var(--card); font-weight: 700; }
```

- [ ] **Step 3: `problems.html`이 `?domain=` 을 읽는지 확인한다**

```powershell
Select-String -Path src/main/resources/static/problems.html -Pattern "searchParams|location.search"
```

안 읽으면 "이 분야만 풀기" 링크가 필터 없이 열린다. 그 경우 `problems.html`의
초기화에서 쿼리스트링을 읽어 필터 초기값으로 넣는다:

```javascript
// 내 기록의 "이 분야만 풀기"가 이 값을 달고 온다. 링크로 필터를 걸 수 있으면
// 화면끼리 이어진다 — 사용자가 필터를 다시 손으로 맞추지 않는다.
const preset = new URLSearchParams(location.search).get("domain");
if (preset) document.getElementById("fDomain").value = preset;
```

- [ ] **Step 4: 앱을 띄워 확인한다**

| 확인 | 기대 |
| --- | --- |
| `/me.html` | 통계 4칸, 분야 **8개 전부**, 약한 곳 카드 |
| 진도순 ↔ 이름순 | 목록 순서가 바뀐다 |
| "이 분야만 풀기" | 문제 목록이 그 분야로 걸린 채 열린다 |
| 사이드바 | "내 기록"이 굵게 |
| 375px | "나" 탭이 굵게. 통계 2×2, 진도 1열 |
| 비로그인으로 열기 | 로그인 화면으로 보낸다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 5: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(me): 내 학습 기록 화면을 만든다

홈이 "오늘 뭐부터 하지"라면 이 화면은 "나 얼마나 왔지"다. 두 질문의
답은 다른 속도로 바뀐다 — 오늘 할 일은 하루에 여러 번 보고 전체 진도는
일주일에 한 번 본다. 한 화면에 두면 자주 보는 것이 가끔 보는 것에 밀린다.

정렬을 진도순과 이름순 둘만 뒀다. 정답률순은 넣지 않았다 — 서버가
분야별 정답률을 주지 않는다. 없는 데이터로 만든 정렬은 화면에서만
그럴듯하고 눌러도 아무 일이 안 일어난다.

같은 이유로 "약한 곳"도 정답률이 아니라 진도로 고른다. 정의로는
정답률이 맞지만 값이 없다. 그래서 화면에도 "가장 적게 푼 분야"라고
있는 그대로 적는다.

활동 격자는 뺐다. 이 앱은 이미 스트릭을 안 쓰기로 정했다 — 하루
빠졌다고 벌을 주는 장치가 "매일 조금씩"이라는 약속과 어긋나서다.
격자도 같은 종류의 압박이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 11: 설정 (`settings.html`)

**Files:**
- Create: `src/main/resources/static/settings.html`
- Modify: `src/main/resources/static/js/shell.js` (설정 값을 읽는 헬퍼)

**Interfaces:**
- Consumes: `renderShell`, `applyStoredTheme`
- Produces: `shell.js`의 `getPref(key)` / `setPref(key, value)` / `applyStoredFontSize()` — 기본값은 인자가 아니라 `PREF_DEFAULTS` 상수에서 온다. 부르는 쪽마다 기본값을 적으면 "이 설정의 기본이 뭔가"의 답이 여러 곳에 생기고, 하나만 고치면 화면마다 다르게 동작한다. `localStorage` 키는 `csquiz_theme` `csquiz_fontsize` `csquiz_quizsize` `csquiz_keyhint`. Task 12와 `quiz.html`·`player.js`가 읽는다.

- [ ] **Step 1: `shell.js`에 설정 헬퍼를 더한다**

```javascript
/* ── 사용자 설정 ──────────────────────────────────────────────────────
 * [왜 서버가 아니라 브라우저인가] 이 설정들(테마·글자 크기·퀴즈 기본값)은 <기기의
 * 성질>이지 계정의 성질이 아니다. 낮에 회사 모니터에서 라이트, 밤에 폰에서 다크가
 * 자연스럽다. 서버에 두면 기기를 옮길 때마다 도로 바뀐다.
 *
 * [대가] 브라우저를 지우면 사라진다. 그래서 설정 화면에 "지금 쓰는 브라우저에만
 * 저장됩니다"라고 적는다 — 사라지는 것이 버그로 보이지 않게.
 *
 * [try/catch를 두르는 이유] 시크릿 모드나 사이트 데이터 차단에서 localStorage
 * 접근 자체가 예외를 던진다. 설정을 못 읽는다고 앱이 멈추면 안 된다. */
const PREF_DEFAULTS = {
  csquiz_theme: "auto",      // auto | light | dark
  csquiz_fontsize: "normal", // small | normal | large
  csquiz_quizsize: "10",
  csquiz_keyhint: "on",      // on | off
};

function getPref(key) {
  try {
    return localStorage.getItem(key) ?? PREF_DEFAULTS[key];
  } catch (e) {
    return PREF_DEFAULTS[key];
  }
}

function setPref(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* 저장 못 해도 이번 세션은 적용된다 */ }
}

/* 글자 크기 — <html>에 클래스를 붙이고 CSS가 --fs-4(문제 지문)만 키운다.
   본문 전체를 키우지 않는 이유: 목록·메뉴까지 커지면 한 화면에 들어가는 줄이
   줄어 오히려 읽기 나빠진다. 키워서 득을 보는 것은 오래 보는 문제 지문이다. */
function applyStoredFontSize() {
  const v = getPref("csquiz_fontsize");
  document.documentElement.classList.remove("fs-small", "fs-large");
  if (v === "small") document.documentElement.classList.add("fs-small");
  if (v === "large") document.documentElement.classList.add("fs-large");
}
```

`renderShell` 안의 `applyStoredTheme()` 다음 줄에 `applyStoredFontSize();`를 더한다.

`style.css`에 크기 규칙을 더한다:

```css
/* 문제 지문만 키운다 — 메뉴·목록은 그대로다 */
.fs-small .q-text { font-size: var(--fs-3); }
.fs-large .q-text { font-size: var(--fs-5); }
```

- [ ] **Step 2: `settings.html`을 만든다**

```html
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>설정 — csquiz</title>
<link rel="stylesheet" href="/css/style.css">
</head>
<body>
<div id="shell"></div>

<main class="container">
  <h1>설정</h1>
  <p class="meta">이 설정은 지금 쓰는 브라우저에만 저장됩니다.</p>

  <div class="section-label"><span>화면</span></div>
  <div class="setting-row">
    <div><b>테마</b><div class="meta">기기 설정을 따르거나 직접 고릅니다</div></div>
    <span class="seg" data-pref="csquiz_theme">
      <button class="seg-btn" data-value="auto">자동</button>
      <button class="seg-btn" data-value="light">라이트</button>
      <button class="seg-btn" data-value="dark">다크</button>
    </span>
  </div>
  <div class="setting-row">
    <div><b>글자 크기</b><div class="meta">문제 지문에 적용됩니다</div></div>
    <span class="seg" data-pref="csquiz_fontsize">
      <button class="seg-btn" data-value="small">작게</button>
      <button class="seg-btn" data-value="normal">보통</button>
      <button class="seg-btn" data-value="large">크게</button>
    </span>
  </div>

  <div class="section-label"><span>퀴즈</span></div>
  <div class="setting-row">
    <div><b>한 판 문제 수</b><div class="meta">자유 퀴즈를 시작할 때의 기본값</div></div>
    <span class="seg" data-pref="csquiz_quizsize">
      <button class="seg-btn" data-value="5">5</button>
      <button class="seg-btn" data-value="10">10</button>
      <button class="seg-btn" data-value="20">20</button>
    </span>
  </div>
  <div class="setting-row">
    <div><b>숫자키 힌트 보이기</b><div class="meta">보기 옆의 1·2·3 표시</div></div>
    <span class="seg" data-pref="csquiz_keyhint">
      <button class="seg-btn" data-value="on">켬</button>
      <button class="seg-btn" data-value="off">끔</button>
    </span>
  </div>

  <div class="section-label"><span>계정</span></div>
  <div class="setting-row">
    <div id="accountLine"></div>
    <button class="btn btn-outline" id="logoutBtn">로그아웃</button>
  </div>
</main>

<script src="/js/api.js"></script>
<script src="/js/shell.js"></script>
<script>
/* 설정 — 전부 브라우저에 저장한다(서버를 건드리지 않는다).
 *
 * [비밀번호 변경이 없는 이유] 그 API가 서버에 없다. 눌러도 아무 일이 안 나는 버튼을
 * 두느니 항목을 빼는 편이 정직하다. 만들려면 현재 비밀번호 검증과 refresh 토큰
 * 전체 폐기(다른 기기 로그아웃)까지 따라오는데, 이번 작업은 화면 개편이다.
 *
 * [저장 즉시 적용] "저장" 버튼을 따로 두지 않는다. 항목이 넷뿐이고 전부 되돌리기
 * 쉬운 것들이라, 눌렀는데 아무 변화가 없고 저장을 또 눌러야 하는 편이 더 헷갈린다. */
if (!isLoggedIn()) {
  location.href = "/login.html";
} else {
  renderShell({ active: "settings", title: "설정" });
  markCurrent();
  document.getElementById("accountLine").innerHTML =
    `<b>${escapeHtml(localStorage.getItem(USERNAME_KEY) || "")}</b>
     <div class="meta">${isAdmin() ? "관리자" : "일반 사용자"}</div>`;

  document.querySelectorAll(".seg[data-pref]").forEach(seg => {
    seg.addEventListener("click", e => {
      const btn = e.target.closest(".seg-btn");
      if (!btn) return;
      setPref(seg.dataset.pref, btn.dataset.value);
      markCurrent();
      // 테마와 글자 크기는 이 화면에서 바로 보여 준다 — 고르고 나서
      // 다른 화면에 가야 확인되면 무엇이 바뀌는 설정인지 알 수 없다
      applyStoredTheme();
      applyStoredFontSize();
    });
  });

  document.getElementById("logoutBtn").id = "logoutLink"; // wireLogout이 찾는 id
  wireLogout();
}

/** 저장된 값에 맞춰 눌린 버튼을 표시한다. 화면을 다시 열어도 지금 값이 보인다. */
function markCurrent() {
  document.querySelectorAll(".seg[data-pref]").forEach(seg => {
    const now = getPref(seg.dataset.pref);
    seg.querySelectorAll(".seg-btn").forEach(b =>
      b.classList.toggle("active", b.dataset.value === now));
  });
}
</script>
</body>
</html>
```

- [ ] **Step 3: `.setting-row` CSS를 쓴다**

```css
.setting-row {
  display: flex; align-items: center; gap: var(--sp-4);
  padding: var(--sp-3) 0; border-top: 1px solid var(--border);
  flex-wrap: wrap;
}
.setting-row > div:first-child { flex: 1; min-width: 160px; }
```

- [ ] **Step 4: `quiz.html`이 저장된 기본값을 쓰게 한다**

`quiz.html`의 `#fSize` 초기화에 더한다:

```javascript
// 설정 화면에서 고른 기본값. 없으면 10이다(PREF_DEFAULTS).
document.getElementById("fSize").value = getPref("csquiz_quizsize");
```

`player.js`의 숫자키 힌트 표시에도 건다:

```javascript
// 설정에서 끄면 힌트 줄을 안 그린다. 번호 칩은 그대로 둔다 —
// 칩은 힌트가 아니라 보기의 이름이라, 키보드를 안 써도 "3번 보기"라고 말할 수 있어야 한다
if (getPref("csquiz_keyhint") === "on") { /* 기존 kbd-hint 그리는 코드 */ }
```

- [ ] **Step 5: 앱을 띄워 확인한다**

| 확인 | 기대 |
| --- | --- |
| 테마를 "다크"로 | **화면이 그 자리에서 어두워진다** |
| 테마를 "라이트"로 | 기기가 다크여도 밝아진다 |
| 테마를 "자동"으로 | 기기 설정을 따른다 (OS 테마를 바꿔 확인) |
| 새로고침 | 고른 값이 유지된다 |
| 글자 크기 "크게" → 퀴즈 | 문제 지문만 커지고 메뉴는 그대로 |
| 한 판 문제 수 "20" → `/quiz.html` | 문제 수 선택이 20으로 열린다 |
| 숫자키 힌트 "끔" → 퀴즈 | 힌트 줄이 사라지고 번호 칩은 남는다 |
| 로그아웃 | 동작한다 |
| 375px | 항목이 두 줄로 감싸진다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(settings): 설정 화면을 만든다 — 브라우저에 저장하는 것만

테마·글자 크기·퀴즈 기본값·숫자키 힌트 넷이다. 서버를 건드리지 않는다.

이 값들은 기기의 성질이지 계정의 성질이 아니다. 낮에 회사 모니터에서
라이트, 밤에 폰에서 다크가 자연스럽다. 서버에 두면 기기를 옮길 때마다
도로 바뀐다. 대가는 브라우저를 지우면 사라지는 것이라, 화면에
"지금 쓰는 브라우저에만 저장됩니다"라고 적어 뒀다.

비밀번호 변경은 넣지 않았다. 그 API가 서버에 없고, 만들려면 현재
비밀번호 검증과 refresh 토큰 전체 폐기까지 따라온다. 눌러도 아무 일이
안 나는 버튼을 두느니 항목을 빼는 편이 정직하다.

저장 버튼을 따로 두지 않았다. 항목이 넷뿐이고 전부 되돌리기 쉬운
것들이라, 눌렀는데 아무 변화가 없고 저장을 또 눌러야 하는 편이 더
헷갈린다.

글자 크기는 문제 지문에만 건다. 본문 전체를 키우면 목록·메뉴까지
커져 한 화면에 들어가는 줄이 줄고 오히려 읽기 나빠진다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 12: 다크 모드를 실제로 켜고 대비를 잰다

토큰은 Task 1에 있고 전환 UI는 Task 11에 있다. 여기서 하는 일은
**모든 화면이 실제로 변수를 쓰는지 확인하고, 대비를 재고, FOUC를 막는 것**이다.

**Files:**
- Modify: 사용자 HTML 13개 (`<head>`에 인라인 한 줄)
- Modify: `src/main/resources/static/css/style.css` (남은 하드코딩 색)

**Interfaces:**
- Consumes: Task 1의 토큰, Task 11의 `csquiz_theme`
- Produces: 없음

- [ ] **Step 1: FOUC를 막는 한 줄을 13개 `<head>`에 넣는다**

`<link rel="stylesheet">` **앞**에 넣는다:

```html
<script>
/* 저장된 테마를 CSS가 오기 전에 <html>에 붙인다 — 이 한 줄만 인라인인 이유.
   shell.js는 <body> 끝에서 도는데, 거기서 붙이면 다크를 고른 사람도
   첫 그림이 라이트로 한 번 번쩍인다(FOUC). 눈에 확실히 보이는 결함이다.
   나머지 테마 코드(전환 UI·저장·기기 설정 감지)는 전부 shell.js에 있다. */
try {
  var t = localStorage.getItem("csquiz_theme");
  if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
} catch (e) {}
</script>
<link rel="stylesheet" href="/css/style.css">
```

넣을 파일 13개: `index` `daily` `document` `documents` `login` `me` `problems`
`quiz` `review` `settings` `signup` `wrong-answers` (12개 — 관리 콘솔은 Task 10 계획에서 한다).

- [ ] **Step 2: 남은 하드코딩 색을 모두 없앤다**

```powershell
Select-String -Path src/main/resources/static -Pattern "#[0-9a-fA-F]{3,6}" -Recurse |
  Where-Object { $_.Line -notmatch "^\s*(--|/\*)" }
```

`style.css`의 토큰 선언부(`:root`, `@media`, `[data-theme]`)를 뺀 모든 hex를 변수로 바꾼다.
JS가 만드는 인라인 스타일(`style="color:#..."`)도 포함이다 — **이게 남아 있으면
다크에서 그 자리만 안 바뀌어 화면이 반쯤 깨져 보인다.**

- [ ] **Step 3: 12개 화면을 다크로 전부 본다**

설정에서 테마를 "다크"로 바꾸고 화면을 하나씩 연다.

| 볼 것 | 기대 |
| --- | --- |
| 흰 판이 남아 있는 곳 | 없어야 한다. 있으면 그 요소가 `--card`를 안 쓰는 것이다 |
| 스크롤바 | 어둡다 (`color-scheme: dark`가 붙었으면) |
| 히어로 세 얼굴 | 인디고·초록 모두 다크 값으로 뜬다 |
| 난이도 배지 셋 | 어두운 바탕에 밝은 글자. 글자가 함께 있다 |
| 정답·오답 | 초록·빨강이 구별되고 ✓/✕가 함께 있다 |
| 진도 막대 | 채움과 바탕이 구별된다 |
| 문서 화면(`document.html`) | 마크다운 본문의 코드 블록·인용이 어둡다 |

- [ ] **Step 4: 대비를 실제로 잰다**

DevTools에서 요소를 고르고 **Elements → Styles → color 옆의 색 견본을 클릭**하면
대비비(Contrast ratio)가 뜬다. 라이트와 다크 양쪽에서 아래 여섯을 잰다.

| 재는 것 | 기준 |
| --- | --- |
| 본문 글자(`--text`) on `--card` | 4.5:1 이상 |
| 보조 글자(`--text-2`) on `--card` | 4.5:1 이상 |
| 흐린 글자(`--muted`) on `--bg` | 4.5:1 이상 |
| 주 버튼 글자 on `--primary` | 4.5:1 이상 |
| 난이도 배지 글자 on 배지 바탕 (셋 다) | 4.5:1 이상 |
| 히어로 제목 on `--primary-soft` | 3:1 이상 (큰 글자) |

**미달이 나오면 그 자리에서 값을 올린다.** 표에 적힌 값이라고 통과했다는 뜻이 아니다 —
docs/19에서 스펙 값을 그대로 옮겼다가 같은 자리에서 걸린 적이 있다.
값을 바꿨으면 Task 1의 두 블록(미디어 쿼리 안과 `[data-theme="dark"]`)을 **둘 다** 고친다.

- [ ] **Step 5: 라이트로 되돌려 한 번 더 훑는다**

다크를 맞추다 라이트가 깨지는 일이 흔하다. 12개 화면을 라이트에서 다시 본다.

- [ ] **Step 6: 스모크 테스트에 테마 규칙을 더한다**

`USER_PAGES`에 `me.html` `settings.html` 둘을 더하고(Task 10·11에서 만든 것들 —
`everyPageIsListed`가 이미 빨간불로 알려 줬을 것이다), 테스트를 하나 더한다:

```java
    @Test
    @DisplayName("모든 사용자 화면이 스타일시트보다 먼저 테마를 붙인다")
    void themeIsAppliedBeforeStylesheet() throws IOException {
        for (String page : USER_PAGES) {
            String html = read(page);

            int theme = html.indexOf("csquiz_theme");
            int css = html.indexOf("/css/style.css");

            assertThat(theme).as("%s: <head>의 테마 인라인 스크립트", page).isNotNegative();

            // 순서가 전부다. 스타일시트보다 늦으면 다크를 고른 사람도 첫 그림이
            // 라이트로 한 번 번쩍인다(FOUC). 눈에 확실히 보이는 결함인데
            // 화면 하나에서만 빠뜨리면 개발자 기기에서는 잘 안 걸린다.
            assertThat(theme).as("%s: 테마 스크립트가 style.css보다 앞에 와야 한다", page)
                    .isLessThan(css);
        }
    }
```

돌려서 통과를 확인한다:

```powershell
.\gradlew.bat test --tests "*StaticPageStructureTest*" --console=plain
```

- [ ] **Step 7: 전체 빌드**

```powershell
.\gradlew.bat build --console=plain
```

기대: 통과.

- [ ] **Step 8: 커밋**

```powershell
git add src/main/resources/static src/test/java
git commit -m @'
feat(theme): 다크 모드를 실제로 켜고 대비를 재서 맞춘다

토큰은 첫 단계에 선언해 뒀고 전환 UI는 설정 화면에 있다. 여기서 한 일은
모든 화면이 실제로 변수를 쓰게 만들고, 대비를 재고, 첫 그림이 번쩍이는
것을 막은 것이다.

다크를 맨 끝에 둔 이유는 모든 화면이 새 토큰만 쓰게 된 뒤라야 한 번에
맞기 때문이다. 중간에 했으면 화면을 옮길 때마다 두 번 고쳤다.

FOUC를 막는 한 줄만 각 head에 인라인으로 넣었다. shell.js는 body 끝에서
도는데 거기서 테마를 붙이면 다크를 고른 사람도 첫 그림이 라이트로 한 번
번쩍인다. 예외를 하나만 만들고 나머지 테마 코드는 전부 shell.js에 뒀다.

style.css 밖에 남아 있던 hex와 JS가 만드는 인라인 색을 전부 변수로
바꿨다. 이게 남으면 다크에서 그 자리만 안 바뀌어 화면이 반쯤 깨져 보인다.

대비는 표를 믿지 않고 DevTools로 다시 쟀다. docs/19에서 스펙 값을 그대로
옮겼다가 AA 미달이던 적이 있다 — 쓰이는 범위가 바뀌면 기준도 다시 잰다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## 끝난 뒤

사용자 화면 개편이 끝난다. 남은 것은 **관리 콘솔(설계 문서 6장)** 이고,
실제 화면을 본 뒤에 별도 계획으로 쓴다.

작업 기록은 `docs/20-ui-redesign.md`에 남긴다 — 무엇을 했는지가 아니라
**무엇이 깨졌고 왜 그렇게 정했는지**를 쓴다. docs/19가 좋은 본보기다.
