# 분야 설정 관리창 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배치가 도는 분야·순환 순서와 분야별 경계 설명(`domainHint`)을 관리자 화면에서 고치고, 그 설정이 DB → 파일 → 클라우드 배치까지 흐르게 한다.

**Architecture:** DB(`domain_setting`)가 원본이고, `generated/_domain-settings.json`이 사본이다. 앱은 DB를 읽고, DB가 없는 GitHub Actions 배치는 그 파일을 읽는다. 주제 대기열(`TopicQueue`/`TopicQueueExporter`/`TopicQueueSyncRunner`)이 이미 쓰는 구조를 그대로 따른다. `Domain` enum은 식별자로 남는다.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / MySQL 8 + Flyway / Jackson / 정적 HTML·JS / JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-09-21-domain-settings-cms-design.md`

## Global Constraints

- 빌드는 `.\gradlew.bat`, 출력이 깨지면 `--console=plain`을 붙인다. 개발 PC는 Windows이고 사용자 경로에 한글이 있다.
- Flyway 마이그레이션에는 **스키마만** 넣는다. 콘텐츠·설정 행은 기동 시 러너가 채운다(docs/11).
- 엔티티는 setter를 열지 않는다. 상태 변경은 이름 있는 메서드로 한다(`TopicQueueItem.edit`·`changeOrder`가 본보기).
- 생성자 주입만 쓴다. 필드 주입 금지.
- enum은 `@Enumerated(EnumType.STRING)`으로 저장한다. ORDINAL 금지.
- 주석에는 "무엇을"이 아니라 **"왜 이렇게 했는지"**를 남긴다(설계 의도·트레이드오프·버린 대안).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`를 붙인다.
- 테이블 이름은 `domain_setting`, 파일 이름은 `_domain-settings.json`으로 고정한다. 두 이름은 여러 태스크가 나눠 쓰므로 철자를 바꾸지 않는다.
- 현재 `Domain` 상수는 11개다: `NETWORK`, `OS`, `DATABASE`, `DS_ALGORITHM`, `SYSTEM_DESIGN`, `SOFTWARE_ENGINEERING`, `SECURITY`, `LANGUAGE_RUNTIME`, `BACKEND_FRAMEWORK`, `CLOUD_INFRA`, `INTEGRATED`.
- 폴백 기본값(`application.yml`의 `llm.generation.batch-domains`)은 지우지 않는다: `NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,LANGUAGE_RUNTIME,BACKEND_FRAMEWORK`

---

### Task 1: 내장 힌트를 한곳으로 모은다 (`DomainHints`)

지금 분야 경계 설명이 `ClaudeProblemGenerator`와 `ClaudeDocumentGenerator`의 `switch` 두 벌에 **거의 같은 문자열로 복사돼** 있다. 설정으로 빼기 전에 먼저 한곳으로 모은다. 이 값이 나중에 DB 행의 초기값이 된다.

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/DomainHints.java`
- Test: `src/test/java/project/study/study_project/llm/support/DomainHintsTest.java`

**Interfaces:**
- Consumes: `project.study.study_project.global.common.Domain`
- Produces:
  - `DomainHints.BUILT_IN` — `DomainHints` 상수
  - `DomainHints.of(Map<Domain, String> hints)` → `DomainHints`
  - `DomainHints.hintFor(Domain domain)` → `String` (없으면 `""`, 있으면 `" (…)"` 꼴로 괄호까지 붙여 반환)
  - `DomainHints.rawHintFor(Domain domain)` → `String` (괄호 없는 원문, 화면·파일용. 없으면 `""`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분야 경계 설명의 <b>단일 원본</b>을 지킨다.
 *
 * <p>예전에는 이 문자열이 ClaudeProblemGenerator와 ClaudeDocumentGenerator의 switch 두 벌에
 * 복사돼 있었다. 한쪽만 고치면 "문서는 절차를 썼는데 문제는 부하를 묻는" 어긋남이 생기고,
 * 그건 근거 문서를 준 목적을 통째로 무너뜨린다.
 */
class DomainHintsTest {

    @Test
    @DisplayName("내장 힌트는 프롬프트에 실릴 꼴로 — 앞 공백과 괄호까지 붙여 준다")
    void builtInHintIsWrappedForPrompt() {
        assertThat(DomainHints.BUILT_IN.hintFor(Domain.BACKEND_FRAMEWORK))
                .startsWith(" (")
                .endsWith(")")
                .contains("Spring DI/IoC");
    }

    @Test
    @DisplayName("힌트가 없는 분야는 빈 문자열 — 지금까지와 같다")
    void missingHintIsEmpty() {
        assertThat(DomainHints.BUILT_IN.hintFor(Domain.NETWORK)).isEmpty();
        assertThat(DomainHints.BUILT_IN.rawHintFor(Domain.NETWORK)).isEmpty();
    }

    @Test
    @DisplayName("설정에서 받은 힌트가 내장값을 대신한다")
    void givenHintsReplaceBuiltIn() {
        DomainHints hints = DomainHints.of(Map.of(Domain.NETWORK, "TCP·HTTP 위주"));

        assertThat(hints.rawHintFor(Domain.NETWORK)).isEqualTo("TCP·HTTP 위주");
        assertThat(hints.hintFor(Domain.NETWORK)).isEqualTo(" (TCP·HTTP 위주)");
        // 설정에 없는 분야는 내장값으로 돌아가지 않는다 — 화면에서 지운 힌트가 되살아나면
        // "지웠는데 왜 그대로인가"가 되고, 그 혼란이 힌트를 못 믿게 만든다.
        assertThat(hints.hintFor(Domain.BACKEND_FRAMEWORK)).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열·공백만 있는 힌트는 없는 것으로 본다")
    void blankHintCountsAsMissing() {
        DomainHints hints = DomainHints.of(Map.of(Domain.OS, "   "));

        assertThat(hints.hintFor(Domain.OS)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainHintsTest" --console=plain`
Expected: 컴파일 실패 — `DomainHints` 없음

- [ ] **Step 3: 구현한다**

`ClaudeProblemGenerator.domainHint`와 `ClaudeDocumentGenerator.domainHint`의 `switch` 본문 문자열을 그대로 옮긴다. 괄호와 앞 공백은 `hintFor`가 붙이므로 **원문에서는 뺀다**.

