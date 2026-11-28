# 백엔드 기술 지도 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 켜진 분야의 개념 주제 전부를 점으로 그린 지도 화면을 만들고, 점마다 내 진도를 칠한다.

**Architecture:** 문서에 주제 id 칸(`document.topic_id`)을 두고 배치 → 문서 파일 → 초안 → 승인 순서로 id를 옮긴다. 이미 나간 문서는 사용 기록과 문서 파일 날짜로 한 번 잇는다. 지도 API가 주제·문서·진도를 한 번에 주고, `map.html`이 SVG로 그린다.

**Tech Stack:** Spring Boot 3.4.1, JPA, Flyway(MySQL), JUnit5 + MockMvc, 정적 HTML/JS.

**Spec:** `docs/superpowers/specs/2026-11-28-backend-map-design.md`

## Global Constraints

- 새 마이그레이션 번호는 `V51`이다. Flyway에는 스키마만 넣는다(콘텐츠 시드 금지).
- 문서 파일의 새 칸 `topicId`는 `@JsonInclude(NON_NULL)`로 쓴다. null을 쓰면 옛 앱이 파일 흡수에 실패한다.
- `DomainCode` 비교는 `equals`로 한다. `==` 금지.
- 지도 API는 공개(`GET`)다. 토큰이 있으면 진도를 싣고 없으면 0이다.
- 라이브러리를 추가하지 않는다. SVG는 직접 그린다.
- 생성자 주입만 쓴다. 응답은 DTO로 내보내고 엔티티를 그대로 내보내지 않는다.
- 빌드는 `.\gradlew.bat ... --console=plain`으로 돌린다. 빌드와 커밋은 따로 실행한다.

## Review Focus

1. 숨긴 문서(`hidden=true`)는 지도에서 빠지고 진도 합산에서도 빠진다. → Task 4 `hiddenDocumentsAreLeftOut`
2. 문제가 0개인 문서만 이어진 주제는 「다 품」이 아니라 「안 품」이다(`solved = problemTotal > 0` 조건). → Task 5 색 판정 함수와 Task 4 `problemTotal`이 0으로 오는지 확인
3. 옛 범위형 주제처럼 한 주제에 문서가 3편 이상 이어져도 전부 나오고 진도가 합산된다. → Task 4 `topicWithManyDocumentsSumsProgress`
4. 주제를 지우면 문서는 남고 `topic_id`만 비워진다. → Task 1 `deletingTopicKeepsDocument`
5. 꺼진 분야의 주제는 문서가 있어도 지도에서 빠진다. → Task 4 `disabledDomainIsLeftOut`

---

### Task 1: 주제 id를 문서 파일 → 초안 → 문서로 옮긴다

**Files:**
- Create: `src/main/resources/db/migration/V51__document_topic_id.sql`
- Modify: `src/main/java/project/study/study_project/document/domain/Document.java`
- Modify: `src/main/java/project/study/study_project/llm/domain/GeneratedDocumentDraft.java`
- Modify: `src/main/java/project/study/study_project/llm/dto/GeneratedDocumentFile.java`
- Modify: `src/main/java/project/study/study_project/llm/service/DocumentImportService.java:183-194`
- Modify: `src/main/java/project/study/study_project/llm/service/LlmDocumentService.java:187-194`
- Test: `src/test/java/project/study/study_project/llm/service/DocumentImportServiceTest.java`
- Test: `src/test/java/project/study/study_project/llm/service/DocumentTopicLinkIntegrationTest.java` (새 파일)

**Interfaces:**
- Produces: `Document.getTopicId(): Long`, `Document.linkTopic(Long topicId): void`
- Produces: `GeneratedDocumentDraft.getTopicId(): Long`, `GeneratedDocumentDraft.recordTopic(Long topicId): void`
- Produces: `GeneratedDocumentFile.topicId(): Long` (레코드 마지막 칸), `GeneratedDocumentFile.withTopicId(Long): GeneratedDocumentFile`

