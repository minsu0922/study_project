# 분야 등록부 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Domain` enum을 없애고 `domain_setting` 표를 분야 등록부로 삼아, 관리자 화면에서 분야를 추가·삭제할 수 있게 한다.

**Architecture:** enum을 `DomainCode`(코드 문자열 하나를 감싼 record)로 바꾸고, 내용 표 5개가 `domain_setting(domain)`을 외래키로 가리킨다. 분야 이름·힌트·켜짐·순서는 `DomainCatalog` 한 창구로 읽는다 — 앱은 DB, 배치는 `generated/_domain-settings.json`. 69+52개 파일의 타입 교체는 **동작을 바꾸지 않는 한 태스크**로 몰고, 동작 변경(이름 버그 수정·외래키·추가/삭제·"전체"의 뜻)은 각각 따로 태스크로 뺀다.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Hibernate 6 / MySQL 8 + Flyway / Jackson / QueryDSL / 정적 HTML·JS / JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-09-22-domain-registry-design.md`

## Global Constraints

- 빌드는 `.\gradlew.bat`, 출력이 깨지면 `--console=plain`. Windows·PowerShell. 사용자 경로에 한글이 있다. 로컬 MySQL `localhost:3306/csquiz`.
- **Gradle 빌드 폴더는 메인 체크아웃과 워크트리가 공유한다**(`build.gradle`이 `C:/Users/Public/gradle-builds/study_project`로 고정). 테스트 결과 XML도 거기 있다. 두 곳에서 동시에 빌드하지 않는다.
- 테스트가 앱을 띄우면 `generated/_existing-documents.json`·`_existing-questions.json`·`_rejection-notes.json`·`_domain-settings.json`이 저절로 바뀐다. **절대 커밋하지 않는다.** 파일 이름으로만 `git add`한다(`git add .`·`-A` 금지).
- Flyway 마이그레이션에는 스키마만. 예외는 V20의 고아 코드 채우기 하나(스펙 4.2 — 콘텐츠를 심는 것이 아니라 있는 데이터를 일관되게 만든다).
- 생성자 주입만. 엔티티 setter 금지 — 상태 변경은 이름 있는 메서드로.
- 주석은 "왜"를 적는다(설계 의도·트레이드오프·버린 대안). 이 저장소는 긴 한국어 Javadoc이 기본이다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **API의 JSON 모양은 바뀌지 않는다.** `"domain": "NETWORK"` 그대로. 화면·배치 파일·생성 결과 파일을 고치지 않는다.
- 분야 코드 형식: `^[A-Z][A-Z0-9_]{1,29}$` (스펙 4.1). 30은 컬럼 길이 `VARCHAR(30)`에서 온다.
- **기본 11개의 순서는 지금 enum 선언 순서와 같아야 한다**: `NETWORK, OS, DATABASE, DS_ALGORITHM, SYSTEM_DESIGN, SOFTWARE_ENGINEERING, SECURITY, LANGUAGE_RUNTIME, BACKEND_FRAMEWORK, CLOUD_INFRA, INTEGRATED`. `Domain.values()`의 순서가 날짜 순환의 폴백 순서로 쓰였기 때문이다 — 순서가 바뀌면 그날 나올 분야가 바뀐다.
- 기본 11개의 이름: 네트워크, 운영체제, 데이터베이스, 자료구조·알고리즘, 시스템설계, 소프트웨어공학, 보안, 언어·런타임, 스프링·백엔드, 클라우드·인프라, 통합시나리오.

---

### Task 1: `DomainCode` 값 타입과 변환기 셋

enum을 대신할 타입과, 그 타입이 DB·요청 파라미터·JSON을 오가게 하는 변환기를 만든다. **아직 아무도 쓰지 않는다** — 단독으로 테스트된다.

**Files:**
- Create: `src/main/java/project/study/study_project/global/common/DomainCode.java`
- Create: `src/main/java/project/study/study_project/global/common/DomainCodeAttributeConverter.java`
- Create: `src/main/java/project/study/study_project/global/common/StringToDomainCodeConverter.java`
- Modify: `src/main/java/project/study/study_project/global/config/WebMvcConfig.java` — `addFormatters`에 등록
- Test: `src/test/java/project/study/study_project/global/common/DomainCodeTest.java`

**Interfaces:**
- Produces:
  - `record DomainCode(String value) implements Comparable<DomainCode>`
  - `DomainCode.of(String raw)` → `DomainCode` (형식이 틀리면 `IllegalArgumentException`)
  - `@JsonValue String value()`, `@JsonCreator static DomainCode fromJson(String raw)`
  - `DomainCodeAttributeConverter implements AttributeConverter<DomainCode, String>`, `@Converter(autoApply = true)`
  - `StringToDomainCodeConverter implements Converter<String, DomainCode>`

- [ ] **Step 1: 실패하는 테스트**

```java
package project.study.study_project.global.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"NETWORK", "OS", "DS_ALGORITHM", "A1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ_123"})
    @DisplayName("대문자로 시작하는 대문자·숫자·밑줄 2~30자는 받는다")
    void acceptsValidCodes(String raw) {
        assertThat(DomainCode.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "network", "Network", "1ABC", "_ABC", "AB-C", "AB C",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"})
    @DisplayName("소문자·숫자 시작·기호·1자·31자는 거부한다 — 소문자를 막는 이유는 대소문자를 구별 않는 정렬 규칙")
    void rejectsInvalidCodes(String raw) {
        assertThatThrownBy(() -> DomainCode.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null은 거부한다")
    void rejectsNull() {
        assertThatThrownBy(() -> DomainCode.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSON에는 문자열 하나로 나간다 — 지금 API의 \"domain\": \"NETWORK\" 모양이 그대로여야 한다")
    void serializesAsPlainString() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(om.writeValueAsString(DomainCode.of("NETWORK"))).isEqualTo("\"NETWORK\"");
        assertThat(om.readValue("\"OS\"", DomainCode.class)).isEqualTo(DomainCode.of("OS"));
    }

    @Test
    @DisplayName("JPA 변환기는 코드 글자를 그대로 저장한다 — 컬럼 값이 enum 시절과 같아야 데이터 이전이 없다")
    void attributeConverterRoundTrips() {
        DomainCodeAttributeConverter c = new DomainCodeAttributeConverter();
        assertThat(c.convertToDatabaseColumn(DomainCode.of("SECURITY"))).isEqualTo("SECURITY");
        assertThat(c.convertToEntityAttribute("SECURITY")).isEqualTo(DomainCode.of("SECURITY"));
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*DomainCodeTest" --console=plain` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package project.study.study_project.global.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.regex.Pattern;

/**
 * 분야 코드 — 예전 {@code Domain} enum의 자리를 잇는 값 타입(docs/superpowers/specs/2026-09-22-domain-registry-design.md).
 *
 * <p><b>왜 그냥 String이 아닌가.</b> 분야 코드와 주제 문자열·제목이 모두 String이면 자리를 바꿔
 * 넘겨도 컴파일된다. enum이 막아 주던 그 실수를 이 타입이 계속 막는다.
 *
 * <p><b>형식만 본다, 존재는 안 본다.</b> "그런 분야가 있나"는 DB(domain_setting)의 사실이라
 * 값 타입이 알 수 없다. 존재는 외래키가 최종적으로 막고, 쓰는 경로가 먼저 확인한다.
 *
 * <p><b>대문자만 받는 이유.</b> 컬럼의 정렬 규칙 utf8mb4_0900_ai_ci가 대소문자를 구별하지 않는다.
 * network와 NETWORK를 둘 다 받으면 기본키 충돌이 알 수 없는 오류로 나온다 — 형식에서 막으면
 * 그 경우가 아예 생기지 않는다.
 */
public record DomainCode(String value) implements Comparable<DomainCode> {

    /** 30은 컬럼 길이 VARCHAR(30)에서 온다. 2자 이상인 것은 한 글자 코드가 무엇인지 읽히지 않아서다. */
    private static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    public DomainCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "분야 코드는 영문 대문자로 시작하는 대문자·숫자·밑줄 2~30자여야 합니다: " + value);
        }
    }

    public static DomainCode of(String raw) {
        return new DomainCode(raw);
    }

    /** JSON에는 "NETWORK" 문자열 하나로 — enum 시절 API 모양 그대로. */
    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @JsonCreator
    static DomainCode fromJson(String raw) {
        return of(raw);
    }

    @Override
    public int compareTo(DomainCode other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

`DomainCodeAttributeConverter`는 `@Converter(autoApply = true)`, null은 양방향 모두 null로 통과. `StringToDomainCodeConverter`는 `DomainCode::of`를 부르고, 형식 오류는 그대로 던진다 — 스프링이 `MethodArgumentTypeMismatchException`으로 감싸 400이 된다(enum 변환 실패와 같은 결과). `WebMvcConfig`에 `addFormatters(FormatterRegistry registry) { registry.addConverter(new StringToDomainCodeConverter()); }`를 더한다.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `feat(domain): 분야 코드 값 타입과 변환기`

---

### Task 2: `DefaultDomains`와 `DomainCatalog`

기본 11개를 한곳에 모으고, 분야 정보를 읽는 창구를 만든다. **아직 enum과 함께 있다** — 다음 태스크가 enum을 지울 때 이것들로 옮겨 간다.

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/DefaultDomains.java`
- Create: `src/main/java/project/study/study_project/llm/support/DomainCatalog.java`
- Create: `src/main/java/project/study/study_project/llm/support/DomainEntry.java`
- Create: `src/test/java/project/study/study_project/TestDomains.java`
- Test: `src/test/java/project/study/study_project/llm/support/DefaultDomainsTest.java`

**Interfaces:**
- Consumes: `DomainCode` (Task 1)
- Produces:
  - `DefaultDomains.codes()` → `List<DomainCode>` — **Global Constraints의 순서 그대로**
  - `DefaultDomains.displayName(DomainCode)` → `String` (모르는 코드면 코드 문자열 그대로)
  - `DefaultDomains.isKnown(DomainCode)` → `boolean`
  - `DefaultDomains.hints()` → `Map<DomainCode, String>` — 지금 `DomainHints.builtInMap()`의 4개
  - `record DomainEntry(DomainCode code, boolean enabled, int sortOrder, String displayName, String hint)`
  - `interface DomainCatalog { List<DomainEntry> all(); List<DomainCode> enabled(); String displayName(DomainCode); DomainHints hints(); boolean exists(DomainCode); }`
  - `TestDomains.NETWORK` … `TestDomains.INTEGRATED` — 11개 `DomainCode` 상수(테스트 전용)

- [ ] **Step 1: 실패하는 테스트**

```java
class DefaultDomainsTest {

    @Test
    @DisplayName("기본 11개는 예전 enum 선언 순서 그대로다 — 이 순서가 날짜 순환의 폴백 순서였다")
    void keepsEnumDeclarationOrder() {
        assertThat(DefaultDomains.codes()).extracting(DomainCode::value).containsExactly(
                "NETWORK", "OS", "DATABASE", "DS_ALGORITHM", "SYSTEM_DESIGN", "SOFTWARE_ENGINEERING",
                "SECURITY", "LANGUAGE_RUNTIME", "BACKEND_FRAMEWORK", "CLOUD_INFRA", "INTEGRATED");
    }

    @Test
    @DisplayName("이름은 예전 enum의 displayName 그대로다")
    void keepsEnumDisplayNames() {
        assertThat(DefaultDomains.displayName(DomainCode.of("DS_ALGORITHM"))).isEqualTo("자료구조·알고리즘");
        assertThat(DefaultDomains.displayName(DomainCode.of("BACKEND_FRAMEWORK"))).isEqualTo("스프링·백엔드");
    }

    @Test
    @DisplayName("모르는 코드의 이름은 코드 글자 그대로 — 화면에 빈칸이 뜨는 것보다 낫다")
    void unknownCodeFallsBackToItself() {
        assertThat(DefaultDomains.displayName(DomainCode.of("MESSAGING"))).isEqualTo("MESSAGING");
        assertThat(DefaultDomains.isKnown(DomainCode.of("MESSAGING"))).isFalse();
    }

    @Test
    @DisplayName("내장 힌트 4개가 옮겨 왔다 — 지금 DomainHints.BUILT_IN과 글자까지 같다")
    void carriesBuiltInHints() {
        assertThat(DefaultDomains.hints()).containsOnlyKeys(
                DomainCode.of("BACKEND_FRAMEWORK"), DomainCode.of("LANGUAGE_RUNTIME"),
                DomainCode.of("SOFTWARE_ENGINEERING"), DomainCode.of("SYSTEM_DESIGN"));
        assertThat(DefaultDomains.hints().get(DomainCode.of("BACKEND_FRAMEWORK")))
                .isEqualTo(DomainHints.BUILT_IN.rawHintFor(Domain.BACKEND_FRAMEWORK));
    }
}
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "*DefaultDomainsTest" --console=plain`
- [ ] **Step 3: 구현**
  - 힌트 문자열 4개는 **`DomainHints.builtInMap()`에서 복사해 온다. 다시 타이핑하지 않는다** — 한 글자라도 다르면 프롬프트가 조용히 바뀐다. 이 태스크에서는 `DomainHints`를 건드리지 않는다(다음 태스크가 `BUILT_IN`을 이쪽으로 돌린다).
  - `DefaultDomains`의 클래스 Javadoc에 적을 것: **본코드에서 특정 분야 코드를 아는 곳은 이 클래스 하나뿐이어야 한다**(스펙 8절). 이 목록의 용도는 둘뿐이다 — 빈 DB의 첫 기동 시드, 설정 파일도 yml 목록도 없을 때의 배치 폴백.
  - `DomainCatalog`의 Javadoc에 적을 것: 구현이 둘(앱=DB, 배치=파일)이고, **이름·힌트를 쓰는 순간에 읽는다**. 생성 시점에 한 번 읽으면 재시작해야 반영된다(지난 작업 최종 리뷰 I-2).
  - `TestDomains`는 `public static final DomainCode NETWORK = DomainCode.of("NETWORK");` 식으로 11개.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(domain): 기본 분야 목록과 분야 카탈로그 인터페이스`

---

### Task 3: enum을 `DomainCode`로 교체 — **동작은 바꾸지 않는다**

이 태스크는 크다(본코드 69개·테스트 52개 파일). 대신 규칙이 하나다: **타입만 바꾸고 동작은 한 곳도 바꾸지 않는다.** 증거는 **기존 테스트 전체가 타입만 고친 채 통과하는 것**이다. 리뷰어는 "치환 외의 변경이 있는가"만 본다.

**Files:**
- Delete: `src/main/java/project/study/study_project/global/common/Domain.java`
- Modify: `Domain`을 import하는 본코드 69개·테스트 52개 파일 전부(`grep -rl "global.common.Domain;"`로 목록을 얻는다)
- Modify: `llm/support/DomainHints.java` — 키 타입을 `DomainCode`로
- Modify: `llm/service/DomainSettingService.java` — `syncWithEnum` → `syncWithDefaults`, `implements DomainCatalog` 추가

**Interfaces:**
- Consumes: Task 1·2의 전부
- Produces:
  - 모든 엔티티의 `domain` 필드가 `DomainCode` 타입(`@Enumerated` 제거 — 변환기가 autoApply)
  - `GenerationSchedule.planFor(LocalDate, List<DomainCode>, LocalDate)`, `cellFor(LocalDate, List<DomainCode>)`
  - `DomainHints.of(Map<DomainCode, String>)`, `hintFor(DomainCode)`, `rawHintFor(DomainCode)`, `BUILT_IN = of(DefaultDomains.hints())`
  - `DomainSettingService implements DomainHintsProvider, DomainCatalog` — `syncWithDefaults()`는 **예전 `syncWithEnum`과 똑같이** 동작(기본 11개에 없는 행 생성, 기본에 없는 행 삭제)
  - `DomainSettings`(배치)도 `implements DomainCatalog`

**치환표 — 이 밖의 변경은 하지 않는다:**

| 예전 | 이제 | 동작 |
|---|---|---|
| `Domain` 타입 | `DomainCode` | 같음 |
| `@Enumerated(EnumType.STRING) Domain domain` | `DomainCode domain`(어노테이션 제거) | 같음 — 컬럼 값 동일 |
| `Domain.values()` (12곳) | `DefaultDomains.codes()` | 같음 — **같은 11개, 같은 순서** |
| `domain.getDisplayName()` (12곳) | `DefaultDomains.displayName(domain)` | 같음 — 옛 이름 그대로(버그 포함. Task 4가 고친다) |
| `Domain.valueOf(s)` / `Domain::valueOf` | `DomainCode.of(s)` **+ `DefaultDomains.isKnown` 확인** | 같음 — 예전엔 모르는 이름이 예외였다. `isKnown`이 없으면 형식만 맞는 `FRONTEND_CS`가 통과해 버린다(`DomainSettingsTest.unknownDomainNameIsSkipped`가 잡는다) |
| `Domain.X` 상수(테스트) | `TestDomains.X` | 같음 |
| `Domain.DATABASE`(`PromptEvalCli:378`) | `DomainCode.of("DATABASE")` + "평가 하네스 전용 폴백" 주석 | 같음. 본코드에서 `DefaultDomains` 밖에 코드 글자를 둔 **유일한 예외** — Task 9의 검사가 이것을 허용 목록에 둔다 |
| `EnumMap<Domain, …>` | `HashMap` 또는 `LinkedHashMap` | 순서가 뜻을 갖는 곳이면 `LinkedHashMap` |
| `domain.name()` | `domain.value()` | 같음 |

**지키기 어려운 자리 셋 — 미리 짚는다:**
1. `DomainSettingRepository.findAllDomainNamesNative` / `deleteByDomainNameNative`는 **그대로 둔다.** 고아 행 정리가 아직 필요하다(외래키는 Task 5).
2. JPQL 프로젝션(`ProblemRepository.DomainDifficultyCount.getDomain()` 등)의 반환 타입도 `DomainCode`로. 변환기가 autoApply라 Hibernate가 알아서 변환한다.
3. QueryDSL Q클래스는 빌드가 다시 만든다. `d.domain.eq(domain)`은 그대로 동작한다.
4. `DefaultDomainsTest.carriesBuiltInHints`(Task 2)는 `DomainHints.BUILT_IN`과 비교한다. 이 태스크에서 `BUILT_IN`이 `DefaultDomains.hints()`로 만들어지면 **자기 자신과 비교하는 동어반복**이 된다. 비교 대상을 고정 문자열로 바꾼다 — 각 힌트의 앞 20자 정도(`"Spring DI/IoC·Bean 생명주기"` 등)를 박아 두면, 누가 힌트 글자를 실수로 바꿨을 때 잡힌다.

- [ ] **Step 1:** `grep -rl "global.common.Domain;" src/main src/test`로 목록을 뽑아 보고서에 적는다.
- [ ] **Step 2:** `Domain.java`를 지우고, 컴파일 오류를 길잡이 삼아 치환표대로 고친다. 본코드부터, 그다음 테스트.
- [ ] **Step 3:** `.\gradlew.bat build --console=plain` → **전체 통과.** 실패하는 테스트가 있으면 그 테스트의 단언을 고치지 말고 치환이 동작을 바꾼 곳을 찾는다. 단언을 바꿔야만 통과한다면 멈추고 보고한다.
- [ ] **Step 4:** 본코드에 enum 흔적이 없는지 확인: `grep -rn "Domain\.values\|Domain\.valueOf\|EnumType.STRING) *\n *private DomainCode" src/main` 결과 0, `grep -rln "global.common.Domain;" src` 결과 0.
- [ ] **Step 5: 커밋** — `refactor(domain): Domain enum을 DomainCode 값 타입으로 교체한다(동작 무변경)`

---

### Task 4: 분야 이름을 한곳에서 — 화면마다 이름이 다른 버그

Task 3이 `DefaultDomains.displayName(...)`으로 옮겨 둔 12곳을 `DomainCatalog.displayName(...)`으로 바꾼다. 스펙 6절의 버그다: 설정 화면에서 이름을 바꿔도 이 12곳은 옛 이름을 쓴다.

**Files:**
- Modify: `quiz/service/ProblemListService.java:159`, `review/dto/ReviewListItem.java:43`, `review/dto/ReviewTodayItem.java:56`, `document/dto/DocumentDetailResponse.java:52`, `document/repository/DocumentRepositoryImpl.java:107`, `llm/dto/TopicQueueItemResponse.java:39`, `llm/service/LlmDocumentService.java:269`, `llm/service/LlmProblemService.java:690`, `llm/dto/DomainTitle.java:21`, `llm/client/ClaudeProblemGenerator.java:809`, `llm/client/ClaudeDocumentGenerator.java:1293·1400` (행 번호는 Task 3 이후 달라졌을 수 있다 — `DefaultDomains.displayName` 호출을 찾으면 된다)
- Modify: 두 생성기의 `DomainHintsProvider` → `DomainCatalog` (생성자 셋 중 `@Autowired`는 여전히 **하나**)
- Test: `src/test/java/project/study/study_project/llm/DomainRenameIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainCatalog.displayName(DomainCode)` (Task 2), `DomainSettingService implements DomainCatalog` (Task 3)
- Produces: 본코드에 `DefaultDomains.displayName` 호출이 **0곳**(DefaultDomains 자신과 시드 제외)

**규칙:**
- DTO의 정적 팩터리는 빈에 접근할 수 없다. **부르는 서비스가 이름을 넘긴다**(`String domainLabel` 인자 또는 `DomainCatalog` 인자 — 파일마다 자연스러운 쪽).
- 생성기는 이름을 **쓰는 순간** `catalog.displayName(code)`로 읽는다. 생성 시점에 한 번 읽지 않는다.
- 생성기의 `DomainHintsProvider` 필드를 `DomainCatalog`로 바꾸면 `hints()`도 같은 창구에서 온다. `DomainHintsProvider` 인터페이스는 **남겨 둔다** — 다른 사용처를 확인하고 없으면 이 태스크에서 지운다(지우면 보고에 적는다).

- [ ] **Step 1: 실패하는 테스트**

```java
@SpringBootTest
@Transactional
class DomainRenameIntegrationTest {
    // 이름을 바꾼 "직후"에 각 자리가 새 이름을 보여야 한다. 지금은 필터 목록만 바뀌고 나머지는
    // 옛 이름이다(스펙 6절). 특히 프롬프트 — 힌트는 새것인데 분야 이름이 옛것인 프롬프트가 나간다.

    @Autowired DomainSettingService settings;
    @Autowired ClaudeProblemGenerator problemGenerator;   // 스프링이 만든 빈 — new로 만들면 이 경로를 못 밟는다
    @Autowired ClaudeDocumentGenerator documentGenerator;
    @Autowired ProblemListService problemListService;
    // + 복습 목록·문서 상세를 부르는 서비스

    @Test
    @DisplayName("이름을 바꾸면 모델 프롬프트의 '분야:' 줄이 새 이름이다")
    void promptUsesRenamedDomain() {
        rename(TestDomains.NETWORK, "네트워크 기초");
        String prompt = problemGenerator.buildPrompt(TestDomains.NETWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 1, List.of(), List.of(), null, null);
        assertThat(prompt).contains("분야: 네트워크 기초").doesNotContain("분야: 네트워크\n");
    }

    // 같은 꼴로: 문서 생성기 프롬프트(입문편·심화편 둘 다), 문제 목록 사이드바 라벨,
    // 복습 목록 항목 라벨, 문서 상세 라벨 — 각각 한 테스트.

    private void rename(DomainCode code, String name) {
        DomainSetting s = settings.findAll().stream().filter(r -> r.getDomain().equals(code)).findFirst().orElseThrow();
        settings.edit(code, new AdminDomainSettingRequest(s.isEnabled(), name, s.getHint()));
    }
}
```

`buildPrompt`는 **8인자**다(`ClaudeProblemGeneratorPromptTest`의 `prompt(...)` 헬퍼 참고). 문서 생성기의 프롬프트 메서드 이름·인자는 `ClaudeDocumentGeneratorTest`에서 확인해 맞춘다. 문제 목록·복습·문서 상세는 테스트용 데이터(문제 한 건, 복습 항목 한 건, 문서 한 건)를 테스트 안에서 만든다 — **기존 DB 데이터에 기대지 않는다.**

- [ ] **Step 2: 실패 확인** — 옛 이름이 나와 실패해야 한다. 하나라도 처음부터 통과하면 그 테스트가 엉뚱한 자리를 보고 있는 것이다.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 위 테스트 + `.\gradlew.bat build --console=plain`
- [ ] **Step 5: 커밋** — `fix(domain): 분야 이름을 설정 표 한곳에서 읽는다 — 화면·프롬프트마다 이름이 갈리던 문제`

---

### Task 5: 없는 분야는 들어올 수 없다 — 외래키와 쓰기 경로 확인

**Files:**
- Create: `src/main/resources/db/migration/V20__domain_foreign_keys.sql`
- Modify: `global/exception/ErrorCode.java` — `DOMAIN_003`(400, 등록되지 않은 분야)
- Modify: 쓰기 경로 8곳 — `AdminProblemService.create/update`, `AdminDocumentService.create/update`, `TopicQueueService.add/update`, `LlmProblemService.generate/generateFromDocument`
- Modify: 초안 흡수 2곳 — `DraftImportService`(59행 근처 null 검사 옆), `DocumentImportService`(85행 근처)
- Modify: `DomainSettingServiceTest` — 외래키 때문에 깨지는 테스트 고치기(아래)
- Test: `src/test/java/project/study/study_project/global/DomainForeignKeyIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainCatalog.exists(DomainCode)`
- Produces: `ErrorCode.DOMAIN_003`

- [ ] **Step 1: 마이그레이션**

```sql
-- V20__domain_foreign_keys.sql
-- 분야 등록부(docs/superpowers/specs/2026-09-22-domain-registry-design.md 4.2).
-- enum이 컴파일 때 막던 "없는 분야"를 이제 DB가 막는다. ON DELETE는 기본값(RESTRICT) —
-- 이것이 "내용이 있는 분야는 지울 수 없다"(사용자 결정)를 DB 수준에서 강제한다.
--
-- [외래키 전에 고아 코드를 채우는 이유]
-- V19만 적용되고 앱이 한 번도 안 뜬 DB에서는 domain_setting이 비어 있다. 그때 내용 표에 행이
-- 있으면 외래키 추가가 실패한다. 콘텐츠를 심는 것이 아니라 이미 있는 데이터가 스스로 일관되게
-- 만드는 것이라 "Flyway엔 스키마만"(docs/11)의 취지와 어긋나지 않는다. 채운 행은 꺼져 있고
-- 이름이 코드 글자 그대로라, 관리 화면에서 한눈에 "자동으로 채워진 줄"임이 보인다.
-- 2026-09-22 실측으로 로컬 DB에는 채울 것이 0건이다.
INSERT INTO domain_setting (domain, enabled, sort_order, display_name, hint, created_at, updated_at)
SELECT o.domain, FALSE,
       1000 + ROW_NUMBER() OVER (ORDER BY o.domain),
       o.domain, NULL, NOW(6), NOW(6)
FROM (SELECT domain FROM problem
      UNION SELECT domain FROM document
      UNION SELECT domain FROM generated_problem_draft
      UNION SELECT domain FROM generated_document_draft
      UNION SELECT domain FROM topic_queue) o
WHERE o.domain NOT IN (SELECT domain FROM domain_setting);

ALTER TABLE problem                  ADD CONSTRAINT fk_problem_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE document                 ADD CONSTRAINT fk_document_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_problem_draft  ADD CONSTRAINT fk_gpd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_document_draft ADD CONSTRAINT fk_gdd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE topic_queue              ADD CONSTRAINT fk_topic_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
```

`created_at`·`updated_at` 칸 이름은 V19를 열어 확인하고 맞춘다.

- [ ] **Step 2: 실패하는 테스트**

```java
@SpringBootTest
@Transactional
class DomainForeignKeyIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("다섯 표 모두 domain_setting을 외래키로 가리킨다")
    void foreignKeysExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'domain'
                  AND REFERENCED_TABLE_NAME = 'domain_setting'""", String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "problem", "document", "generated_problem_draft", "generated_document_draft", "topic_queue");
    }

    @Test
    @DisplayName("등록되지 않은 분야로 문제를 넣으면 DB가 거부한다 — 서비스 확인을 빠뜨려도 막히는 최후 방어선")
    void databaseRejectsUnknownDomain() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO topic_queue (domain, topic, sort_order, used_count, created_at) VALUES ('NOPE_X', 't', 0, 0, NOW(6))"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 쓰기 경로 8곳 각각: 등록되지 않은 코드로 부르면 500이 아니라 400(DOMAIN_003).
    // 컨트롤러를 거치는 MockMvc 테스트로 쓴다 — 서비스만 부르면 "500이 아니다"를 못 본다.
}
```

`topic_queue`의 실제 칸 이름은 V10·V11을 열어 맞춘다.

- [ ] **Step 3: 깨지는 기존 테스트 고치기.** `DomainSettingServiceTest`에는 `repository.deleteAll()`로 표를 비우는 테스트가 있다. 로컬 DB에는 문제가 147건 있어 외래키가 그 삭제를 거부한다. **테스트가 전역 표를 비우는 방식을 버리고**, 내용이 없는 새 코드(예: `DomainCode.of("TEST_SYNC_X")`)를 만들어 그 행으로 검증하게 고친다. 무엇을 검증하는지는 그대로 둔다.
- [ ] **Step 4: 구현** — 쓰기 경로 8곳에 저장 전 `if (!catalog.exists(code)) throw new BusinessException(ErrorCode.DOMAIN_003, "등록되지 않은 분야입니다: " + code)`. 초안 흡수 2곳은 기존 null 검사와 같은 꼴로 `IllegalArgumentException`을 던진다 — 러너가 파일 단위로 잡아 건너뛰고 **들여온 것으로 표시하지 않으므로**, 그 분야를 추가한 뒤 다음 기동에 들어온다. 주석에 그 흐름을 적는다.
- [ ] **Step 5: 통과 확인** — 위 테스트 + 전체 빌드
- [ ] **Step 6: 커밋** — `feat(domain): 외래키로 없는 분야를 막고, 쓰기 경로는 먼저 400으로 돌려준다`

---

### Task 6: 등록부 — 분야 추가·삭제 API

**Files:**
- Modify: `llm/service/DomainSettingService.java` — `create`, `delete`, `syncWithDefaults` → `seedIfEmpty`
- Modify: `llm/repository/DomainSettingRepository.java` — 네이티브 고아 질의 둘 **삭제**, `existsByDomain`·`findMaxSortOrder` 추가
- Modify: `llm/service/DomainSettingSyncRunner.java` — `seedIfEmpty` 호출, 주석 갱신
- Modify: `admin/controller/AdminDomainSettingController.java` — `POST`, `DELETE`
- Create: `admin/dto/AdminDomainCreateRequest.java`
- Modify: `global/exception/ErrorCode.java` — `DOMAIN_004`(400 중복 코드), `DOMAIN_005`(400 사용 중)
- Test: `src/test/java/project/study/study_project/admin/AdminDomainRegistryIntegrationTest.java`

**Interfaces:**
- Consumes: 외래키(Task 5), `DomainCatalog`
- Produces:
  - `POST /api/admin/domain-settings` 본문 `{ "code": "MESSAGING", "displayName": "메시징·비동기", "hint": "…" }` → 201, 새 행 반환
  - `DELETE /api/admin/domain-settings/{domain}` → 200 / 400(`DOMAIN_005`, 표별 건수 메시지) / 404
  - `DomainSettingService.create(AdminDomainCreateRequest)` → `DomainSetting`
  - `DomainSettingService.delete(DomainCode)` → `void`
  - `DomainSettingService.seedIfEmpty()` → `void`

**규칙(스펙 5절 그대로):**

| 동작 | 규칙 |
|---|---|
| 추가 | 형식 오류 400 / 코드 중복 400(`DOMAIN_004`) / 이름 필수·40자 / 힌트 500자 / **꺼진 채로** `sortOrder = max + 1` |
| 삭제 | 문제·문서·문제 초안·문서 초안(거절 포함)·주제 대기열 중 한 건이라도 있으면 400(`DOMAIN_005`). 메시지에 **0이 아닌 표만** 이름과 건수 |
| 삭제 — 마지막 켜진 분야 | 400(`DOMAIN_002`, 지금 규칙) |
| 삭제 — 기본 11개 | 특별 취급 없음 |
| 추가·삭제 후 | `DomainSettingChanged` 발행 → 내보내기가 파일을 다시 쓴다 |
| `seedIfEmpty` | 표가 **완전히 비었을 때만** `DefaultDomains`로 11행(켜짐은 yml `batch-domains` 기준 — 지금 `syncWithDefaults`의 초기값 규칙 그대로). 행이 하나라도 있으면 아무것도 안 한다. **남는 행을 지우지 않는다** |

**삭제 메시지 꼴:** `'클라우드·인프라'를 지울 수 없습니다 — 주제 대기열 4건이 이 분야를 씁니다. 순환에서만 빼려면 체크를 끄세요.` 여러 표면 `문제 12건, 주제 대기열 4건`처럼 쉼표로.

- [ ] **Step 1: 실패하는 테스트** — 스펙 10절의 추가·삭제 줄 전부. 핵심 셋:

```java
@Test
@DisplayName("추가한 분야는 꺼진 채 맨 끝에 생기고, 내보낸 파일에도 들어간다")
void createdDomainIsOffAtTheEnd() { /* POST → findAll의 마지막, enabled=false */ }

@Test
@DisplayName("내용이 있으면 지울 수 없다 — 어느 표에 몇 건인지 알려 준다")
void refusesDeleteWhenInUse() {
    // 새 코드를 만들고 주제 대기열에 한 건 넣은 뒤 DELETE → 400, 메시지에 "주제 대기열 1건"
}

@Test
@DisplayName("사람이 추가한 분야는 재시작해도 남는다 — 기본 목록에 없다고 지우던 예전 동기화가 사라졌다")
void seedDoesNotDeleteUserDomains() {
    // create(MESSAGING) → seedIfEmpty() → MESSAGING 행이 그대로
}
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → 통과 + 전체 빌드
- [ ] **Step 5: 커밋** — `feat(admin): 분야 추가·삭제 API — 내용이 있는 분야는 지울 수 없다`

---

### Task 7: "전체 분야"의 뜻 — 앱과 배치

Task 3이 `Domain.values()`를 `DefaultDomains.codes()`로 옮겨 두었다. 이제 분야를 화면에서 더할 수 있으니 "전체"는 **등록부 전체**여야 한다(스펙 4.4).

**Files:**
- Modify: `llm/service/LlmProblemService.java`, `admin/service/AdminBatchService.java`, 그 밖에 `DefaultDomains.codes()`를 "전체"의 뜻으로 부르는 앱 쪽 자리 → `catalog.all()` 코드 목록
- Modify: `llm/support/GenerationSchedule.java` — 빈 목록을 넓히는 `candidates(...)`가 `DefaultDomains.codes()`를 쓰지 않게: **부르는 쪽이 넓힌 목록을 넘긴다**
- Modify: `llm/cli/DraftGeneratorCli.java` — "전체" = 파일 → yml → `DefaultDomains`, `--domain` 검증
- Modify: `llm/support/DomainSettings.java`, `llm/support/TopicQueue.java` — `DefaultDomains.isKnown` → "파일에 있나"
- Test: `DraftGeneratorCliTest`, `DomainSettingsTest`에 추가

**규칙:**

| 어디서 | "전체" = | 모르는 코드 |
|---|---|---|
| 앱 | 설정 표의 모든 행 | 쓰기 경로는 Task 5가 막는다 |
| 배치 — 설정 파일 있음 | 파일의 모든 항목 | 파일이 등록부다 — **파일에 있으면 유효** |
| 배치 — 파일 없음 | yml `batch-domains` | yml 목록에 없으면 모름 |
| 배치 — 둘 다 없음 | `DefaultDomains.codes()` | 기본 11개에 없으면 모름 |
| `--domain=X` | 위에서 정한 "전체"에 X가 없으면 **실패**(돈이 드는 실행을 모르는 분야로 돌리지 않는다) | |

- [ ] **Step 1: 실패하는 테스트**

```java
@Test
@DisplayName("파일에 새 분야가 있으면 배치가 그 분야를 안다 — 화면에서 추가한 분야가 배치까지 닿는다")
void fileDefinedDomainIsKnownToBatch(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
            {"note":"","domains":[
              {"domain":"MESSAGING","enabled":true,"sortOrder":0,"displayName":"메시징·비동기","hint":"큐·이벤트"},
              {"domain":"OS","enabled":true,"sortOrder":1,"displayName":"운영체제","hint":null}
            ]}""");
    DomainSettings s = DomainSettings.read(dir);
    assertThat(s.enabled()).containsExactly(DomainCode.of("MESSAGING"), DomainCode.of("OS"));
    assertThat(s.displayName(DomainCode.of("MESSAGING"))).isEqualTo("메시징·비동기");
}

@Test
@DisplayName("--domain에 전체에 없는 분야를 주면 요금을 쓰기 전에 실패한다")
void unknownManualDomainFailsBeforeSpending() { /* DraftGeneratorCliTest의 기존 실패 경로 테스트 꼴 */ }
```

예전 `DomainSettingsTest.unknownDomainNameIsSkipped`(`FRONTEND_CS`를 버린다)는 **뜻이 바뀐다**: 이제 파일에 있으면 유효하다. 그 테스트는 "형식이 틀린 코드(`frontend-cs` 같은)만 버린다"로 고쳐 쓰고, 왜 바뀌었는지 주석에 남긴다.

- [ ] **Step 2~4:** 실패 확인 → 구현 → 통과 + 전체 빌드. `DraftGeneratorCliTest`의 기존 88개가 전부 통과해야 한다.
- [ ] **Step 5: 커밋** — `feat(llm): "전체 분야"를 등록부 기준으로 — 화면에서 추가한 분야가 배치까지 닿는다`

---

### Task 8: 화면 — 분야 추가 폼과 삭제

**Files:**
- Modify: `src/main/resources/static/admin/settings.html`
- Modify: `src/main/resources/static/css/style.css` — 추가 폼 한 줄

**규칙(스펙 9절):**
- 목록 카드 맨 위에 한 줄 폼: 코드 입력(`placeholder="MESSAGING"`, 입력 중 대문자로 바꿔 보여 준다) · 이름 입력 · "추가" 버튼. 힌트는 폼에 없다 — 추가한 줄의 편집칸에서 쓴다.
- 추가에 성공하면 목록을 다시 불러오고 **새 줄의 편집칸을 자동으로 펼쳐** 힌트 칸에 초점을 둔다. 저장 안내(커밋 필요)도 띄운다.
- 형식 오류는 서버에 보내기 전에 화면에서 막는다(같은 정규식). 서버 오류(중복 등)는 기존 `showError`로.
- **삭제 버튼은 편집칸 안에만** 둔다. 누르면 `confirm`으로 한 번 묻는다. 거부되면 서버 메시지를 그대로 보인다.
- 한 줄 목록·오른쪽 미리보기(2026-09-22 개편)의 배치를 깨지 않는다.

- [ ] **Step 1:** 구현
- [ ] **Step 2: 눈으로 확인.** 관리 화면은 관리자 쿠키가 없으면 404를 낸다(`AdminGateFilter`). 관리자 비밀번호는 환경변수(`ADMIN_PASSWORD`)라 **대화·보고서에 적지 않는다.** 2026-09-22 개편 때 쓴 방법을 쓴다: 8081로 따로 띄우고(`--args='--server.port=8081'`, 8080의 사용자 앱은 건드리지 않는다), Playwright에서 `page.route`로 정적 파일은 디스크에서, `/api/admin/**`는 가짜 응답으로 채우고, 역할만 ADMIN인 서명 없는 토큰을 localStorage에 넣는다. 확인할 것: 추가 → 새 줄이 맨 끝·꺼짐·편집칸 펼침 / 형식 오류가 화면에서 막힘 / 삭제 거부 메시지 표시 / 1400px에서 한 화면 유지 / 390px 가로 넘침 없음.
- [ ] **Step 3:** 전체 빌드(`AdminPageStructureTest`가 관리 화면 구조를 검사한다)
- [ ] **Step 4: 커밋** — `feat(admin): 분야 설정 화면에서 분야 추가·삭제`

---

### Task 9: 문서와 마지막 점검

**Files:**
- Modify: `docs/02-domain-enums.md` — 분야 절을 "등록부"로 고쳐 쓴다. 난이도·문제 유형은 여전히 enum이라 문서는 지우지 않는다
- Modify: `docs/21-domain-settings.md` — 추가·삭제·외래키·"전체"의 뜻
- Modify: `docs/README.md` — 02의 한 줄 요약에서 "도메인(11)" 수정
- Modify: `application.yml` — `batch-domains` 주석에 "기본 11개가 아니라 등록부" 반영

- [ ] **Step 1:** 문서
- [ ] **Step 2: 스펙 8절 점검** — 본코드에서 분야 코드 글자를 아는 곳이 `DefaultDomains`와 `PromptEvalCli`(Task 3의 허용 예외)뿐인지:

```bash
grep -rnE '"(NETWORK|OS|DATABASE|DS_ALGORITHM|SYSTEM_DESIGN|SOFTWARE_ENGINEERING|SECURITY|LANGUAGE_RUNTIME|BACKEND_FRAMEWORK|CLOUD_INFRA|INTEGRATED)"' src/main/java
```

결과가 이 두 파일 밖에 있으면 고친다.
- [ ] **Step 3:** `.\gradlew.bat build --console=plain` → 통과
- [ ] **Step 4: 커밋** — `docs: 분야 등록부 문서화`

---

## 마무리 점검

- [ ] 전체 빌드 통과
- [ ] 로컬 DB에 V20이 적용됐고 외래키 5개가 있다(`information_schema`)
- [ ] 화면에서 분야 하나 추가 → 켜기 → `generated/_domain-settings.json`에 들어감 → 삭제 → 파일에서 빠짐
