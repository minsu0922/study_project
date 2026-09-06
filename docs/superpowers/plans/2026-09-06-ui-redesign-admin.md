# 화면 전면 개편 — 관리 콘솔 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리 콘솔 화면 일곱을 새 셸(상단 진한 띠 + 사이드바)로 옮기고, 목록 화면이 공유하는 표·필터·페이저를 공용 렌더러 하나로 모은다.

**Architecture:** 사용자 화면과 같은 토큰·눈금을 쓰되 셸은 따로 둔다(콘솔은 다른 영역이다). 목록을 그리는 코드가 화면마다 복붙돼 있는데, **표로 그릴 수 있는 화면만** 공용 렌더러로 모으고 카드 목록(검수)은 페이저·필터 바만 공유한다. 마지막에 옛 상단 가로바(`renderNav`, `.nav`)를 걷어낸다.

**Tech Stack:** Spring Boot 3.4.1 / 순수 CSS / 바닐라 JS / Gradle (`.\gradlew.bat`)

**Spec:** `docs/superpowers/specs/2026-09-06-ui-redesign-design.md` (6장)

**선행 작업:** 사용자 화면 개편은 끝났다 — `docs/20-ui-redesign.md`

## Global Constraints

- **서버를 건드리지 않는다.** 이 계획은 화면만 다룬다. API가 모자라 화면을 못 만드는 자리가 나오면 **만들지 말고 적어 두고** 사용자에게 말한다(사용자 화면 개편에서 "없는 값으로 화면을 만들지 않는다"를 규칙으로 세웠다).
- **기능을 빼지 않는다.** 이 개편은 겉모습과 코드 구조를 바꾸는 것이다. 검수 화면의 경고 상자·절 목록·일괄 승인처럼 눈에 안 띄는 기능이 많으니, 옮기기 전에 그 화면이 무엇을 하는지 읽는다.
- **디자인 토큰만 쓴다.** 새 hex를 적지 않는다. `--bg --card --fill --border --text --text-2 --muted --primary --on-primary --lv*` 등 이미 있는 이름으로 해결한다. 없으면 이름을 새로 두고 라이트·다크 두 벌 모두에 넣는다.
- **다크 두 블록은 함께 고친다.** `:root[data-theme="dark"]`와 `@media (prefers-color-scheme: dark)`가 같은 값을 두 벌 들고 있다. 한쪽만 고치는 사고가 사용자 화면 개편에서 실제로 났다.
- 화면 확인 폭은 **375px · 768px · 1280px**. 콘솔은 데스크톱에서 주로 쓰지만 폰에서 열었을 때 못 쓸 정도면 안 된다.
- **브라우저 콘솔 오류 0**을 유지한다.
- 대비는 **WCAG AA**(본문 4.5:1, 큰 글자 3:1). 라이트·다크 양쪽에서 잰다.
- 주석은 **"왜 이렇게 했는지"**를 남긴다. 프로젝트 규칙이다.
- 커밋 메시지 끝에 두 줄을 붙인다.
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
  ```
- **`git push`는 하지 않는다.**

## 관리 콘솔을 띄우고 확인하는 법

```powershell
.\gradlew.bat bootRun --console=plain
```

콘솔은 **관리자 계정으로 로그인해야** 열린다. 출입증 쿠키가 없으면 `/admin/*`이 404다
(`AdminGateFilter` — 장식이 아니라 서버 방어다). 확인용 계정은 이렇게 만든다.

```powershell
curl -s -X POST http://localhost:8080/api/auth/signup -H "Content-Type: application/json" -d '{\"username\":\"admintest1\",\"password\":\"AdminTest!23\"}'
mysql -h 127.0.0.1 -u csquiz -pcsquiz1234 csquiz -e "UPDATE user SET role='ADMIN' WHERE username='admintest1';"
```

확인이 끝나면 **지운다**. 순서가 있다(외래 키) — `daily_quiz_item` → `daily_quiz` →
`review_item` → `submission` → `user`.

## 지금 상태와 목표를 정직하게

계획을 쓰기 전에 코드를 읽었더니 스펙의 표현("목록 화면 6개를 한 틀로")이 실제와 어긋났다.
**목록 화면이 여섯이 아니고, 940줄이 전부 보일러플레이트도 아니다.**

| 화면 | 줄 수 | 실제 성격 | 공용 렌더러로 가나 |
| --- | --- | --- | --- |
| `llm.html` 검수 | 941 | **카드 목록** — 경고 상자·절 목록·반려 사유·일괄 승인 | ✗ 페이저·필터만 공유 |
| `generate.html` 생성 | 725 | 폼 + 주제 범위 관리(옛 `topics.html` 흡수) | ✗ 목록이 아니다 |
| `problems.html` 문제 | 598 | 표 목록 + **편집 폼** + 백필 도구 둘 | △ 목록 부분만 |
| `index.html` 현황 | 400 | 대시보드(히트맵·차트) | ✗ |
| `documents.html` 문서 | 199 | 표 목록 | ○ |
| `reports.html` 제보 | 187 | 카드 목록 | △ 페이저·필터만 |
| `batch.html` 배치 | 169 | 현황 표 넷 | ✗ 읽기 전용 |

**따라서 "900줄 → 200줄"은 목표로 삼지 않는다.** 검수 화면이 긴 것은 그 화면이 실제로
하는 일이 많아서다 — 줄이려면 기능을 빼야 하고, 그건 이 개편의 목적이 아니다.

대신 **줄일 수 있는 것만 줄인다.**

| 줄일 것 | 지금 | 어디에 |
| --- | --- | --- |
| 표 머리글 + 행 + 빈 상태 + 클릭 위임 | 화면마다 복붙 | `admin-table.js` |
| 페이저(이전/다음/쪽수/전체 건수) | 세 화면에 각각 | 같은 파일 |
| 필터 한 줄 → 쿼리 문자열 | 세 화면에 각각 | 같은 파일 |
| 옛 상단 가로바 | `shell.js` + `style.css` | 삭제 |

## 파일 구조

| 파일 | 책임 | 상태 |
| --- | --- | --- |
| `admin/js/admin-shell.js` | 콘솔 셸(상단 띠 + 사이드바 세 묶음 + 배지) | **신규** |
| `admin/js/admin-table.js` | 표 목록 렌더러 — 필터·표·페이저·일괄 처리 | **신규** |
| `admin/js/admin-common.js` | 가드·이동·삭제 확인·복사 등 나머지 도우미 | 수정(셸 코드 빠짐) |
| `css/style.css` | 콘솔 셸·표 스타일, 옛 `.nav` 제거 | 수정 |
| `js/shell.js` | `renderNav` 제거 | 수정 |
| `admin/*.html` 7개 | 새 셸 + 새 렌더러 | 수정 |
| `admin/topics.html` | 넘겨주기 전용 — 그대로 둔다 | 손대지 않음 |
| `test/.../web/AdminPageStructureTest.java` | 콘솔 화면 구조 스모크 테스트 | **신규** |

---

## Task 1: 콘솔 화면 구조 스모크 테스트

사용자 화면에서 이 테스트가 실제로 세 번 사고를 잡았다(로드 순서, 목록에 빠진 새 화면,
테마 스크립트 누락). 콘솔도 화면 일곱을 전부 건드리므로 같은 장치를 먼저 둔다.

**지금 통과하는 규칙만 넣는다.** 새 셸의 규칙은 그것을 만드는 Task에서 이어 붙인다.

**Files:**
- Create: `src/test/java/project/study/study_project/web/AdminPageStructureTest.java`

**Interfaces:**
- Consumes: 없음 (스프링 컨텍스트를 안 띄운다)
- Produces: `ADMIN_PAGES` 상수 — Task 3·6이 규칙을 더할 때 이 목록을 쓴다

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
 * 관리 콘솔 화면의 <b>구조</b>를 지키는 스모크 테스트.
 *
 * <p>사용자 화면 쪽 {@link StaticPageStructureTest}와 같은 장치를 콘솔에도 둔다.
 * 그쪽에서 이 테스트가 실제로 세 번 사고를 잡았다 — 스크립트 로드 순서, 목록에 안 넣은
 * 새 화면, 테마 스크립트가 빠진 화면 하나. 콘솔도 화면 일곱을 전부 건드리므로 같은
 * 종류의 사고가 난다.
 *
 * <p><b>왜 파일만 읽나.</b> 확인하려는 것이 파일의 내용이지 실행 결과가 아니다.
 * 게다가 콘솔은 <b>서버가 출입증 쿠키로 막고 있어</b>(AdminGateFilter) HTTP로 열려면
 * 로그인부터 해야 한다 — 구조를 보자고 그 절차를 태울 이유가 없다.
 *
 * <p><b>{@code topics.html}은 목록에 없다.</b> 그 화면은 meta refresh로 넘겨주기만 하는
 * 열몇 줄짜리 파일이고 스크립트가 없다. 규칙을 들이대면 전부 실패하는데, 실패가 맞는
 * 것이 아니라 <b>다른 종류의 파일</b>이다.
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

            assertThat(html).as("%s: lang=\"ko\"가 없다", page).contains("lang=\"ko\"");
            assertThat(html).as("%s: viewport meta가 없다", page).contains("name=\"viewport\"");
            assertThat(html).as("%s: <title>이 없다", page).contains("<title>");
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
            assertThat(shell).as("%s: shell.js는 api.js 뒤", page).isGreaterThan(api);
            assertThat(common).as("%s: admin-common.js는 shell.js 뒤", page).isGreaterThan(shell);
        }
    }

    /**
     * 폴더에 있는데 목록에 없는 화면(그리고 그 반대)을 잡는다.
     *
     * <p>규칙 검사는 목록에 든 것만 보므로, 목록을 갱신하지 않으면 새 화면이 조용히
     * 빠져나간다. 새 화면이야말로 규칙을 빠뜨리기 가장 쉬운 곳이다.
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

    private String read(String page) throws IOException {
        Path path = ADMIN_DIR.resolve(page);
        assertThat(Files.exists(path)).as("%s 파일이 있어야 한다", page).isTrue();
        return Files.readString(path);
    }
}
```

- [ ] **Step 2: 테스트를 돌려 통과를 확인한다**

```powershell
.\gradlew.bat test --tests "*AdminPageStructureTest*" --console=plain
```

기대: **PASS 3건.** 지금 상태를 그대로 적은 규칙이라 처음부터 초록이다.

빨간불이면 **테스트가 아니라 화면이 규칙을 어기고 있는 것**이다. 어느 화면인지 메시지에
나온다 — 그 화면을 고친다(규칙을 낮추지 않는다).

- [ ] **Step 3: 일부러 깨뜨려 본다**

`admin/reports.html`에서 `<script src="/js/shell.js"></script>` 줄을 잠깐 지우고:

```powershell
.\gradlew.bat test --tests "*AdminPageStructureTest*" --console=plain
```

기대: **실패**, 메시지에 `reports.html: shell.js를 안 싣는다`가 뜬다.
**확인했으면 지운 줄을 되돌린다.** 통과하는 것만 본 테스트는 통과만 하는 테스트일 수 있다.

- [ ] **Step 4: 커밋**

```powershell
git add src/test/java
git commit -m @'
test(admin): 콘솔 화면 구조 스모크 테스트를 둔다

사용자 화면 쪽에서 같은 장치가 세 번 사고를 잡았다 — 스크립트 로드
순서, 목록에 안 넣은 새 화면, 테마 스크립트가 빠진 화면 하나. 콘솔도
화면 일곱을 전부 건드리므로 같은 종류의 사고가 난다.

파일만 읽는다. 확인하려는 것이 파일의 내용이지 실행 결과가 아니고,
콘솔은 서버가 출입증 쿠키로 막고 있어 HTTP로 열려면 로그인부터 해야
한다 — 구조를 보자고 그 절차를 태울 이유가 없다.

topics.html은 목록 밖에 뒀다. 넘겨주기만 하는 열몇 줄짜리 파일이라
규칙을 들이대면 전부 실패하는데, 실패가 맞는 게 아니라 다른 종류의
파일이다.

reports.html의 script를 잠깐 지워 테스트가 실제로 빨개지는 것을
확인하고 되돌렸다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 2: 콘솔 셸 — 상단 진한 띠 + 사이드바

콘솔은 **다른 방**이다. 사용자 화면과 사이드바를 공유하지 않는다 —
`admin-common.js`의 기존 판단이다("어느 쪽에 있는지 흐려지고, 검수하다 잘못 눌러 나가기 쉽다").

**Files:**
- Create: `src/main/resources/static/admin/js/admin-shell.js`
- Modify: `src/main/resources/static/admin/js/admin-common.js` (`ADMIN_PAGES`·`renderConsoleNav`·`renderAdminNav`·`refreshAdminBadges`·`setBadge`를 옮긴다)
- Modify: `src/main/resources/static/css/style.css` (콘솔 셸 스타일)
- Modify: `admin/*.html` 7개 (`<div id="shell">` + script 추가)

**Interfaces:**
- Consumes: `shell.js`의 `authAreaHtml()` `wireLogout()` `applyStoredTheme()` `applyStoredFontSize()`, `api.js`의 `escapeHtml()` `api()`
- Produces: `renderAdminShell(active)` — `active`는 `ADMIN_MENUS`의 `key`. 각 화면이 `initAdminPage(active)` 안에서 이것을 부른다. `initAdminPage`의 시그니처와 반환값(관리자면 `true`)은 그대로 둔다.

- [ ] **Step 1: 지금 셸 코드가 무엇을 하는지 읽는다**

```powershell
Get-Content src/main/resources/static/admin/js/admin-common.js | Select-Object -Skip 40 -First 130
```

옮길 범위는 `ADMIN_PAGES` 선언부터 `setBadge` 끝까지다. **주석을 통째로 가져간다** —
콘솔에 학습 메뉴를 안 두는 이유, 배지를 모든 화면에서 부르는 이유가 거기 적혀 있다.

- [ ] **Step 2: `admin-shell.js`를 만든다**

```javascript
/* =====================================================================
 * admin-shell.js — 관리 콘솔의 껍데기
 * ---------------------------------------------------------------------
 * [왜 사용자 셸(shell.js)과 나누나]
 * 콘솔은 <다른 방>이다. 같은 사이드바에 학습 메뉴와 관리 메뉴가 함께 뜨면 "지금 어느
 * 쪽에 있는지"가 흐려지고, 검수하다 잘못 눌러 나가기 쉽다 — 이 판단은 개편 전부터
 * 이 파일의 조상(admin-common.js)에 적혀 있었고, 시안에서 사이드바를 공유하는 안이
 * 더 빨랐지만 뒤집을 새 근거가 없어 그대로 뒀다.
 *
 * [그래도 shell.js를 부른다] authAreaHtml·wireLogout·테마 적용은 그쪽 것을 쓴다.
 * 복사해 두면 서버 토큰 폐기가 빠진 사본이 생겨, 콘솔에서 로그아웃했을 때만 서버에
 * 14일짜리 출입증이 남는 상태가 된다.
 *
 * [로드 순서] api.js → shell.js → admin-shell.js → admin-common.js.
 * 번들러가 없어 순서가 곧 의존성이다. AdminPageStructureTest가 그 순서를 지킨다.
 * ===================================================================== */