- [ ] **Step 1: 마이그레이션 `V51__document_topic_id.sql`을 쓴다**

```sql
ALTER TABLE document ADD COLUMN topic_id BIGINT NULL;
ALTER TABLE document ADD CONSTRAINT fk_document_topic
    FOREIGN KEY (topic_id) REFERENCES topic_queue (id) ON DELETE SET NULL;
ALTER TABLE generated_document_draft ADD COLUMN topic_id BIGINT NULL;
```

초안 쪽은 외래키를 걸지 않는다. 승인 전에 주제가 지워져도 초안 흡수가 깨지면 안 된다. 승인할 때 없는 id면 null로 옮긴다(Step 6).

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`DocumentImportServiceTest`:
```java
@Test
@DisplayName("문서 파일의 topicId가 입문편·심화편 초안 둘 다에 실린다")
void importCarriesTopicIdToBothEditions() {
    // 기존 테스트의 파일 만드는 방식을 따르고 .withTopicId(77L)을 붙인다
    // then: 저장된 초안 2개의 getTopicId() 둘 다 77L
}

@Test
@DisplayName("topicId 칸이 없는 옛 파일은 null로 들어온다")
void oldFileWithoutTopicIdImportsAsNull() { /* 초안 getTopicId() == null */ }
```

`DocumentTopicLinkIntegrationTest` (`@SpringBootTest @Transactional`):
```java
@Test void approveMovesTopicIdToDocument()     // 주제 저장 → 초안.recordTopic(주제id) → approve → document.getTopicId() == 주제id
@Test void approveWithMissingTopicLeavesNull()  // 초안.recordTopic(999_999L) → approve 성공, document.getTopicId() == null
@Test void deletingTopicKeepsDocument()         // 문서.linkTopic(주제id) → 주제 삭제 → flush·clear → 문서가 남고 getTopicId() == null
@Test void topicIdIsNotWrittenWhenNull()        // FileFormats.toJson(... topicId=null) 결과에 "topicId" 문자열이 없다
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `.\gradlew.bat test --tests "*DocumentImportServiceTest" --tests "*DocumentTopicLinkIntegrationTest" --console=plain`
Expected: 컴파일 실패(`withTopicId`, `recordTopic`, `linkTopic` 없음)

- [ ] **Step 4: 엔티티 두 곳에 칸을 더한다**

`Document`: `@Column(name = "topic_id") private Long topicId;` + `linkTopic(Long)`. `update(...)`는 건드리지 않는다. 문서 수정 폼이 topicId를 모르므로 PUT이 연결을 지우면 안 된다.
`GeneratedDocumentDraft`: 같은 칸 + `recordTopic(Long)`.

- [ ] **Step 5: `GeneratedDocumentFile`에 `topicId`를 마지막 칸으로 더한다**

`@JsonInclude(NON_NULL) Long topicId`. 기존 생성자는 전부 `topicId=null`로 위임한다. `withFactFindings`·`withReaderFindings`·`withPolished`는 `topicId`를 그대로 넘긴다. `DocumentFileSync.java:130`의 `new GeneratedDocumentFile(...)`도 `parsed.topicId()`를 넘긴다.

- [ ] **Step 6: 들여오기와 승인에서 옮긴다**

`DocumentImportService.saveOne`: `draft.recordTopic(parsed.topicId());`
`LlmDocumentService.approve`: `adminDocumentService.create(...)` 뒤에 `draft.getTopicId()`가 `topicQueueItemRepository.existsById`면 `documentRepository.findById(created.id())`로 `linkTopic`한다. 없으면 건너뛴다.

- [ ] **Step 7: 테스트 통과 확인**

Run: Step 3과 같은 명령
Expected: PASS. 이어서 `.\gradlew.bat test --console=plain` 전체 PASS

- [ ] **Step 8: 커밋**

```
feat(document): 문서와 초안에 주제 id 칸을 두고 승인할 때 옮긴다
```

---

### Task 2: 배치가 고른 주제 id를 문서 파일에 찍는다

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/support/TopicQueue.java:119-131,222`
- Modify: `src/main/java/project/study/study_project/llm/cli/DocumentBatch.java:182-184`
- Modify: `docs/16-llm-pipeline-operations.md` (API 없이 손으로 쓰는 흐름 절)
- Test: `src/test/java/project/study/study_project/llm/support/TopicQueueTest.java`
- Test: `src/test/java/project/study/study_project/llm/cli/DocumentBatchTopicIdTest.java` (새 파일)