```java
package project.study.study_project.llm.support;

import project.study.study_project.global.common.Domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 분야 경계 설명 — 모델에게 "이 분야는 어디까지인가"를 알려 주는 한 줄.
 *
 * <h2>왜 값 객체로 뺐나</h2>
 *
 * <p>같은 문자열이 문제 생성기와 문서 생성기의 {@code switch} 두 벌에 복사돼 있었다.
 * 둘이 어긋나면 <b>문서는 개발 절차를 썼는데 문제는 부하를 묻는</b> 날이 오고, 근거 문서를
 * 준 목적이 통째로 무너진다. 게다가 이 값은 곧 관리자 화면에서 고칠 값이라, "코드에 박힌
 * 기본값"과 "설정에서 온 값"을 같은 모양으로 다룰 자리가 필요했다.
 *
 * <h2>설정값이 오면 내장값은 쓰지 않는다</h2>
 *
 * <p>{@link #of}로 만든 것은 <b>그 맵이 전부</b>다. 빠진 분야를 {@link #BUILT_IN}으로 메우지
 * 않는다 — 화면에서 지운 힌트가 조용히 되살아나면 "지웠는데 왜 그대로인가"가 되고,
 * 그 순간부터 사람은 이 화면을 믿지 않는다. 내장값은 <b>행을 처음 만들 때의 초기값</b>으로만
 * 쓰인다({@code DomainSettingSyncRunner}).
 */
public final class DomainHints {

    /** 2026-09-21까지 코드에 박혀 있던 값. 설정 행의 초기값으로 쓰인다. */
    public static final DomainHints BUILT_IN = of(builtInMap());

    private final Map<Domain, String> hints;

    private DomainHints(Map<Domain, String> hints) {
        this.hints = hints;
    }

    public static DomainHints of(Map<Domain, String> hints) {
        EnumMap<Domain, String> copy = new EnumMap<>(Domain.class);
        hints.forEach((domain, hint) -> {
            if (hint != null && !hint.isBlank()) {
                copy.put(domain, hint.trim());
            }
        });
        return new DomainHints(copy);
    }

    /** 프롬프트에 그대로 이어 붙일 꼴 — 앞 공백과 괄호까지 붙여서 준다. 없으면 빈 문자열. */
    public String hintFor(Domain domain) {
        String raw = rawHintFor(domain);
        return raw.isEmpty() ? "" : " (" + raw + ")";
    }

    /** 괄호 없는 원문 — 화면 입력칸과 내보내기 파일이 쓴다. */
    public String rawHintFor(Domain domain) {
        return hints.getOrDefault(domain, "");
    }

    private static Map<Domain, String> builtInMap() {
        EnumMap<Domain, String> map = new EnumMap<>(Domain.class);
        map.put(Domain.BACKEND_FRAMEWORK,
                "Spring DI/IoC·Bean 생명주기·AOP·@Transactional 전파·MVC 흐름, "
                        + "JPA 영속성 컨텍스트·지연 로딩·N+1, 커넥션 풀·서블릿 컨테이너. "
                        + "순수 JVM/GC 주제는 제외");
        map.put(Domain.LANGUAGE_RUNTIME,
                "Java 언어·JVM 내부: 메모리 구조·GC·클래스로딩·동시성. "
                        + "Spring/JPA 등 프레임워크 주제는 제외");
        map.put(Domain.SOFTWARE_ENGINEERING,
                "요구사항 분석·UML·디자인 패턴·테스트 기법·형상관리·개발방법론. "
                        + "즉 사람이 코드를 만들고 관리하는 절차. "
                        + "부하·확장·장애처럼 돌아가는 시스템을 다루는 주제는 제외");
        map.put(Domain.SYSTEM_DESIGN,
                "돌아가는 시스템의 구조: 부하 분산·캐시 계층·확장·장애 대응·데이터 흐름. "
                        + "요구사항·UML·테스트 기법 같은 개발 절차 주제는 제외");
        return map;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainHintsTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/support/DomainHints.java src/test/java/project/study/study_project/llm/support/DomainHintsTest.java
git commit -m "refactor(llm): 분야 경계 설명을 DomainHints 한곳으로 모은다"
```

---

### Task 2: 생성기 둘이 `DomainHints`를 받는다

`switch` 두 벌을 지우고 주입받은 값을 쓴다. 생성자에 인자가 하나 늘지만, **기존 한 인자 생성자를 남겨** 호출부 여섯 곳이 한꺼번에 깨지지 않게 한다.

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/client/ClaudeProblemGenerator.java:91-93`, 그리고 `domainHint` 메서드
- Modify: `src/main/java/project/study/study_project/llm/client/ClaudeDocumentGenerator.java` 의 `domainHint` 메서드
- Test: `src/test/java/project/study/study_project/llm/client/ClaudeProblemGeneratorPromptTest.java` (기존 파일에 추가)

**Interfaces:**
- Consumes: `DomainHints.BUILT_IN`, `DomainHints.hintFor(Domain)` (Task 1)
- Produces:
  - `new ClaudeProblemGenerator(String model)` — 내장 힌트로 (기존 호출부가 그대로 쓴다)
  - `new ClaudeProblemGenerator(String model, DomainHints hints)`
  - `new ClaudeDocumentGenerator(String model)` / `new ClaudeDocumentGenerator(String model, DomainHints hints)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ClaudeProblemGeneratorPromptTest`에 아래 두 개를 더한다. 이 파일은 이미 프롬프트 문자열을 검사하고 있으므로 같은 방식으로 붙인다(기존 테스트가 프롬프트를 꺼내 쓰는 방법을 먼저 읽고 그대로 따른다).

```java
    @Test
    @DisplayName("주입한 힌트가 프롬프트에 실린다 — 재배포 없이 경계를 고칠 수 있어야 한다")
    void injectedHintAppearsInPrompt() {
        ClaudeProblemGenerator custom = new ClaudeProblemGenerator(
                "claude-opus-5", DomainHints.of(Map.of(Domain.NETWORK, "TCP 혼잡 제어 위주")));

        String prompt = custom.buildPrompt(Domain.NETWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 1, null, List.of());

        assertThat(prompt).contains("(TCP 혼잡 제어 위주)");
    }

    @Test
    @DisplayName("힌트를 안 주면 내장값으로 돈다 — 설정이 없는 환경에서도 지금과 같아야 한다")
    void fallsBackToBuiltInHints() {
        String prompt = generator.buildPrompt(Domain.BACKEND_FRAMEWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 1, null, List.of());

        assertThat(prompt).contains("Spring DI/IoC");
    }
```

> `buildPrompt`의 정확한 이름·인자는 기존 테스트가 부르는 그대로 맞춘다. 이 테스트를 쓰기 전에 `ClaudeProblemGeneratorPromptTest`를 열어 실제 호출 꼴을 확인하고, 다르면 **기존 호출 꼴에 맞춰** 위 두 테스트를 고친다. 프로덕션 메서드의 시그니처를 테스트에 맞춰 바꾸지 않는다.

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*ClaudeProblemGeneratorPromptTest" --console=plain`
Expected: 컴파일 실패 — 두 인자 생성자 없음

