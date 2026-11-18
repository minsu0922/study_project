# 개념 문서 윤문 단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문서일 배치가 두 편을 만든 뒤 윤문 호출을 한 번 더 하고, 내용이 그대로인 윤문본만 골라 저장한다.

**Architecture:** 생성(그대로) → 원본 저장(그대로) → 두 편 윤문 동시 제출 → 코드로 보존 대조·형식 경고 비교 → 통과한 편만 교체 저장 → 두 편 사실 검수 동시 제출. 윤문은 `ClaudeDocumentPolisher`(client)가, 대조는 `PolishPreservation`(support)이, 채택 판단과 배선은 `DocumentBatch`(cli)가 맡는다. 설정 `llm.generation.polish-enabled`가 꺼져 있으면 지금과 같게 돈다.

**Tech Stack:** Java 21, Spring Boot 3.4.1(배치는 스프링 없이 main), Anthropic Java SDK, Jackson, JUnit 5 + AssertJ, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-18-document-polish-design.md` (함께 읽는다. `docs/28-content-flow.md` 4장도)

## Global Constraints

- 순차 단계는 생성 2 + 윤문 1 + 사실 검수 1 = 4개. 윤문·사실 검수는 두 편을 **동시에** 제출한다. 여러 건 묶음 제출(customId 여러 개)은 만들지 않는다.
- `ClaudeCalls.BATCH_DEADLINE` 45분, 워크플로 `timeout-minutes: 300`은 바꾸지 않는다.
- 윤문 호출: `MAX_TOKENS` 64,000, **사고(thinking) 끔**. `MAX_TOKENS`에서 잘리면 대조 실패로 본다.
- 실패(호출 실패·시간 초과·잘림·대조 실패·새 형식 경고)는 모두 **원본 유지**, job은 계속 간다.
- 원본은 `GeneratedDocumentFile`의 새 칸 `originalDocument`·`originalAdvancedDocument`에 둔다(사용자 결정, 2026-11-18). 읽는 곳은 아직 없지만 유지한다. 원본 저장과 윤문본 저장이 한 커밋에 들어가 git 이력에 원본이 남지 않으므로, 윤문이 무엇을 바꿨는지 되짚을 기록은 이 칸뿐이다. `GeneratedDocumentItem`은 모델 응답 스키마라 건드리지 않는다. 두 칸은 null 허용, `FileFormats.Kind.DOCUMENTS` 버전은 1 그대로.
- 문제 배치가 읽는 `document`·`advancedDocument`의 `contentMd`는 **윤문본**이다.
- 수치 대조는 **나온 수치의 종류(집합)**가 같은지만 본다. 횟수는 보지 않는다(사용자 결정).
- 조건어는 정도 부사 15개만 보고, 낱말마다 **나온 횟수**가 같아야 한다: 기본적으로·대부분·대개·보통·주로·흔히·일반적으로·거의·가끔·종종·드물게·최대·최소·적어도·늦어도 (사용자 결정. "경우"는 넣지 않는다).
- `llm.generation.polish-enabled` 기본값 false. 키가 없어도 꺼진 것으로 본다.
- 윤문 프롬프트 파일: `src/main/resources/prompts/document-polish/system.txt`. 지시의 핵심 문장은 "내용은 그대로, 문장만 고친다".
- 하지 않는 것: 문제 질문·보기 윤문, 윤문 전 사실 검수, 문단 단위 선별 윤문.
- 코드 주석 규칙(전역 CLAUDE.md): 이유만, 인라인 2줄·메서드 5줄까지. 생성자 주입, 필드 주입 금지.
- 빌드: `.\gradlew.bat test --console=plain`. 빌드와 커밋은 따로 실행한다.

## Review Focus

1. 모델이 윤문본을 ```markdown 펜스로 감싸거나 앞에 "다음은 윤문본입니다" 같은 말을 붙여 돌려준다 → 원본 유지여야 한다. Task 2에 테스트.
2. 윤문이 마지막 절(「참고 자료」 등)을 통째로 빠뜨렸는데 `stop_reason`은 정상이다 → 소제목 대조로 원본 유지. Task 2에 테스트.
3. 윤문이 「글의 주제」 상자 문장을 고친다 → 형식 검사의 새 차단 항목으로 원본 유지. Task 6에 테스트.
4. 심화편 생성이 실패해 `advanced == null`인 날 → 입문편만 윤문하고 예외 없이 진행. Task 6에 테스트.
5. 앱이 손으로 문서를 고쳐 배치 파일을 다시 쓸 때(`DocumentFileSync`) 원본 칸이 사라진다 → 두 칸을 그대로 옮겨야 한다. Task 4에 테스트.

