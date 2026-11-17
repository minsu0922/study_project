# 문제 AI 검수 다시 짜기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 객관식 문제 검수를 "풀이"와 "편집" 두 호출로 다시 짜서, 정답 결함은 지적하고 해설·오답 설명은 AI가 고쳐 넣는다.

**Architecture:** 새 패키지 `llm/review`에 풀이(`ProblemSolver`), 편집(`ProblemEditor`), 입구(`ProblemReviewPipeline`)를 둔다. 순수 함수(프롬프트 만들기, 응답 대조, 재검사, 고친 글 넣기)와 API 호출을 나눠, 호출은 생성자로 받은 함수로 바꿔 끼워 테스트한다(`ClaudeCodeProblemReviewer`와 같은 방식). 배치 CLI와 `LlmProblemService`가 같은 입구를 부른다. 옛 `ClaudeProblemReviewer`와 A·C 호출, 백필은 지운다.

**Tech Stack:** Java 21, Spring Boot 3.4, Anthropic Java SDK(구조화 출력), JUnit 5 + AssertJ, 정적 HTML/JS(관리 화면)

**Spec:** `docs/superpowers/specs/2026-11-17-ai-review-rebuild-design.md`

## Global Constraints

- 작업은 브랜치 `review-rebuild`에서 한다. 측정(Task 8)이 문턱을 넘은 뒤에만 main에 병합·push한다.
- 해설 길이 `ProblemItemRule.EXPLANATION_MIN`(150)~`EXPLANATION_MAX`(700), 오답 설명 하한 `ProblemItemRule.RATIONALE_MIN`(30). 새 숫자를 만들지 않는다.
- 해설 기준 번호와 뜻은 `docs/superpowers/specs/2026-11-16-explanation-review-design.md`의 기준표(A1~A4, B1~B6, C1~C4)를 쓴다. D1(문체)은 쓰지 않는다.
- AI가 초안에 직접 넣는 칸은 해설과 **오답** 보기의 오답 설명뿐이다. 질문·보기 글·정답은 제안으로만 둔다.
- 풀이가 표시된 정답과 다른 보기를 고른 문제는 편집에 넘기지 않는다.
- 두 호출 모두 생성과 같은 모델(`llm.generation.model`)로 부른다. 풀이는 thinking을 켜고 보기를 섞는다. 편집은 thinking을 끄고 보기를 섞지 않는다.
- `ProblemReview.FindingType`의 기존 값은 지우지 않는다. 저장된 지적(승인 654건, 대기 초안)을 읽을 때 필요하다. 새 코드가 만들지 않을 뿐이다.
- 코드 문제 검수(`ClaudeCodeProblemReviewer`, `CodeProblemCli`)의 동작은 바꾸지 않는다.
- 요금이 드는 실행(`evalProblemReview` 실제 측정)은 횟수·예상 비용을 적어 사용자에게 먼저 묻는다.
- 사용자에게 보이는 문구는 한국어, 전역 CLAUDE.md 문체 규칙을 따른다. 주석은 이유만 2줄 이내.
- 빌드와 커밋은 따로 실행한다. 커밋 메시지는 `타입(범위): …`, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 큰따옴표가 있으면 `git commit -F`.

## Review Focus

1. 편집이 고친 해설이 다른 보기를 정답처럼 설명한다 — "그래서" 줄이 정답 보기 낱말을 담지 않으면 넣지 않고 제안으로 남긴다(Task 5 테스트).
2. 편집이 정답 보기나 없는 보기 번호의 오답 설명을 고쳐 보낸다 — 버린다(Task 5 테스트).
3. 관리 화면 생성 중 검수가 실패한다 — 초안은 고친 글 없이 그대로 남고 지적 칸은 비어 있다(`null`)(Task 6 테스트).
4. 같은 질문 글을 가진 문제가 한 묶음에 둘 있다 — 고친 글이 엉뚱한 초안에 들어가지 않는다. 짝짓기는 목록 위치로 한다(Task 6 테스트).
5. 승인된 문제의 옛 지적(`CHOICE_CUE_LEAK`, `DIFFICULTY_MISMATCH` 등)이 담긴 JSON을 읽는다 — 예외 없이 읽힌다(Task 7 테스트).

---

### Task 0: 작업용 브랜치