/**
 * 콘솔 메뉴 — <b>세 묶음</b>으로 나눈다.
 *
 * <p>예전에는 평평한 탭 일곱이었다. "지금 할 일"(검수·제보)과 "가끔 보는 것"(문제 목록,
 * 배치 현황)이 같은 줄에 서 있어서, 콘솔을 여는 이유의 대부분인 검수가 다른 것들과
 * 같은 무게로 보였다.
 *
 * <p><b>현황(index)이 메뉴에서 빠졌다.</b> 콘솔에 들어오는 이유가 대부분 검수라
 * <b>검수를 첫 화면</b>으로 삼는다. 현황의 숫자들은 검수 화면 위쪽 요약 줄로 옮긴다
 * — 화면을 지우지는 않는다(히트맵·차트는 거기서만 볼 수 있다). 브랜드를 누르면 간다.
 *
 * <p><b>"주제 범위"도 항목이 아니다.</b> 스펙 표에는 콘텐츠 묶음에 들어 있지만, 그 화면은
 * 2026-08-29에 <b>생성 화면 안으로 흡수됐다</b>(topics.html은 넘겨주기만 한다). 주제를
 * 정하는 것은 생성의 <입력>이지 별개 작업이 아니라는 판단이었고, 여전히 맞다.
 * 대기 중인 주제 수는 "생성 실행" 항목의 배지로 보인다.
 */
const ADMIN_MENUS = [
  { group: "할 일", items: [
    { key: "llm",       label: "검수",      href: "/admin/llm.html",       icon: "📋", badge: "badge-llm" },
    { key: "reports",   label: "제보",      href: "/admin/reports.html",   icon: "🚩", badge: "badge-reports" },
  ]},
  { group: "콘텐츠", items: [
    { key: "problems",  label: "문제",      href: "/admin/problems.html",  icon: "🗂️" },
    { key: "documents", label: "문서",      href: "/admin/documents.html", icon: "📚" },
  ]},
  { group: "생성", items: [
    { key: "generate",  label: "생성 실행", href: "/admin/generate.html",  icon: "✨", badge: "badge-topics" },
    { key: "batch",     label: "배치 현황", href: "/admin/batch.html",     icon: "📅" },
  ]},
];