배포 순서 주의(테스트 대상 아님): 파일 읽기 쪽은 `JsonMappers.STRICT`라 모르는 칸이 있으면 실패한다. Task 4가 들어간 코드를 로컬 앱에 먼저 받은 뒤에 설정을 켠다. Task 8 문서에 적는다.

---

### Task 1: 응답이 MAX_TOKENS에서 잘렸는지 알려 준다

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/ClaudeCalls.java` (`Result`, `Raw`, `create`, `SdkTransport.toRaw`)
- Test: `src/test/java/project/study/study_project/llm/client/ClaudeCallsTest.java`

**Interfaces:**
- Produces: `ClaudeCalls.Result<T>`에 마지막 컴포넌트 `boolean truncated` 추가. `ClaudeCalls.Raw`에 마지막 컴포넌트 `boolean truncated` 추가, 기존 5·6인자 생성자는 `truncated=false`로 남긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
@Test
void MAX_TOKENS에서_잘린_응답은_truncated로_알린다() {
    FakeTransport fake = new FakeTransport();          // 파일 안 기존 가짜를 쓴다
    fake.sendResult = new Raw(JSON, 1000, 64000, 0, false, "", true);
    ClaudeCalls.useTransport(fake, ms -> { });
    var result = ClaudeCalls.create("문서 윤문(입문편)", params());
    assertThat(result.truncated()).isTrue();
}

@Test
void 정상_응답은_truncated가_아니다() {
    // 기존 5인자 Raw로 만든 응답 → result.truncated() == false
}
```

- [ ] **Step 2: 실행해 실패 확인**

Run: `.\gradlew.bat test --console=plain --tests "*ClaudeCallsTest"`
Expected: 컴파일 실패(`truncated` 없음)

- [ ] **Step 3: 구현** — `toRaw`에서 `m.stopReason().filter(r -> r.equals(StopReason.MAX_TOKENS)).isPresent()`를 `truncated`로 넘기고, `create`가 `Result`에 그대로 싣는다. 배치 경로(`result(batchId)`)도 같은 `toRaw`를 쓰므로 따로 손대지 않는다.

- [ ] **Step 4: 실행해 통과 확인** — 같은 명령, Expected: PASS (기존 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/client/ClaudeCalls.java src/test/java/project/study/study_project/llm/client/ClaudeCallsTest.java
git commit -m "feat(llm): 응답이 MAX_TOKENS에서 잘렸는지 호출 결과에 싣는다"
```

---

### Task 2: 윤문 전후 보존 대조

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/PolishPreservation.java`
- Test: `src/test/java/project/study/study_project/llm/support/PolishPreservationTest.java`

**Interfaces:**
- Produces: `public static List<String> PolishPreservation.differences(String original, String polished)` — 빈 목록이면 통과. 항목마다 사람이 읽을 한 줄(예: `"소제목이 다릅니다: ## 참고 자료 없음"`).
- Produces: `public static final List<String> CONDITION_WORDS` (Global Constraints의 15개, 그 순서)

대조 항목(하나라도 다르면 그 항목 문장을 목록에 넣는다):

| 항목 | 뽑는 법 | 같음의 기준 |
|---|---|---|
| 소제목 | 펜스 밖에서 `^#{1,6} ` 로 시작하는 줄 | 순서까지 같은 목록 |
| 코드·그림 블록 | 펜스(```) 블록 전체 문자열(mermaid 포함) | 순서까지 같은 목록 |
| 그림 줄·링크 | `!\[[^\]]*\]\([^)]*\)`, `\[[^\]]*\]\([^)]*\)` 일치 문자열 | 순서까지 같은 목록 |
| 수치 | 전체 본문에서 `\d+(?:[.,:]\d+)*` | **집합**이 같음 |
| 용어 | `## 용어 한눈에` 절 표의 첫 칸(머리줄·구분줄 제외) | 순서까지 같은 목록. 절이 없으면 둘 다 빈 목록 |
| 조건어 | `CONDITION_WORDS` 낱말마다 부분 문자열 등장 횟수 | 낱말마다 횟수가 같음 |