**Interfaces:**
- Consumes: `GeneratedDocumentFile.withTopicId(Long)` (Task 1)
- Produces: `TopicQueue.Picked.id(): Long` (레코드 마지막 칸. 손으로 적은 줄은 null)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TopicQueueTest`:
```java
@Test void pickedCarriesEntryId()        // Entry(id=1137L, ...) 하나 → next().id() == 1137L
@Test void pickedIdIsNullForHandWrittenRow() // Entry(id=null, ...) → next().id() == null
```

`DocumentBatchTopicIdTest`: 배치의 파일 조립 부분을 `static GeneratedDocumentFile documentFile(LocalDate date, DomainCode domain, String model, GeneratedDocumentItem doc, GeneratedDocumentItem adv, TopicQueue.Picked picked)`로 뽑아 시험한다.
```java
@Test void fileCarriesPickedTopicId()   // picked.id()=1137L → file.topicId() == 1137L
@Test void manualTopicWritesNoTopicId()  // picked=null(--topic) → file.topicId() == null
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "*TopicQueueTest" --tests "*DocumentBatchTopicIdTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: `Picked`에 `Long id`를 마지막 칸으로 더한다**

기존 3·4·6인자 생성자는 `id=null`로 위임한다. `TopicQueue.java:222`에서 `picked.id()`(Entry의 id)를 넘긴다.

- [ ] **Step 4: `DocumentBatch`가 `documentFile(...)`로 파일을 만든다**

182~184줄을 `documentFile(...)` 호출로 바꾼다. 안에서 `picked == null ? null : picked.id()`를 `withTopicId`로 싣는다.

- [ ] **Step 5: 손으로 쓰는 흐름 안내에 한 줄 더한다**

`docs/16`에서 서브에이전트가 문서 파일을 쓰는 절에 적는다: 「문서 파일에 `topicId`로 `_topics.json`의 주제 id를 넣는다. 빠뜨리면 지도에서 그 주제가 회색으로 남는다.」

- [ ] **Step 6: 테스트 통과 확인 후 전체 테스트**

Run: Step 2 명령, 이어서 `.\gradlew.bat test --console=plain`
Expected: PASS

- [ ] **Step 7: 커밋**

```
feat(llm): 배치가 고른 주제 id를 문서 파일에 적는다
```

---

### Task 3: 이미 나간 문서 잇기와 손으로 잇기

**Files:**
- Create: `src/main/java/project/study/study_project/admin/service/DocumentTopicBackfillService.java`
- Create: `src/main/java/project/study/study_project/admin/dto/DocumentTopicBackfillResult.java`
- Create: `src/main/java/project/study/study_project/admin/dto/AdminDocumentTopicRequest.java`
- Modify: `src/main/java/project/study/study_project/admin/controller/AdminDocumentController.java`
- Modify: 관리 문서 상세 응답 DTO(`AdminDocumentController` `GET /{id}`가 돌려주는 타입)에 `topicId` 추가
- Modify: `src/main/resources/static/admin/documents.html` (`editDocument` 근처)
- Test: `src/test/java/project/study/study_project/admin/DocumentTopicBackfillIntegrationTest.java` (새 파일)

