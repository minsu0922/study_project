# 초급 문제 유형 섞기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 초급 배치 한 번이 객관식 4·순서 배열 1·짝짓기 1·OX 1을 섞어 낸다(재료 없는 유형은 객관식으로 채움).

**Architecture:** 코드가 근거 본문의 형식(`ProblemTypeHints`)으로 유형 구성(`TypeMix`)을 정해 프롬프트에 싣는다. 모델은 문제마다 `type`을 적고, 검사·검수·들여오기는 문제의 유형(없으면 파일 유형)을 쓴다.

**Tech Stack:** Java 21, Spring Boot 3.4, Anthropic Java SDK(구조화 출력), JUnit 5·AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-27-problem-type-mix-design.md`

## Global Constraints

- 섞는 조건: 난이도 BEGINNER, `--problem-type` 미지정, 근거 문서 있음. 셋 중 하나라도 아니면 지금과 똑같이 동작한다.
- 배분: 순서 배열 1, 짝짓기 1, OX 1, 나머지 객관식. 객관식은 최소 1개.
- 파일의 `type`은 섞은 날에도 `MULTIPLE_CHOICE`.
- 시스템 프롬프트(`prompts/problem/system.txt`)는 고치지 않는다.
- 순서 배열 재료 절에서 `ClaudeProblemGenerator.SOURCE_SECTIONS`의 INTERMEDIATE·ADVANCED 절 제목(앞 `#` 빼고 비교)은 뺀다.
- 유료 실물 생성은 호출 횟수·예상 비용을 적고 사용자 승인 뒤에만 돌린다.

## Review Focus

- 검수(ProblemEditor)·DB 되살리기(LlmProblemService.itemOf)가 문제를 새로 만들 때 `type`을 잃으면, 섞은 문제가 객관식으로 저장된다 → Task 1에서 보존 테스트.
- 모델이 구성에 없는 유형(SHORT_ANSWER·ESSAY)이나 `null`을 적으면 → 들여오기는 파일 유형으로 떨어지고, 수확 점검이 경고한다(Task 4).
- 문제마다 `type` 칸이 없는 옛 배치 파일 → 지금처럼 파일 유형으로 저장(Task 5).
- 객관식이 0개인 세트(모델이 다른 유형만 냄) → 검수를 건너뛴다(Task 4).
- 순서 배열 힌트 절이 모두 중급 절이면 → 순서 배열을 넣지 않고 객관식으로 채운다(Task 2).

---

### Task 1: 문제 항목에 유형 칸

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/GeneratedProblemItem.java`
- Modify: `src/main/java/project/study/study_project/llm/review/ProblemEditor.java:303,308`
- Modify: `src/main/java/project/study/study_project/llm/review/ProblemReviewPipeline.java:145`
- Modify: `src/main/java/project/study/study_project/llm/service/LlmProblemService.java:1070`
- Test: `src/test/java/project/study/study_project/llm/client/GeneratedProblemItemTypeTest.java` (새 파일), `ProblemEditorTest`

**Interfaces:**
- Produces: record 맨 앞 컴포넌트 `ProblemType type` (스키마 설명: "이 문제의 유형. 요청의 유형 구성에 적힌 것 중 하나"). 기존 9인자 생성자 `(question, exhibit, answer, explanation, choices, sourceQuote, title, questionKind, skipReason)`는 `type=null`로 위임하는 편의 생성자로 남긴다.
- Produces: `ProblemType typeOr(ProblemType fallback)` — `type`이 null이면 fallback.
- Produces: `GeneratedProblemItem withType(ProblemType type)`.

맨 앞에 두는 이유: 유형이 보기·정답 칸의 모양을 정하므로 모델이 먼저 적어야 한다. 기존 주석의 "형태를 먼저 정하면 지어낸다"는 questionKind 이야기이고, 유형은 코드가 구성으로 미리 정해 준다는 점을 칸 주석에 적는다.

- [ ] **Step 1: 실패 테스트**
  - `typeOrFallsBackWhenNull`: 9인자 생성자로 만든 항목의 `typeOr(MULTIPLE_CHOICE)` == MULTIPLE_CHOICE, `withType(OX).typeOr(MULTIPLE_CHOICE)` == OX.
  - `oldJsonWithoutTypeReadsNull`: `{"question":"q","answer":"O",...}`(type 없음)를 ObjectMapper로 읽으면 `type()`이 null.
  - `ProblemEditorTest`에 `editedItemKeepsType`: `withType(MULTIPLE_CHOICE)` 항목의 해설을 편집기가 고친 결과도 `type()`이 MULTIPLE_CHOICE다(null로 사라지지 않는다).
- [ ] **Step 2:** `.\gradlew.bat test --tests "*GeneratedProblemItemTypeTest" --tests "*ProblemEditorTest"` → 컴파일 실패 확인
- [ ] **Step 3:** 칸·메서드 추가, 본 코드 6곳(ProblemEditor 2, ProblemReviewPipeline 1, LlmProblemService.itemOf 1은 `d.getType()`을 넘김, ProblemReviewEvalCli·CodeProblem은 null 그대로)에서 새로 만드는 항목에 원래 `type`을 넘긴다
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5:** 커밋 `feat(llm): 생성 문제마다 유형 칸을 두고 검수·편집에서도 유지한다`

### Task 2: 유형 구성 계산

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/TypeMix.java`
- Test: `src/test/java/project/study/study_project/llm/support/TypeMixTest.java`