- [ ] **Step 1: 실패하는 테스트 작성** — 표본은 소제목 둘·코드 블록 하나·그림 줄 하나·수치 `300초`·`1시간`·용어 표 두 줄·"대부분"이 든 짧은 마크다운 `ORIGINAL`.

```java
@Test void 문장만_바뀌면_통과() { assertThat(differences(ORIGINAL, ORIGINAL.replace("저장해 두고 다시 쓴다", "저장했다가 다시 쓴다"))).isEmpty(); }
@Test void 소제목이_빠지면_실패() { /* "## 참고 자료" 절 삭제 → 결과에 "소제목" 포함 */ }
@Test void 펜스로_감싼_응답은_실패() { assertThat(differences(ORIGINAL, "```markdown\n" + ORIGINAL + "\n```")).isNotEmpty(); }
@Test void 앞에_안내문을_붙인_응답은_실패() { /* "다음은 윤문본입니다.\n\n" + ORIGINAL → 결과에 "첫 줄" 포함 */ }
@Test void 수치가_바뀌면_실패() { /* 300초 → 600초 */ }
@Test void 같은_수치를_되풀이한_문장을_합치면_통과() { /* "1시간"이 두 번 → 한 번 */ }
@Test void 코드_블록이_바뀌면_실패() { }
@Test void 그림_줄이_바뀌면_실패() { }
@Test void 용어_표의_용어가_바뀌면_실패() { }
@Test void 조건어가_빠지면_실패() { /* "대부분" 삭제 → 결과에 "대부분" 포함 */ }
```

- [ ] **Step 2: 실행해 실패 확인** — `.\gradlew.bat test --console=plain --tests "*PolishPreservationTest"`, Expected: 컴파일 실패

- [ ] **Step 3: 구현** — 위 표 그대로 하고, 일곱째 항목으로 "윤문본 첫 줄이 원문 첫 줄(`# 제목`)과 같아야 한다"를 더한다(사유 문장 `"첫 줄이 다릅니다: …"`). 소제목 목록만으로는 앞에 붙은 안내문을 못 잡는다.

- [ ] **Step 4: 실행해 통과 확인** — Expected: PASS

- [ ] **Step 5: 커밋** — `feat(llm): 윤문 전후 소제목·수치·코드·용어·조건어가 같은지 대조한다`

---

### Task 3: 윤문 호출기와 윤문 프롬프트

**Files:**
- Create: `src/main/java/project/study/study_project/llm/client/DocumentPolisher.java`
- Create: `src/main/java/project/study/study_project/llm/client/ClaudeDocumentPolisher.java`
- Create: `src/main/java/project/study/study_project/llm/client/PolishedDocument.java`
- Create: `src/main/resources/prompts/document-polish/system.txt`
- Modify: `src/main/resources/prompts/document/beginner-system.txt`, `src/main/resources/prompts/document/advanced-system.txt`
- Test: `src/test/java/project/study/study_project/llm/client/ClaudeDocumentPolisherTest.java`

**Interfaces:**
- Produces: `public interface DocumentPolisher { String polish(String title, String contentMd, DocumentEdition edition); }` — 윤문본 본문을 돌려준다. 실패는 `BusinessException(ErrorCode.LLM_003, …)`로 던진다.
- Produces: `public record PolishedDocument(@JsonPropertyDescription("윤문한 마크다운 본문 전체") String contentMd)` — 응답 스키마.
- Produces: `public ClaudeDocumentPolisher(String model)`; `static final long MAX_TOKENS = 64_000L`; `static final String SYSTEM_PROMPT = Prompts.load("document-polish/system.txt")`.
- Consumes: Task 1의 `Result.truncated()`.

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 전송은 `ClaudeCallsTest`처럼 `ClaudeCalls.useTransport`. 같은 패키지라 쓸 수 있다)