- [ ] **Step 1:** `git switch -c review-rebuild`
- [ ] **Step 2:** `git status`로 `generated/_existing-*.json` 두 파일만 바뀌어 있는지 본다. 이 두 파일은 끝까지 커밋하지 않는다.

---

### Task 1: 제목·오답 설명 백필 지우기

**Files:**
- Delete: `llm/service/ProblemTitleBackfillService.java`, `llm/service/ChoiceRationaleBackfillService.java`, `llm/client/ClaudeTitleGenerator.java`, `llm/client/ClaudeRationaleGenerator.java`, `llm/client/TitleGenerator.java`, `llm/client/RationaleGenerator.java`, `llm/client/GeneratedRationale.java`, `llm/dto/TitleBackfillResponse.java`, `llm/dto/RationaleBackfillResponse.java`, `resources/prompts/title/`, `resources/prompts/rationale/`, `resources/static/admin/backfill.html`, 테스트 `ProblemTitleBackfillServiceTest`, `ChoiceRationaleBackfillServiceTest`, `ClaudeTitleGeneratorPromptTest`, `ClaudeRationaleGeneratorPromptTest`
- Modify: `admin/controller/AdminProblemController.java`(백필 엔드포인트), `admin/service/AdminBadgeService.java`(백필 배지), `static/admin/js/admin-shell.js`(메뉴), `admin/audit/AdminActionLabels.java`, 그리고 아래 grep에 걸리는 테스트
- 경로 접두어: `src/main/java/project/study/study_project/`, `src/main/resources/`, `src/test/java/project/study/study_project/`

- [ ] **Step 1:** `grep -rn "Backfill\|backfill\|TitleGenerator\|RationaleGenerator" src/` 결과를 하나씩 본다. `DraftImportRunner`·`DocumentImportService`·`ImportRollbackContractTest`처럼 "backfill"을 다른 뜻(옛 데이터 들여오기)으로 쓰는 곳은 건드리지 않는다. 제목·오답 설명 백필만 지운다.
- [ ] **Step 2:** 위 파일을 지우고 참조를 걷어 낸다. `AdminPageStructureTest`, `AdminBadgeServiceTest`, `PromptsTest`의 백필 기대를 지운다.
- [ ] **Step 3:** `.\gradlew.bat test --console=plain` → `BUILD SUCCESSFUL`
- [ ] **Step 4:** `node <scratchpad>/inline_check.js src/main/resources/static/admin/js/admin-shell.js`로 문법을 본다(없으면 `node --check`). Expected: 오류 0
- [ ] **Step 5:** `docs/25-admin-menu-reorg.md`의 백필 메뉴 줄에 "2026-11-17 지움(채울 데이터 0건)"을 적는다.
- [ ] **Step 6: 커밋** `chore(admin): 할 일이 없는 제목·오답 설명 백필 기능을 지운다`

---

### Task 2: 해설 형식 검사 `ExplanationFormRule`과 단서 경고 정리

**Files:**
- Create: `llm/support/ExplanationFormRule.java`, Test: `llm/support/ExplanationFormRuleTest.java`
- Modify: `llm/support/ProblemItemRule.java`(해설·오답 설명 길이 경고를 이 규칙으로 바꾸고, 단서 경고 셋을 지움), `llm/support/ProblemItemRuleTest.java`, `llm/cli/ProblemReviewEvalCli.java`(`ruleTypeOf`에서 지운 경고 매핑 제거)

**Interfaces:**
- Produces:
  - `public record FormIssue(String criterion, String message)` — `criterion`은 "A4"·"B1"·"B3"·"B5"·"C2"·"C4"
  - `public static List<FormIssue> issuesOf(GeneratedProblemItem item, String sourceSlug, String sourceMd)` — 둘 다 `null`이면 A4를 건너뛴다. `sourceMd`만 없으면 안내 줄 꼴만 본다
  - `public static boolean passes(GeneratedProblemItem item, String sourceSlug, String sourceMd)`

