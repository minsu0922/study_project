# 분야 설정 관리창 — 설계

작성일: 2026-09-21

관리자 화면에서 **어떤 분야가 배치에 도는지**와 **각 분야의 이름·경계 설명**을 고친다.
지금은 둘 다 코드와 `application.yml`에 박혀 있어서, 한 줄 고치려면 배포를 해야 한다.

---

## 1. 왜 하나

이 프로젝트는 이미 CMS다. 관리 콘솔에 컨트롤러 8개·화면 8개가 있고, 문제와 문서를
만들고 검수하고 내보내는 흐름이 전부 화면으로 돈다. 빠진 것은 콘텐츠가 아니라 **설정**이다.

고치는 빈도가 높은 순서대로 세 가지가 코드에 갇혀 있다.

| 값 | 지금 어디에 | 고치려면 |
|---|---|---|
| 모델에게 주는 분야 경계 설명(`domainHint`) | `ClaudeProblemGenerator`·`ClaudeDocumentGenerator`의 switch | 코드 수정 → 재배포 |
| 배치가 도는 분야와 순환 순서 | `application.yml`의 `batch-domains` | 파일 수정 → 재배포 |
| 화면에 뜨는 분야 이름 | `Domain` enum + `static/js/api.js`의 하드코딩 배열 | 두 곳을 같이 수정 |

특히 첫째가 급하다. 그 한 줄이 생성 품질을 직접 흔드는데, 손대는 값치고 절차가 가장 무겁다.

**하지 않는 것**: 화면에서 분야를 새로 만들거나 지우는 기능은 넣지 않는다. 그러려면
`Domain` enum을 없애야 하고, 그 순간 `Domain`을 참조하는 본코드 70개·테스트 44개 파일에서
컴파일러가 잡아 주던 오타가 런타임 검증으로 내려간다. 분야를 새로 만드는 일은 1년에 몇 번이다.

---

## 2. 이 설계를 정한 제약 — 배치에는 DB가 없다

일일 배치는 GitHub Actions에서 돈다. 그리고 그 job에는 **MySQL이 없다**.

```yaml
# .github/workflows/llm-daily.yml
# MySQL·Redis 서비스 컨테이너가 없다: DraftGeneratorCli는 Spring을 띄우지 않고
# 생성기만 직접 만들어 호출하므로 DB가 필요 없다(그래서 빠르고 싸다).
```

그래서 설정을 DB에만 두면 **배치가 그 설정을 못 읽는다**. 배치에 DB를 붙이는 선택지는
버린다 — 지금 배치가 싸고 빠른 이유가 Spring도 DB도 안 띄우는 것이기 때문이다.

### 이미 같은 문제를 푼 곳이 있다

주제 대기열이 똑같은 벽에 부딪혔고, 그 해법이 `TopicQueueService` 주석에 적혀 있다.

```
관리자 화면 → DB → (내보내기) → generated/_topics.json → 커밋 → 배치가 읽음
             ↑                                              ↓
             └────── (기동 시 동기화) ── usedAt 도장 ────────┘
```

**DB가 원본, 파일은 사본**이다. 이 설계는 같은 구조를 그대로 쓴다. 새 방식을 만들지 않는
이유는 둘이다. 하나, 이미 네 벌의 내보내기가 `SnapshotExporter`라는 공통 뼈대를 쓰고 있어
붙일 자리가 준비돼 있다. 둘, 운영하는 사람이 규칙을 하나만 기억하면 된다 —
"화면에서 고쳤으면 `generated/`를 커밋한다."

분야 설정은 주제 대기열보다 오히려 쉽다. 대기열은 배치가 사용 도장을 되찍어서 양방향인데,
분야 설정은 **사람만 고치고 배치는 읽기만 한다**. 되돌아오는 값이 없어 충돌할 자리가 없다.

---

## 3. 테이블 — 하나로 합친다

1단계(도는 분야·순서)와 2단계(이름·힌트)를 나누지 않는다. 같은 행의 다른 칸이고,
나누면 화면도 둘이 되어 "네트워크를 고치려면 두 군데를 연다"가 된다.