```java
@Test void 요청은_64000토큰이고_사고를_켜지_않는다() { /* 가짜가 받은 MessageCreateParams: maxTokens()==64000, thinking() 비어 있음, system에 SYSTEM_PROMPT */ }
@Test void 사용자_메시지에_제목과_본문을_싣는다() { /* "# 제목" 줄과 본문 전문이 들어 있다 */ }
@Test void 응답의_본문을_돌려준다() { /* {"contentMd":"# 제목\n고친 본문"} → "# 제목\n고친 본문" */ }
@Test void 잘린_응답은_예외() { /* truncated=true → BusinessException, 메시지에 "MAX_TOKENS" */ }
@Test void 빈_본문은_예외() { }
@Test void 비용_요약_이름은_편을_밝힌다() { /* ClaudeCalls.ledger()의 label == "문서 윤문(입문편)" */ }
```

`PromptsTest`에 한 줄 추가: `document-polish/system.txt`가 로드되고 "내용은 그대로" 를 담는다.

- [ ] **Step 2: 실행해 실패 확인** — `--tests "*ClaudeDocumentPolisherTest" --tests "*PromptsTest"`, Expected: 컴파일 실패

- [ ] **Step 3: 호출기 구현** — 구조는 `ClaudeDocumentFactChecker.check`를 따른다(같은 예외 변환). 다른 점: `.thinking(...)`을 부르지 않는다, 출력 형식 `PolishedDocument`, label `"문서 윤문(" + edition.getDisplayName() + ")"`.

- [ ] **Step 4: 윤문 프롬프트 작성과 생성 프롬프트에서 옮기기**
  1. 생성 프롬프트 두 파일의 `[문체]` `[덜어낼 것]` `[강조와 줄바꿈]` `[절과 절을 잇는다]` 절을 읽는다.
  2. **문장 다듬기 지시**(어순, 군더더기, 번역투, 접속사, 강조 꼴 고르기)는 `document-polish/system.txt`로 옮기고 생성 쪽에서 지운다.
  3. **종결어미 규칙과 용어 규칙**은 생성 쪽에 남긴다(윤문 프롬프트에도 "바꾸지 않는다"로 적는다).
  4. 윤문 프롬프트는 아래 **다섯 묶음만** 고치게 한다(사용자 확정, 2026-11-18).

| 묶음 | 고치는 것 | 가져올 곳 |
|---|---|---|
| A. 번역투·흐린 말 | ~에 대해·~를 통해, 형식명사(것·점·것이다), "~할 수 있다" 남발, 이중 피동, "~의" 겹침, "~해야 한다"로 문단 끝내기 | 생성 [덜어낼 것] |
| B. 군더더기 | 예고("이제 ~를 살펴보자"), "정리하면·즉", 빈말, 같은 설명 되풀이, 비유 부연 | 생성 [덜어낼 것], [절과 절을 잇는다]의 예고·요약 금지 |
| C. 같은 틀 반복 | 그림 앞뒤 틀 문장, "A가 아니라 B" 대구, 문두 "그래서", "~와 같다" 비유 다리 | humanize 진단(2026-11-17 DNS 입문편) |
| D. 문장 길이 | 60자 넘는 문장 끊기, 명사 앞 세 겹 이상 꾸밈 풀기, 문단 3문장 | 생성 [문체] |
| E. 줄바꿈 | 한 문단에 이야기 하나, 굵은 줄 아래 본문 붙여 쓰기 금지, 문장 가운데 줄표 금지 | 생성 [강조와 줄바꿈], [덜어낼 것] |

     **건드리지 않는 것**도 프롬프트에 목록으로 적는다: 절과 소제목, 표, 코드·그림 블록, 그림 줄, 수치, 용어 정의 줄의 앞부분(`- **용어(영문)** —`), 「글의 주제」 상자, 비유(세운 비유를 다른 비유로 바꾸지 않는다), 결론 상자(`> **…**`)의 개수와 위치, 종결어미(평서 "-다"), 용어(동의어로 바꾸지 않는다). 각 절 첫 문장이 하는 일(앞 절에서 무엇이 문제가 되는지)은 남기고 말투만 고친다. 그림 앞뒤 한 줄씩은 생성 프롬프트 형식 규칙이라 **지우지 말고 말투만 바꾼다**.
  5. 그 밖의 윤문 프롬프트 내용: 첫 줄에 역할과 "내용은 그대로, 문장만 고친다"; Task 2 대조 항목 일곱 가지를 "바꾸면 원본으로 되돌린다"는 이유와 함께; 출력은 본문 전체, 앞뒤에 말을 붙이지 않음.
  6. 생성 프롬프트에서 지우는 것은 위 표의 "가져올 곳"에서 A·B·D·E에 해당하는 줄뿐이다. [문체]의 평서체·결론 먼저·표로 옮기기·중학생 눈높이, [강조와 줄바꿈]의 결론 상자 개수·백틱·HTML 금지, [절과 절을 잇는다]의 첫 문장 규칙은 생성 쪽에 남긴다(글의 구조라서).