- [ ] **Step 3: 구현한다**

`ClaudeProblemGenerator`:

```java
    private final String model;

    /**
     * 분야 경계 설명. 예전에는 {@code switch}로 코드에 박혀 있었는데, 그 한 줄이 생성 품질을
     * 직접 흔드는 값인데도 고치려면 재배포가 필요했다. 이제 관리자 화면에서 고치고
     * {@code generated/_domain-settings.json}으로 배치까지 나른다(docs/21).
     */
    private final DomainHints domainHints;

    /** 설정을 못 읽는 자리(테스트·평가 CLI)에서 쓰는 생성자 — 내장 힌트로 돈다. */
    public ClaudeProblemGenerator(@Value("${llm.generation.model:claude-opus-4-8}") String model) {
        this(model, DomainHints.BUILT_IN);
    }

    public ClaudeProblemGenerator(String model, DomainHints domainHints) {
        this.model = model;
        this.domainHints = domainHints;
    }
```

`domainHint(Domain)` 메서드는 지우고, 부르던 자리를 `domainHints.hintFor(domain)`으로 바꾼다. `ClaudeDocumentGenerator`도 똑같이 한다.

> 주의: 한 인자 생성자에만 `@Value`가 붙어야 한다. 둘 다 붙으면 스프링이 어느 것을 쓸지 모른다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*ClaudeProblemGeneratorPromptTest" --tests "*ClaudeDocumentGeneratorTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/client/ src/test/java/project/study/study_project/llm/client/
git commit -m "refactor(llm): 생성기가 분야 경계 설명을 주입받는다"
```

---

### Task 3: 테이블과 엔티티

**Files:**
- Create: `src/main/resources/db/migration/V19__domain_setting.sql`
- Create: `src/main/java/project/study/study_project/llm/domain/DomainSetting.java`
- Create: `src/main/java/project/study/study_project/llm/repository/DomainSettingRepository.java`
- Test: `src/test/java/project/study/study_project/llm/domain/DomainSettingTest.java`

**Interfaces:**
- Produces:
  - `DomainSetting.initial(Domain domain, boolean enabled, int sortOrder, String displayName, String hint)` → `DomainSetting`
  - `getDomain()` / `isEnabled()` / `getSortOrder()` / `getDisplayName()` / `getHint()`
  - `edit(boolean enabled, String displayName, String hint)` → `void`
  - `changeOrder(int sortOrder)` → `void`
  - `DomainSettingRepository.findAllByOrderBySortOrderAsc()` → `List<DomainSetting>`
  - `DomainSettingRepository.findByDomain(Domain domain)` → `Optional<DomainSetting>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package project.study.study_project.llm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainSettingTest {

    @Test
    @DisplayName("고치면 값이 바뀐다 — 순서는 여기서 못 바꾼다")
    void editChangesEditableFields() {
        DomainSetting setting = DomainSetting.initial(Domain.NETWORK, true, 0, "네트워크", null);

        setting.edit(false, "네트워크 기초", "TCP 위주");

        assertThat(setting.isEnabled()).isFalse();
        assertThat(setting.getDisplayName()).isEqualTo("네트워크 기초");
        assertThat(setting.getHint()).isEqualTo("TCP 위주");
        assertThat(setting.getSortOrder()).isZero();   // edit은 순서를 건드리지 않는다
    }

    @Test
    @DisplayName("공백만 있는 힌트는 null로 저장한다 — 빈 칸과 '안 쓴 칸'을 한 가지로 만든다")
    void blankHintBecomesNull() {
        DomainSetting setting = DomainSetting.initial(Domain.OS, true, 1, "운영체제", "   ");

        assertThat(setting.getHint()).isNull();
    }

    @Test
    @DisplayName("화면 이름은 비울 수 없다 — 비면 목록에서 그 줄이 사라진 것처럼 보인다")
    void displayNameIsRequired() {
        DomainSetting setting = DomainSetting.initial(Domain.OS, true, 1, "운영체제", null);

        assertThatThrownBy(() -> setting.edit(true, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 마이그레이션과 엔티티를 쓴다**

```sql
-- V19__domain_setting.sql
-- 분야 설정 — 배치가 도는 분야·순환 순서와 화면 이름·경계 설명(docs/21).
--
-- [왜 행을 여기서 넣지 않나]
-- 마이그레이션에는 스키마만 둔다(docs/11). 더 큰 이유는 enum Domain에 상수를 더하거나
-- 뺐을 때다 — 시드를 여기 두면 그때마다 마이그레이션을 새로 써야 하고, 깜빡하면
-- 새 분야에 설정 행이 없어 배치에서 조용히 빠진다. 대신 기동 시
-- DomainSettingSyncRunner가 enum을 훑어 없는 행을 만들고 사라진 행을 지운다.
--
-- [왜 숫자 id가 없나]
-- 행 수가 enum 상수 수로 고정이고, 읽는 쪽이 늘 "NETWORK의 설정"을 찾지 "3번 행"을
-- 찾지 않는다. enum 이름을 그대로 PK로 쓰면 조인 없이도 뜻이 읽힌다.
CREATE TABLE domain_setting (
    domain       VARCHAR(30) NOT NULL,                 -- enum Domain 상수명
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,    -- 배치 자동 선택 후보인가
    sort_order   INT         NOT NULL,                 -- 날짜 순환 순서
    display_name VARCHAR(40) NOT NULL,                 -- 화면에 뜨는 이름
    hint         TEXT        NULL,                     -- 모델에게 주는 경계 설명
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

엔티티는 `TopicQueueItem`의 꼴을 따른다 — `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, setter 없음, `@EntityListeners(AuditingEntityListener.class)`. `edit`은 `displayName`이 비면 `IllegalArgumentException`을 던지고, `hint`는 `isBlank()`면 `null`로 저장한다. 순서는 `changeOrder`만 바꾼다(`TopicQueueItem.edit`이 순서를 일부러 안 받는 것과 같은 이유 — 주석에 그 이유를 적는다).

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 마이그레이션이 실제로 도는지 본다**

Run: `.\gradlew.bat test --tests "*AdminBatchStatusIntegrationTest" --console=plain`
Expected: PASS (스프링이 뜨면서 V19가 적용된다. `ddl-auto: validate`라 엔티티와 테이블이 어긋나면 여기서 부팅이 깨진다)

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/db/migration/V19__domain_setting.sql src/main/java/project/study/study_project/llm/domain/DomainSetting.java src/main/java/project/study/study_project/llm/repository/DomainSettingRepository.java src/test/java/project/study/study_project/llm/domain/DomainSettingTest.java
git commit -m "feat(llm): 분야 설정 테이블과 엔티티"
```

---

### Task 4: 기동 시 enum과 행을 맞춘다

**Files:**
- Create: `src/main/java/project/study/study_project/llm/service/DomainSettingService.java`
- Create: `src/main/java/project/study/study_project/llm/service/DomainSettingSyncRunner.java`
- Test: `src/test/java/project/study/study_project/llm/service/DomainSettingServiceTest.java`

**Interfaces:**
- Consumes: `DomainSettingRepository` (Task 3), `DomainHints.BUILT_IN` (Task 1)
- Produces:
  - `DomainSettingService.syncWithEnum()` → `void`
  - `DomainSettingService.batchDomains()` → `List<Domain>` (`enabled`인 것만, `sortOrder` 순)
  - `DomainSettingService.hints()` → `DomainHints`
  - `DomainSettingService.findAll()` → `List<DomainSetting>` (`sortOrder` 순)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 행이 enum을 따라오는지를 지킨다.
 *
 * <p>여기서 틀리면 조용히 틀린다. 새 분야에 행이 없으면 배치 후보에서 빠지는데, 화면에는
 * 그 분야가 멀쩡히 보이므로 "왜 저기만 문제가 안 늘지"로만 드러난다.
 */
@SpringBootTest
@Transactional
class DomainSettingServiceTest {

    @Autowired DomainSettingService service;
    @Autowired DomainSettingRepository repository;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("enum에 있는데 행이 없으면 만든다 — 폴백 목록에 든 분야는 켠 채로")
    void createsMissingRows() {
        repository.deleteAll();

        service.syncWithEnum();

        assertThat(repository.count()).isEqualTo(Domain.values().length);
        assertThat(repository.findByDomain(Domain.NETWORK)).get()
                .extracting(DomainSetting::isEnabled).isEqualTo(true);
        // CLOUD_INFRA는 폴백 목록에 없다 → 꺼진 채로 태어난다
        assertThat(repository.findByDomain(Domain.CLOUD_INFRA)).get()
                .extracting(DomainSetting::isEnabled).isEqualTo(false);
    }

    @Test
    @DisplayName("행이 있는데 enum에 없으면 지운다 — 지운 분야가 화면에 남지 않는다")
    void deletesOrphanRows() {
        service.syncWithEnum();
        // enum에 없는 값은 JPA로 저장할 수 없으므로 네이티브로 심는다. 2026-09-21에 실제로
        // FRONTEND_CS를 지웠고, 앞으로도 enum에서 빠지는 이름이 나온다 — 그때 이 행이
        // 남아 있으면 화면 목록에 뜻 없는 줄이 하나 붙는다.
        em.createNativeQuery("""
                INSERT INTO domain_setting
                    (domain, enabled, sort_order, display_name, hint, created_at, updated_at)
                VALUES ('FRONTEND_CS', true, 99, '프론트엔드CS', NULL, NOW(6), NOW(6))
                """).executeUpdate();
        em.flush();
        em.clear();

        service.syncWithEnum();

        Long left = (Long) em.createNativeQuery(
                        "SELECT COUNT(*) FROM domain_setting WHERE domain = 'FRONTEND_CS'")
                .getSingleResult();
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("이미 있는 행은 건드리지 않는다 — 기동할 때마다 화면 설정이 초기화되면 못 쓴다")
    void keepsExistingRows() {
        service.syncWithEnum();
        repository.findByDomain(Domain.NETWORK).orElseThrow().edit(false, "네트워크", "내가 쓴 힌트");
        repository.flush();

        service.syncWithEnum();

        assertThat(repository.findByDomain(Domain.NETWORK)).get()
                .extracting(DomainSetting::getHint).isEqualTo("내가 쓴 힌트");
    }

    @Test
    @DisplayName("배치 후보는 켜진 것만, 순서대로 준다")
    void batchDomainsAreEnabledOnesInOrder() {
        service.syncWithEnum();

        List<Domain> domains = service.batchDomains();

        assertThat(domains).doesNotContain(Domain.CLOUD_INFRA, Domain.INTEGRATED);
        assertThat(domains).startsWith(Domain.NETWORK, Domain.OS, Domain.DATABASE);
    }

    @Test
    @DisplayName("힌트는 내장값을 초기값으로 받는다")
    void hintsStartFromBuiltIn() {
        service.syncWithEnum();

        assertThat(service.hints().rawHintFor(Domain.BACKEND_FRAMEWORK)).contains("Spring DI/IoC");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingServiceTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 서비스와 러너를 쓴다**

`DomainSettingService`:
- `syncWithEnum()` — `@Transactional`. enum 전체를 돌며 없는 행을 `DomainSetting.initial(...)`로 만든다. `enabled`는 `@Value("${llm.generation.batch-domains:...}")`로 받은 폴백 목록에 들었는지로 정한다. `sortOrder`는 폴백 목록의 자리, 목록 밖이면 목록 길이부터 이어 붙인다. `displayName`은 `domain.getDisplayName()`, `hint`는 `DomainHints.BUILT_IN.rawHintFor(domain)`. 그다음 DB에만 있는 행을 지운다.
- `batchDomains()` — `@Transactional(readOnly = true)`. `findAllByOrderBySortOrderAsc()`에서 `enabled`만 골라 `Domain`으로.
- `hints()` — 같은 목록을 `Map<Domain, String>`으로 모아 `DomainHints.of(...)`.

`DomainSettingSyncRunner`는 `ApplicationRunner`, `@Order(4)`. 주제 대기열 동기화(`@Order(5)`)보다 앞이면 되는데, 이유를 주석에 남긴다: **내보내기(Task 5, `@Order(60)`)보다 반드시 앞서야** 한다. 뒤집히면 행을 만들기도 전에 빈 파일을 써 버려, 배치가 설정 없이 도는 날이 하루 생긴다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingServiceTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/service/DomainSettingService.java src/main/java/project/study/study_project/llm/service/DomainSettingSyncRunner.java src/test/java/project/study/study_project/llm/service/DomainSettingServiceTest.java
git commit -m "feat(llm): 기동 시 분야 설정 행을 enum에 맞춘다"
```

---

### Task 5: 설정을 파일로 내보낸다

**Files:**
- Create: `src/main/java/project/study/study_project/llm/dto/DomainSettingsFile.java`
- Create: `src/main/java/project/study/study_project/llm/service/DomainSettingExporter.java`
- Create: `src/main/java/project/study/study_project/llm/service/DomainSettingChanged.java`
- Test: `src/test/java/project/study/study_project/llm/service/DomainSettingExporterTest.java`

**Interfaces:**
- Consumes: `SnapshotExporter`(`fileName()`/`label()`/`build(boolean)`/`exportQuietly(String)`), `DomainSettingRepository` (Task 3)
- Produces:
  - `DomainSettingsFile(String note, List<DomainSettingsFile.Entry> domains)`
  - `DomainSettingsFile.Entry(String domain, boolean enabled, int sortOrder, String displayName, String hint)`
  - `DomainSettings.FILE_NAME` 상수는 Task 6이 만든다. 이 태스크에서는 `DomainSettingExporter`에 `static final String FILE_NAME = "_domain-settings.json";`을 두고, Task 6에서 `DomainSettings.FILE_NAME`을 빌려 쓰도록 바꾼다(`TopicQueueExporter`가 `TopicQueue.FILE_NAME`을 빌려 쓰는 것과 같은 꼴).
  - `DomainSettingChanged` — 빈 이벤트 레코드

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.DomainSettingsFile;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DomainSettingExporterTest {

    @Autowired DomainSettingService service;
    @Autowired DomainSettingExporter exporter;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("켜짐·순서·이름·힌트를 그대로 싣는다 — 배치가 이 파일만 보고 돌 수 있어야 한다")
    void exportsEveryFieldTheBatchNeeds() {
        service.syncWithEnum();

        DomainSettingsFile file = exporter.snapshotForTest();

        assertThat(file.domains()).hasSize(Domain.values().length);
        DomainSettingsFile.Entry first = file.domains().get(0);
        assertThat(first.domain()).isEqualTo("NETWORK");
        assertThat(first.enabled()).isTrue();
        assertThat(first.sortOrder()).isZero();
        assertThat(first.displayName()).isEqualTo("네트워크");
    }

    @Test
    @DisplayName("꺼진 분야도 내보낸다 — 화면에서 다시 켤 때 이름·힌트가 남아 있어야 한다")
    void disabledDomainsAreExportedToo() {
        service.syncWithEnum();

        DomainSettingsFile file = exporter.snapshotForTest();

        assertThat(file.domains()).anyMatch(e -> e.domain().equals("CLOUD_INFRA") && !e.enabled());
    }
}
```

> `snapshotForTest()`는 `build(true).payload()`를 `DomainSettingsFile`로 돌려주는 **패키지 전용** 메서드다. `build`가 `protected`라 테스트에서 직접 못 부르므로 이 한 겹만 연다.

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingExporterTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`DomainSettingExporter extends SnapshotExporter`, `@Component`, `@Order(60)`, `@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)` — `TopicQueueExporter`와 같은 조건이다. `build`는 `findAllByOrderBySortOrderAsc()`를 `Entry`로 옮긴다. 행이 하나도 없고 파일도 없으면 `null`(= 안 쓴다).

`note`에는 배치가 이 파일을 어떻게 쓰는지와 **"이 파일이 갱신되면 커밋해야 다음 배치부터 반영된다"**를 적는다. `TopicQueueExporter.NOTE`가 본보기다.

`@TransactionalEventListener(phase = AFTER_COMMIT)`로 `DomainSettingChanged`를 듣는다. 커밋 전에 쓰면 롤백된 변경이 파일에 남는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingExporterTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/dto/DomainSettingsFile.java src/main/java/project/study/study_project/llm/service/DomainSettingExporter.java src/main/java/project/study/study_project/llm/service/DomainSettingChanged.java src/test/java/project/study/study_project/llm/service/DomainSettingExporterTest.java
git commit -m "feat(llm): 분야 설정을 generated/_domain-settings.json으로 내보낸다"
```

---

### Task 6: 배치가 그 파일을 읽는다

**Files:**
- Create: `src/main/java/project/study/study_project/llm/support/DomainSettings.java`
- Modify: `src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java:281`(생성기 생성), `:684`(문서 생성기), 그리고 후보 분야를 정하는 자리
- Modify: `src/main/java/project/study/study_project/llm/service/DomainSettingExporter.java` — `FILE_NAME`을 `DomainSettings.FILE_NAME`에서 빌려 쓰게
- Test: `src/test/java/project/study/study_project/llm/support/DomainSettingsTest.java`

**Interfaces:**
- Consumes: `DomainSettingsFile` (Task 5), `DomainHints` (Task 1)
- Produces:
  - `DomainSettings.FILE_NAME` = `"_domain-settings.json"`
  - `DomainSettings.read(Path dir)` → `DomainSettings` (파일이 없거나 깨졌으면 빈 설정)
  - `DomainSettings.batchDomains()` → `List<Domain>` (비었으면 빈 목록)
  - `DomainSettings.hints()` → `DomainHints` (비었으면 `DomainHints.BUILT_IN`)
  - `DomainSettings.isEmpty()` → `boolean`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.global.common.Domain;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치가 설정을 읽는 길. <b>읽기에 실패해도 배치는 돌아야 한다</b> — 이미 지불한 API 요금이
 * 설정 파일 오타 하나로 버려지면 안 된다. 그래서 모든 실패는 "빈 설정"으로 떨어지고,
 * 부르는 쪽이 폴백(application.yml → enum 전체)으로 이어 간다.
 */
class DomainSettingsTest {

    @Test
    @DisplayName("파일을 읽어 켜진 분야를 순서대로 준다")
    void readsEnabledDomainsInOrder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"OS","enabled":true,"sortOrder":0,"displayName":"운영체제","hint":null},
                  {"domain":"NETWORK","enabled":true,"sortOrder":1,"displayName":"네트워크","hint":"TCP 위주"},
                  {"domain":"CLOUD_INFRA","enabled":false,"sortOrder":2,"displayName":"클라우드","hint":null}
                ]}""");

        DomainSettings settings = DomainSettings.read(dir);

        assertThat(settings.batchDomains()).containsExactly(Domain.OS, Domain.NETWORK);
        assertThat(settings.hints().rawHintFor(Domain.NETWORK)).isEqualTo("TCP 위주");
    }

    @Test
    @DisplayName("파일이 없으면 빈 설정 — 부르는 쪽이 yml 폴백으로 간다")
    void missingFileGivesEmptySettings(@TempDir Path dir) {
        DomainSettings settings = DomainSettings.read(dir);

        assertThat(settings.isEmpty()).isTrue();
        assertThat(settings.batchDomains()).isEmpty();
    }

    @Test
    @DisplayName("깨진 파일도 빈 설정 — 오타 하나로 그날 생성이 통째로 죽지 않는다")
    void brokenFileGivesEmptySettings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), "{ 이건 JSON이 아니다");

        assertThat(DomainSettings.read(dir).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("모르는 분야 이름은 그 줄만 버린다 — 나머지 설정은 살린다")
    void unknownDomainNameIsSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"FRONTEND_CS","enabled":true,"sortOrder":0,"displayName":"옛 분야","hint":null},
                  {"domain":"OS","enabled":true,"sortOrder":1,"displayName":"운영체제","hint":null}
                ]}""");

        assertThat(DomainSettings.read(dir).batchDomains()).containsExactly(Domain.OS);
    }

    @Test
    @DisplayName("빈 설정의 힌트는 내장값 — 설정이 없던 때와 같은 프롬프트가 나간다")
    void emptySettingsFallBackToBuiltInHints(@TempDir Path dir) {
        assertThat(DomainSettings.read(dir).hints().rawHintFor(Domain.SYSTEM_DESIGN))
                .isEqualTo(DomainHints.BUILT_IN.rawHintFor(Domain.SYSTEM_DESIGN));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingsTest" --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`DomainSettings`는 `TopicQueue`와 같은 자리·같은 꼴이다(정적 `read(Path)`, 자체 `ObjectMapper`, 실패를 삼키고 빈 것을 돌려줌). 모르는 분야 이름은 `IllegalArgumentException`을 잡아 그 줄만 건너뛴다 — 2026-09-21에 `FRONTEND_CS`를 지웠듯 앞으로도 enum에서 빠지는 이름이 나온다.

CLI 쪽 연결:

```java
// DraftGeneratorCli — 설정 파일이 있으면 그것, 없으면 지금까지의 yml 값
DomainSettings settings = DomainSettings.read(Path.of(outDir));
List<Domain> domains = settings.batchDomains().isEmpty()
        ? parseDomains((String) config.get("batch-domains"))
        : settings.batchDomains();
```

생성기 두 곳은 `new ClaudeProblemGenerator(model, settings.hints())`, `new ClaudeDocumentGenerator(model, settings.hints())`로 바꾼다.

`--domain=...`으로 직접 지정한 경우는 지금과 같다 — 후보 제한은 자동 선택에만 걸린다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainSettingsTest" --tests "*DraftGeneratorCliTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/support/DomainSettings.java src/main/java/project/study/study_project/llm/cli/DraftGeneratorCli.java src/main/java/project/study/study_project/llm/service/DomainSettingExporter.java src/test/java/project/study/study_project/llm/support/DomainSettingsTest.java
git commit -m "feat(llm): 배치가 분야 설정 파일을 읽는다"
```

---

### Task 7: 앱이 읽는 자리를 DB로 옮긴다

`@Value`로 같은 기본값 문자열을 들고 있던 두 곳을 서비스 하나로 모은다. `AdminStatsService` 주석이 "한 곳에 모으는 편이 낫지만…"이라고 적어 둔 그 문제가 여기서 풀린다.

**Files:**
- Modify: `src/main/java/project/study/study_project/llm/service/LlmProblemService.java:140` 언저리 + `@Value` 필드
- Modify: `src/main/java/project/study/study_project/admin/service/AdminStatsService.java:72-85`
- Test: `src/test/java/project/study/study_project/llm/service/LlmProblemServiceTest.java` (기존 파일 수정)

**Interfaces:**
- Consumes: `DomainSettingService.batchDomains()` (Task 4)

- [ ] **Step 1: 기존 테스트를 새 의존성에 맞춘다**

`LlmProblemServiceTest`의 `newService(List<Domain>)` 도우미가 지금은 후보 목록을 생성자 인자로 넘긴다. 이제 `DomainSettingService` 모의 객체가 그 목록을 돌려주게 바꾼다.

```java
    private LlmProblemService newService(List<Domain> batchDomains) {
        DomainSettingService settings = mock(DomainSettingService.class);
        when(settings.batchDomains()).thenReturn(batchDomains);
        return new LlmProblemService(/* 기존 인자들 */, settings);
    }
```

기존 테스트 이름과 단언은 그대로 둔다 — 바뀌는 것은 "후보 목록이 어디서 오는가"뿐이고, 그 목록이 하는 일은 같아야 한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*LlmProblemServiceTest" --console=plain`
Expected: 컴파일 실패 — 생성자에 그 인자 없음

- [ ] **Step 3: 구현한다**

두 서비스에서 `@Value` 필드를 지우고 `DomainSettingService`를 생성자로 받는다. `LlmProblemService:140`의 `batchDomains.isEmpty() ? List.of(Domain.values()) : ...` 분기는 **그대로 둔다** — 설정 행이 아직 없는 첫 기동에서 목록이 빌 수 있다.

`AdminStatsService`의 "왜 전체 분야로 세지 않나" 주석은 살리되, 기본값 문자열이 두 곳에 복사돼 있다던 마지막 문단은 사실이 아니게 되므로 고쳐 쓴다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*LlmProblemServiceTest" --tests "*AdminStats*" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/llm/service/LlmProblemService.java src/main/java/project/study/study_project/admin/service/AdminStatsService.java src/test/java/project/study/study_project/llm/service/LlmProblemServiceTest.java
git commit -m "refactor(llm): 배치 후보 분야를 설정에서 읽는다"
```

---

### Task 8: 분야 목록을 서버에서 준다 (`GET /api/domains`)

`static/js/api.js`가 분야 이름을 하드코딩하고 있어, 이름을 고치려면 자바와 JS 두 곳을 같이 고쳐야 한다. 서버가 주게 바꾼다.

**Files:**
- Create: `src/main/java/project/study/study_project/quiz/controller/DomainController.java`
- Create: `src/main/java/project/study/study_project/quiz/dto/DomainResponse.java`
- Modify: `src/main/resources/static/js/api.js:163-170`, `domainLabel` 함수
- Test: `src/test/java/project/study/study_project/quiz/DomainListIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainSettingService.findAll()` (Task 4)
- Produces:
  - `GET /api/domains` → `ApiResponse<List<DomainResponse>>`
  - `DomainResponse(String code, String displayName)` — 순환 순서대로, **꺼진 분야도 포함**(학습자 화면에는 기존 문제가 있는 분야가 다 보여야 한다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    @Test
    @DisplayName("분야 목록을 순환 순서대로 준다 — 화면의 하드코딩을 대신한다")
    void listsDomainsInCycleOrder() throws Exception {
        mockMvc.perform(get("/api/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("NETWORK"))
                .andExpect(jsonPath("$.data[0].displayName").value("네트워크"))
                .andExpect(jsonPath("$.data.length()").value(Domain.values().length));
    }

    @Test
    @DisplayName("로그인 없이 볼 수 있다 — 문제 목록 화면이 로그인 전에도 필터를 그린다")
    void isPublic() throws Exception {
        mockMvc.perform(get("/api/domains")).andExpect(status().isOk());
    }
```

공통 응답 봉투(`ApiResponse`) 꼴은 docs/04를 따른다. 기존 공개 API 통합 테스트(`ProblemListIntegrationTest`)의 설정을 그대로 본뜬다.

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainListIntegrationTest" --console=plain`
Expected: 404 또는 컴파일 실패

- [ ] **Step 3: 구현한다**

컨트롤러는 `quiz` 패키지에 둔다 — 학습자 화면이 쓰는 공개 목록이기 때문이다(관리자 전용 설정 API는 Task 9에서 따로 만든다). `SecurityConfig`의 공개 경로에 `/api/domains`를 더한다.

`api.js`:

```javascript
/* 분야 목록은 서버가 준다(GET /api/domains). 예전에는 이 배열이 자바 enum과 따로 있어서
 * 이름 하나를 고치려면 두 곳을 같이 고쳐야 했고, 한쪽만 고치면 화면에만 옛 이름이 남았다.
 * 첫 호출에서 받아 두고 이후에는 그 값을 쓴다 — 한 번 뜬 화면에서 분야가 바뀔 일은 없다. */
let _domains = null;
async function domains() {
  if (_domains === null) {
    const res = await apiGet("/api/domains");
    _domains = res.data.map(d => [d.code, d.displayName]);
  }
  return _domains;
}
```

`domainLabel(v)`를 부르던 자리가 동기 함수를 기대하므로, 화면 그리기 전에 `await domains()`로 먼저 채우고 `domainLabel`은 채워진 배열을 보게 한다. 부르는 화면(`problems.html`, `quiz.html`, 관리자 화면들)을 모두 확인한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DomainListIntegrationTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 화면을 눈으로 확인한다**

`verify` 스킬의 레시피로 앱을 띄우고 문제 목록 화면의 분야 필터가 열한 줄 그대로 뜨는지 본다. 여기서 빠지면 학습자 화면이 조용히 반쪽이 된다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/study_project/quiz/ src/main/resources/static/js/api.js src/test/java/project/study/study_project/quiz/DomainListIntegrationTest.java
git commit -m "feat(quiz): 분야 목록을 서버에서 준다"
```

---

### Task 9: 관리자 설정 API

**Files:**
- Create: `src/main/java/project/study/study_project/admin/controller/AdminDomainSettingController.java`
- Create: `src/main/java/project/study/study_project/admin/dto/AdminDomainSettingRequest.java`
- Create: `src/main/java/project/study/study_project/admin/dto/AdminDomainSettingResponse.java`
- Modify: `src/main/java/project/study/study_project/llm/service/DomainSettingService.java` — 수정·순서이동·미리보기
- Test: `src/test/java/project/study/study_project/admin/AdminDomainSettingIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainSetting.edit(...)`·`changeOrder(int)` (Task 3), `DomainSettingChanged` (Task 5)
- Produces:
  - `GET /api/admin/domain-settings` → `ApiResponse<List<AdminDomainSettingResponse>>`
  - `PUT /api/admin/domain-settings/{domain}` — 본문 `AdminDomainSettingRequest(boolean enabled, String displayName, String hint)`
  - `POST /api/admin/domain-settings/{domain}/move` — 본문 `{"direction":"UP"|"DOWN"}`
  - `GET /api/admin/domain-settings/preview?days=7` → `ApiResponse<List<PreviewCell>>`
  - `DomainSettingService.edit(Domain, AdminDomainSettingRequest)` → `void`
  - `DomainSettingService.Direction` — `enum { UP, DOWN }`. `TopicQueueService.Direction`을 빌려 쓰지 않고 따로 둔다: 그쪽에는 `TOP`이 있는데 여기서는 열한 줄뿐이라 쓸 자리가 없고, 남의 enum을 빌리면 그쪽에 값이 늘 때 여기까지 흔들린다
  - `DomainSettingService.move(Domain, Direction)` → `void`
  - `DomainSettingService.preview(List<Domain> domains, int days)` → `List<PreviewCell>`
  - `AdminDomainSettingResponse(String code, boolean enabled, int sortOrder, String displayName, String hint, String promptPreview)` — `promptPreview`는 프롬프트에 실릴 꼴 그대로(`DomainHints.hintFor`)
  - `PreviewCell(LocalDate date, boolean documentDay, String domain, String difficulty)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    @Test
    @DisplayName("힌트를 고치면 다음 생성부터 그 값이 실린다")
    void editHintIsStored() throws Exception {
        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"displayName":"네트워크","hint":"TCP 혼잡 제어 위주"}"""))
                .andExpect(status().isOk());

        assertThat(domainSettingService.hints().rawHintFor(Domain.NETWORK))
                .isEqualTo("TCP 혼잡 제어 위주");
    }

    @Test
    @DisplayName("힌트 상한은 500자 — 프롬프트를 통째로 밀어 넣는 입력을 막는다")
    void hintHasMaxLength() throws Exception {
        String tooLong = "가".repeat(501);

        mockMvc.perform(put("/api/admin/domain-settings/NETWORK")
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"displayName\":\"네트워크\",\"hint\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("순서를 올리면 앞줄과 자리를 바꾼다")
    void moveUpSwapsWithPrevious() throws Exception {
        mockMvc.perform(post("/api/admin/domain-settings/OS/move")
                        .contentType(APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        assertThat(domainSettingService.batchDomains()).startsWith(Domain.OS, Domain.NETWORK);
    }

    @Test
    @DisplayName("맨 위를 더 올려도 아무 일도 없다 — 오류로 만들면 버튼을 눌러 보기가 무서워진다")
    void moveUpAtTopIsNoop() throws Exception {
        mockMvc.perform(post("/api/admin/domain-settings/NETWORK/move")
                        .contentType(APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        assertThat(domainSettingService.batchDomains()).startsWith(Domain.NETWORK);
    }

    @Test
    @DisplayName("미리보기는 저장하지 않은 순서로도 계산한다 — 보고 나서 저장할 수 있어야 한다")
    void previewUsesGivenOrderWithoutSaving() throws Exception {
        List<PreviewCell> cells = domainSettingService.preview(
                List.of(Domain.OS, Domain.NETWORK), 7);

        assertThat(cells).hasSize(7);
        assertThat(cells).anyMatch(c -> c.domain().equals("OS"));
        // 저장은 안 됐다
        assertThat(domainSettingService.batchDomains()).startsWith(Domain.NETWORK);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*AdminDomainSettingIntegrationTest" --console=plain`
Expected: 404 또는 컴파일 실패

- [ ] **Step 3: 구현한다**

`edit`과 `move`는 `@Transactional`이고, 끝에 `events.publishEvent(new DomainSettingChanged())`를 낸다(`TopicQueueService`가 `TopicQueueChanged`를 내는 것과 같은 꼴). 그래야 내보내기가 파일을 다시 쓴다.

`move`는 앞/뒤 줄과 `sortOrder`를 맞바꾼다. 맨 끝에서 더 밀면 아무 일도 하지 않는다.

`preview(domains, days)`는 `GenerationSchedule.planFor(date, domains, anchor)`를 오늘부터 `days`일 돌린다. 앵커는 `application.yml`의 `cycle-anchor`를 그대로 쓴다. **저장하지 않는다** — 인자로 받은 목록으로만 계산한다.

컨트롤러 경로와 권한은 기존 관리자 API(`AdminTopicQueueController`)를 그대로 따른다.

- [ ] **Step 4: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*AdminDomainSettingIntegrationTest" --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/study_project/admin/ src/main/java/project/study/study_project/llm/service/DomainSettingService.java src/test/java/project/study/study_project/admin/AdminDomainSettingIntegrationTest.java
git commit -m "feat(admin): 분야 설정 API — 수정·순서이동·다음 7일 미리보기"
```

---

### Task 10: 관리자 화면

**Files:**
- Create: `src/main/resources/static/admin/settings.html`
- Modify: `src/main/resources/static/admin/index.html` — 메뉴에 "분야 설정" 추가

**Interfaces:**
- Consumes: Task 9의 API 넷

- [ ] **Step 1: 화면을 만든다**

기존 관리자 화면(`admin/topics.html`)의 뼈대·토큰·표 스타일을 그대로 쓴다. 줄마다 이렇게 둔다.

```
 ☑ 네트워크          [▲][▼]   이름 [네트워크        ]
   힌트 [                                              ]  0/500
   프롬프트에 이렇게 실립니다:  (아직 없음)
```

- 체크박스 = `enabled`, `▲▼` = 순서 이동
- 힌트 입력칸 아래에 **프롬프트에 실릴 꼴 그대로**를 보여 준다(`promptPreview`). 이 값이 재배포 없이 생성 품질을 바꾸므로, 무엇이 나가는지 눈으로 보고 저장해야 한다.
- 힌트는 500자 상한, 남은 글자 수를 옆에 센다.

- [ ] **Step 2: 다음 7일 미리보기를 붙인다**

저장 버튼 옆에 둔다. 체크·순서를 바꾸면 **저장 전에** 현재 화면 상태로 `preview`를 불러 결과를 표로 그린다.

```
 9/22 (월)  문제  네트워크 · 초급
 9/23 (화)  문제  네트워크 · 중급
 9/24 (수)  문제  네트워크 · 고급
 9/25 (목)  문서  운영체제
 …
```

순서가 곧 날짜 순환이라, 한 줄만 올려도 앞으로 며칠에 나올 것이 통째로 밀린다. 예전에 순환이 두 분야에 갇혀 나머지 여섯이 개념 문서를 영원히 못 받던 버그가 이 자리에서 났다(`DraftGeneratorCli` 주석). 그 일이 다시 나면 **저장 전에** 보이게 한다.

- [ ] **Step 3: 저장 뒤 안내를 띄운다**

저장에 성공하면 이렇게 알린다.

> 저장했습니다. `generated/_domain-settings.json`이 갱신됐습니다 — **커밋해야 다음 배치부터 반영됩니다.**

이 한 줄이 없으면 화면에서 고친 것이 클라우드 배치에 언제 닿는지 알 길이 없다.

- [ ] **Step 4: 눈으로 확인한다**

`verify` 스킬로 앱을 띄우고 관리 콘솔에서 이 화면을 연다. 체크 하나를 끄고 미리보기가 바뀌는지, 저장 뒤 `generated/_domain-settings.json`이 실제로 바뀌는지 본다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/static/admin/settings.html src/main/resources/static/admin/index.html
git commit -m "feat(admin): 분야 설정 화면 — 켜기·순서·이름·힌트와 다음 7일 미리보기"
```

---

### Task 11: 문서를 맞춘다

**Files:**
- Create: `docs/21-domain-settings.md`
- Modify: `docs/README.md` — 목록에 21 추가
- Modify: `docs/02-domain-enums.md` — 설정 행이 enum을 따라온다는 점
- Modify: `docs/07-build-config.md:78`, `docs/13-llm-problem-generation.md:123`, `docs/14-llm-batch-automation.md:284`, `docs/16-llm-pipeline-operations.md:123` — `batch-domains`는 이제 **폴백**이다
- Modify: `src/main/resources/application.yml:209-215` — 주석 갱신

- [ ] **Step 1: docs/21을 쓴다**

설계 문서(`docs/superpowers/specs/2026-09-21-domain-settings-cms-design.md`)를 바탕으로, **결과물 기준**으로 다시 쓴다. 꼭 담을 것 셋:
- DB → 파일 → 배치로 값이 흐르는 그림
- "화면에서 고쳤으면 `generated/`를 커밋한다"는 운영 규칙
- 순서가 날짜 순환을 바꾼다는 경고와 미리보기가 있는 이유

- [ ] **Step 2: `batch-domains`를 적어 둔 네 곳을 고친다**

네 곳 모두 이 값을 "설정 원본"으로 적고 있다. 이제 원본은 DB고 이 값은 **행이 없을 때의 초기값이자 파일이 없을 때의 폴백**이다.

- [ ] **Step 3: 전체 빌드**

Run: `.\gradlew.bat build --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add docs/ src/main/resources/application.yml
git commit -m "docs: 분야 설정 관리창 문서화"
```

---

## 마무리 점검

- [ ] `.\gradlew.bat build --console=plain` 통과
- [ ] 앱을 띄워 관리 콘솔 → 분야 설정에서 체크·순서·이름·힌트를 각각 한 번씩 고쳐 본다
- [ ] `generated/_domain-settings.json`이 갱신되고, 내용이 그대로일 때는 **다시 쓰이지 않는지** 본다(`git status`가 깨끗해야 한다)
- [ ] 그 파일을 커밋한 뒤 `workflow_dispatch`로 배치를 한 번 돌려, 설정대로 분야가 뽑히는지 확인한다