**Interfaces:**
- Consumes: `ProblemTypeHints.of(String)` → `List<Hint(type, label, count, sections)>`
- Produces: `record TypeMix(Map<ProblemType, Integer> counts, Map<ProblemType, List<String>> sections)` (counts는 EnumMap 순서: MULTIPLE_CHOICE, OX, MATCHING, ORDERING 중 0 아닌 것만)
- Produces: `static TypeMix forBeginner(int count, String contentMd)` — 다른 유형 각 1개(재료 있을 때), 객관식 = count − 합, 최소 1(합이 넘치면 ORDERING→MATCHING→OX 순으로 덜어 냄).
- Produces: `boolean isMixed()` — 객관식 밖 유형이 하나라도 있으면 true.
- Produces: `String summary()` — "객관식 4 · 순서 배열 1 · 짝짓기 1 · OX 1" (`ProblemType.getLabel()` 사용, counts 순서).

- [ ] **Step 1: 실패 테스트** (본문은 `ProblemTypeHintsTest` 식 텍스트 블록)
  - `allMaterial`: 번호 목록 4단계(「요금을 멈추는 방법」 아래)·용어 표 4행·오해 항목 1개 → count 7에서 `summary()` == "객관식 4 · OX 1 · 짝짓기 1 · 순서 배열 1", sections(ORDERING) == ["요금을 멈추는 방법"].
  - `noGlossary`: 용어 표 없음 → 객관식 5, 짝짓기 없음.
  - `intermediateSectionDropped`: 번호 목록이 「실무에서는 이렇게 쓴다」 아래에만 있음 → ORDERING 없음, 객관식 5.
  - `smallCountKeepsOneChoice`: count 2, 재료 셋 다 → 객관식 1 + 다른 유형 1(OX), `counts` 합 2.
  - `nothing`: 재료 없음 → `isMixed()` false, 객관식 7.
- [ ] **Step 2:** `.\gradlew.bat test --tests "*TypeMixTest"` → FAIL
- [ ] **Step 3:** 구현. 중급·고급 절 제목 집합은 `SOURCE_SECTIONS`의 INTERMEDIATE·ADVANCED 값에서 `^#+\s+`를 뗀 문자열.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5:** 커밋 `feat(llm): 초급 근거 본문으로 문제 유형 구성을 정한다`