- [ ] **Step 5: 실행해 통과 확인** — `.\gradlew.bat test --console=plain --tests "*ClaudeDocumentPolisherTest" --tests "*PromptsTest" --tests "*ClaudeDocumentGenerator*"` Expected: PASS (생성 프롬프트 테스트가 옮긴 문구를 찾고 있으면 그 테스트의 기대값도 함께 옮긴다)

- [ ] **Step 6: 커밋** — `feat(llm): 문서 윤문 호출기와 윤문 프롬프트를 만들고 문장 다듬기 지시를 생성 프롬프트에서 옮긴다`

---

### Task 4: 문서 파일에 원본 칸을 더한다

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/dto/GeneratedDocumentFile.java`
- Modify: `src/main/java/project/study/study_project/llm/service/DocumentFileSync.java:130-133`
- Test: `src/test/java/project/study/study_project/llm/service/DocumentFileSyncTest.java`, `src/test/java/project/study/study_project/llm/cli/DocumentFactFindingsTest.java`

**Interfaces:**
- Produces: `GeneratedDocumentFile`의 컴포넌트 끝에 `GeneratedDocumentItem originalDocument, GeneratedDocumentItem originalAdvancedDocument` (null 허용). 기존 7·8인자 생성자는 두 칸 null로 남긴다.
- Produces: `public GeneratedDocumentFile withPolished(GeneratedDocumentItem document, GeneratedDocumentItem advancedDocument, GeneratedDocumentItem originalDocument, GeneratedDocumentItem originalAdvancedDocument)`
- 기존 `withFactFindings`는 두 원본 칸을 그대로 옮긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// DocumentFactFindingsTest
@Test void 원본_칸은_STRICT로_왕복한다() { /* withPolished로 만든 파일을 FileFormats.toJson → STRICT read → originalDocument().contentMd() 같음 */ }
@Test void 원본_칸이_없는_옛_파일도_읽는다() { /* 원본 칸 없는 JSON → originalDocument()==null */ }
@Test void 사실_검수_지적을_붙여도_원본_칸이_남는다() { /* withPolished(...).withFactFindings(...) */ }
// DocumentFileSyncTest
@Test void 앱이_문서를_고쳐_파일을_다시_써도_원본_칸이_남는다() { }
```

- [ ] **Step 2: 실행해 실패 확인** — `--tests "*DocumentFactFindingsTest" --tests "*DocumentFileSyncTest"`, Expected: 컴파일 실패

- [ ] **Step 3: 구현** — record 컴포넌트·생성자·`withPolished` 추가. `DocumentFileSync.rewrite`의 `new GeneratedDocumentFile(...)`에 `parsed.originalDocument(), parsed.originalAdvancedDocument()`를 넘긴다. 칸 설명은 클래스 Javadoc `@param`에 한 줄씩(원본 = 윤문 전 모델 출력, 윤문을 안 했거나 안 쓴 편은 null).

- [ ] **Step 4: 전체 테스트로 통과 확인** — `.\gradlew.bat test --console=plain` Expected: PASS (`GeneratedDocumentFile` 생성자를 쓰는 테스트 전부)

- [ ] **Step 5: 커밋** — `feat(llm): 문서 파일에 윤문 전 원본 두 편을 담을 칸을 더한다`

---

### Task 5: 두 호출을 동시에 보내는 도우미