/**
 * 콘솔 셸을 그린다 — 상단 진한 띠 + 좌측 사이드바.
 *
 * <p><b>[왜 진한 띠인가]</b> 콘솔이 다른 방이라는 것을 <b>색으로</b> 알린다. 사이드바
 * 내용만으로도 구별되지만, 검수는 화면을 아래로 길게 훑는 작업이라 사이드바가 시야
 * 밖으로 나간다. 띠는 sticky라 늘 보인다.
 *
 * <p><b>[나가는 문은 하나]</b> "← 학습 화면으로"를 띠 오른쪽에 둔다. 관리자도 학습자라
 * 오갈 일이 있고 막을 이유가 없다 — 다만 <b>메뉴가 아니라 문</b>이라 사이드바가 아니라
 * 띠에 있다.
 *
 * @param active ADMIN_MENUS의 key. 해당 없으면 빈 문자열(현황 화면이 그렇다)
 */
function renderAdminShell(active = "") {
  applyStoredTheme();
  applyStoredFontSize();

  const el = document.getElementById("shell");
  if (!el) return;

  el.innerHTML = `
    <header class="admin-bar">
      <a class="brand" href="/admin/llm.html">csquiz 관리</a>
      <span class="who">${escapeHtml(localStorage.getItem(USERNAME_KEY) || "")}</span>
      <span class="spacer"></span>
      <a href="/">← 학습 화면으로</a>
      <a href="#" id="logoutLink">로그아웃</a>
    </header>

    <nav class="shell-side" aria-label="관리 메뉴">
      <a class="brand" href="/admin/index.html">현황</a>
      ${ADMIN_MENUS.map(g => `
        <div class="shell-group">${escapeHtml(g.group)}</div>
        ${g.items.map(m => `
          <a class="shell-link${m.key === active ? " active" : ""}" href="${m.href}"
             ${m.key === active ? 'aria-current="page"' : ""}>
            <span class="ic" aria-hidden="true">${m.icon}</span>
            <span>${escapeHtml(m.label)}</span>
            ${m.badge ? `<span id="${m.badge}"></span>` : ""}
          </a>`).join("")}`).join("")}
    </nav>`;

  wireLogout();
  refreshAdminBadges();
}

/**
 * 사이드바 배지 셋을 갱신한다 — 검수 대기 2종 + 제보 + 주제 범위.
 *
 * <p>배지를 <b>모든 화면</b>에서 부르는 것은 의도다. "검수할 게 남았다"는 지금 보고 있는
 * 화면과 무관하게 알아야 하는 정보이고, 화면을 옮길 때마다 최신값이 보인다.
 *
 * <p>넷을 한꺼번에 보낸다 — 순서대로 기다리면 화면이 뜨는 데 왕복 네 번이 그대로 더해진다.
 */
async function refreshAdminBadges() {
  const [problems, documents, topics, reports] = await Promise.all([
    countOf("/api/admin/llm-problems/pending-count"),
    countOf("/api/admin/llm-documents/pending-count"),
    countOf("/api/admin/topic-queue/count"),
    countOf("/api/admin/reports/pending-count"),
  ]);

  // 검수 배지는 문제·문서 대기를 합쳐 하나로 보여 준다 — 한 화면이 둘 다 다루므로
  // 배지도 하나여야 "저기 들어가면 할 일이 있다"가 정확해진다.
  setBadge("badge-llm", (problems ?? 0) + (documents ?? 0), true);
  setBadge("badge-reports", reports, true);
  setBadge("badge-topics", topics, false);
}

/**
 * 배지 하나를 채운다. 0이면 아예 안 그린다 — "0"이 떠 있으면 그것도 읽어야 할 숫자가 된다.
 *
 * @param highlight 급한 것(주황)인가. 주제 범위는 할 일이 아니라 재고라 회색이다
 */
function setBadge(id, value, highlight) {
  const el = document.getElementById(id);
  if (!el) return;
  el.innerHTML = value > 0
    ? `<span class="nav-badge${highlight ? "" : " gray"}">${value}</span>`
    : "";
}
```

- [ ] **Step 3: `admin-common.js`에서 옮긴 부분을 지운다**

`ADMIN_PAGES`·`renderConsoleNav`·`renderAdminNav`·`refreshAdminBadges`·`setBadge`를 지우고,
`initAdminPage`가 새 셸을 부르게 한다.

```javascript
/**
 * 관리 화면 공통 초기화 — 가드 → 셸 순서로 한 번에 처리한다.
 *
 * <p>가드가 먼저인 이유: 관리자가 아니면 셸을 그릴 이유가 없다. 그리고 이 가드는
 * <b>장식</b>이다 — 진짜 방어는 서버 두 겹이다(정적 파일은 AdminGateFilter의 쿠키 검사,
 * API는 SecurityConfig의 hasRole). 화면은 속일 수 있어도 서버는 못 속인다.
 *
 * @param active ADMIN_MENUS의 key
 * @returns 관리자면 true — 호출부는 이 값이 false면 데이터 적재를 건너뛴다
 */
function initAdminPage(active) {
  if (!isAdmin()) {
    document.body.innerHTML =
      `<main class="container narrow" style="padding-top:60px">
         <div class="alert error">관리자만 볼 수 있는 화면입니다.
           <a href="/">학습 화면으로</a></div>
       </main>`;
    return false;
  }
  renderAdminShell(active);
  return true;
}
```

파일 상단 주석에서 옮긴 것들을 지우고 한 줄 남긴다:

```javascript
/* 셸(상단 띠·사이드바·배지)은 admin/js/admin-shell.js로 옮겼다(2026-09-06 개편).
   이 파일은 가드·화면 간 이동·삭제 확인·복사 같은 <도우미>만 맡는다. */
```

- [ ] **Step 4: 콘솔 셸 CSS를 쓴다**

`style.css`에 더한다. 사이드바 규칙(`.shell-side` `.shell-link`)은 사용자 화면 것을
**그대로 재사용**하고, 콘솔에만 필요한 것을 더한다.

```css
/* ═════════════════════════════════════════════════════════════════════
 * 관리 콘솔 셸 — 상단 진한 띠 + 사이드바 (2026-09-06)
 * ---------------------------------------------------------------------
 * 사이드바(.shell-side/.shell-link)는 사용자 화면 것을 그대로 쓴다. 두 벌을 두면
 * 여백 하나를 고칠 때 한쪽만 고치게 되고, 그 어긋남은 두 화면을 나란히 놓아야만 보인다.
 * 콘솔에만 있는 것은 <상단 띠>와 <묶음 제목>뿐이다.
 * ═══════════════════════════════════════════════════════════════════ */

/* 콘솔은 사이드바가 좁은 화면에서도 남는다 — 관리 작업은 메뉴를 자주 오가고,
   폰 탭바처럼 다섯 칸에 담기에는 항목이 여섯이라 이름이 잘린다.
   대신 좁으면 기둥을 접어 가로 줄로 눕힌다(아래 미디어 쿼리). */
body.admin {
  grid-template-areas: "top" "side" "main";
  grid-template-rows: auto auto minmax(0, 1fr);
}
@media (min-width: 768px) {
  body.admin {
    grid-template-areas: "top top" "side main";
    grid-template-columns: 208px minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
  }
}

/* 상단 진한 띠 — "여기는 관리"를 색으로 알린다.
   --text를 바탕으로 쓰는 이유: 라이트에서 가장 진한 색이고, 다크에서는 밝은 색으로
   뒤집혀 여전히 본문과 대비된다. 글자는 그 반대인 --bg다. */
.admin-bar {
  grid-area: top;
  display: flex; align-items: center; gap: var(--sp-3);
  height: var(--shell-top-h); padding: 0 var(--sp-4);
  background: var(--text); color: var(--bg);
  position: sticky; top: 0; z-index: 60;   /* 사이드바(50)보다 위 */
  font-size: var(--fs-2);
}
.admin-bar .brand { font-weight: 800; color: var(--bg); text-decoration: none; }
.admin-bar .who { color: var(--bg); opacity: .65; }
.admin-bar .spacer { flex: 1; }
.admin-bar a {
  display: inline-flex; align-items: center; align-self: stretch;
  color: var(--bg); text-decoration: none; white-space: nowrap;
}
.admin-bar a:hover { opacity: .75; }

/* 사이드바 안의 묶음 제목(할 일 / 콘텐츠 / 생성) */
.shell-group {
  font-size: 9px; letter-spacing: .1em; text-transform: uppercase;
  color: var(--muted); font-weight: 700;
  padding: var(--sp-3) var(--sp-2) var(--sp-1);
}
.shell-side .shell-group:first-of-type { padding-top: var(--sp-2); }

/* 급하지 않은 배지(주제 범위 재고) — 주황은 "지금 할 일"에만 쓴다 */
.nav-badge.gray { background: var(--fill); color: var(--muted); }

