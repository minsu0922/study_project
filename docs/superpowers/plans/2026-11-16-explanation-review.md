# 해설 검수 강화 Implementation Plan

> 2026-11-17: 이 문서는 [문제 AI 검수 다시 짜기 설계](../specs/2026-11-17-ai-review-rebuild-design.md)로 대신했다. 해설 기준표만 그쪽이 가져다 쓴다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문제 검수에 해설 검수 호출 D를 더해, 기준표 A·B·C에 걸린 해설과 오답 설명을 AI가 고쳐 초안에 넣고 옛 글을 지적에 남긴다.

**Architecture:** 코드로 셀 수 있는 기준은 새 순수 클래스 `ExplanationFormRule`이 본다. `ClaudeProblemReviewer`가 C 다음에 D를 부르고, 응답을 코드로 재검사해 "고쳐 넣을 것"과 "제안으로 남길 것"을 지적으로 나눈다. `DraftGeneratorCli`가 고쳐 넣을 지적을 문제에 반영해 결과 파일을 다시 쓰고, 들여오기는 그 지적을 처리된 것으로 저장한다.

**Tech Stack:** Java 21, Spring Boot 3.4, Anthropic Java SDK(구조화 출력), JUnit 5 + AssertJ, 정적 HTML/JS(관리 화면)

**Spec:** `docs/superpowers/specs/2026-11-16-explanation-review-design.md`

## Global Constraints

- 해설 길이: `ProblemItemRule.EXPLANATION_MIN`(150) ~ `EXPLANATION_MAX`(700). 오답 설명 하한: `ProblemItemRule.RATIONALE_MIN`(30). 새 숫자를 만들지 말고 이 상수를 쓴다.
- 기준 번호와 뜻은 스펙의 기준표(A1~A4, B1~B6, C1~C4, D1)를 그대로 쓴다. 지적 근거에 기준 번호를 적는다.
- A·B·C에 걸리면 고친다. D1은 의견으로만 남기고 고치지 않는다.
- AI가 고칠 수 있는 칸은 해설과 **오답** 보기의 오답 설명뿐이다. 질문·보기 글·정답·정답 보기의 설명은 바꾸지 않는다.
- B가 표시된 정답을 고르지 않은 문제는 D에 넘기지 않고, 고치지도 않는다.
- D는 생성과 같은 모델(`ClaudeProblemReviewer`의 `model`)로 부른다.
- 편 판별: 근거 문서 slug가 `-advanced`로 끝나면 심화편, 아니면 입문편. 본문에 `DocumentEditionRule.BEGINNER_EXCERPT_HEADER`가 있으면 그 뒤는 입문편 절이다.
- 자동 승인·자동 거절·승인 막기는 하지 않는다. 고쳐진 초안도 일괄 승인에 그대로 둔다.
- 사용자에게 보이는 문구는 한국어, 전역 CLAUDE.md 문체 규칙을 따른다. 주석은 이유만 2줄 이내.
- 실제 API를 부르는 측정(`evalProblemReview`)은 요금이 들어 실행 전 사용자에게 횟수·비용을 묻는다.
- 빌드와 커밋은 따로 실행한다. 커밋 메시지는 `타입(범위): …` 꼴, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. D가 고친 해설이 다른 보기를 정답처럼 설명하는 경우 — 재검사로 막을 수 없으니, 고친 해설의 "그래서" 줄이 정답 보기 글의 핵심어를 담지 않으면 넣지 않고 제안으로 남긴다(Task 3 테스트).
2. D가 정답 보기나 없는 보기 번호에 오답 설명을 고쳐 보낸 경우 — 버린다(Task 3 테스트).
3. D가 원래와 똑같은 글을 "고친 해설"로 보낸 경우 — 지적을 만들지 않는다(Task 3 테스트).
4. D 호출이 실패한 날 — A·B·C 지적과 1차 코드 검사 지적은 남고 해설은 그대로다(Task 3 테스트).
5. 입문편 발췌를 함께 받은 심화편 문제가 입문편 절을 안내한 경우 — A4로 걸지 않는다(Task 1 테스트).