### Task 3: 프롬프트에 유형 구성 싣기

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/ProblemGenerator.java`
- Modify: `src/main/java/project/study/study_project/llm/client/ClaudeProblemGenerator.java` (`generate`, `buildPrompt`)
- Test: `ClaudeProblemGenerator`의 프롬프트 테스트(기존 `buildPrompt` 테스트가 있는 파일에 추가; 없으면 `ClaudeProblemGeneratorMixPromptTest` 새 파일)

**Interfaces:**
- Consumes: `TypeMix` (Task 2)
- Produces: `ProblemGenerator`에 9인자 추상 메서드 `generate(domain, difficulty, type, count, avoid, rejectionNotes, sourceDocument, requestedKind, TypeMix mix)`; 기존 8인자는 `mix=null`로 위임하는 default가 된다. 다른 구현체(테스트 가짜 포함)는 컴파일러가 알려 주는 대로 9인자로 옮긴다.
- Produces: `buildPrompt(..., QuestionKind requestedKind, TypeMix mix)`.

- [ ] **Step 1: 실패 테스트**
  - `mixedPromptListsComposition`: `mix.isMixed()`인 구성을 주면 프롬프트에 `- 유형 구성: 객관식 4 · OX 1 · 짝짓기 1 · 순서 배열 1`과 `재료:` 뒤에 「요금을 멈추는 방법」이 있고, `typeRule(ORDERING)` 문구("순서 배열 — choices에")가 들어 있고, `- 유형: 객관식` 줄은 없다.
  - `oxRuleAsksOneOfTwo`: OX 줄에 "정답 X"와 "정답 O"가 함께 있다.
  - `unmixedPromptUnchanged`: `mix=null`과 `isMixed()==false` 둘 다 지금 프롬프트와 글자까지 같다(기존 8인자 호출 결과와 `equals`).
- [ ] **Step 2:** 해당 테스트 실행 → FAIL
- [ ] **Step 3:** 섞은 날에만 `- 유형:` 줄 대신 아래 블록을 넣는다(설계 §3 문구):

```
- 유형 구성: {mix.summary()}. 문제마다 type에 그 유형을 적는다.
  - {label}: {typeRule(t)} [재료: 문서의 「a」·「b」 절]
  ...
  재료가 정해진 유형은 그 절에서만 낸다. 재료가 얇아 못 만들겠으면 객관식으로 바꾸지 말고 덜 낸다([개수를 채우지 못할 때]).
```
  OX 줄에는 "「자주 하는 오해」의 오해 항목으로, 오해를 그대로 적은 문장(정답 X)과 문서가 바로잡은 사실을 적은 문장(정답 O) 가운데 하나로 낸다"를 붙인다.
- [ ] **Step 4:** → PASS, 그리고 `.\gradlew.bat test --tests "*llm.client*"` 전체 통과
- [ ] **Step 5:** 커밋 `feat(llm): 섞은 날 문제 프롬프트에 유형 구성과 재료 절을 싣는다`

### Task 4: 배치가 구성을 정하고, 점검·검수를 문제마다 유형으로

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java`
- Modify: `src/main/java/project/study/study_project/llm/cli/YieldReporter.java`
- Test: `src/test/java/project/study/study_project/llm/cli/DraftGeneratorCliTest.java`, `YieldReporter` 테스트가 있는 파일

**Interfaces:**
- Consumes: `TypeMix.forBeginner`, 9인자 `generate`, `GeneratedProblemItem.typeOr/withType`
- Produces: `static TypeMix mixFor(Difficulty difficulty, boolean typePinned, SourceDocument source, int count)` in DraftGeneratorCli — 조건 밖이면 null.
- Produces: `YieldReporter.checkYield(problems, requested, type, difficulty, source)`가 항목마다 `item.typeOr(type)`으로 `defectOf`·`qualityWarningsOf`를 부르고, `batchWarningsOf`는 유형별 묶음마다 부른다.
- Produces: `static String mixMismatchOf(TypeMix mix, List<GeneratedProblemItem> kept)` — 구성과 받은 유형별 개수가 다르면 "유형 구성과 다름: 짝짓기 요청 1 → 0" 식 한 줄, 같으면 null.
- Produces: `static ReviewSplit splitForReview(List<GeneratedProblemItem> kept, ProblemType fileType)` → `record ReviewSplit(List<Integer> indexes, List<GeneratedProblemItem> choices)`; `static List<GeneratedProblemItem> mergeReviewed(List<GeneratedProblemItem> kept, ReviewSplit split, List<GeneratedProblemItem> reviewed)`.