**Files:**
- Create: `src/main/java/project/study/study_project/llm/cli/BothEditions.java`
- Test: `src/test/java/project/study/study_project/llm/cli/BothEditionsTest.java`

**Interfaces:**
- Produces: `static <T> Pair<T> BothEditions.run(Supplier<T> beginner, Supplier<T> advanced)` 와 `record Pair<T>(T beginner, T advanced)`. `advanced`가 null이면 입문편만 돌리고 `advanced()`는 null. 한쪽 Supplier가 던진 `RuntimeException`은 그대로 다시 던진다(호출부가 각 Supplier 안에서 이미 잡는다는 전제).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
@Test void 두_작업이_동시에_돈다() { /* CountDownLatch(2): 각 Supplier가 countDown 후 await(2초) — 순차면 시간 초과로 실패 */ }
@Test void 심화편이_없으면_입문편만_돈다() { }
@Test void 결과를_편별로_돌려준다() { }
```

- [ ] **Step 2: 실패 확인** — `--tests "*BothEditionsTest"`
- [ ] **Step 3: 구현** — `CompletableFuture.supplyAsync`에 스레드 2개짜리 실행기를 쓰고, 끝나면 `shutdown`. 기본 공용 풀은 쓰지 않는다(배치 main이 끝날 때 남는 스레드 문제를 피한다는 이유를 주석 한 줄로).
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(llm): 입문편·심화편 호출을 동시에 보내는 도우미를 만든다`

---

### Task 6: 윤문본 채택 판단

**Files:**
- Create: `src/main/java/project/study/study_project/llm/cli/PolishStep.java`
- Test: `src/test/java/project/study/study_project/llm/cli/PolishStepTest.java`

**Interfaces:**
- Consumes: Task 2 `PolishPreservation.differences`, Task 3 `DocumentPolisher`, Task 5 `BothEditions.run`, `DocumentDraftValidator.validate(title, slug, contentMd, expectedTopicBox)`.
- Produces: `record PolishOutcome(GeneratedDocumentItem chosen, boolean applied, String reason)` — `applied`면 `reason`은 null.
- Produces: `static PolishOutcome PolishStep.decide(DocumentPolisher polisher, GeneratedDocumentItem original, String expectedTopicBox)`
- Produces: `static BothEditions.Pair<PolishOutcome> PolishStep.polishBoth(DocumentPolisher polisher, GeneratedDocumentItem beginner, GeneratedDocumentItem advanced, TopicBrief brief)` — 상자는 `brief == null ? null : brief.beginnerTopicBox()/advancedTopicBox()`.
- Produces: `static String PolishStep.summaryLine(String edition, PolishOutcome outcome)` → `"✨ 입문편 윤문 적용"` 또는 `"↩️ 심화편 원본 유지 — <reason>"`.

`decide` 순서: 윤문 호출(예외면 원본 유지, 사유 `"윤문 호출 실패: " + 메시지`) → 보존 대조(다르면 원본 유지, 사유 `"보존 대조 실패: " + 첫 항목`) → 형식 경고 비교(아래) → 채택. 채택하면 `chosen`은 원본의 title·slug·tags에 윤문 본문을 끼운 새 `GeneratedDocumentItem`.

형식 경고 비교: 두 본문을 `validate`로 검사하고, 경고 문장의 숫자를 `#`로 바꾼 뒤(분량 같은 수치 차이를 새 경고로 치지 않으려고) 윤문본에만 있는 문장이 하나라도 있으면 원본 유지, 사유 `"새 형식 경고: " + 첫 문장`.

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 `DocumentPolisher`는 람다)

```java
@Test void 문장만_고친_윤문본은_채택() { }
@Test void 호출이_실패하면_원본_유지() { /* polisher가 BusinessException → applied=false, reason이 "윤문 호출 실패"로 시작 */ }
@Test void 소제목을_바꾸면_원본_유지() { }
@Test void 글의_주제_상자를_고치면_원본_유지() { /* expectedTopicBox를 넘기고 윤문본이 상자 문장 하나를 바꿈 → reason이 "새 형식 경고"로 시작 */ }
@Test void 원본에도_있던_경고는_새_경고가_아니다() { /* 원본·윤문본 둘 다 같은 경고, 숫자만 다름 → 채택 */ }
@Test void 심화편이_없으면_입문편만_윤문() { /* polishBoth(…, null, …).advanced()==null, polisher 호출 1회 */ }
@Test void 요약_줄() { /* summaryLine 두 꼴 */ }
```