---

### Task 1: 해설 형식 코드 검사 `ExplanationFormRule`

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/ExplanationFormRule.java`
- Test: `src/test/java/project/study/study_project/llm/support/ExplanationFormRuleTest.java`

**Interfaces:**
- Consumes: `GeneratedProblemItem`, `SourceDocument`, `ProblemItemRule.EXPLANATION_MIN/MAX/RATIONALE_MIN`, `DocumentEditionRule.BEGINNER_EXCERPT_HEADER`
- Produces:
  - `public record FormIssue(String criterion, String message)` (중첩 record, `criterion`은 "A4"·"B1"·"B3"·"B5"·"C2"·"C4")
  - `public static List<FormIssue> issuesOf(GeneratedProblemItem item, SourceDocument source)` — `source`가 `null`이면 A4를 건너뛴다
  - `public static boolean passes(GeneratedProblemItem item, SourceDocument source)` — `issuesOf`가 비었는지

판정 규칙(스펙의 코드 몫):

| 기준 | 걸리는 조건 |
|---|---|
| A4 | 마지막 줄이 `(입문편|심화편|문서)의 '절 이름' 절을 다시 읽어 보라)` 꼴이 아니거나, 편이 근거 문서 편과 다르고 입문편 발췌에도 해당하지 않거나, 그 편 본문에 `## 절 이름`·`### 절 이름`·`#### 절 이름` 머리글이 없다. 머리글은 앞뒤 공백과 `~~`를 떼고 견준다. 입문편 발췌가 있는 심화편 문서는 입문편 절 안내를 발췌 쪽 머리글로 견준다 |
| B1 | 첫 줄이 `규칙:`으로 시작하지 않는다 |
| B3 | 첫 줄과 "그래서" 줄 사이에서 `:`나 `→`를 담은 줄이 3개 미만이다 |
| B5 | `그래서`로 시작하는 줄이 없거나, 그 줄 뒤에 안내 줄 말고 다른 줄이 있다 |
| C2 | 정답이 아닌 보기 가운데 오답 설명이 비었거나 30자 미만인 것이 있다. 메시지에 보기 위치(1부터)를 적는다 |
| C4 | 해설(앞뒤 공백 뺀 길이)이 150자 미만이거나 700자 초과다 |

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ExplanationFormRuleTest`에 아래를 둔다. 테스트용 해설은 이번에 고친 문제 48459의 해설(`규칙: …\n…\n그래서 …\n(심화편의 '어떤 때 통하지 않는가' 절을 다시 읽어 보라)`)과, `## 어떤 때 통하지 않는가` 머리글을 가진 심화편 문서(slug `port-number-program-demultiplexing-advanced`)를 쓴다.

```java
@Test void 형식을_다_지킨_해설은_걸리지_않는다()            { assertThat(issuesOf(good, advDoc)).isEmpty(); }
@Test void 문서에_없는_절을_안내하면_A4()                   { assertThat(criteria(withPointer("심화편", "없는 절"), advDoc)).containsExactly("A4"); }
@Test void 근거와_다른_편을_안내하면_A4()                   { assertThat(criteria(withPointer("입문편", "어떤 때 통하지 않는가"), advDocWithoutExcerpt)).containsExactly("A4"); }
@Test void 입문편_발췌의_절을_안내하면_통과()                { assertThat(criteria(withPointer("입문편", "실무에서는 이렇게 쓴다"), advDocWithExcerpt)).isEmpty(); }
@Test void 규칙으로_시작하지_않으면_B1()                    { /* 첫 줄을 "정답은 …"으로 바꾼 해설 → "B1" */ }
@Test void 걸음이_둘뿐이면_B3()                             { /* ':' 담은 중간 줄 2개 → "B3" */ }
@Test void 그래서로_맺지_않으면_B5()                        { /* 그래서 줄을 지운 해설 → "B5" */ }
@Test void 오답_설명이_짧으면_C2_보기_위치를_적는다()        { /* 셋째 보기 rationale "틀렸다" → message에 "3" 포함 */ }
@Test void 해설이_150자_미만이면_C4()                       { /* 짧은 해설 → "C4" */ }
@Test void 근거_문서가_없으면_A4를_보지_않는다()             { assertThat(criteria(withPointer("심화편", "없는 절"), null)).doesNotContain("A4"); }
```