```sql
-- V19__domain_setting.sql
CREATE TABLE domain_setting (
    domain       VARCHAR(30)  NOT NULL,   -- enum Domain 상수명. 그대로 PK
    enabled      BOOLEAN      NOT NULL,   -- 배치 자동 선택 후보인가
    sort_order   INT          NOT NULL,   -- 날짜 순환 순서
    display_name VARCHAR(40)  NOT NULL,   -- 화면에 뜨는 이름
    hint         TEXT         NULL,       -- 모델에게 주는 경계 설명
    PRIMARY KEY (domain)
);
```

`domain`을 따로 만든 숫자 id 없이 PK로 쓴다. 행 수가 열한 개로 고정이고, 이 표를 읽는
쪽이 늘 "NETWORK의 설정"을 찾지 "3번 행"을 찾지 않기 때문이다.

### 행은 Flyway가 넣지 않는다

마이그레이션에는 스키마만 넣는다(docs/11). 그보다 중요한 이유가 따로 있다.
`Domain` enum에 상수를 더하거나 뺐을 때다. 시드를 마이그레이션에 넣어 두면 그때마다
새 마이그레이션을 써야 하고, 깜빡하면 **새 분야에 설정 행이 없어 조용히 빠진다**.

대신 기동 시 `DomainSettingSyncRunner`가 enum을 훑는다.

- enum에 있는데 행이 없으면 → 기본값으로 만든다(`enabled`는 `application.yml`의 폴백 목록 기준,
  `display_name`은 enum의 `getDisplayName()`, `hint`는 비움)
- 행이 있는데 enum에 없으면 → 지운다(2026-09-21의 `FRONTEND_CS`가 이 경우다)

`TopicQueueSyncRunner`가 서 있는 자리(`@Order(5)`)와 같은 종류의 일이다.

---

## 4. 값이 흐르는 길

```
관리자 화면 ──저장──→ DB(domain_setting)
                        │
          ┌─────────────┼──────────────┐
          │             │              │
      앱이 읽음     내보내기        GET /api/domains
   (배치 후보·힌트)      │          (화면 목록·이름)
                        ↓
        generated/_domain-settings.json ──커밋──→ 배치(CLI)가 읽음
```

읽는 쪽이 셋이고, 각자 다른 경로로 같은 값을 본다.

| 읽는 쪽 | 무엇을 | 지금 | 바뀐 뒤 |
|---|---|---|---|
| `LlmProblemService` | 배치 후보 분야 | `@Value("${llm.generation.batch-domains}")` | `DomainSettingService` 조회 |
| `AdminStatsService` | "빈 칸" 세는 기준 | 같은 `@Value`(기본값 문자열이 두 곳에 복사돼 있음) | 같은 서비스 조회 |
| `DraftGeneratorCli` | 후보 분야 + 힌트 | `application.yml`을 SnakeYAML로 직접 | `_domain-settings.json`, 없으면 yml 폴백 |
| `Claude*Generator` | 분야 경계 설명 | 코드 안 `switch` | 주입받은 맵 조회 |
| `static/js/api.js` | 분야 이름 목록 | 하드코딩 배열 | `GET /api/domains` |

`GenerationSchedule.planFor(date, domains, anchor)`는 **손대지 않는다**. 이미 분야 목록을
인자로 받는 순수 함수라, 바뀌는 것은 "누가 그 목록을 건네주느냐"뿐이다.

### application.yml의 batch-domains는 남긴다

지우지 않고 **폴백 기본값**으로 내린다. 파일도 DB도 없는 상태에서 배치가 멈추면 안 된다.
`AdminStatsService`와 `LlmProblemService`에 같은 기본값 문자열이 복사돼 있던 문제
(그 클래스 주석이 "한 곳에 모으는 편이 낫지만"이라고 적어 둔 그 문제)는 이 참에 사라진다 —
둘 다 서비스 하나를 보게 되므로.

---

## 5. 화면

`/admin/settings.html` 하나를 새로 만든다. 분야 열한 줄이 순환 순서대로 놓이고,
줄마다 네 가지를 고친다.

```
 ☑ 네트워크          [▲][▼]   이름 [네트워크        ]
   힌트 [                                              ]
 ☑ 운영체제          [▲][▼]   이름 [운영체제        ]
   힌트 [                                              ]
 ☐ 소프트웨어공학    [▲][▼]   이름 [소프트웨어공학  ]
   힌트 [요구사항 분석·UML·디자인 패턴·테스트 기법…    ]
```