/* 좁은 화면: 기둥을 가로 줄로 눕힌다 */
@media (max-width: 767px) {
  body.admin .shell-side {
    flex-direction: row; overflow-x: auto; height: auto;
    position: static; border-right: 0; border-bottom: 1px solid var(--border);
    gap: var(--sp-1); padding: var(--sp-2);
  }
  body.admin .shell-side .brand,
  body.admin .shell-group { display: none; }   /* 가로 줄에서는 묶음 제목이 자리만 먹는다 */
  body.admin .shell-link { white-space: nowrap; }
}
```

- [ ] **Step 5: 콘솔 HTML 일곱을 바꾼다**

각 파일에서 `<header id="nav"></header>`와 `<nav id="adminNav" ...>`(있으면)를 지우고
`<div id="shell"></div>` 하나로 바꾼다. `<body>`에 `class="admin"`을 붙이고,
`admin-shell.js`를 `shell.js`와 `admin-common.js` 사이에 넣는다.

```html
<body class="admin">
<div id="shell"></div>
...
<script src="/js/api.js"></script>
<script src="/js/shell.js"></script>
<script src="/admin/js/admin-shell.js"></script>
<script src="/admin/js/admin-common.js"></script>
```

`initAdminPage("...")` 호출의 인자를 `ADMIN_MENUS`의 key로 맞춘다.

| 파일 | 인자 |
| --- | --- |
| `index.html` | `initAdminPage("")` — 현황은 메뉴에 없다 |
| `llm.html` | `initAdminPage("llm")` |
| `reports.html` | `initAdminPage("reports")` |
| `problems.html` | `initAdminPage("problems")` |
| `documents.html` | `initAdminPage("documents")` |
| `generate.html` | `initAdminPage("generate")` |
| `batch.html` | `initAdminPage("batch")` |

`<head>`에도 테마 인라인 스크립트를 넣는다(사용자 화면과 같은 한 줄, `style.css` **앞**):

```html
<script>
/* 저장된 테마를 CSS가 오기 전에 <html>에 붙인다 — FOUC 방지.
   자세한 이유는 사용자 화면의 같은 주석 참고. */
try {
  var t = localStorage.getItem("csquiz_theme");
  if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
} catch (e) {}
</script>
<link rel="stylesheet" href="/css/style.css">
```

- [ ] **Step 6: 앱을 띄워 확인한다**

관리자 계정으로 로그인한 뒤 콘솔 일곱 화면을 연다.

| 확인 | 기대 |
| --- | --- |
| 상단 띠 | 진한 바탕에 "csquiz 관리 / 계정 / ← 학습 화면으로 / 로그아웃" |
| 사이드바 | 세 묶음(할 일·콘텐츠·생성), 지금 화면이 굵게 |
| 배지 | 검수·제보는 주황, 주제 범위는 회색. 0이면 안 뜬다 |
| 로그아웃 | 동작하고 학습 첫 화면으로 간다 |
| 375px | 기둥이 가로 줄로 눕고 가로 스크롤로 오간다 |
| 다크 | 상단 띠가 밝은 바탕 + 어두운 글자로 뒤집힌다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 7: 스모크 테스트에 셸 규칙을 더한다**

```java
    @Test
    @DisplayName("모든 콘솔 화면이 셸 자리를 두고, 옛 내비 자리는 남기지 않는다")
    void everyPageHasAdminShell() throws IOException {
        for (String page : ADMIN_PAGES) {
            String html = read(page);

            assertThat(html).as("%s: <div id=\"shell\">가 없다", page).contains("id=\"shell\"");
            assertThat(html).as("%s: <body class=\"admin\">이 아니다", page)
                    .contains("class=\"admin\"");
            assertThat(html).as("%s: admin-shell.js를 안 싣는다", page)
                    .contains("/admin/js/admin-shell.js");

            // 옛 자리가 남아 있으면 빈 요소가 화면 맨 위에 여백으로 남는다
            assertThat(html).as("%s: 옛 id=\"nav\"가 남아 있다", page).doesNotContain("id=\"nav\"");
            assertThat(html).as("%s: 옛 id=\"adminNav\"가 남아 있다", page)
                    .doesNotContain("id=\"adminNav\"");

            // 테마 스크립트가 스타일시트보다 앞이어야 첫 그림이 안 번쩍인다
            int theme = html.indexOf("csquiz_theme");
            int css = html.indexOf("/css/style.css");
            assertThat(theme).as("%s: 테마 인라인 스크립트가 없다", page).isNotNegative();
            assertThat(theme).as("%s: 테마 스크립트가 style.css보다 앞", page).isLessThan(css);
        }
    }
```

돌려서 통과를 확인한다:

```powershell
.\gradlew.bat test --tests "*AdminPageStructureTest*" --console=plain
```

- [ ] **Step 8: 커밋**

```powershell
git add src/main/resources/static src/test/java
git commit -m @'
feat(admin): 콘솔 셸을 상단 진한 띠와 사이드바로 바꾼다

콘솔은 다른 방이다. 사용자 화면과 사이드바를 공유하지 않는다 — 같은
기둥에 학습 메뉴와 관리 메뉴가 함께 뜨면 지금 어느 쪽인지 흐려지고
검수하다 잘못 눌러 나가기 쉽다. 개편 전부터 이 파일에 적혀 있던 판단이고,
시안에서 공유하는 안이 더 빨랐지만 뒤집을 새 근거가 없었다.

진한 띠로 "여기는 관리"를 색으로 알린다. 사이드바 내용만으로도 구별되지만
검수는 화면을 아래로 길게 훑는 작업이라 사이드바가 시야 밖으로 나간다.

평평한 탭 일곱을 세 묶음으로 갈랐다. "지금 할 일"(검수·제보)과 "가끔 보는
것"(문제 목록, 배치 현황)이 같은 줄에 서 있어서, 콘솔을 여는 이유의
대부분인 검수가 다른 것들과 같은 무게로 보였다.

현황을 메뉴에서 뺐다. 콘솔에 들어오는 이유가 대부분 검수라 검수를 첫
화면으로 삼는다. 화면은 지우지 않았다 — 히트맵과 차트는 거기서만 본다.

사이드바 CSS는 사용자 화면 것을 그대로 쓴다. 두 벌을 두면 여백 하나를
고칠 때 한쪽만 고치게 되고, 그 어긋남은 두 화면을 나란히 놓아야만 보인다.

authAreaHtml·wireLogout은 shell.js 것을 부른다. 복사해 두면 서버 토큰
폐기가 빠진 사본이 생겨, 콘솔에서 로그아웃했을 때만 서버에 14일짜리
출입증이 남는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 3: 공용 표 렌더러

목록을 그리는 코드가 화면마다 복붙돼 있다 — 머리글, 행, 빈 상태, 클릭 위임, 페이저,
전체 건수. 하나로 모은다.

**적용 대상은 표로 그릴 수 있는 화면뿐이다.** 검수(카드 목록)는 Task 5에서 페이저와
필터 바만 나눠 쓴다.

**Files:**
- Create: `src/main/resources/static/admin/js/admin-table.js`
- Modify: `src/main/resources/static/css/style.css` (표·필터·일괄 처리 줄)

**스펙의 예시와 두 곳이 다르다.**

- `filters`가 **배열이 아니라 함수**다. 스펙은 `filters: [domain, difficulty, sort]`로
  적었는데, 실제 화면들은 "빈 값은 안 넘긴다"는 규칙을 각자 갖고 있고 파라미터 이름과
  요소 id가 1:1이 아니다(`documentSlug` ↔ `fpDocument`). 함수로 두면 그 규칙이 한 곳에
  모이고, 특이한 필터가 생겨도 렌더러를 안 고친다.
- 스펙 예시의 `url`이 `"/api/admin/llm-problems"`(검수)인데 **검수는 이 렌더러를 안 쓴다**.
  위 "지금 상태와 목표를 정직하게" 참고.

**Interfaces:**
- Consumes: `api.js`의 `api()` `escapeHtml()`
- Produces:
  - `renderAdminTable(mountSelector, config)` → `Promise<void>`
  - `config` = `{ url, columns, filters, rowId, onRowClick, bulk, emptyText }`
    - `url: string` — 페이지·필터 없는 기본 주소 (`"/api/admin/problems"`)
    - `columns: Array<{ head: string, cell: (row) => string, width?: string, wrap?: boolean }>`
    - `filters?: () => string` — 쿼리 조각(`"&domain=OS"`). 없으면 필터 없음
    - `rowId: (row) => number|string`
    - `onRowClick?: (id, row) => void`
    - `bulk?: Array<{ label, className, run: (ids) => Promise<void> }>`
    - `emptyText?: string`
  - `adminTableReload(mountSelector)` → `Promise<void>` — 같은 조건으로 다시 그린다