`criteria(item, source)`는 `issuesOf(...).stream().map(FormIssue::criterion).toList()`를 돌려주는 테스트 안 도우미다.

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.support.ExplanationFormRuleTest" --console=plain`
Expected: 컴파일 실패(`ExplanationFormRule` 없음)

- [ ] **Step 3: `ExplanationFormRule`을 구현한다**

위 표대로 판정한다. 줄은 `explanation.strip().lines()`로 나누고 빈 줄은 건너뛴다. 머리글은 `(?m)^#{2,4}\s+(.+)$`로 뽑는다.

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: 위와 같은 명령
Expected: `BUILD SUCCESSFUL`, 10개 통과

- [ ] **Step 5: 이번에 고친 문제 해설로 헛경보를 본다(요금 없음)**

`generated/2026-10-27-port-int.json`, `2026-10-28-subnet-int.json`, `2026-10-31-packet2-adv.json`, `2026-10-31-packet2-int.json`의 문제와 `generated/documents/`의 해당 심화편 본문으로 `issuesOf`를 돌리는 일회용 테스트를 만들어 결과를 본다. 손으로 고친 9개 해설에서 걸리는 기준이 있으면 규칙과 해설 중 어느 쪽이 틀렸는지 보고 규칙을 고친다. 일회용 테스트는 지운다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/support/ExplanationFormRule.java src/test/java/project/study/study_project/llm/support/ExplanationFormRuleTest.java
git commit -m "feat(review): 해설 형식을 코드로 검사한다"
```

---

### Task 2: D 응답 스키마·지적 종류·프롬프트

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/ProblemReview.java`
- Create: `src/main/resources/prompts/problem-review/explanation.txt`
- Modify: `src/main/resources/prompts/problem-review/after-solve.txt`
- Test: `src/test/java/project/study/study_project/llm/client/ClaudeProblemReviewerTest.java`(AfterSolve 생성자 바뀐 곳만)

**Interfaces:**
- Produces (모두 `ProblemReview` 안):
  - `public record RationaleFix(int choiceNo, String text)` — `choiceNo`는 원래 순서 1~4
  - `public record ExplanationReview(int problemNo, String criteriaReason, List<String> failedCriteria, String rewrittenExplanation, List<RationaleFix> rewrittenRationales, String styleNote)` — 필드 순서는 이유 → 결론. `failedCriteria`는 "A2"·"B3" 같은 번호 목록. `criteriaReason`은 기준마다 `기준: 걸린 문장 인용 — 이유` 한 줄씩
  - `public record ExplanationReviewBatch(List<ExplanationReview> reviews)`
  - `FindingType` 새 값: `EXPLANATION_REWRITTEN("AI가 해설을 고침", Grade.POLISH)`, `RATIONALE_REWRITTEN("AI가 오답 설명을 고침", Grade.POLISH)`, `EXPLANATION_WEAK("해설 다듬기 필요", Grade.POLISH)`, `EXPLANATION_STYLE("해설 문체", Grade.OPINION)`. 기존 `EXPLANATION_MISMATCH`(결함)는 "A에 걸렸는데 고쳐 넣지 못함"에 쓴다
  - `AfterSolve`에서 `explanationIssue` 필드를 뺀다. 편의 생성자는 `AfterSolve(int problemNo, AfterSolveFix fixTarget, int fixChoiceNo, String fixText)`가 된다

- [ ] **Step 1: `AfterSolve`에서 `explanationIssue`를 빼고, 컴파일이 깨진 테스트 호출을 새 생성자로 바꾼다**