- [ ] **Step 2: 실패 확인** — `--tests "*PolishStepTest"`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(llm): 보존 대조와 새 형식 경고로 윤문본 채택을 정한다`

---

### Task 7: 문서일 배치에 연결하고 설정을 둔다

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/cli/DocumentBatch.java` (`generateDocument` 166-194행, `attachFactFindings`)
- Modify: `src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java:127-163`
- Modify: `src/main/java/project/study/study_project/llm/cli/BatchOptions.java` (`polishEnabled` 추가)
- Modify: `src/main/resources/application.yml` (`llm.generation`, `batch-enabled` 아래)
- Test: `src/test/java/project/study/study_project/llm/cli/DocumentFactFindingsTest.java`, `src/test/java/project/study/study_project/llm/cli/DraftGeneratorCliTest.java`

**Interfaces:**
- Consumes: Task 3 `ClaudeDocumentPolisher`, Task 4 `withPolished`, Task 5 `BothEditions.run`, Task 6 `PolishStep.polishBoth`/`summaryLine`.
- Produces: `generateDocument(Map<String,String> opts, String model, DomainSettings settings, List<DomainCode> batchDomains, LocalDate cycleAnchor, boolean polishEnabled)`
- Produces: `static boolean BatchOptions.polishEnabled(Map<String,Object> generation)` — `Boolean.TRUE.equals(generation.get("polish-enabled"))`.
- Produces: `static GeneratedDocumentFile DocumentBatch.applyPolish(Path outFile, GeneratedDocumentFile file, BothEditions.Pair<PolishOutcome> outcomes)` — 채택된 편이 하나라도 있으면 `withPolished`로 바꿔 임시 파일 → 이동으로 쓰고 새 파일 객체를 돌려준다. 원본 칸에는 **채택된 편의 원본만** 넣는다. 둘 다 원본 유지면 쓰지 않고 `file`을 그대로 돌려준다. 쓰기에 실패하면 알리고 `file`을 돌려준다.
- `attachFactFindings`와 `applyPolish`는 임시 파일 쓰기를 `private static boolean replaceFile(Path outFile, GeneratedDocumentFile file)` 하나로 같이 쓴다.

`generateDocument`의 새 순서(166행 이후):
1. 파일 저장(그대로).
2. `polishEnabled`면 `PolishStep.polishBoth(new ClaudeDocumentPolisher(model), document, advanced, brief)` → 편마다 `BatchReports.announce(summaryLine(...))` → `file = applyPolish(...)`, `document`·`advanced`를 채택본으로 바꾼다.
3. `reportDraftChecks`를 이 자리로 옮긴다(최종본 기준).
4. 사실 검수: `BothEditions.run(() -> BatchReports.checkFacts(checker, document, date).findings(), advanced == null ? null : () -> BatchReports.checkFacts(checker, advanced, date).findings())` → `mergeFactFindings` → `attachFactFindings(outFile, file, facts)`. **`file`은 2에서 바뀐 객체를 넘긴다**(원본 칸이 지워지지 않게).
5. 사용 표시(그대로).

`application.yml`:

```yaml
    # 문서 두 편을 만든 뒤 문장만 다시 다듬는 호출을 한 번 더 한다(설계 2026-11-18).
    # 평가(evalPolish) 결과를 본 뒤 켠다. 켜기 전에 로컬 앱 코드를 먼저 받는다(문서 파일에 칸이 는다).
    polish-enabled: false
```

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// DocumentFactFindingsTest
@Test void 윤문본을_채택하면_파일에_윤문본과_원본이_함께_실린다() { }
@Test void 둘_다_원본_유지면_파일을_다시_쓰지_않는다() { /* 파일 수정 시각 그대로 */ }
@Test void 입문편만_채택하면_심화편_원본_칸은_null() { }
@Test void 윤문_뒤_사실_검수_지적을_붙여도_원본_칸이_남는다() { /* applyPolish → attachFactFindings → STRICT read */ }
// DraftGeneratorCliTest
@Test void 윤문_설정이_없으면_꺼진_것으로_본다() { assertThat(BatchOptions.polishEnabled(Map.of())).isFalse(); }
@Test void 윤문_설정을_켜면_켜진다() { assertThat(BatchOptions.polishEnabled(Map.of("polish-enabled", true))).isTrue(); }
```

- [ ] **Step 2: 실패 확인** — `--tests "*DocumentFactFindingsTest" --tests "*DraftGeneratorCliTest"`
- [ ] **Step 3: 구현** — 위 순서대로. `DraftGeneratorCli.run`에서 `BatchOptions.polishEnabled(generation)`을 읽어 넘긴다.
- [ ] **Step 4: 전체 테스트** — `.\gradlew.bat test --console=plain` Expected: PASS
- [ ] **Step 5: 커밋** — `feat(llm): 문서일 배치에 윤문 단계를 넣고 사실 검수를 두 편 동시에 보낸다`

---

### Task 8: 평가 도구와 문서

**Files:**
- Create: `src/main/java/project/study/study_project/llm/cli/PolishEvalCli.java`
- Modify: `build.gradle` (`evalReview` 태스크 옆에 `evalPolish`)
- Modify: `docs/28-content-flow.md` 4장(시퀀스 그림과 번호 목록), `docs/22-ai-review.md` 비용 표(윤문 호출 한 줄)
- Test: `src/test/java/project/study/study_project/llm/cli/PolishEvalCliTest.java`

**Interfaces:**
- Consumes: Task 3 `DocumentPolisher`, Task 6 `PolishStep.decide`, `DocumentFactChecker`, `DocumentDraftValidator`.
- Produces: `PolishEvalCli.main(String[] args)`; 옵션 `--samples=eval-samples/documents`(기본), `--skip-facts=true`, `--label=<꼬리표>`, `--out=eval`(기본).
- Produces: `static String PolishEvalCli.render(List<Row> rows)`와 `record Row(String file, String edition, boolean applied, String reason, int warningsBefore, int warningsAfter, Integer factsBefore, Integer factsAfter)` — `--skip-facts`면 facts 칸은 null.

보고서(`eval/polish-<시각>-<꼬리표>.md`)는 표 한 장 + 합계 세 줄: 채택 수/편 수(대조 실패율), 형식 경고 합 전→후, 사실 지적 합 전→후.

- [ ] **Step 1: 실패하는 테스트 작성** — `render`에 행 두 개(채택 1, 원본 유지 1)를 넣으면 표 두 줄과 `"채택 1/2"`가 나온다. `--skip-facts` 행은 사실 칸이 `-`.
- [ ] **Step 2: 실패 확인** — `--tests "*PolishEvalCliTest"`
- [ ] **Step 3: 구현** — 표본 파일은 `JsonMappers.LENIENT`로 읽는다(옛 표본). 편마다 `PolishStep.decide`(상자 대조는 null) → 형식 경고 수 전후 → `--skip-facts`가 아니면 사실 검수 전후. `ClaudeCalls.useBatch`는 켜지 않는다(바로 호출, 사람이 기다린다). `build.gradle`의 `evalPolish`는 `evalReview`와 같은 키 주입 블록을 쓴다.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 문서 갱신** — docs/28 4장 그림에 "두 편 윤문(동시)" 단계와 번호 목록 한 줄, 배포 순서 주의(앱 코드 먼저, 그다음 설정 켜기). docs/22 비용 표에 "문서 윤문 | 문서일마다 2회 | 편당 출력 최대 64K". 마지막에 humanize-korean으로 바꾼 문단만 윤문하고 코드·수치·경로가 그대로인지 대조한다(프로젝트 CLAUDE.md).
- [ ] **Step 6: 커밋** — `feat(llm): 윤문 전후를 비교하는 평가 도구를 만들고 흐름 문서를 고친다`

**실제 평가 실행은 이 계획 밖이다.** 4편 × 2편 = 윤문 8회 + 사실 검수 16회가 든다. 횟수와 예상 비용을 적어 사용자 승인을 받은 뒤 돌린다(전역 CLAUDE.md).