- [ ] **Step 1: 지금 목록 코드가 무엇을 하는지 읽는다**

```powershell
Get-Content src/main/resources/static/admin/problems.html | Select-Object -Skip 353 -First 50
```

여섯 가지를 한다: ① 조회 ② 머리글+행 그리기 ③ 빈 상태 ④ 클릭 위임 ⑤ 전체 건수
⑥ 페이저. `documents.html`도 같은 여섯을 다른 열로 되풀이한다.

**⑤와 ⑥의 주석을 옮겨 온다** — "전체 건수를 늘 적는" 이유(필터를 걸었을 때 몇 건으로
좁혀졌나가 곧 답이다)와 클릭을 표 하나에 위임하는 이유(값이 HTML 문자열 안에서 코드가
되는 자리를 없앤다)가 거기 적혀 있다.

- [ ] **Step 2: `admin-table.js`를 쓴다**

```javascript
/* =====================================================================
 * admin-table.js — 관리 콘솔의 표 목록 한 벌
 * ---------------------------------------------------------------------
 * [무엇을 모았나] 목록 화면이 되풀이하던 여섯 가지다.
 *   ① 조회(페이지·필터) ② 머리글+행 ③ 빈 상태 ④ 클릭 위임 ⑤ 전체 건수 ⑥ 페이저
 * 화면은 <열이 무엇인가>와 <행을 누르면 무엇을 하는가>만 적는다.
 *
 * [왜 모았나] 여섯 중 다섯은 화면마다 같은 코드였는데, 같은 코드가 여러 벌이면 한 곳을
 * 고칠 때 나머지를 잊는다. 실제로 "결과 0건" 안내가 문제 목록에만 있고 문서 목록에는
 * 없었다 — 필터를 걸어 0건이 되면 머리글만 남아 로딩 중인지 없는 건지 알 수 없었다.
 *
 * [무엇을 안 모았나] 검수 화면(llm.html)은 <카드> 목록이다. 경고 상자·절 목록·반려
 * 사유처럼 한 건마다 그리는 것이 많아 표로 눕히면 그 정보가 갈 곳이 없다. 그쪽은
 * 페이저와 필터 바만 나눠 쓴다(renderAdminPager·filterQuery).
 * ===================================================================== */

/** 마운트 지점별 상태. 다시 그릴 때 같은 조건을 쓰려고 들고 있는다. */
const adminTableState = new Map();

/**
 * 표 목록을 그린다.
 *
 * @param mountSelector 표를 넣을 자리(예 "#list")
 * @param config 위 파일 주석의 형태. columns만 필수다
 */
async function renderAdminTable(mountSelector, config) {
  const prev = adminTableState.get(mountSelector);
  const page = prev ? prev.page : 0;
  adminTableState.set(mountSelector, { config, page });

  const mount = document.querySelector(mountSelector);
  if (!mount) return;

  const query = `?page=${page}` + (config.filters ? config.filters() : "");
  let data;
  try {
    data = await api(config.url + query);
  } catch (e) {
    mount.innerHTML = `<div class="alert error">목록을 불러오지 못했습니다: ${escapeHtml(e.message)}</div>`;
    return;
  }

  const rows = data.content ?? [];
  // 빈 상태를 <반드시> 그린다. 머리글만 남으면 로딩 중인지 결과가 없는 건지 알 수 없다.
  // 필터가 생긴 뒤로 0건이 흔해졌고, 그때 "필터를 지우면 전체가 보인다"까지 말해 준다.
  const empty = `<tr><td colspan="${config.columns.length}" class="meta"
      style="padding:18px;text-align:center">${escapeHtml(config.emptyText
        ?? "조건에 맞는 항목이 없습니다 — 필터를 지우면 전체가 보입니다.")}</td></tr>`;

  const hasBulk = Array.isArray(config.bulk) && config.bulk.length > 0;

  mount.innerHTML = `
    <div class="table-wrap">
      <table class="grid">
        <thead><tr>
          ${hasBulk ? `<th style="width:32px"><input type="checkbox" id="bulkAll" aria-label="전체 선택"></th>` : ""}
          ${config.columns.map(c => `<th${c.width ? ` style="width:${c.width}"` : ""}>${escapeHtml(c.head)}</th>`).join("")}
        </tr></thead>
        <tbody>${rows.length === 0 ? empty : rows.map(r => `
          <tr class="${config.onRowClick ? "click-row" : ""}" data-id="${config.rowId(r)}">
            ${hasBulk ? `<td><input type="checkbox" class="bulk-pick" aria-label="선택"></td>` : ""}
            ${config.columns.map(c => `<td${c.wrap ? ' class="wrap"' : ""}>${c.cell(r)}</td>`).join("")}
          </tr>`).join("")}</tbody>
      </table>
    </div>
    ${hasBulk ? `<div class="bulk-bar" hidden>
      <span class="picked"></span>
      ${config.bulk.map((b, i) => `<button class="${b.className ?? ""}" data-bulk="${i}">${escapeHtml(b.label)}</button>`).join("")}
    </div>` : ""}
    <div class="pager-row">
      <span class="meta">전체 ${data.totalElements}건</span>
      <span class="spacer"></span>
      ${renderAdminPager(data)}
    </div>`;

  wireTable(mountSelector, mount, config, rows, data);
}

/**
 * 표에 동작을 붙인다.
 *
 * <p><b>클릭은 표 하나에 위임한다.</b> 행마다 {@code onclick="..."} 문자열을 심는 방식은
 * 값이 HTML 문자열 안에서 <b>코드가 되는</b> 구조라, escapeHtml(HTML용 이스케이프)로
 * 자바스크립트 문자열을 막으려는 잘못된 코드가 실제로 있었다(문서 목록의 slug).
 * data-*에 넣고 여기서 읽으면 값이 코드로 해석될 자리 자체가 없어진다.
 */
function wireTable(mountSelector, mount, config, rows, data) {
  const table = mount.querySelector("table");

  if (config.onRowClick) {
    table.onclick = e => {
      // 체크박스와 행 안 버튼을 먼저 걸러낸다 — 순서를 뒤집으면 체크하려다 상세가 열린다
      if (e.target.closest("input, button")) return;
      const tr = e.target.closest(".click-row");
      if (!tr) return;
      const row = rows.find(r => String(config.rowId(r)) === tr.dataset.id);
      config.onRowClick(tr.dataset.id, row);
    };
  }

  const bulkBar = mount.querySelector(".bulk-bar");
  if (bulkBar) {
    const picks = () => [...mount.querySelectorAll(".bulk-pick")];
    const pickedIds = () => picks()
      .filter(c => c.checked)
      .map(c => c.closest("tr").dataset.id);

    const sync = () => {
      const n = pickedIds().length;
      bulkBar.hidden = n === 0;
      bulkBar.querySelector(".picked").textContent = `${n}개 선택됨`;
    };

    mount.querySelector("#bulkAll").addEventListener("change", e => {
      picks().forEach(c => { c.checked = e.target.checked; });
      sync();
    });
    picks().forEach(c => c.addEventListener("change", sync));

    bulkBar.querySelectorAll("[data-bulk]").forEach(btn => {
      btn.addEventListener("click", async () => {
        const ids = pickedIds();
        if (ids.length === 0) return;
        btn.disabled = true;
        try {
          await config.bulk[Number(btn.dataset.bulk)].run(ids);
          await adminTableReload(mountSelector);
        } finally {
          btn.disabled = false;
        }
      });
    });
  }

  mount.querySelectorAll("[data-page]").forEach(btn => {
    btn.addEventListener("click", () => {
      const st = adminTableState.get(mountSelector);
      st.page = Number(btn.dataset.page);
      renderAdminTable(mountSelector, st.config);
    });
  });
}

/** 같은 조건으로 다시 그린다. 삭제·일괄 처리 뒤에 부른다. */
async function adminTableReload(mountSelector) {
  const st = adminTableState.get(mountSelector);
  if (st) await renderAdminTable(mountSelector, st.config);
}

/**
 * 페이저 한 벌 — 표와 카드 목록이 함께 쓴다.
 *
 * <p>쪽이 하나면 통째로 안 그린다. 누를 수 없는 버튼 둘이 떠 있으면 "다음이 있나?"를
 * 매번 확인하게 된다.
 */
function renderAdminPager(data) {
  if (data.totalPages <= 1) return "";
  return `
    <button class="btn-outline" data-page="${data.page - 1}" ${data.page === 0 ? "disabled" : ""}>이전</button>
    <span class="meta">${data.page + 1} / ${data.totalPages}</span>
    <button class="btn-outline" data-page="${data.page + 1}" ${data.hasNext ? "" : "disabled"}>다음</button>`;
}

/**
 * 셀렉트·입력 묶음을 쿼리 조각으로. 빈 값은 넘기지 않는다(서버가 조건을 건너뛴다).
 *
 * @param map { 파라미터이름: 요소id } — 예 { domain: "fDomain", difficulty: "fLevel" }
 */
function filterQuery(map) {
  return Object.entries(map)
    .map(([key, id]) => {
      const el = document.getElementById(id);
      const v = el ? el.value : "";
      return v ? `&${key}=${encodeURIComponent(v)}` : "";
    })
    .join("");
}
```