`ClaudeProblemReviewer.applyAfterSolve`의 `EXPLANATION_MISMATCH` 분기도 함께 지운다(Task 3이 D에서 다시 만든다). `after-solve.txt`에서 1번 항목(explanationIssue)을 지우고 번호를 당긴다.

- [ ] **Step 2: 새 record와 `FindingType` 값을 더한다**

`@JsonPropertyDescription`에 각 칸의 뜻을 적는다. `rewrittenExplanation`과 `rewrittenRationales`는 "A·B·C 기준에 걸렸을 때만 채운다. 정답과 보기는 그대로 두고 근거 문서에 없는 사실을 더하지 않는다"를 담는다.

- [ ] **Step 3: `explanation.txt`를 쓴다**

내용: 역할(해설 검수자), 스펙 기준표 A1~A3·B1~B6·C1~C4·D1을 예/아니오 물음으로, 걸린 문장은 글자 그대로 인용, 고칠 때 지킬 것(정답·보기 불변, 문서에 없는 사실 금지, 생성 프롬프트 `[해설]`·`[오답 설명]` 꼴), 함께 넘기는 "코드 검사 경고"는 이미 확인된 사실이니 고칠 때 반드시 반영, 걸린 기준이 없으면 빈 목록과 빈 문자열. 생성 프롬프트 `src/main/resources/prompts/problem/system.txt`의 `[해설]`(426~459줄)과 `[오답 설명]`(461~478줄) 절의 꼴 설명과 예시를 옮겨 싣는다.

- [ ] **Step 4: 기존 테스트가 통과하는지 본다**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.client.*" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/client/ProblemReview.java src/main/resources/prompts/problem-review/ src/test/java/project/study/study_project/llm/client/ClaudeProblemReviewerTest.java src/main/java/project/study/study_project/llm/client/ClaudeProblemReviewer.java
git commit -m "feat(review): 해설 검수 응답 꼴과 지적 종류를 정하고 해설 어긋남 검사를 C에서 뺀다"
```

---

### Task 3: `ClaudeProblemReviewer`에 D 호출과 재검사

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/ClaudeProblemReviewer.java`
- Test: `src/test/java/project/study/study_project/llm/client/ClaudeProblemReviewerTest.java`

**Interfaces:**
- Consumes: Task 1 `ExplanationFormRule.issuesOf/passes`, Task 2 record·`FindingType`
- Produces:
  - `static final String EXPLANATION_PROMPT = Prompts.load("problem-review/explanation.txt");`
  - `static String buildExplanationPrompt(List<Shown> shown, JudgementBatch judged, SourceDocument source)` — B가 표시된 정답을 고른 문제만 싣는다. 문제마다 질문(자료 포함), 원래 순서 보기와 `← 표시된 정답`, 오답 설명, 해설, 검수자 풀이, `코드 검사 경고: [B3] …` 줄. 맨 앞에 근거 문서 본문
  - `static List<Finding> applyExplanationReview(List<Shown> shown, JudgementBatch judged, SourceDocument source, ExplanationReviewBatch review)` — 순수 함수
  - `review(...)`는 C 다음에 D를 부르고, D가 실패하면(`BusinessException`) 1차 검사 지적만 붙인다

`applyExplanationReview`가 만드는 지적(문제마다):