순서 이동은 주제 대기열 화면이 이미 쓰는 방식을 따른다(`TopicQueueService.Direction`).
거기서 얻은 교훈 하나는 미리 반영한다 — 열한 줄이라 `TOP`은 필요 없고 `UP`/`DOWN`이면 된다.

### 저장 버튼 옆에 "다음 7일 미리보기"

이 화면에서 제일 위험한 자리가 순서다. **순서가 곧 날짜 순환**이라, 한 줄만 올려도
앞으로 며칠에 무엇이 나올지가 통째로 밀린다. 예전에 순환이 두 분야에 갇혀 나머지 여섯이
개념 문서를 영원히 못 받던 버그가 정확히 이 자리에서 났다(`DraftGeneratorCli` 주석).

그래서 저장하기 전에 바뀐 순서로 계산한 다음 7일을 보여 준다. `planFor`가 순수 함수라
저장하지 않은 목록으로도 그대로 돌려 볼 수 있다.

### 힌트를 고치면 생성 결과가 바뀐다

힌트는 프롬프트에 그대로 실린다. 화면에서 고칠 수 있게 되면 **재배포 없이 생성 품질이
바뀐다** — 좋아지는 쪽으로도, 나빠지는 쪽으로도. 입력칸 아래에 실제로 프롬프트에 어떻게
실리는지를 그대로 보여 주고(`" (…)"` 괄호까지 포함), 500자 상한을 둔다.

---

## 6. 테스트

| 무엇을 | 어떻게 |
|---|---|
| 동기화 러너 | enum에 있고 행이 없으면 만든다 / 행만 있으면 지운다 |
| 순서가 순환을 정한다 | 순서를 바꾼 목록으로 `planFor`를 돌려 7일 결과가 달라지는지 |
| 폴백 | 파일이 없을 때 CLI가 yml 기본값으로 돈다 / 둘 다 없으면 enum 전체 |
| 힌트 | 설정에 힌트가 있으면 프롬프트에 실리고, 없으면 빈 문자열(지금과 같음) |
| 내보내기 | 내용이 그대로면 파일을 다시 쓰지 않는다(`SnapshotExporter`의 규칙) |
| 화면 API | `GET /api/domains`가 순환 순서대로 준다 |

`ClaudeProblemGeneratorPromptTest`가 이미 프롬프트 문자열을 검사하고 있어, 힌트 테스트는
거기에 붙인다.

---

## 7. 손대는 파일

새로 만드는 것 아홉, 고치는 것은 코드 일곱과 문서 넷이다.

**새로**
- `db/migration/V19__domain_setting.sql`
- `llm/domain/DomainSetting.java`, `llm/repository/DomainSettingRepository.java`
- `llm/service/DomainSettingService.java`, `DomainSettingSyncRunner.java`, `DomainSettingExporter.java`
- `admin/controller/AdminDomainSettingController.java`, `static/admin/settings.html`
- `llm/support/DomainSettings.java` — 배치 쪽에서 JSON을 읽는 자리(`TopicQueue`와 같은 역할)

**고침**
- `llm/service/LlmProblemService.java`, `admin/service/AdminStatsService.java` — `@Value` 제거
- `llm/cli/DraftGeneratorCli.java` — 설정 파일 읽기, 힌트 전달
- `llm/client/ClaudeProblemGenerator.java`, `ClaudeDocumentGenerator.java` — `switch` → 주입된 맵
- `static/js/api.js` — 하드코딩 배열 제거, `GET /api/domains`
- `application.yml` — `batch-domains`를 폴백으로 격하(주석 갱신)
- `docs/07-build-config.md`, `docs/13`, `docs/14`, `docs/16` — `batch-domains`를 설정 원본으로
  적어 둔 네 곳. 이제 원본은 DB고 이 값은 폴백이다

---

## 8. 남겨 둔 결정

**분야 추가는 여전히 코드다.** 3절에서 적었듯 enum을 지우는 값이 아직 안 나온다.
enum에 상수를 더하면 동기화 러너가 설정 행을 자동으로 만들어 주므로, 코드 한 줄 추가로
끝나기는 한다.

**힌트 변경 이력은 안 남긴다.** 생성 품질이 나빠졌을 때 "언제 힌트를 고쳤나"를 되짚고
싶어질 수는 있다. 다만 지금은 고치는 사람이 한 명이고, 이력 테이블을 붙이면 화면도
따라 커진다. 실제로 되짚고 싶은 일이 생긴 뒤에 붙인다.