- [ ] **Step 3: 표·필터·일괄 처리 CSS를 쓴다**

기존 `table.grid` 규칙은 남기고 하드코딩 색만 토큰으로 바꾼 뒤, 새 것을 더한다.

```css
/* 표 머리글 — #f3f4f6은 밝은 배경 전용이라 다크에서 머리글만 하얗게 남는다 */
table.grid th { background: var(--fill); color: var(--muted); font-weight: 600; }
table.grid td.zero { color: var(--border-strong); }

/* 전체 건수 + 페이저를 한 줄에. 왼쪽이 "얼마나 있나", 오른쪽이 "어디를 보고 있나"다. */
.pager-row {
  display: flex; align-items: center; gap: var(--sp-2);
  margin-top: var(--sp-3);
}
.pager-row .spacer { flex: 1; }
.pager-row button { min-height: 32px; padding: 5px 12px; }

/* 일괄 처리 줄 — 고른 것이 있을 때만 뜬다.
   늘 떠 있으면 "무엇에 대한 버튼인지"가 모호해지고, 고르지 않은 채 누르는 사고가 난다. */
.bulk-bar {
  display: flex; align-items: center; gap: var(--sp-2);
  margin-top: var(--sp-3); padding: var(--sp-2) var(--sp-3);
  background: var(--primary-soft); border: 1px solid var(--primary-border);
  border-radius: var(--radius);
}
.bulk-bar .picked { font-size: var(--fs-2); font-weight: 700; color: var(--primary); }
.bulk-bar button { min-height: 32px; padding: 5px 12px; }
```

- [ ] **Step 4: 문서 목록을 새 렌더러로 옮긴다 (가장 단순한 것부터)**

`admin/documents.html`의 목록 그리는 코드를 지우고 이것으로 바꾼다.
**열 구성과 행 클릭 동작은 지금 화면에서 그대로 옮긴다** — 무엇을 보여 주고 있었는지
먼저 읽고, 빠뜨린 열이 없는지 확인한다.

```javascript
renderAdminTable("#docList", {
  url: "/api/admin/documents",
  rowId: d => d.id,
  columns: [
    { head: "제목", wrap: true, cell: d => `${escapeHtml(d.title)}${editionBadge(d.edition)}` },
    { head: "분야", width: "90px", cell: d => escapeHtml(domainLabel(d.domain)) },
    { head: "수정", width: "110px", cell: d => shortDate(d.updatedAt) },
  ],
  onRowClick: id => location.href = `/admin/documents.html?id=${id}`,
  emptyText: "문서가 없습니다.",
});
```

- [ ] **Step 5: 문제 목록을 옮긴다**

`admin/problems.html`의 `loadProblems()`를 바꾼다. **편집 폼과 백필 도구는 그대로 둔다** —
목록만 옮기는 것이다.

마크업의 `<table class="grid" id="problemTable"></table>`을 감싸던 `.table-wrap`째
`<div id="problemList"></div>` 하나로 바꾼다 — 렌더러가 표와 감싸개를 함께 만든다.

필터 id는 **실제 값을 그대로 쓴다**(`fpDomain` `fpDifficulty` `fpType` `fpDocument`).
지금 `listFilterQuery()`가 쓰는 것과 같아야 하고, 그 함수는 이 교체로 사라진다.

```javascript
function loadProblems() {
  return renderAdminTable("#problemList", {
    url: "/api/admin/problems",
    rowId: p => p.id,
    filters: () => filterQuery({
      domain: "fpDomain", difficulty: "fpDifficulty",
      type: "fpType", documentSlug: "fpDocument",
    }),
    columns: [
      { head: "ID", width: "60px", cell: p => p.id },
      { head: "제목 / 지문", wrap: true, cell: p => problemLabel(p) },
      { head: "분야", width: "90px", cell: p => escapeHtml(domainLabel(p.domain)) },
      { head: "난이도", width: "70px", cell: p => difficultyBadge(p.difficulty) },
      { head: "유형", width: "80px", cell: p => escapeHtml(typeLabel(p.type)) },
      { head: "정답", width: "140px", cell: p => escapeHtml(cut(displayAnswer(p), 20)) },
    ],
    // 행 전체가 "수정"이라 수정 버튼은 없다 — 같은 일을 하는 두 가지 길을 두면
    // 사람은 어느 쪽이 진짜인지 확인하려고 오히려 망설인다.
    onRowClick: id => editProblem(Number(id)),
    emptyText: "조건에 맞는 문제가 없습니다 — 필터를 지우면 전체가 보입니다.",
  });
}
```

**삭제 버튼이 열에서 빠졌다.** 지금은 행 안에 `삭제` 버튼이 있는데, 일괄 처리 줄로 옮긴다:

```javascript
    bulk: [{
      label: "선택한 문제 삭제",
      className: "danger",
      run: async ids => {
        // 되돌릴 수 없는 동작이라 개수를 말하고 한 번 묻는다.
        // armedDelete(두 번 누르기)는 버튼 하나에 붙는 장치라 여기서는 confirm을 쓴다.
        if (!confirm(`${ids.length}개 문제를 삭제합니다. 되돌릴 수 없습니다.`)) return;
        for (const id of ids) {
          await api(`/api/admin/problems/${id}`, { method: "DELETE" });
        }
      },
    }],
```

- [ ] **Step 6: 앱을 띄워 두 목록을 확인한다**

| 확인 | 기대 |
| --- | --- |
| 문서 목록 | 열 셋, 행을 누르면 편집으로 |
| 문제 목록 | 열 여섯, 필터가 걸리고 전체 건수가 바뀐다 |
| 필터로 0건 | "조건에 맞는 문제가 없습니다 — 필터를 지우면…"이 뜬다 |
| 페이저 | 이전/다음이 동작하고, 쪽이 하나면 안 뜬다 |
| 체크박스 | 고르면 일괄 처리 줄이 뜨고, 다 풀면 사라진다 |
| 전체 선택 | 머리글 체크박스가 전부 켜고 끈다 |
| 삭제 | 개수를 말하고 묻는다. 지운 뒤 목록이 다시 그려진다 |
| 다크 | 표 머리글이 어둡다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 7: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(admin): 표 목록을 공용 렌더러 하나로 모은다

목록을 그리는 여섯 가지가 화면마다 복붙돼 있었다 — 조회, 머리글+행,
빈 상태, 클릭 위임, 전체 건수, 페이저. 그중 다섯은 화면마다 같은
코드였고, 같은 코드가 여러 벌이면 한 곳을 고칠 때 나머지를 잊는다.

실제로 "결과 0건" 안내가 문제 목록에만 있고 문서 목록에는 없었다.
필터를 걸어 0건이 되면 머리글만 남아 로딩 중인지 없는 건지 알 수 없었다.

화면은 이제 열이 무엇인가와 행을 누르면 무엇을 하는가만 적는다.

행 안의 삭제 버튼을 일괄 처리 줄로 옮겼다. 하나씩 지우는 것보다 골라서
한 번에 지우는 것이 실제 작업 흐름이고, 행마다 버튼이 있으면 행 클릭
(수정)과 버튼 클릭(삭제)을 판별하는 순서 문제가 계속 따라다닌다.

일괄 처리 줄은 고른 것이 있을 때만 뜬다. 늘 떠 있으면 무엇에 대한
버튼인지 모호해지고, 고르지 않은 채 누르는 사고가 난다.

클릭은 표 하나에 위임한다. 행마다 onclick 문자열을 심는 방식은 값이
HTML 문자열 안에서 코드가 되는 구조라, escapeHtml로 자바스크립트
문자열을 막으려는 잘못된 코드가 실제로 있었다(문서 목록의 slug).

검수 화면은 이 렌더러를 안 쓴다. 카드 목록이고 한 건마다 그리는 것이
많아 표로 눕히면 그 정보가 갈 곳이 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 4: 제보 화면 — 카드 목록에 공용 페이저·필터

`reports.html`은 187줄로 이미 짧다. 표로 바꾸지 않는다 — 제보는 **본문이 긴 글**이라
칸에 넣으면 잘린다. 페이저와 필터만 공용 것으로 바꾸고 셸·토큰을 맞춘다.

**Files:**
- Modify: `src/main/resources/static/admin/reports.html`

**Interfaces:**
- Consumes: Task 3의 `renderAdminPager(data)` `filterQuery(map)`
- Produces: 없음