| 경우 | 지적 |
|---|---|
| 고친 해설이 있고, 원래와 다르고, 그 해설로 바꾼 문제가 `ExplanationFormRule.passes`이고, "그래서" 줄이 정답 보기 글의 낱말(2자 이상, 조사 뗀 것) 하나 이상을 담음 | `EXPLANATION_REWRITTEN`, `suggestion = Suggestion(EXPLANATION, null, 고친 해설)`, detail = `criteriaReason` 줄들 + `옛 해설: …` |
| 고친 해설이 있지만 위 조건을 못 넘음 | A1~A4가 걸렸으면 `EXPLANATION_MISMATCH`, 아니면 `EXPLANATION_WEAK`. 같은 suggestion, detail에 `넣지 않은 이유: …` |
| 고친 오답 설명: 보기 번호가 1~보기 수이고, 그 보기가 정답이 아니고, 30자 이상이고, 원래와 다름 | 보기마다 `RATIONALE_REWRITTEN`, `suggestion = Suggestion(RATIONALE, 보기 위치(0부터), 글)`, detail에 `옛 오답 설명: …` |
| 걸린 기준은 있는데 고친 글이 없음 | A면 `EXPLANATION_MISMATCH`, 아니면 `EXPLANATION_WEAK`, suggestion 없음 |
| D가 다루지 않았는데 1차 검사에 걸림 | `EXPLANATION_WEAK`, detail = 1차 검사 메시지 |
| `styleNote`가 있음 | `EXPLANATION_STYLE` |
| `criteriaReason`의 인용이 해설·오답 설명에 글자 그대로 없음 | 그 줄은 detail에서 버린다. 버리고 남은 근거가 없고 1차 검사에도 안 걸렸으면 지적을 만들지 않는다 |

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void 고친_해설이_재검사를_통과하면_EXPLANATION_REWRITTEN과_옛_해설을_남긴다()
@Test void 고친_해설이_형식을_어기면_넣지_않고_제안으로_남긴다()          // A2 걸림 → EXPLANATION_MISMATCH + suggestion
@Test void 고친_해설의_그래서_줄이_정답_보기를_가리키지_않으면_넣지_않는다()
@Test void 정답_보기의_오답_설명_고침은_버린다()
@Test void 없는_보기_번호의_오답_설명_고침은_버린다()
@Test void 원래와_같은_글은_지적을_만들지_않는다()
@Test void B가_다른_보기를_고른_문제는_D_입력에서_빠진다()               // buildExplanationPrompt 결과에 그 질문이 없다
@Test void 인용이_원문에_없는_근거는_버린다()
@Test void D가_다루지_않은_문제도_1차_검사_지적은_붙는다()
@Test void 문체_의견은_EXPLANATION_STYLE로_싣는다()
```

`review()`의 D 실패 경로는 `call`이 API를 직접 부르므로, D 실패 시 붙일 지적을 만드는 순수 함수 `static List<Finding> formFindingsOf(List<Shown> shown, SourceDocument source)`를 따로 두고 그 함수를 테스트한다:

```java
@Test void D가_실패하면_1차_검사_지적만_붙는다()   // formFindingsOf 결과가 EXPLANATION_WEAK만
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.client.ClaudeProblemReviewerTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

D 호출은 `call(EXPLANATION_PROMPT, buildExplanationPrompt(...), ExplanationReviewBatch.class, false)`. 사고 과정(thinking)은 끈다(C와 같다. 바꾸려면 측정 뒤에). 보기는 섞지 않는다.

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: 위와 같은 명령
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/client/ClaudeProblemReviewer.java src/test/java/project/study/study_project/llm/client/ClaudeProblemReviewerTest.java
git commit -m "feat(review): 해설 검수 호출을 더하고 고친 해설을 코드로 다시 검사한다"
```

---

### Task 4: 고친 해설을 결과 파일에 넣기

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/ExplanationRewrites.java`
- Modify: `src/main/java/project/study/study_project/llm/dto/GeneratedBatchFile.java` (`withProblemsAndFindings` 추가)
- Modify: `src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java:299-337`
- Test: `src/test/java/project/study/study_project/llm/support/ExplanationRewritesTest.java`