| 기준 | 걸리는 조건 |
|---|---|
| A4 | 마지막 줄이 `(입문편|심화편)의 '절 이름' 절을 다시 읽어 보라)` 꼴이 아니다. 또는 편이 근거 문서 편(slug가 `-advanced`로 끝나면 심화편)과 다르고 입문편 발췌(`DocumentEditionRule.BEGINNER_EXCERPT_HEADER` 뒤)에도 없다. 또는 그 편 본문에 `##`~`####` 머리글로 그 절이 없다. 머리글은 앞뒤 공백과 `~~`를 떼고 견준다 |
| B1 | 첫 줄이 `규칙:`으로 시작하지 않는다 |
| B3 | 첫 줄과 "그래서" 줄 사이의 줄이 3개 미만이다 |
| B5 | `그래서`로 시작하는 줄이 없거나, 그 줄 뒤에 안내 줄 말고 다른 줄이 있다 |
| C2 | 정답이 아닌 보기의 오답 설명이 비었거나 30자 미만이다. 메시지에 보기 위치(1부터)를 적는다 |
| C4 | 해설(앞뒤 공백 뺀 길이)이 150자 미만이거나 700자 초과다 |

B3의 걸음은 우선 "사이의 빈 줄 아닌 줄 수"로 센다. Step 5의 측정으로 다듬는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void 형식을_다_지킨_해설은_걸리지_않는다()
@Test void 문서에_없는_절을_안내하면_A4()
@Test void 근거와_다른_편을_안내하면_A4()
@Test void 입문편_발췌의_절을_안내하면_통과()
@Test void 근거_문서가_없으면_A4를_보지_않는다()
@Test void 규칙으로_시작하지_않으면_B1()
@Test void 걸음이_둘뿐이면_B3()
@Test void 그래서로_맺지_않으면_B5()
@Test void 오답_설명이_짧으면_C2_보기_위치를_적는다()   // 셋째 보기 "틀렸다" → message에 "3"
@Test void 해설이_150자_미만이면_C4()
```

기준 해설은 문제 48459의 해설(`generated/2026-10-27-port-int.json`)과 `## 어떤 때 통하지 않는가` 머리글을 가진 심화편 본문을 쓴다.

- [ ] **Step 2:** `.\gradlew.bat test --tests "*ExplanationFormRuleTest" --console=plain` → 컴파일 실패 확인
- [ ] **Step 3:** 구현한다. 줄은 `explanation.strip().lines()`에서 빈 줄을 뺀다. 머리글은 `(?m)^#{2,4}\s+(.+)$`.
- [ ] **Step 4:** 같은 명령 → 10개 통과
- [ ] **Step 5: 승인된 654문제로 헛경보를 잰다(요금 없음)**

`mysql.exe`로 승인 문제의 해설·보기·오답 설명·근거 slug·근거 본문을 JSON 하나로 뽑아 scratchpad에 둔다. 그 파일을 읽어 `issuesOf`를 돌리는 일회용 테스트(`-Dform.sample=<경로>`가 없으면 건너뜀)로 기준마다 걸린 수와 예시 3건을 찍는다. 한 기준이 20%를 넘게 걸면 예시를 읽고 규칙을 고친다. 고칠 수 없으면 그 기준을 빼고 이유를 보고에 적는다. 결과 표를 사용자에게 보고한다. 일회용 테스트는 지운다.

- [ ] **Step 6: `ProblemItemRule`에 잇는다**

`qualityWarningsOf`에서 해설 길이·"다시 읽을 문서 절이 없음"·"편을 밝히지 않음"·오답 설명 "빠진 보기"·"너무 짧음" 경고를 `ExplanationFormRule.issuesOf(item, null, null)`의 메시지로 바꾼다(`hasSourceDocument`가 거짓이면 A4는 빼고). "해설 없음"과 "정답 보기에 오답 설명이 붙음"은 그대로 둔다. 단서 경고 셋을 지운다: `choiceLengthBiasOf`(정답이 가장 긴 보기), `answerGiveawayOf`(정답이 질문을 되풀이함), `correctLongestSkewOf`(배치 몰림)과 그 상수·도우미·테스트. `sharedChoiceTextOf`(보기끼리 되풀이)는 둔다.

- [ ] **Step 7:** `.\gradlew.bat test --console=plain` → `BUILD SUCCESSFUL`
- [ ] **Step 8: 커밋** `feat(review): 해설 형식을 코드로 검사하고 길이·문장 비교로 거는 단서 경고를 지운다`

---

### Task 3: 공용 인용 대조 `QuoteMatch`와 `Shown` 옮기기