- [ ] **Step 1: 지금 페이저 코드를 찾는다**

```powershell
Select-String -Path src/main/resources/static/admin/reports.html -Pattern "pager|page|hasNext"
```

- [ ] **Step 2: 페이저를 공용 것으로 바꾼다**

목록을 그린 뒤 페이저 자리에 이렇게 넣는다:

```javascript
  // 페이저는 표와 카드가 같은 것을 쓴다 — 쪽 넘기기는 목록의 생김새와 무관한 동작이다
  document.getElementById("pagerRow").innerHTML = `
    <span class="meta">전체 ${data.totalElements}건</span>
    <span class="spacer"></span>
    ${renderAdminPager(data)}`;

  document.querySelectorAll("#pagerRow [data-page]").forEach(btn => {
    btn.addEventListener("click", () => { page = Number(btn.dataset.page); loadReports(); });
  });
```

마크업의 옛 페이저를 `<div class="pager-row" id="pagerRow"></div>` 하나로 바꾼다.

- [ ] **Step 3: 앱을 띄워 확인한다**

제보가 여러 건 있어야 페이저가 뜬다. 없으면 사용자 화면에서 문제 몇 개에 제보를
넣어 만든다(퀴즈 채점 화면의 "이 문제가 이상한가요?").

| 확인 | 기대 |
| --- | --- |
| 목록 | 지금과 같은 카드 모양 |
| 전체 건수 | 왼쪽에 뜬다 |
| 페이저 | 오른쪽. 쪽이 하나면 안 뜬다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 4: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(admin): 제보 화면의 페이저를 공용 것으로 바꾼다

표로 바꾸지 않았다. 제보는 본문이 긴 글이라 칸에 넣으면 잘리고,
잘린 제보는 읽으나 마나다.

페이저만 공용 것으로 바꾼다 — 쪽 넘기기는 목록의 생김새와 무관한
동작이라, 카드든 표든 같은 것을 쓰는 게 맞다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 5: 검수 화면 — 셸·토큰·페이저만, 기능은 그대로

941줄로 가장 긴 화면이다. **줄이지 않는다.** 긴 이유가 보일러플레이트가 아니라
그 화면이 실제로 하는 일이기 때문이다 — 경고 상자, 난이도 균형 표시, 닫는 질문 검사,
절 목록, 반려 사유, 일괄 승인, 딥링크.

**Files:**
- Modify: `src/main/resources/static/admin/llm.html`

**Interfaces:**
- Consumes: Task 3의 `renderAdminPager(data)` `filterQuery(map)`
- Produces: 없음

- [ ] **Step 1: 이 화면이 무엇을 하는지 먼저 읽는다**

```powershell
Select-String -Path src/main/resources/static/admin/llm.html -Pattern "^function |^async function "
```

스무 개 넘는 함수가 있다. **하나도 지우지 않는다.** 옮기는 것은 셸·색·페이저뿐이다.

- [ ] **Step 2: 이 화면 안의 `renderPager`를 공용 것으로 바꾼다**

`llm.html`에는 이미 `renderPager(prefix, data)`가 있다(191줄). 공용 `renderAdminPager`와
겹치므로 지우고 공용 것을 쓴다. **`prefix`로 두 목록(문제 초안·문서 초안)을 구분하던
구조는 유지한다** — 한 화면에 목록이 둘이라 마운트 지점이 둘이다.

```javascript
/* 목록이 둘(문제 초안·문서 초안)이라 페이저도 둘이다. 공용 renderAdminPager는
   마크업만 만들고 동작은 안 붙이므로, 어느 목록의 쪽을 넘기는지는 여기서 정한다. */
function drawPager(prefix, data, onGo) {
  const row = document.getElementById(prefix + "Pager");
  row.innerHTML = `
    <span class="meta">전체 ${data.totalElements}건</span>
    <span class="spacer"></span>
    ${renderAdminPager(data)}`;
  row.querySelectorAll("[data-page]").forEach(btn => {
    btn.addEventListener("click", () => onGo(Number(btn.dataset.page)));
  });
}
```

- [ ] **Step 3: 현황 숫자를 이 화면 위쪽에 붙인다**

현황(index)이 메뉴에서 빠졌으므로(Task 2), 콘솔을 열면 검수가 첫 화면이다.
현황에서 **자주 보는 숫자만** 여기로 옮긴다 — 히트맵·차트는 옮기지 않는다(그건 현황 화면의 것이다).

```javascript
/**
 * 검수 화면 맨 위 요약 — "지금 콘솔에 할 일이 얼마나 있나".
 *
 * <p>현황 화면을 메뉴에서 뺐으므로 여기가 콘솔의 첫 화면이 됐다. 그 화면의 숫자 중
 * <b>매번 보던 것</b>만 옮긴다 — 히트맵과 차트는 가끔 보는 것이라 현황에 남겨 둔다.
 * 전부 옮기면 검수 화면이 대시보드가 되고, 정작 검수할 초안이 스크롤 아래로 밀린다.
 *
 * <p>실패는 조용히 넘긴다. 요약이 없다고 검수를 못 할 이유가 없다.
 */
async function loadReviewSummary() {
  try {
    const [problems, documents, reports] = await Promise.all([
      countOf("/api/admin/llm-problems/pending-count"),
      countOf("/api/admin/llm-documents/pending-count"),
      countOf("/api/admin/reports/pending-count"),
    ]);
    document.getElementById("reviewSummary").innerHTML = [
      ["문제 초안", problems],
      ["문서 초안", documents],
      ["제보", reports],
    ].map(([label, n]) => `
      <div class="stat">
        <div class="label">${label}</div>
        <div class="num">${n ?? "–"}</div>
      </div>`).join("");
  } catch (e) { /* 요약이 없다고 검수를 못 할 이유는 없다 */ }
}
```

마크업 맨 위에 넣는다:

```html
<div class="stat-row" id="reviewSummary"></div>
```

- [ ] **Step 4: 하드코딩 색을 토큰으로 바꾼다**

```powershell
Select-String -Path src/main/resources/static/admin/llm.html -Pattern "#[0-9a-fA-F]{3,6}"
```

나오는 것을 전부 토큰으로 바꾼다. **HTML의 인라인 `style="color:#..."`이 남아 있으면
다크에서 그 자리만 안 바뀐다** — 사용자 화면 개편에서 이것 때문에 화면이 반쯤 깨져 보였다.

- [ ] **Step 5: 앱을 띄워 확인한다**

**기능을 빼지 않았는지 보는 것이 이 단계의 핵심이다.**

| 확인 | 기대 |
| --- | --- |
| 요약 줄 | 문제 초안·문서 초안·제보 세 칸 |
| 문제 초안 목록 | 경고 상자, 난이도 균형, 절 목록이 그대로 |
| 승인 | 동작하고 카드가 완료 표시로 바뀐다 |
| 반려 | 사유 고르기가 열리고, 반려 뒤 복구가 된다 |
| 일괄 승인 | 여러 개 골라 한 번에 승인된다 |
| 문서 초안 목록 | 본문 뷰어와 절 목록이 그대로 |
| 페이저 | 두 목록 각각 동작한다 |
| 딥링크 | `?id=` 로 들어가면 그 초안으로 간다 |
| 다크 | 흰 판이 남은 곳이 없다 |
| 콘솔 | 빨간 줄 0 |

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(admin): 검수 화면을 새 셸과 토큰에 맞춘다

941줄인데 줄이지 않았다. 긴 이유가 보일러플레이트가 아니라 그 화면이
실제로 하는 일이기 때문이다 — 경고 상자, 난이도 균형, 닫는 질문 검사,
절 목록, 반려 사유, 일괄 승인, 딥링크. 줄이려면 기능을 빼야 하고
그건 이 개편의 목적이 아니다.

바꾼 것은 셋뿐이다. 이 화면 안에만 있던 페이저를 공용 것으로 바꿨고,
하드코딩 색을 토큰으로 옮겼고, 맨 위에 요약 세 칸을 붙였다.

요약은 현황 화면에서 매번 보던 숫자만 가져왔다. 현황을 메뉴에서 빼면서
콘솔의 첫 화면이 여기가 됐는데, 히트맵과 차트까지 옮기면 검수 화면이
대시보드가 되고 정작 검수할 초안이 스크롤 아래로 밀린다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 6: 나머지 셋 — 현황·생성·배치

목록 화면이 아니라 셸·토큰·눈금만 맞춘다.

**Files:**
- Modify: `admin/index.html` `admin/generate.html` `admin/batch.html`
- Modify: `src/main/resources/static/css/style.css` (남은 하드코딩 색)

**Interfaces:**
- Consumes: Task 2의 셸
- Produces: 없음

- [ ] **Step 1: 하드코딩 색을 전부 찾는다**