**Interfaces:**
- Consumes: Task 2 `FindingType`, `Suggestion`
- Produces:
  - `public record Applied(List<GeneratedProblemItem> problems, List<Finding> findings)` (중첩)
  - `public static Applied apply(List<GeneratedProblemItem> problems, List<Finding> findings)` — `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN` 지적의 suggestion을 그 문제에 반영하고, 반영한 지적은 suggestion을 떼서 돌려준다. 다른 지적은 그대로
  - `GeneratedBatchFile withProblemsAndFindings(List<GeneratedProblemItem> problems, List<ProblemReview.Finding> findings)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void 해설_고침을_반영하고_제안을_뗀다()              // problems.get(i).explanation()이 새 글, 지적 suggestion null, detail 그대로(옛 해설 줄 포함)
@Test void 오답_설명_고침은_그_보기만_바꾼다()             // 다른 보기·정답 표시·보기 글 불변
@Test void 다른_지적은_건드리지_않는다()                   // CHOICE_CUE_LEAK의 suggestion은 남는다
@Test void 질문_글은_바뀌지_않는다()                      // 들여오기가 질문 글로 짝짓기 때문
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.support.ExplanationRewritesTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현하고 `DraftGeneratorCli`에 잇는다**

`DraftGeneratorCli`의 검수 블록에서 `all`을 만든 뒤 `ExplanationRewrites.apply(kept, all)`를 부르고, `attachReviewFindings`가 `batch.withProblemsAndFindings(applied.problems(), applied.findings())`를 쓰게 바꾼다. Actions 요약에 `✏️ 해설 n건·오답 설명 m건을 AI가 고쳐 넣었습니다` 한 줄을 남긴다(`BatchReports.announce`).

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.*" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/support/ExplanationRewrites.java src/main/java/project/study/study_project/llm/dto/GeneratedBatchFile.java src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java src/test/java/project/study/study_project/llm/support/ExplanationRewritesTest.java
git commit -m "feat(review): AI가 고친 해설과 오답 설명을 결과 파일에 넣는다"
```

---

### Task 5: 들여오기와 검수함 표시

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/dto/DraftAiFinding.java` (`groupByQuestion`)
- Modify: `src/main/resources/static/admin/llm.html` (`aiFindingsBoxOf` 근처)
- Test: `src/test/java/project/study/study_project/llm/dto/DraftAiFindingTest.java` (없으면 만든다)

**Interfaces:**
- Consumes: Task 2 `FindingType`
- Produces: `groupByQuestion`이 `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN` 지적을 `resolution = "AI가 고쳐 넣음"`으로 저장한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test void AI가_고쳐_넣은_지적은_처리된_것으로_저장한다()   // resolution "AI가 고쳐 넣음"
@Test void 제안으로_남은_해설_지적은_미처리로_저장한다()      // EXPLANATION_WEAK + suggestion → resolution null
```

- [ ] **Step 2: 실패 확인 → 구현 → 통과 확인**

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.dto.DraftAiFindingTest" --console=plain`
Expected: 처음엔 FAIL, 구현 뒤 `BUILD SUCCESSFUL`

- [ ] **Step 3: 검수함에 표시를 단다**

초안 카드 머리에, `aiFindings`에 `EXPLANATION_REWRITTEN`·`RATIONALE_REWRITTEN`이 하나라도 있으면 "AI가 해설을 고침" 칩을 단다. 그 지적을 펼치면 detail의 `옛 해설:`·`옛 오답 설명:` 줄을 지금 해설·오답 설명 위에 나란히 보여 준다(옛 글은 흐린 글자, 새 글은 진한 글자). 기존 `aiFindingsBoxOf`의 상자·칩 모양을 그대로 쓴다.

- [ ] **Step 4: 화면을 확인한다**

1. `node scratchpad/inline_check.js src/main/resources/static/admin/llm.html`로 인라인 스크립트 문법을 본다. Expected: `errors: 0`
2. 8081을 띄우고, 확인용 초안 한 건을 `generated_problem_draft`에 SQL로 넣는다(`ai_findings_json`에 `EXPLANATION_REWRITTEN` 지적 1건). `admin/llm.html`에서 칩과 옛·새 해설이 보이는지 캡처로 본다. 확인 뒤 그 초안을 지우고 8081을 내린다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/dto/DraftAiFinding.java src/main/resources/static/admin/llm.html src/test/java/project/study/study_project/llm/dto/DraftAiFindingTest.java
git commit -m "feat(review): 검수함에 AI가 고친 해설을 표시하고 옛 글과 나란히 보여 준다"
```

---

### Task 6: 측정 표본과 측정

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/cli/ProblemReviewEvalCli.java` (`Mutation`에 `explanation`, `rationaleOf`, `rationale` 더하기, `mutate` 반영)
- Modify: `eval-samples/problem-review.json` (해설 결함 그룹 추가)
- Test: `src/test/java/project/study/study_project/llm/cli/ProblemReviewEvalCliTest.java` (`mutate`만)