**Files:**
- Create: `llm/review/QuoteMatch.java`, `llm/review/Shown.java`, Test: `llm/review/QuoteMatchTest.java`, `llm/review/ShownTest.java`
- Modify: `llm/client/ClaudeCodeProblemReviewer.java`(`appearsIn`이 `QuoteMatch`를 부름)

**Interfaces:**
- Produces:
  - `public static boolean QuoteMatch.in(String quote, String... texts)` — `ClaudeDocumentFactChecker.normalize`로 맞춰 어느 글에든 들어 있으면 참. 빈 인용은 거짓
  - `public record Shown(int index, GeneratedProblemItem item, List<Integer> order)`와 `correctNo()`, `choiceText(int no)`, `inOriginalNumbers(String)`, `public static List<Shown> showable(List<GeneratedProblemItem>)` — `ClaudeProblemReviewer`의 것을 그대로 옮긴다(질문 해시 씨앗으로 섞기 포함)

- [ ] **Step 1:** `ClaudeProblemReviewerTest`의 `Shown`·`showable` 테스트를 `ShownTest`로 옮기고, `QuoteMatchTest`(공백·줄바꿈 차이 무시, 빈 인용 거짓)를 쓴다.
- [ ] **Step 2:** 옮겨 구현하고 `ClaudeCodeProblemReviewer.appearsIn`의 본문을 `QuoteMatch.in(...)`으로 바꾼다. `ClaudeProblemReviewer`는 Task 6에서 통째로 지우므로 여기서 손대지 않는다.
- [ ] **Step 3:** `.\gradlew.bat test --tests "project.study.study_project.llm.*" --console=plain` → 통과
- [ ] **Step 4: 커밋** `refactor(review): 인용 대조와 보기 섞기를 검수 공용 클래스로 옮긴다`

---

### Task 4: 풀이 호출 `ProblemSolver`

**Files:**
- Create: `llm/review/ProblemSolver.java`, `resources/prompts/problem-review/solve.txt`, Test: `llm/review/ProblemSolverTest.java`
- Modify: `llm/client/ProblemReview.java`(`QUESTION_REVEALS` 라벨을 "질문에 정답이 적힘"으로)

**Interfaces:**
- Consumes: Task 3 `Shown`, `QuoteMatch`
- Produces (모두 `ProblemSolver` 안):
  - `public record Solve(int problemNo, String answerReason, int chosenNo, int otherCorrectNo, String otherCorrectReason, String supportQuote, String answerInQuestionQuote)`
  - `public record SolveBatch(List<Solve> solves)`
  - `static final String PROMPT = Prompts.load("problem-review/solve.txt")`
  - `static String buildPrompt(List<Shown> shown, SourceDocument source)` — 지금 `buildJudgePrompt`와 같은 꼴(문서 → 문제, 섞인 보기, 자료 포함)
  - `static List<Finding> findingsOf(List<Shown> shown, SolveBatch batch, String documentMd)`
  - `static boolean solvedAsMarked(Shown s, Solve solve)`

`findingsOf`가 내는 지적은 지금 `compare`의 정답·근거 부분을 그대로 가져온다: `ANSWER_MISMATCH`, `OTHER_CORRECT`, `NO_SUPPORT`(인용 없음 / 문서에 없는 인용). 새로 `QUESTION_REVEALS`는 `answerInQuestionQuote`가 비어 있지 않고 `QuoteMatch.in(quote, question)`일 때만 낸다. detail에 그 구절을 적는다. 난이도·의견·단서 지적은 내지 않는다.