**Interfaces:**
- Consumes: `Document.linkTopic(Long)` (Task 1), `TopicUsageFile.Usage(id, domain, topic, lastUsedAt, usedCount)`, `@Value("${llm.import.dir:generated}")`
- Produces: `POST /api/admin/documents/topic-backfill` → `ApiResponse<DocumentTopicBackfillResult>`
- Produces: `PUT /api/admin/documents/{id}/topic` body `{"topicId": Long|null}` → `ApiResponse<Void>`
- Produces: `record DocumentTopicBackfillResult(int linked, List<Unlinked> unlinked)`, `record Unlinked(Long topicId, String date, String reason)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

테스트는 `@TempDir`에 `_topic-usage.json`과 `documents/<날짜>.json`을 쓰고 `llm.import.dir`를 그 폴더로 덮는다.
```java
@Test void linksBothEditionsByDateAndSlug()
    // 사용 기록 (주제A, 2026-10-05, NETWORK) + 파일 2026-10-05.json(NETWORK, 입문 slug a, 심화 slug b)
    // 문서 a, b 저장 → POST → linked == 2, 두 문서 getTopicId() == 주제A.id
@Test void missingTopicIsReportedNotFailed()
    // 사용 기록 id=10(테이블에 없음) → linked == 0, unlinked[0].reason == "TOPIC_MISSING"
@Test void domainMismatchIsReported()
    // 파일 분야가 다르면 unlinked reason == "DOMAIN_MISMATCH"
@Test void missingFileOrSlugIsReported()
    // 파일 없음 → "FILE_MISSING", 파일의 slug로 문서가 없음 → "DOCUMENT_MISSING"
@Test void runningTwiceIsHarmless()
    // 두 번 돌려도 오류 없이 같은 연결 상태
@Test void manualLinkAndUnlink()
    // PUT /{id}/topic {"topicId": 주제A.id} → 연결, {"topicId": null} → 해제, 없는 id → 404
@Test void documentPutKeepsTopicLink()
    // 연결 뒤 기존 PUT /api/admin/documents/{id}로 제목만 고쳐도 getTopicId() 그대로
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "*DocumentTopicBackfillIntegrationTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: `DocumentTopicBackfillService.backfill(): DocumentTopicBackfillResult`를 구현한다**

사용 기록 한 줄마다: 주제가 테이블에 있는지 → `documents/<lastUsedAt>.json`이 있는지 → 파일 분야가 기록 분야와 `equals`인지 → 입문편·심화편 slug로 문서를 찾아 `linkTopic`. 걸리는 단계의 이름을 reason으로 남긴다. 이미 같은 주제로 이어진 문서는 다시 써도 같다.

- [ ] **Step 4: 컨트롤러 두 엔드포인트와 상세 응답 `topicId`를 더한다**

`PUT /{id}/topic`은 없는 주제 id면 기존 주제 없음 오류 코드(관리 주제 API가 쓰는 404 코드)를 던진다.

- [ ] **Step 5: 테스트 통과 확인 후 전체 테스트**

Run: Step 2 명령, 이어서 `.\gradlew.bat test --console=plain`
Expected: PASS

- [ ] **Step 6: 관리 문서 화면에 주제 선택 칸을 더한다**

`editDocument(id)` 폼 아래에 「지도 주제」 `<select>`를 둔다. 목록은 기존 관리 주제 대기열 API(개념 주제만)에서 「분야 · 주제 문장」으로 채운다. 맨 위는 「(잇지 않음)」이다. 바꾸면 바로 `PUT /{id}/topic`을 부른다. 문서 저장 버튼과 묶지 않는다.

- [ ] **Step 7: 화면 확인**

인라인 스크립트 문법 검사 후, 8081에 띄워 Playwright로 선택 칸을 바꾸고 새로고침해 값이 남는지 본다. 확인 뒤 앱을 내린다.

- [ ] **Step 8: 커밋**

```
feat(admin): 이미 나간 문서를 주제에 잇는 API와 주제 선택 칸을 더한다
```

---

### Task 4: 지도 API