```powershell
Select-String -Path src/main/resources/static/admin -Pattern "#[0-9a-fA-F]{6}" -Recurse
Select-String -Path src/main/resources/static/css/style.css -Pattern "#[0-9a-fA-F]{6}" |
  Where-Object { $_.Line -notmatch "^\s*(--|\*|/\*)" }
```

`style.css`의 관리자 전용 규칙(`.gen-panel` `.todo-tile` `.heatmap` `.danger` `table.grid`)에
남은 것들이 이번 대상이다. 토큰 선언부(`:root`, `[data-theme]`, `@media`)는 건드리지 않는다.

- [ ] **Step 2: 히트맵 색을 다크에서도 읽히게 한다**

출제 현황 히트맵은 농도로 값을 나타낸다. 밝은 배경 전용 값이라 다크에서 뒤집힌다.

CSS 쪽 두 줄:

```css
/* 칸 사이 간격이 흰색이었다 — 다크에서 흰 격자가 남는다. 표가 얹힌 면색을 쓴다. */
.grid.heatmap td.click-cell { border: 2px solid var(--card); }
/* 값이 0인 칸 — #fafafa는 밝은 배경 전용이다 */
.grid.heatmap td.zero { color: var(--muted); background: var(--fill); }
```

**농도는 JS가 만든다.** `admin/index.html`의 `shade()`가
`style="background: rgba(79, 70, 229, α)"`를 직접 찍는다(223줄 부근). 그 `79, 70, 229`는
라이트의 `--primary`(#4f46e5)를 손으로 풀어 쓴 값이라, 다크에서 `--primary`가
`#818cf8`로 바뀌어도 따라오지 않는다.

CSS 변수를 `rgba()`에 그대로 넣을 수 없으므로(변수가 색 전체를 담고 있다),
`color-mix`로 농도를 만든다 — 사용자 화면의 문서 표 짝수 행에서 이미 쓴 방법이다.

```javascript
  /* 색 농도 — 한 가지 색으로 <많을수록 진하게>.
   *
   * 예전에는 rgba(79, 70, 229, α)를 직접 찍었는데, 그 숫자는 라이트의 --primary를
   * 손으로 풀어 쓴 값이라 다크에서 브랜드색이 바뀌어도 따라오지 않았다.
   * color-mix로 <토큰과 투명도를 섞으면> 테마가 바뀔 때 농도도 함께 따라온다. */
  const shade = n => {
    if (n === 0) return "";
    const t = /* 기존 계산 그대로 */;
    return ` style="background: color-mix(in srgb, var(--primary) ${Math.round(t * 100)}%, transparent)"`;
  };
```

**기존 농도 계산식(`t`)은 손대지 않는다** — 몇 건일 때 얼마나 진한가는 이 개편의
관심사가 아니고, 그 값을 바꾸면 화면이 달라 보이는 이유가 둘이 된다.

- [ ] **Step 3: 세 화면을 띄워 확인한다**

| 화면 | 특히 볼 것 |
| --- | --- |
| 현황 | 히트맵·차트가 다크에서 읽히는지. 툴팁 글자가 보이는지 |
| 생성 | 폼이 375px에서 세로로 접히는지. 주제 범위 목록이 잘리지 않는지 |
| 배치 | 표 넷이 좁은 화면에서 가로 스크롤되는지(페이지가 안 밀리게) |

- [ ] **Step 4: 대비를 잰다**

DevTools에서 관리자 전용 색 조합을 라이트·다크 양쪽에서 잰다.

| 재는 것 | 기준 |
| --- | --- |
| 표 머리글 글자 on `--fill` | 4.5:1 |
| 히트맵 숫자 on 각 농도 | 4.5:1 (가장 진한 칸이 최악이다) |
| `.danger` 버튼 글자 | 4.5:1 |
| 배지 글자(회색·주황) | 4.5:1 |

미달이면 그 자리에서 값을 올린다. **다크 두 블록을 함께 고친다.**

- [ ] **Step 5: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
feat(admin): 현황·생성·배치를 새 셸과 토큰에 맞춘다

목록 화면이 아니라 겉모습만 옮겼다. 기능은 그대로다.

히트맵은 색을 다시 정했다. 칸 사이 간격을 흰색으로 그리고 있어서
다크에서 흰 격자가 남았고, 값이 0인 칸도 밝은 배경 전용 회색이었다.
농도로 값을 나타내는 도구라 테마가 바뀌면 그 농도 자체가 뒤집힌다.

관리자 전용 색 조합의 대비를 라이트·다크 양쪽에서 쟀다. 가장 나쁜
자리는 히트맵의 가장 진한 칸이다 — 거기서 통과하면 나머지는 통과한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## Task 7: 옛 상단 가로바를 걷어낸다

콘솔이 마지막 사용처였다. 이제 지운다.

**Files:**
- Modify: `src/main/resources/static/js/shell.js` (`renderNav` 삭제)
- Modify: `src/main/resources/static/css/style.css` (`.nav` 계열 삭제)

**Interfaces:**
- Consumes: 없음
- Produces: 없음

- [ ] **Step 1: 정말 아무도 안 쓰는지 확인한다**

```powershell
Select-String -Path src/main/resources/static -Pattern "renderNav|renderConsoleNav|renderAdminNav" -Recurse
Select-String -Path src/main/resources/static -Pattern 'class="nav"|id="nav"' -Recurse
```

**둘 다 결과가 비어야 한다.** 하나라도 남으면 그 화면이 아직 안 옮겨진 것이니
지우지 말고 그 화면부터 옮긴다.

- [ ] **Step 2: `shell.js`에서 `renderNav`와 그 주석을 지운다**

`renderNav` 함수와 그 위의 "관리 콘솔이 쓰는 옛 상단 가로바" 주석을 통째로 지운다.
`MENUS`·`sideLinks`·`authAreaHtml`·`wireLogout`·`loadReviewBadge`는 남는다.

- [ ] **Step 3: `style.css`에서 `.nav` 계열을 지운다**

```powershell
Select-String -Path src/main/resources/static/css/style.css -Pattern "^\.nav"
```

`.nav` `.nav .brand` `.nav a` `.nav .spacer` `.nav .user-email` 규칙과 그 위의 주석을 지운다.
**`.nav-badge`는 남긴다** — 이름만 비슷할 뿐 사이드바·탭바·콘솔이 지금도 쓴다.

모바일 보정의 `.nav` 규칙도 함께 지운다:

```powershell
Select-String -Path src/main/resources/static/css/style.css -Pattern "\.nav \{|\.nav a \{|\.nav \.user-email"
```

- [ ] **Step 4: 전 화면을 훑어 아무것도 안 깨졌는지 본다**

사용자 화면 12개와 콘솔 7개를 375·1280 두 폭에서 연다. 콘솔 빨간 줄 0.

- [ ] **Step 5: 전체 빌드**

```powershell
.\gradlew.bat build --console=plain
```

기대: 통과.

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/static
git commit -m @'
refactor(shell): 옛 상단 가로바를 걷어낸다

사용자 화면은 개편 3단계에서 사이드바로 옮겼지만 관리 콘솔이 아직
이 마크업 위에 서 있어 남겨 뒀다. 콘솔까지 옮겼으니 지운다.

renderNav와 .nav 계열 규칙, 모바일 보정까지 함께 지웠다.
.nav-badge는 남긴다 — 이름만 비슷할 뿐 사이드바·탭바·콘솔이 지금도 쓴다.

지우기 전에 아무도 안 쓰는지 코드로 확인했다. "이제 안 쓸 것"과
"지금 안 쓰는 것"은 다르다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KAnMirEG161iMhDTvY6LAH
'@
```

---

## 끝난 뒤

화면 개편 전체가 끝난다. 작업 기록을 `docs/20-ui-redesign.md`에 이어 적는다 —
그 문서의 9장("남은 일")이 이 계획이었으므로, 그 절을 결과로 바꾸고
콘솔에서 깨진 것들을 5장 형식으로 더한다.

`docs/IMPROVEMENTS.md`에도 발견/원인/조치/확인 네 칸으로 남긴다.

## 이 계획이 손대지 않는 것

| 것 | 이유 |
| --- | --- |
| 검수 화면의 기능 | 941줄이 긴 이유가 실제로 하는 일이다. 줄이려면 기능을 빼야 한다 |
| 현황 화면의 히트맵·차트 | 가끔 보는 것이라 검수로 옮기지 않는다. 색만 다크에 맞춘다 |
| `admin/topics.html` | 넘겨주기 전용. 북마크와 문서에 남은 옛 주소를 받는 자리다 |
| 서버 API | 이 계획은 화면만 다룬다. 모자라면 적어 두고 말한다 |
| 관리자 전용 화면의 모바일 최적화 | 깨지지만 않으면 된다. 콘솔은 데스크톱에서 쓴다 |