`solve.txt`는 `judge.txt`의 1~3번을 그대로 두고, 4번을 "질문 문장 안에 정답이 그대로 적혀 있으면(정답 보기의 핵심 낱말이나 그 뜻을 질문이 이미 말하면) 그 구절을 글자 그대로 옮긴다. 아니면 빈 문자열. 보기와 질문이 같은 주제어를 쓰는 것은 해당하지 않는다"로 바꾼다. 5·6번(난이도·의견)과 난이도 정의는 뺀다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void 다른_보기를_고르면_ANSWER_MISMATCH와_원래_번호의_근거()
@Test void 다른_정답이_있으면_OTHER_CORRECT()
@Test void 근거_인용이_없거나_지어내면_NO_SUPPORT()
@Test void 질문에_있는_정답_구절이면_QUESTION_REVEALS()
@Test void 질문에_없는_구절은_QUESTION_REVEALS를_내지_않는다()
@Test void 프롬프트에_정답_표시와_해설이_없다()
@Test void 문제를_빠뜨린_응답은_그_문제만_건너뛴다()
```

- [ ] **Step 2:** 실패 확인 → **Step 3:** 구현 → **Step 4:** `.\gradlew.bat test --tests "*ProblemSolverTest" --console=plain` 통과
- [ ] **Step 5: 커밋** `feat(review): 정답을 가린 채 문서로 푸는 풀이 호출을 만든다`

---

### Task 5: 편집 호출 `ProblemEditor`와 재검사

**Files:**
- Create: `llm/review/ProblemEditor.java`, `resources/prompts/problem-review/edit.txt`, Test: `llm/review/ProblemEditorTest.java`
- Modify: `llm/client/ProblemReview.java`(새 `FindingType` 값)

**Interfaces:**
- Consumes: Task 2 `ExplanationFormRule`, Task 3 `Shown`·`QuoteMatch`, Task 4 `Solve`
- Produces:
  - `public record RationaleFix(int choiceNo, String text)` — `choiceNo`는 원래 순서 1부터
  - `public record Edit(int problemNo, String criteriaReason, List<String> failedCriteria, String rewrittenExplanation, List<RationaleFix> rewrittenRationales, String questionFix, String exhibitIssue)` — 이유 먼저, 결론 나중
  - `public record EditBatch(List<Edit> edits)`
  - `static final String PROMPT = Prompts.load("problem-review/edit.txt")`
  - `static String buildPrompt(List<Shown> toEdit, Map<Integer, Solve> solves, SourceDocument source, Map<Integer, List<FormIssue>> formIssues)` — 문서 → 문제마다 질문(자료 포함), **원래 순서** 보기와 `← 정답`, 오답 설명, 해설, 풀이 이유, 풀이가 낸 질문 구절, `코드 검사: [B3] …` 줄. 키는 `Shown.index()`
  - `static List<Finding> findingsOf(List<Shown> toEdit, EditBatch batch, SourceDocument source)` — 재검사까지 하는 순수 함수
  - `FindingType` 새 값: `EXPLANATION_REWRITTEN("AI가 해설을 고침", Grade.POLISH)`, `RATIONALE_REWRITTEN("AI가 오답 설명을 고침", Grade.POLISH)`, `EXPLANATION_SUGGESTED("해설 고친 안", Grade.POLISH)`

`findingsOf`의 판정:

| 경우 | 지적 |
|---|---|
| 고친 해설이 원래와 다르고, 바꾼 문제가 `ExplanationFormRule.passes`, "그래서" 줄이 정답 보기 글의 2자 이상 낱말 하나를 담음 | `EXPLANATION_REWRITTEN`, `Suggestion(EXPLANATION, null, 새 해설)`, detail = 남은 근거 줄 + `옛 해설: …` |
| 고친 해설이 위를 못 넘음 | A1~A3이 걸렸으면 `EXPLANATION_MISMATCH`(결함), 아니면 `EXPLANATION_SUGGESTED`. 같은 suggestion, detail에 `넣지 않은 이유: …` |
| 고친 오답 설명: 실제 보기 번호, 정답 아님, 30자 이상, 원래와 다름 | 보기마다 `RATIONALE_REWRITTEN`, `Suggestion(RATIONALE, 보기 위치(0부터), 글)`, detail `옛 오답 설명: …`. 아니면 버림 |
| `questionFix`가 있고 원래와 다르고 `ProblemItemRule.defectOf`를 통과 | `QUESTION_REVEALS`, `Suggestion(QUESTION, null, 고친 질문)`. 풀이가 구절을 낸 문제에만 |
| `exhibitIssue`가 있음 | `EXHIBIT_MISMATCH`(결함) |
| 걸린 기준은 있는데 고친 글이 없음 | A면 `EXPLANATION_MISMATCH`, 아니면 `EXPLANATION_SUGGESTED`(suggestion 없음) |
| `criteriaReason`의 줄: 인용(「」 안)이 해설·오답 설명에 없음 | 그 줄을 버린다. 남은 근거가 없고 코드 검사에도 안 걸렸으면 해설 지적을 만들지 않는다 |

`edit.txt` 내용: 역할(마지막 편집자), 기준표 A1~A3·B1~B6·C1~C4를 예/아니오 물음으로, 걸린 문장은 「」로 글자 그대로 인용, 지킬 것(정답·보기 뜻 불변, 문서에 없는 사실 금지), 생성 프롬프트 `prompts/problem/system.txt`의 `[해설]`·`[오답 설명]` 절 꼴(그 절을 그대로 옮겨 싣는다), 코드 검사 줄은 확인된 사실이니 반드시 고칠 것, 질문 구절이 넘어온 문제만 `questionFix`, 자료 어긋남(지금 `after-solve.txt` 2번 문구), 걸린 것이 없으면 빈 값.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void 재검사를_통과한_해설은_REWRITTEN과_옛_해설()
@Test void 형식을_어긴_해설은_제안으로_남는다()
@Test void 그래서_줄이_정답을_가리키지_않으면_넣지_않는다()
@Test void A_기준이_걸렸는데_못_넣으면_EXPLANATION_MISMATCH()
@Test void 정답_보기나_없는_번호의_오답_설명은_버린다()
@Test void 원래와_같은_글은_지적이_없다()
@Test void 질문_구절이_없던_문제의_questionFix는_버린다()
@Test void 자료_어긋남은_EXHIBIT_MISMATCH()
@Test void 인용이_없는_근거_줄은_버린다()
@Test void 프롬프트는_원래_순서_보기와_코드_검사_줄을_싣는다()
```