**Files:**
- Create: `src/main/java/project/study/study_project/map/controller/MapController.java`
- Create: `src/main/java/project/study/study_project/map/service/MapService.java`
- Create: `src/main/java/project/study/study_project/map/dto/MapResponse.java`
- Create: `src/main/java/project/study/study_project/map/repository/MapQueryRepository.java`
- Modify: `src/main/java/project/study/study_project/auth/config/SecurityConfig.java:132` 근처
- Test: `src/test/java/project/study/study_project/map/MapApiIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainCatalog.enabled()`, `DomainCatalog.displayName(code)`, `LearningPathStatsRepository.countProblemsBySlug(slugs)`, `LearningPathStatsRepository.progressBySlug(userId, slugs)`, `ClaudeDocumentGenerator.editionOf(contentMd)`
- Produces: `GET /api/map` → `ApiResponse<MapResponse>`

```java
record MapResponse(List<DomainNode> domains) {
  record DomainNode(String code, String label, List<TopicNode> topics) {}
  record TopicNode(Long id, String title, List<DocLink> documents,
                   long problemTotal, long attempted, long solved) {}
  record DocLink(String slug, String title, String edition) {}   // edition: "BEGINNER" | "ADVANCED"
}
```

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LearningPathApiIntegrationTest`의 `doc()`·`problem()` 도우미 방식을 따른다.
```java
@Test void anonymousSeesTopicsWithZeroProgress()
    // 켜진 분야 주제 1개 + 이어진 문서 1편 + 문제 2개 → 비로그인 GET → problemTotal 2, attempted 0, solved 0
@Test void loggedInSeesOwnProgress()
    // 한 문제 맞힘 → attempted 1, solved 1
@Test void topicWithoutDocumentsHasEmptyDocuments()
    // documents.length() == 0, title은 줄인 주제 문장
@Test void titleUsesBeginnerDocumentTitle()
    // 입문편·심화편 둘 다 이어짐 → title == 입문편 제목
@Test void topicWithManyDocumentsSumsProgress()
    // 문서 3편 각 문제 1개 → documents.length() == 3, problemTotal == 3
@Test void hiddenDocumentsAreLeftOut()
    // 숨긴 문서는 documents에 없고 problemTotal에서도 빠진다
@Test void disabledDomainIsLeftOut()
    // 꺼진 분야 주제는 domains에 없다
@Test void codeTopicsAreLeftOut()
    // kind=CODE 주제는 나오지 않는다
