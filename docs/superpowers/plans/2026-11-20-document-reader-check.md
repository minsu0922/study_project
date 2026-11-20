# 개념 문서 독자 점검과 나란히 검수 화면 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배치가 개념 문서마다 독자 점검(막힘·되풀이·수치 불일치 + 고친 문장 제안)을 돌려 저장하고, 관리 콘솔에 렌더링한 본문과 사실 검수·독자 점검 지적을 나란히 보는 검수 화면을 만든다.

**Architecture:** 사실 검수(`ClaudeDocumentFactChecker` → 배치 파일 `factFindings` → 초안 `fact_findings_json` → 검수함)가 깔아 둔 길을 그대로 따른다. 독자 점검 호출은 사실 검수와 동시에 돌고, 결과는 편별로 배치 파일 `readerFindings`와 초안 `reader_findings_json`(V49)에 남는다. 새 정적 페이지 `admin/doc-review.html`이 초안 하나를 marked + DOMPurify로 렌더링해 지적을 칠하고, 제안 수락 API가 본문과 배치 파일을 함께 고친다.

**Tech Stack:** Java 21, Spring Boot 3.4, Anthropic Java SDK(구조화 출력), Flyway, 정적 HTML/JS, marked 12.0.0 + DOMPurify 3.2.6(CDN, `document.html`과 같은 버전·integrity).

**Spec:** `docs/superpowers/specs/2026-11-20-document-reader-check-design.md`

## Global Constraints