- [ ] **Step 2:** 실패 확인 → **Step 3:** 구현 → **Step 4:** `.\gradlew.bat test --tests "*ProblemEditorTest" --console=plain` 통과
- [ ] **Step 5: 커밋** `feat(review): 해설·오답 설명을 고치는 편집 호출과 코드 재검사를 만든다`

---

### Task 6: 입구 `ProblemReviewPipeline`과 두 경로 잇기, 옛 검수기 지우기

**Files:**
- Create: `llm/review/ProblemReviewPipeline.java`, Test: `llm/review/ProblemReviewPipelineTest.java`
- Modify: `llm/client/ProblemReviewer.java`, `llm/client/ProblemReviewerConfig.java`, `llm/cli/BatchReports.java`, `llm/cli/DraftGeneratorCli.java:299-337`, `llm/dto/GeneratedBatchFile.java`, `llm/service/LlmProblemService.java:327-371`, `llm/client/ProblemReview.java`, 테스트 `LlmProblemServiceTest`, `BatchReportsTest`·`DraftGeneratorCliTest`(있으면)
- Delete: `llm/client/ClaudeProblemReviewer.java`, `ClaudeProblemReviewerTest.java`, `prompts/problem-review/choice-only.txt`, `judge.txt`, `after-solve.txt`, `ProblemReview`의 `ChoiceOnly*`·`Judgement*`·`AfterSolve*`·`AfterSolveFix`

**Interfaces:**
- Consumes: Task 4·5 전부, `FlaggedSourceRule.link`
- Produces:
  - `ProblemReviewer`: `Reviewed review(List<GeneratedProblemItem> problems, SourceDocument source, List<FactCheckFinding> facts)` — `facts`는 `null` 가능
  - `public record Reviewed(List<GeneratedProblemItem> problems, List<Finding> findings)` (`ProblemReviewer` 안)
  - `ProblemReviewPipeline(String model)`와 테스트용 `ProblemReviewPipeline(Function<String, SolveBatch> solver, Function<String, EditBatch> editor)`
  - `static List<GeneratedProblemItem> apply(List<GeneratedProblemItem> problems, List<Finding> findings)` — `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN`의 suggestion을 반영한다. 반영한 지적에서는 suggestion을 뗀다(`Finding`의 새 `withoutSuggestion()`)
  - `GeneratedBatchFile withProblemsAndFindings(List<GeneratedProblemItem> problems, List<Finding> findings)`

흐름(`review`): `Shown.showable` → 풀이 호출 → `ProblemSolver.findingsOf` → 표시대로 푼 문제만 편집 호출 → `ProblemEditor.findingsOf` → `apply` → `FlaggedSourceRule.link`를 더한다. 풀이가 실패하면(`BusinessException`) 예외를 그대로 던진다. 부르는 쪽이 지금처럼 "검수 실패"로 처리한다. 편집이 실패하면 풀이 지적만 담아 돌려준다.