@Test void ordersFollowRegistryAndSortOrder()
    // 분야는 등록부 순서, 주제는 sort_order 순서
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "*MapApiIntegrationTest" --console=plain`
Expected: 404 또는 컴파일 실패

- [ ] **Step 3: `MapQueryRepository`에 두 조회를 둔다**

`List<TopicRow> conceptTopics(Collection<DomainCode> domains)`: `kind=CONCEPT`, 분야 필터, `sort_order, id` 순.
`List<DocRow> linkedDocuments(Collection<Long> topicIds)`: `hidden=false`, `topic_id IN (...)`, 칸은 topicId·slug·title·contentMd.

- [ ] **Step 4: `MapService.map(Long userId): MapResponse`를 구현한다**

쿼리는 네 번이다(주제, 문서, 문제 수, 내 진도). 주제 수와 상관없다. 비로그인이면 진도 쿼리를 건너뛴다.
점 이름 규칙: 입문편 문서가 있으면 그 제목, 없으면 첫 문서 제목, 문서가 없으면 주제 문장을 줄인다. 줄이는 규칙은 ` — `, ` (`, `—` 중 처음 나오는 곳 앞까지 자르고, 그래도 24자를 넘으면 24자 + `…`이다.

- [ ] **Step 5: 컨트롤러와 공개 경로를 더한다**

`SecurityConfig` 132줄의 permitAll 목록에 `"/api/map"`을 더한다.

- [ ] **Step 6: 테스트 통과 확인 후 전체 테스트**

Run: Step 2 명령, 이어서 `.\gradlew.bat test --console=plain`
Expected: PASS

- [ ] **Step 7: 커밋**

```
feat(map): 주제별 문서와 진도를 주는 지도 API를 더한다
```

---

### Task 5: 지도 화면

**Files:**
- Create: `src/main/resources/static/map.html`
- Create: `src/main/resources/static/js/map.js`
- Modify: `src/main/resources/static/js/shell.js:170` 근처 (메뉴 항목)
- Modify: `src/main/resources/static/css/style.css` (끝에 `/* ── 기술 지도 (map.html) ── */` 블록)

**Interfaces:**
- Consumes: `GET /api/map` (Task 4)
- Produces: `function topicState(t): "none" | "unsolved" | "partial" | "done"` (map.js 안, 아래 표 그대로)

| 상태 | 조건 |
|---|---|
| `none` | `documents.length === 0` |
| `unsolved` | 문서 있음, `attempted === 0` 또는 `problemTotal === 0` |
| `partial` | `0 < solved < problemTotal` 이거나 `attempted > 0`이면서 `solved === 0` |
| `done` | `problemTotal > 0 && solved === problemTotal` |

- [ ] **Step 1: 메뉴 항목을 더한다**

`shell.js` 로드맵 항목 바로 뒤: `{ key: "map", label: "기술 지도", href: "/map.html", need: "public", icon: "route" }`. 아이콘은 기존 아이콘 세트에 있는 것 중 고른다.

- [ ] **Step 2: `map.html` 뼈대와 `map.js`를 쓴다**

- 넓은 화면(가로 768px 이상): SVG 하나. 가운데 「백엔드」, 분야 수만큼 원 둘레에 분야 원, 분야마다 바깥쪽 부채꼴로 주제 점. 분야 수는 응답에서 읽는다.
- 좁은 화면: SVG 대신 분야별 `<details>` 목록, 주제마다 상태 색 점 + 이름.
- 점을 누르면 옆 칸(좁은 화면에선 목록 아래)에 주제 이름, `solved / problemTotal`, 입문편·심화편 링크(`/document.html?slug=`)를 띄운다. `none`이면 「준비 중」만 띄운다.
- 비로그인이면 `none`과 그 밖의 두 가지로만 칠하고 「로그인하면 진도가 칠해집니다」 안내를 띄운다.
- 색은 상태별로 CSS 클래스(`map-dot--none` 등)로 주고, 분야색은 기존 분야 배지 색 변수를 쓴다. 다크 테마에서도 보이게 한다.

- [ ] **Step 3: 문법 검사**

Run: `node --check src/main/resources/static/js/map.js` (node가 없으면 Playwright 콘솔 오류 확인으로 대신한다)
Expected: 오류 없음

- [ ] **Step 4: 화면 확인 (8081)**

`verify` 스킬 순서대로 8081에 띄운다. Playwright로 확인한다.
1. 비로그인: 점이 그려지고 안내가 보인다.
2. 확인용 계정으로 한 문제 풀기 → 그 주제 점이 `partial` 또는 `done` 색이다.
3. 점 클릭 → 링크가 문서로 간다.
4. 폭 390px → 접는 목록으로 바뀐다.
5. 콘솔 오류 0건.
확인용 계정·제출을 지우고 앱을 내린다. 확인 뒤 한 줄이라도 고치면 1~5를 다시 한다.

- [ ] **Step 5: 커밋**

```
feat(map): 기술 지도 화면과 메뉴를 더한다
```

---

## 모두 끝난 뒤

- 8080 앱(사용자 것)에는 손대지 않는다. 백필(`POST /api/admin/documents/topic-backfill`)은 사용자가 8080에서 한 번 누르도록 보고에 적는다. 못 이은 목록은 관리 문서 화면에서 손으로 잇는다.
- `docs/IMPROVEMENTS.md`에 "문서와 주제를 잇는 값이 없었다"를 발견·원인·조치·확인 4칸으로 적고 humanize-korean을 돌린다.