- [ ] **Step 1: 실패 테스트**
  - `mixOnlyForUnpinnedBeginnerWithSource`: (BEGINNER, false, 문서) → 섞음; INTERMEDIATE·typePinned=true·source=null → null.
  - `yieldUsesItemType`: 파일 유형 MULTIPLE_CHOICE, 항목 하나가 `withType(OX)`이고 choices 비고 answer "X" → 결함 없음(지금은 객관식 규칙으로 결함).
  - `mismatchReported`: 구성 짝짓기 1, 받은 항목에 짝짓기 없음 → "짝짓기 요청 1 → 0" 포함.
  - `reviewOnlyChoices`: [MC, OX, MC] → indexes [0,2]; 검수 결과로 해설을 바꾼 두 항목을 merge하면 0·2번만 바뀌고 1번(OX)은 그대로.
  - `noChoicesSkipsReview`: 모두 OX면 `split.choices()`가 비고, 배치는 검수를 부르지 않는다.
- [ ] **Step 2:** `.\gradlew.bat test --tests "*DraftGeneratorCliTest" --tests "*YieldReporter*"` → FAIL
- [ ] **Step 3:** 구현.
  - 생성 직전 `mix = mixFor(difficulty, opts.containsKey(PROBLEM_TYPE_OPT), source, count)`; "생성 시작" 줄 뒤에 `mix.summary()`를 찍는다.
  - `generate(..., source, null, mix)`.
  - 저장 뒤 성공 요약에 유형별 개수를 덧붙이고, `mixMismatchOf`가 null 아니면 `BatchReports.announce`로 경고(실패시키지 않음).
  - 검수 조건을 `source != null && !split.choices().isEmpty()`로 바꾸고, 검수에는 `split.choices()`를 넘겨 `mergeReviewed`로 되돌린 목록을 `withProblemsAndFindings`에 넣는다.
- [ ] **Step 4:** → PASS
- [ ] **Step 5:** 커밋 `feat(llm): 초급 배치가 유형을 섞어 내고 객관식만 AI 검수한다`

### Task 5: 들여오기가 문제마다 유형으로 저장

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/service/LlmProblemService.java` (`saveDrafts` 7인자 루프)
- Test: `src/test/java/project/study/study_project/llm/service/DraftImportServiceTest.java`

**Interfaces:**
- Consumes: `GeneratedProblemItem.typeOr`

- [ ] **Step 1: 실패 테스트**
  - `importMixedFileStoresTypePerProblem`: 파일 type MULTIPLE_CHOICE, 문제 [MC 정상, OX(answer "X"), MATCHING 4쌍, ORDERING 4항목 answer "4|2|1|3"] → 저장된 초안 유형이 순서대로 MC·OX·MATCHING·ORDERING.
  - `importOldFileUsesFileType`: 문제에 type 칸이 없는 파일(OX 파일) → 초안 유형 OX.
- [ ] **Step 2:** `.\gradlew.bat test --tests "*DraftImportServiceTest"` → FAIL
- [ ] **Step 3:** 루프에서 `toDraft(item, domain, difficulty, item.typeOr(type), ...)`
- [ ] **Step 4:** → PASS
- [ ] **Step 5:** 커밋 `feat(llm): 문제 초안을 문제마다 적힌 유형으로 들여온다`

### Task 6: 문서·전체 확인·실물 1회

**Files:**
- Modify: `docs/28-content-flow.md` (흐름 B 단계 목록에 "초급은 유형 구성을 정한다" 한 줄과 AI 검수 조건)
- Modify: `docs/superpowers/specs/2026-11-27-problem-type-mix-design.md` (상태: 구현됨)

- [ ] **Step 1:** 문서 고치기
- [ ] **Step 2:** `.\gradlew.bat build --console=plain` → BUILD SUCCESSFUL
- [ ] **Step 3:** 커밋 `docs(llm): 초급 유형 섞기를 흐름 문서에 적는다`
- [ ] **Step 4:** 사용자에게 실물 1회 승인 요청: `.\gradlew.bat generateDrafts -PdraftArgs="--date=2026-11-03 --difficulty=BEGINNER --document-date=2026-11-02 --suffix=mix-trial"` 류 호출 1번(생성 1 + 검수 2 = 3호출, 예상 비용을 계산해 적는다). 결과 파일을 8081 검수함에 들여와 보여 준다.