**Interfaces:**
- Produces: `record Mutation(Integer moveAnswerTo, Integer choice, String text, String explanation, Integer rationaleOf, String rationale)` — 기존 3인자 생성자는 남긴다

- [ ] **Step 1: `mutate` 테스트를 쓰고 구현한다**

```java
@Test void 해설을_바꾼다()       // explanation 칸만 새 글
@Test void 오답_설명을_바꾼다()   // rationaleOf 위치 보기의 rationale만 새 글
```

Run: `.\gradlew.bat test --tests "project.study.study_project.llm.cli.ProblemReviewEvalCliTest" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 표본을 더한다**

이번에 문서와 맞춘 심화편 문제에서 그룹 하나(`explanation-quality`)를 만든다. 결함 9건(A3 틀린 계산, A4 없는 절, A4 다른 편, B2 풀지 않은 용어, B3 값 없는 예시, B5 맺음 없음, C1 해설이 오답 나열, C2 "틀렸다" 한마디, C3 다른 보기를 가리킴)과 멀쩡한 문제 10건이다. 결함마다 `expect`는 `["EXPLANATION_REWRITTEN", "RATIONALE_REWRITTEN", "EXPLANATION_MISMATCH", "EXPLANATION_WEAK"]` 가운데 맞는 것을 적는다. `score`가 "기대 종류 가운데 하나라도 나오면 적발"로 세는지 먼저 확인하고, 아니면 그렇게 세도록 고친다.

- [ ] **Step 3: 요금 없는 확인**

Run: `.\gradlew.bat evalProblemReview -PevalArgs="--rules-only=true --label=explanation-rules" --console=plain`
Expected: 표본을 읽고 보고서가 나온다(1차 코드 검사가 A4·B5·C2를 잡는지 본다)

- [ ] **Step 4: 실제 측정은 사용자에게 묻고 돌린다**

호출 수(그룹당 A·B·C·D 최대 4회)와 예상 비용을 적어 묻는다. 승인되면 `--label=explanation-d`로 돌린다. 문턱: 적발률 80% 이상, 멀쩡한 문제 10건에 헛경보 2건 이하. 고친 해설이 정답과 문서 사실을 지켰는지 보고서의 고친 글을 사람이 읽는다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/cli/ProblemReviewEvalCli.java eval-samples/problem-review.json src/test/java/project/study/study_project/llm/cli/ProblemReviewEvalCliTest.java
git commit -m "test(review): 해설 결함을 심은 측정 표본을 더한다"
```

---

### Task 7: 문서 정리

**Files:**
- Modify: `docs/22-ai-review.md` (§3에 "3.5 해설 검수" 추가, §6 표에 D 호출, §7 비용 표에 D 줄, §8에서 "AI가 본문이나 문제를 직접 고치기" 항목을 "해설·오답 설명만 예외(2026-11-16)"로 고침)
- Modify: `docs/IMPROVEMENTS.md` (발견/원인/조치/확인 4칸)

- [ ] **Step 1: 두 문서를 고친다**

§8 예외에는 이유(지적만 내면 28건 중 27건이 고쳐지지 않음)와 막는 장치(재검사, 옛 글 보존, 사람 승인)를 적는다. 측정 결과(Task 6)를 확인 칸에 적는다.

- [ ] **Step 2: humanize-korean으로 윤문하고, 코드 블록·수치·경로·용어가 그대로인지 원문과 대조한다**

- [ ] **Step 3: 전체 빌드와 테스트**

Run: `.\gradlew.bat build --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋하고 push한다**

```bash
git add docs/22-ai-review.md docs/IMPROVEMENTS.md
git commit -m "docs(review): 해설 검수 호출과 해설 직접 고침 예외를 적는다"
git push
```