호출 코드(`ClaudeCalls.create`, thinking 켜기, 토큰 합계)는 `ClaudeProblemReviewer.call`을 그대로 옮긴다.

연결:
- `DraftGeneratorCli`: `reportProblemReview(new ProblemReviewPipeline(model), kept, source, resolved.factFindings(), date)`를 부르고, 결과가 있으면 `attachReviewFindings`가 `batch.withProblemsAndFindings(reviewed.problems(), reviewed.findings())`를 쓴다. `FlaggedSourceRule`을 따로 부르던 줄은 지운다(입구가 한다). `reportFlaggedSources`의 요약 줄은 입구 결과에서 `SOURCE_FLAGGED`만 골라 남긴다. 고친 수를 `✏️ 해설 n건·오답 설명 m건을 AI가 고쳐 넣었습니다`로 요약에 적는다.
- `LlmProblemService`: 초안 저장은 지금처럼 먼저 한다. 검수 결과가 오면 `drafts.get(i)`와 `reviewed.problems().get(i)`를 **목록 위치로** 짝지어, 해설이나 보기 JSON이 달라진 초안은 `draft.applyAiSuggestion(question, explanation, choicesJson, findingsJson)`으로 고친다. 지적 JSON은 지금처럼 `DraftAiFinding.groupByQuestion`으로 만든다. 문서 사실 지적은 이 경로에 없으므로 `facts`는 `null`.
- `difficulty` 인자는 검수 경로에서 뺀다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
// ProblemReviewPipelineTest (가짜 solver·editor 함수)
@Test void 표시대로_푼_문제만_편집에_넘긴다()
@Test void 고친_해설과_오답_설명이_문제에_들어가고_제안은_떨어진다()
@Test void 편집이_실패하면_풀이_지적만_돌려준다()
@Test void 풀이가_실패하면_예외를_던진다()
@Test void 문서_사실_지적을_근거_인용에_잇는다()
// LlmProblemServiceTest
@Test void 관리_화면_생성은_고친_해설로_초안을_고친다()
@Test void 검수가_실패하면_초안은_그대로이고_지적은_null이다()
@Test void 질문이_같은_두_문제도_위치로_짝지어_고친다()
```

- [ ] **Step 2:** 실패 확인 → **Step 3:** 구현하고 옛 클래스·프롬프트·record를 지운다 → **Step 4:** `.\gradlew.bat test --console=plain` → `BUILD SUCCESSFUL`
- [ ] **Step 5: 커밋** `feat(review): 풀이·편집 검수를 한 입구로 묶어 배치와 관리 화면이 함께 쓰고 옛 검수기를 지운다`

---

### Task 7: 들여오기와 검수함

**Files:**
- Modify: `llm/dto/DraftAiFinding.java`(`groupByQuestion`), `static/admin/llm.html`(`aiFindingsBoxOf` 근처)
- Test: `llm/dto/DraftAiFindingTest.java`(없으면 만든다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void AI가_고쳐_넣은_지적은_처리된_것으로_저장한다()    // resolution "AI가 고쳐 넣음"
@Test void 제안으로_남은_해설_지적은_미처리다()               // EXPLANATION_SUGGESTED → resolution null
@Test void 옛_지적_종류가_담긴_JSON도_읽힌다()                // CHOICE_CUE_LEAK·DIFFICULTY_MISMATCH·AWKWARD_SENTENCE
```

- [ ] **Step 2:** 실패 확인 → 구현 → `.\gradlew.bat test --tests "*DraftAiFindingTest" --console=plain` 통과
- [ ] **Step 3: 검수함 표시** — `aiFindings`에 `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN`이 있으면 카드 머리에 "AI가 해설을 고침" 칩을 단다. 그 지적을 펼치면 detail의 `옛 해설:`·`옛 오답 설명:` 아래 글을 지금 글과 나란히 보여 준다(옛 글 흐리게). 기존 상자·칩 모양을 쓴다.
- [ ] **Step 4: 화면 확인** — 인라인 스크립트 문법 검사(오류 0). 8081을 띄우고 확인용 초안 한 건을 SQL로 넣어(`ai_findings_json`에 `EXPLANATION_REWRITTEN` 1건) 칩과 나란히 보기를 캡처로 본다. 확인 뒤 그 초안을 지우고 8081을 내린다.
- [ ] **Step 5: 커밋** `feat(review): 검수함에 AI가 고친 해설을 표시하고 옛 글과 나란히 보여 준다`