- 설정 키 `llm.generation.reader-check-enabled`. 키가 없으면 끈다. `application.yml`에는 `reader-check-enabled: false`로 커밋한다.
- 독자 점검은 승인을 막지 않는다. 사실 검수의 「남은 지적이 있으면 승인 안 됨」 규칙은 그대로다.
- 독자 점검이 실패해도 배치 job과 문서 저장은 실패하지 않는다. 그 편 결과는 `null`로 둔다.
- 인용·target 대조는 `ClaudeDocumentFactChecker.normalize`(강조 `*`·백틱 제거, 공백 한 칸)로 한다.
- 수락은 PENDING 초안만. 수락 금지 자리: 소제목 줄(`#`), 코드·그림 블록(``` 안), 그림 줄(`![`), 인용 상자 줄(`>`), 표 줄(`|`), 용어 정의 줄 앞부분(`- **…** —`의 줄표까지).
- 수락하면 `DocumentFileSync.replaceContent(slug, before, after)`로 배치 파일도 고친다.
- 승인 전 본문을 innerHTML로 넣을 때는 반드시 `DOMPurify.sanitize`를 거친다.
- 파일 읽기는 `JsonMappers.STRICT`다. 로컬 앱 코드를 먼저 배포하고 나서 설정을 켠다(이 계획은 끈 채로 끝낸다).
- 주석·커밋 메시지는 프로젝트 문체 규칙(평서문, 번역투 금지)을 따른다. 빌드와 커밋은 따로 실행한다.

## Review Focus

1. 같은 문장을 고치는 두 제안을 차례로 수락 → 두 번째는 "제안한 문장이 본문에서 바뀌었다"로 거절되고 본문은 첫 수락 결과 그대로다. (Task 6 테스트)
2. 걷어 낸 글 기준으로 target이 두 번 나오는 제안 → 흡수 전에 `need_human`으로 바뀌고 수락 버튼이 없다. (Task 1 테스트)
3. 검증기가 HTML 태그를 막는 오류로 짚은 초안 → 검수 화면이 원문 보기로 열리고, 렌더링 경로도 DOMPurify를 거친다. (Task 7 확인)
4. 독자 점검 호출이 예외를 던짐 → 배치는 계속하고 `factFindings`는 파일에 실리며 `readerFindings`의 그 편은 `null`이다. (Task 4 테스트)
5. `readerFindings` 칸이 없는 옛 배치 파일과 `reader_findings_json`이 NULL인 옛 초안 → 그대로 읽히고 응답의 `readerFindings`는 `null`, 화면은 "독자 점검 안 돌림". (Task 3·6 테스트)

---

### Task 1: 독자 점검 호출기와 프롬프트

**Files:**
- Create: `java/.../llm/client/ReaderFinding.java`, `java/.../llm/client/DocumentReaderCheck.java`, `java/.../llm/client/ClaudeDocumentReaderCheck.java`, `java/.../llm/support/TextSpans.java`
- Create: `resources/prompts/document-reader/beginner.txt`, `resources/prompts/document-reader/advanced.txt`
- Test: `test/.../llm/client/ClaudeDocumentReaderCheckTest.java`, `test/.../llm/support/TextSpansTest.java`

(`java/...`는 `src/main/java/project/study/study_project`, `test/...`는 `src/test/java/project/study/study_project`, `resources/`는 `src/main/resources`.)

**Interfaces:**
- Produces:
  - `record ReaderFinding(Kind kind, Type type, Weight weight, String section, String quote, String earlier, String reason, Suggestion suggestion)` — 구조화 출력용 `@JsonPropertyDescription`을 단다. `enum Kind {BLOCK, REPEAT}`, `enum Type {UNDEFINED_TERM, UNCLEAR_SENTENCE, SKIPPED_STEP, ANALOGY, FIGURE, NUMBER_MISMATCH, REPEAT}`, `enum Weight {STOP, PAUSE}`, `record Suggestion(Op op, String target, String text, String note)`, `enum Op {REPLACE, INSERT_AFTER, DELETE, NEED_HUMAN}`, `record Result(List<ReaderFinding> findings)`.
  - `interface DocumentReaderCheck { List<ReaderFinding> check(String title, String contentMd, DocumentEdition edition, List<String> earlierTopics, String beginnerContentMd); }` — `beginnerContentMd`는 심화편일 때만 쓴다(입문편이면 `null`).
  - `ClaudeDocumentReaderCheck(String model)` — 사실 검수와 같은 모델, `MAX_TOKENS = 32_000L`, 사고 켬(`ThinkingConfigAdaptive`), 웹 검색 없음, 비용 레이블 `"독자 점검(" + edition.getDisplayName() + ")"`.
  - `static List<ReaderFinding> sanitize(List<ReaderFinding> raw, String contentMd)` — quote가 본문에 없으면 버림, target이 `TextSpans.locateOnce`로 안 잡히면 suggestion을 `NEED_HUMAN`(target·text 비움, note `"제안한 문장을 본문에서 찾지 못했다"`)으로 바꿈.
  - `TextSpans.locateOnce(String contentMd, String target) -> Optional<int[]>` — 정규화한 target이 정규화한 본문에 정확히 한 번 있으면 원문의 `[start, end)`를 준다.

- [ ] **Step 1: 실패하는 테스트 작성**

`TextSpansTest`:
```java
@Test void locatesAcrossEmphasis() {
    String md = "앞 문장. **리졸버**는 `53`번으로 묻는다. 뒤 문장.";
    int[] at = TextSpans.locateOnce(md, "리졸버는 53번으로 묻는다.").orElseThrow();
    assertThat(md.substring(at[0], at[1])).isEqualTo("**리졸버**는 `53`번으로 묻는다.");
}
@Test void emptyWhenTwice() { assertThat(TextSpans.locateOnce("가다. 가다.", "가다.")).isEmpty(); }
@Test void emptyWhenMissing() { assertThat(TextSpans.locateOnce("가다.", "오다.")).isEmpty(); }
```
`ClaudeDocumentReaderCheckTest`(가짜 전송기는 `ClaudeDocumentPolisherTest.Fake`와 같은 꼴):
- `dropsFindingWhoseQuoteIsNotInDocument` — 본문에 없는 quote 지적은 결과에서 빠진다.
- `turnsAmbiguousTargetIntoNeedHuman` — target이 두 번 나오면 `op == NEED_HUMAN`, `target`·`text`가 빈 문자열, note가 `"제안한 문장을 본문에서 찾지 못했다"`.
- `advancedRequestCarriesBeginnerAndEarlierTopics` — 심화편 요청의 사용자 메시지에 입문편 본문과 앞 주제 이름이 들어 있다. 입문편 요청에는 입문편 블록 머리글이 없다.
- `emptyEarlierTopicsOmitsTheLine` — 앞 주제가 빈 목록이면 사용자 메시지에 `"코스 앞 글"` 줄이 없다.
- `promptCarriesTheFiveAdditions` — `ClaudeDocumentReaderCheck.systemPromptFor(BEGINNER)`가 `"수치 안 맞음"`, `"면접에서 이렇게 물어본다"`, `"need_human"`(또는 `NEED_HUMAN`), `"코스 앞 글"`, `"세운 비유"`를 담는다. 심화편 프롬프트는 `"입문편을 방금 다 읽었다"`를 담는다.

- [ ] **Step 2: 실행해 실패 확인**

Run: `.\gradlew.bat test --tests "*TextSpansTest" --tests "*ClaudeDocumentReaderCheckTest" --console=plain`
Expected: 컴파일 실패(클래스 없음)

- [ ] **Step 3: 구현**

- 프롬프트 두 파일은 설계 문서 부록의 시험 프롬프트 원문을 출발점으로, 「2. 독자와 프롬프트」 표의 보완 1~5를 더해 쓴다. 출력 형식 설명은 구조화 출력 칸 설명(`@JsonPropertyDescription`)이 맡으므로 프롬프트에는 종류 이름과 판단 기준만 둔다. 제안 문체는 "`document-polish/system.txt`의 문체 규칙(평서문, 60자, 비유 알림말, 건드리지 않는 것)을 따른다"를 넣는다.
- 사용자 메시지: `# 제목`, (앞 주제가 있으면) `# 코스 앞 글(독자가 이미 읽었다)` + 한 줄씩, (심화편이면) `# 입문편 전문` + 본문, `# 점검할 본문` + 본문.
- `TextSpans.locateOnce`: 원문을 한 글자씩 걸으며 정규화 문자열과 "정규화 글자 → 원문 위치" 배열을 함께 만든다(`*`·백틱은 건너뜀, 연속 공백은 한 칸으로). 정규화 target의 첫·마지막 출현 위치가 같을 때만 원문 위치로 되짚어 돌려준다.

- [ ] **Step 4: 실행해 통과 확인**

Run: 위 명령. Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/client/ReaderFinding.java src/main/java/project/study/study_project/llm/client/DocumentReaderCheck.java src/main/java/project/study/study_project/llm/client/ClaudeDocumentReaderCheck.java src/main/java/project/study/study_project/llm/support/TextSpans.java src/main/resources/prompts/document-reader src/test/java/project/study/study_project/llm/client/ClaudeDocumentReaderCheckTest.java src/test/java/project/study/study_project/llm/support/TextSpansTest.java
git commit -m "feat(llm): 개념 문서 독자 점검 호출기와 프롬프트를 더한다"
```

### Task 2: 코스 앞 주제 목록

**Files:**
- Modify: `java/.../llm/support/TopicQueue.java` (`laterTopicsInBlock` 옆)
- Test: `test/.../llm/support/TopicQueueTest.java`

**Interfaces:**
- Produces: `public List<String> earlierUsedTopicsInBlock(Picked picked)` — 같은 분야로 이어진 블록에서 `picked` 앞쪽 주제 가운데 사용 기록(`lastUsedAt`)이 있는 것만, 코스 순서(앞→뒤)로, 이름은 `laterTopicsInBlock`과 같게 `" — "` 앞까지 자른다. 다른 분야 항목이나 쓸 수 없는 항목을 만나면 멈춘다. `picked == null`이면 빈 목록.

- [ ] **Step 1: 실패하는 테스트** — `earlierUsedTopicsKeepCourseOrderAndSkipUnused`: 같은 분야 A(사용함)·B(안 씀)·C(사용함)·D(picked) 앞에 다른 분야 X가 있을 때 결과가 `["A", "C"]`. `earlierUsedTopicsStopAtOtherDomain`: X(다른 분야, 사용함)는 들어가지 않는다.
- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*TopicQueueTest" --console=plain` → 컴파일 실패
- [ ] **Step 3: 구현** — 위 시그니처대로.
- [ ] **Step 4: 통과 확인** — 같은 명령, PASS
- [ ] **Step 5: 커밋** — `feat(llm): 대기열에서 코스 앞쪽에 이미 나온 주제 목록을 꺼낸다`

### Task 3: 배치 파일 `readerFindings` 칸

**Files:**
- Modify: `java/.../llm/dto/GeneratedDocumentFile.java`, `java/.../llm/service/DocumentFileSync.java`
- Create: `java/.../llm/dto/ReaderFindings.java`
- Test: `test/.../llm/dto/GeneratedDocumentFileTest.java`(없으면 만든다), `test/.../llm/service/DocumentFileSyncTest.java`

**Interfaces:**
- Produces: `record ReaderFindings(List<ReaderFinding> beginner, List<ReaderFinding> advanced)` — 편을 못 돌렸으면 그 칸 `null`. `GeneratedDocumentFile`에 마지막 컴포넌트 `ReaderFindings readerFindings`를 더하고, 지금 있는 생성자들은 `null`을 넘겨 유지한다. `withReaderFindings(ReaderFindings)`를 더하고 `withFactFindings`·`withPolished`는 이 칸을 보존한다. `DocumentFileSync`가 파일을 다시 쓸 때도 보존한다.

- [ ] **Step 1: 실패하는 테스트** — `oldFileWithoutReaderFindingsStillReads`(칸 없는 JSON을 `JsonMappers.STRICT`로 읽으면 `readerFindings() == null`), `withFactFindingsKeepsReaderFindings`, `withPolishedKeepsReaderFindings`, `DocumentFileSyncTest.replaceContentKeepsReaderFindings`.
- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*GeneratedDocumentFile*" --tests "*DocumentFileSyncTest" --console=plain`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(llm): 배치 문서 파일에 독자 점검 칸을 더한다`

### Task 4: 배치에 독자 점검 넣기

**Files:**
- Modify: `java/.../llm/cli/DocumentBatch.java`(사실 검수 블록, 196~213행 부근), `java/.../llm/cli/BatchOptions.java`, `java/.../llm/cli/DraftGeneratorCli.java`, `java/.../llm/cli/BatchReports.java`, `resources/application.yml`
- Create: `java/.../llm/cli/ParallelSteps.java`
- Test: `test/.../llm/cli/DocumentBatchReaderCheckTest.java`, `test/.../llm/cli/BatchOptionsModelTest.java`, `test/.../llm/cli/ParallelStepsTest.java`

**Interfaces:**
- Consumes: Task 1 `DocumentReaderCheck`, Task 2 `earlierUsedTopicsInBlock`, Task 3 `withReaderFindings`.
- Produces:
  - `BatchOptions.readerCheckEnabled(Map<String,Object> generation) -> boolean`(`Boolean.TRUE.equals(generation.get("reader-check-enabled"))`).
  - `generateDocument(..., boolean polishEnabled, boolean readerCheckEnabled)` — 매개변수 하나를 더한다.
  - `ParallelSteps.run(Supplier<A> a, Supplier<B> b) -> ParallelSteps.Pair<A,B>` — `BothEditions`와 같은 방식(고정 스레드 2개, 끝나면 shutdown, `CompletionException`에서 원래 예외를 꺼내 던짐).
  - `BatchReports.checkReader(DocumentReaderCheck, GeneratedDocumentItem, DocumentEdition, List<String> earlierTopics, String beginnerContentMd, LocalDate) -> List<ReaderFinding>` — 실패하면 예외를 삼키고 `null`을 돌려주며 요약에 `"⚠️ 독자 점검(편) 실패 — 사유"` 한 줄. 성공하면 `"🔎 독자 점검(편) n건 — 막힘 a · 되풀이 b · 제안 c"` 한 줄.
  - `DocumentBatch`의 사실 검수 블록을 `ParallelSteps.run(사실 검수 BothEditions, 독자 점검 BothEditions)`로 바꾼다. 독자 점검이 꺼져 있으면 두 번째 공급자는 `() -> null`. 독자 점검 결과는 두 편 다 `null`이 아니면(하나라도 돌았으면) `withReaderFindings`로 파일에 싣는다. 사실 검수 파일 쓰기와 한 번의 `replaceFile`로 합친다.

- [ ] **Step 1: 실패하는 테스트**
  - `BatchOptionsModelTest.readerCheckOffWhenKeyMissing` / `readerCheckOnWhenTrue`.
  - `ParallelStepsTest.runsBothAndRethrowsRuntimeException`.
  - `DocumentBatchReaderCheckTest.readerFailureKeepsFactFindingsAndNullsThatEdition` — 가짜 사실 검수기(지적 1건)와 입문편에서 예외를 던지는 가짜 독자 점검기로 사실 검수 블록을 돌리면, 파일의 `factFindings`는 1건이고 `readerFindings.beginner() == null`, `readerFindings.advanced()`는 가짜가 준 목록이다. (사실 검수 블록을 `static` 메서드 `checkAndAttach(...)`로 떼어 가짜를 넣어 시험한다.)
  - `DocumentBatchReaderCheckTest.disabledLeavesFileWithoutReaderFindings` — 꺼져 있으면 `readerFindings() == null`.
- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*BatchOptionsModelTest" --tests "*ParallelStepsTest" --tests "*DocumentBatchReaderCheckTest" --console=plain`
- [ ] **Step 3: 구현** — `application.yml`의 `llm.generation`에 `reader-check-enabled: false`를 `polish-enabled` 아래에 더한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 이어서 `.\gradlew.bat test --console=plain` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(llm): 배치가 사실 검수와 나란히 독자 점검을 돌린다`

### Task 5: 초안에 독자 점검 저장(V49)

**Files:**
- Create: `resources/db/migration/V49__document_draft_reader_findings.sql`, `java/.../llm/dto/DraftReaderFinding.java`
- Modify: `java/.../llm/domain/GeneratedDocumentDraft.java`, `java/.../llm/service/DocumentImportService.java`
- Test: `test/.../llm/service/DocumentImportServiceTest.java`(있는 시험 파일에 더한다)

**Interfaces:**
- Produces:
  - V49: `ALTER TABLE generated_document_draft ADD COLUMN reader_findings_json TEXT NULL;` 머리 주석은 V45 꼴(NULL = 안 돌림, `[]` = 지적 없음).
  - `record DraftReaderFinding(ReaderFinding finding, @JsonInclude(NON_NULL) String resolution) implements FindingList.Resolvable<DraftReaderFinding>` — `static of(ReaderFinding)`, `resolvedWith(String)`, `Status status()`(`enum Status {OPEN, ACCEPTED, DISMISSED}`; resolution이 `"수락: "`로 시작하면 ACCEPTED, `"넘김"`으로 시작하면 DISMISSED). 머리말 상수 `ACCEPTED_PREFIX = "수락: "`, `DISMISSED_PREFIX = "넘김"`.
  - 엔티티: `@JdbcTypeCode(LONGVARCHAR) @Column(name="reader_findings_json") String readerFindingsJson`, `recordReaderFindings(String)`, `resolveReaderFinding(String contentMd, String readerFindingsJson)`(requirePending).
  - `DocumentImportService.saveOne`이 편에 맞는 목록(입문편 항목이면 `readerFindings.beginner()`, 심화편이면 `advanced()`)을 `DraftReaderFinding::of`로 감싸 저장한다. 파일 칸이 없거나 그 편이 `null`이면 `null`을 둔다.

- [ ] **Step 1: 실패하는 테스트** — `importStoresReaderFindingsPerEdition`(입문 2건·심화 1건 파일을 흡수하면 두 초안에 각각 2건·1건), `importWithoutReaderFindingsLeavesNull`.
- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*DocumentImportServiceTest" --console=plain`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS
- [ ] **Step 5: 커밋** — `feat(llm): 문서 초안에 독자 점검 지적을 담는다(V49)`

### Task 6: 응답·단건 조회·수락·넘김 API

**Files:**
- Modify: `java/.../llm/dto/LlmDocumentDraftResponse.java`, `java/.../llm/service/LlmDocumentService.java`, `java/.../admin/controller/AdminLlmDocumentController.java`
- Create: `java/.../llm/support/SuggestionZones.java`
- Test: `test/.../llm/service/LlmDocumentServiceReaderFindingTest.java`, `test/.../llm/support/SuggestionZonesTest.java`, 컨트롤러 테스트(있는 `AdminLlmDocumentController` 테스트에 더한다)

**Interfaces:**
- Consumes: Task 1 `TextSpans.locateOnce`, Task 5 `DraftReaderFinding`.
- Produces:
  - 응답 마지막 컴포넌트 `List<ReaderFindingView> readerFindings`(안 돌렸으면 `null`). `record ReaderFindingView(String kind, String kindLabel, String type, String typeLabel, String weight, String section, String quote, String earlier, String reason, String op, String target, String text, String note, boolean acceptable, String resolution, String status)` — `acceptable`은 OPEN이고 op가 NEED_HUMAN이 아니며 지금 본문에서 `locateOnce`로 잡히고 금지 자리가 아닐 때 true. 한글 이름은 막힘/되풀이, 풀지 않은 말·안 잡히는 문장·건너뛴 단계·비유·그림·표·수치 안 맞음·되풀이.
  - `GET /api/admin/llm-documents/{id}` → `ApiResponse<LlmDocumentDraftResponse>`(검수 화면이 쓴다).
  - `POST /api/admin/llm-documents/{id}/reader-findings/{index}/accept` → 응답 DTO. `POST .../{index}/dismiss` 본문 `{"note": "선택"}` → 응답 DTO.
  - `SuggestionZones.forbidden(String contentMd, int start, int end) -> boolean` — Global Constraints의 금지 자리에 걸치면 true.
  - `LlmDocumentService.acceptReaderFinding(Long draftId, int index)`: PENDING 확인 → OPEN 확인(`FindingList.requireOpen`) → op가 NEED_HUMAN이면 400 `"사람 확인이 필요한 지적은 수락할 수 없습니다. 넘김으로 처리하세요."` → `locateOnce` 실패면 400 `"제안한 문장이 본문에서 바뀌었습니다."` → 금지 자리면 400 `"제목·코드·그림·표·인용 상자는 제안으로 고치지 않습니다."` → op대로 고침(REPLACE: 자리를 text로, INSERT_AFTER: 자리 뒤에 `" " + text`, DELETE: 자리를 지우고 앞뒤 공백 두 칸을 한 칸으로) → `DocumentDraftValidator.validate`의 막는 오류가 고치기 전보다 늘면 400 `"제안을 넣으면 문서 검증에 걸립니다."` → `documentFileSync.replaceContent` → resolution `"수락: 「old」 → 「new」(배치 파일 X도 고침|배치 파일은 못 찾아 그대로)"` → `draft.resolveReaderFinding`.
  - `dismissReaderFinding(Long draftId, int index, String note)`: resolution `"넘김"` 또는 `"넘김: " + note`. 본문은 그대로.

- [ ] **Step 1: 실패하는 테스트**
  - `acceptReplaceChangesOnlyTarget`, `acceptInsertAfterAppendsInSameParagraph`, `acceptDeleteCollapsesSpaces`.
  - `secondAcceptOnChangedTargetIsRejected` (Review Focus 1): 같은 문장을 고치는 제안 둘 중 첫째를 수락한 뒤 둘째는 400, 본문은 첫 수락 결과 그대로.
  - `needHumanCannotBeAccepted`, `targetInHeadingIsRejected`(`SuggestionZonesTest`에서 소제목·코드 블록·표·인용 상자·정의 줄 앞부분 다섯 경우를 각각).
  - `acceptAlsoRewritesBatchFile` — `DocumentFileSync` 가짜/실제 임시 폴더로 배치 파일 본문이 바뀌었는지.
  - `acceptOnApprovedDraftIsRejected`.
  - `dismissKeepsContentAndRecordsNote`.
  - `responseReaderFindingsNullWhenNotRun` (Review Focus 5).
  - `approveIgnoresOpenReaderFindings` — 독자 점검 미처리가 남아도 승인된다.
  - 컨트롤러: `getOneReturnsDraft`, `acceptEndpointReturns400OnNeedHuman`.
- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*ReaderFinding*" --tests "*SuggestionZonesTest" --tests "*AdminLlmDocument*" --console=plain`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 이어서 전체 테스트 PASS
- [ ] **Step 5: 커밋** — `feat(admin): 독자 점검 제안을 수락·넘김 처리하는 API를 더한다`

### Task 7: 나란히 검수 화면

**Files:**
- Create: `resources/static/admin/doc-review.html`, `resources/static/admin/js/doc-review.js`
- Modify: `resources/static/admin/llm.html`(문서 카드 머리에 「나란히 검수」 링크, 새 탭), `resources/static/css/style.css`(검수 화면 블록)

**Interfaces:**
- Consumes: Task 6 GET·accept·dismiss API, 기존 `POST .../fact-findings/{index}/resolve`, `.../approve`, `.../reject`, `admin-common.js`의 `renderDiagramPreviews`·`escapeHtml`·`api`.

화면 구성은 설계 문서 「5. 나란히 검수 화면」의 스케치를 따른다. 정할 것만 적는다.
- 페이지 골격은 `admin/topic.html`과 같다(테마 스크립트, `/css/style.css`, `#shell`, `#guard`, `#adminUi`, 같은 script 순서). `document.html`의 marked·DOMPurify `<script>` 두 줄을 integrity까지 그대로 옮긴다.
- 렌더링: `DOMPurify.sanitize(marked.parse(md))`. 칠하기는 마크다운에 사용자 영역 문자 표지(`{n}` … ``)를 넣고 렌더링한 뒤 DOM의 텍스트 노드에서 표지를 `<mark data-n>`로 바꾸는 방식으로 한다. 표지 위치는 클라이언트에서 `normalize` 규칙으로 찾고, 못 찾은 지적은 카드에 "본문에서 위치를 못 찾음"으로 둔다. mermaid 펜스는 `renderDiagramPreviews`로 그린다.
- 검증기 `checks`에 HTML 태그 막는 오류가 있으면 처음부터 원문 보기(`<pre>`)로 연다. 「원문 보기」 단추로 언제든 바꾼다.
- 오른쪽 패널: 탭 「사실 검수 n」「독자 점검 n」「처리함 n」. 카드 색: 사실 검수(빨강 계열), 독자 막힘(주황), 독자 되풀이(파랑), 처리함(회색). 독자 카드에 "지금/제안" 글자 비교(지운 글자·새 글자)와 `acceptable`일 때만 [제안 수락], 늘 [넘김](이유 입력 선택). 사실 카드는 검수함과 같은 「고침 — 문단 고치기」·「헛경보」.
- 처리 뒤에는 GET으로 다시 받아 전체를 다시 그린다. 승인·거절 단추는 상단에 둔다(사실 검수 미처리가 있으면 승인 끔, 기존 규칙).
- 900px 아래에서는 패널이 본문 아래로 내려간다. 패널은 넓은 화면에서 붙박이(`position: sticky`)로 따로 스크롤한다.

- [ ] **Step 1: 화면 만들기**
- [ ] **Step 2: 문법 검사** — 인라인·분리 스크립트를 `node --check resources/static/admin/js/doc-review.js`로 검사. Expected: 출력 없음
- [ ] **Step 3: 8081 확인** — 테스트 DB나 개발 DB의 PENDING 초안 하나에 `reader_findings_json`을 DNS 시험 결과(`scratchpad/reader-trial/dns-beg-findings.json` + `suggestions.json`을 `DraftReaderFinding` 꼴로 바꾼 것)로 넣고, 8081을 띄워 Playwright로 `/admin/doc-review.html?id=N`을 넓은 폭·좁은 폭 두 번 찍는다. 확인할 것: 칠한 문장 번호 ↔ 카드 이동, 제안 수락 후 본문 반영, 넘김, HTML 태그 초안이 원문 보기로 열림(Review Focus 3). 확인용 데이터는 끝나고 원래대로 돌린다. 8081은 내린다.
- [ ] **Step 4: 커밋** — `feat(admin): 본문과 사실 검수·독자 점검 지적을 나란히 보는 검수 화면을 더한다`

### Task 8: 문서와 기록

**Files:**
- Modify: `docs/28-content-flow.md`(4장 흐름에 독자 점검), `docs/22-ai-review.md`(비용 표에 편당 호출 하나), `docs/15-*.md` 중 검수함 설명이 있는 곳(나란히 검수 화면 한 단락)
- 메모리: `document-polish-step.md` 옆에 독자 점검 메모 하나와 `MEMORY.md` 한 줄

- [ ] **Step 1: 문서 고치기** — 프로젝트 CLAUDE.md 규칙대로 docs를 크게 고쳤으면 마지막에 humanize-korean 윤문 후 코드·수치·경로 대조.
- [ ] **Step 2: 빌드** — `.\gradlew.bat build --console=plain` PASS
- [ ] **Step 3: 커밋** — `docs: 독자 점검 단계와 나란히 검수 화면을 흐름 문서에 적는다`

## 범위 밖(이 계획 뒤에 사용자에게 물을 것)

- `reader-check-enabled`를 켜기(로컬 앱 배포 뒤, 요금 듦).
- 이미 나간 문서에 독자 점검을 돌리는 CLI.