---

### Task 8: 측정

**Files:**
- Modify: `llm/cli/ProblemReviewEvalCli.java`, `eval-samples/problem-review.json`, Test: `llm/cli/ProblemReviewEvalCliTest.java`

**Interfaces:**
- Produces: `record Mutation(Integer moveAnswerTo, Integer choice, String text, String explanation, Integer rationaleOf, String rationale)` — 기존 3인자 생성자 유지

- [ ] **Step 1:** `ProblemReviewEvalCli`가 `ProblemReviewPipeline`을 부르게 바꾸고, `mutate`에 해설·오답 설명 바꾸기를 더한다. 테스트 `해설을_바꾼다`, `오답_설명을_바꾼다`. `score`는 "기대 종류 중 하나라도 나오면 적발"로 센다(이미 그렇다).
- [ ] **Step 2: 표본을 고친다** — 지금 표본 18문제의 기대값 중 `CHOICE_CUE_LEAK` 7건·`DIFFICULTY_MISMATCH` 2건은 더 이상 내지 않으므로 지운다(그 문제는 멀쩡한 문제로 센다). `QUESTION_REVEALS` 2건은 질문에 정답이 그대로 적힌 경우인지 읽어 보고, 아니면 지운다. 해설 결함 그룹 `explanation-quality`를 더한다: 결함 9건(A3 틀린 계산, A4 없는 절, A4 다른 편, B2 풀지 않은 용어, B3 값 없는 예시, B5 맺음 없음, C1 오답 나열, C2 "틀렸다" 한마디, C3 다른 보기를 가리킴)과 멀쩡한 문제 10건. 기대값은 `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN`·`EXPLANATION_MISMATCH`·`EXPLANATION_SUGGESTED` 중 맞는 것.
- [ ] **Step 3: 요금 없는 확인** — `.\gradlew.bat evalProblemReview -PevalArgs="--rules-only=true --label=rebuild-rules" --console=plain` → 보고서가 나오고, 코드 검사가 A4·B5·C2·C4 결함을 잡는다.
- [ ] **Step 4: 실제 측정은 사용자에게 묻는다** — 그룹 수 × 2호출과 예상 비용(배치 1회 약 $0.25 기준)을 적는다. 승인되면 `--label=rebuild`로 돌린다. 문턱: 적발률 80% 이상, 멀쩡한 문제 10건에 헛경보 2건 이하, 정답 결함(`ANSWER_MISMATCH`·`OTHER_CORRECT`) 4건 중 놓침 1건 이하. 고친 해설을 사람이 읽어 정답·문서 사실이 지켜졌는지 본다. 결과를 사용자에게 보고한다.
- [ ] **Step 5: 커밋** `test(review): 새 검수 측정 표본을 고치고 해설 결함 그룹을 더한다`

---

### Task 9: 문서 정리와 병합

**Files:**
- Modify: `docs/22-ai-review.md`(§3 문제 검수를 풀이·편집으로 다시 쓰고, §6·§7 비용, §8 "AI가 직접 고치기" 항목에 해설·오답 설명 예외), `docs/IMPROVEMENTS.md`(발견/원인/조치/확인), `docs/superpowers/specs/2026-11-16-explanation-review-design.md`와 `plans/2026-11-16-explanation-review.md` 맨 위에 "2026-11-17 설계로 대신함" 한 줄

- [ ] **Step 1:** 문서를 고치고 측정 결과(Task 8)를 확인 칸에 적는다.
- [ ] **Step 2:** humanize-korean으로 윤문하고, 코드 블록·수치·경로·용어가 그대로인지 원문과 대조한다.
- [ ] **Step 3:** `.\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`
- [ ] **Step 4:** 커밋 `docs(review): 풀이·편집 두 호출로 바뀐 문제 검수를 적는다`
- [ ] **Step 5:** 측정이 문턱을 넘었으면 `git switch main`, `git merge --no-ff review-rebuild`, `git push`, `git branch -d review-rebuild`. 못 넘었으면 병합하지 않고 결과와 함께 사용자에게 묻는다.
