# 21. 분야 설정 관리창

> 관련: [02-domain-enums](02-domain-enums.md)(Domain enum 자체) ·
> [13-llm-problem-generation](13-llm-problem-generation.md)(힌트가 프롬프트에 실리는 자리) ·
> [14-llm-batch-automation](14-llm-batch-automation.md)(날짜 순환·과거 사고) ·
> [16-llm-pipeline-operations](16-llm-pipeline-operations.md)(설정 표·운영 절차)
>
> 이 문서는 **지금 무엇이 있는가**를 남긴다. "왜 이 구조를 골랐나"의 논거는
> `docs/superpowers/specs/2026-09-21-domain-settings-cms-design.md`에 더 자세히 있다.

## 쉽게 말하면

배치가 매일 어떤 분야(네트워크·운영체제·…) 문제를 낼지, 그 분야를 모델에게 어디까지라고
설명해 줄지를 정하는 화면이다. 예전에는 이 값이 코드와 `application.yml`에 박혀 있어서
한 줄 고치려면 배포를 해야 했다. 지금은 관리 콘솔 **분야 설정** 화면에서 체크박스·▲▼
버튼·글자 입력으로 고치고, 저장하면 바로 DB에 반영된다.

## 값이 흐르는 길

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

로컬에서 도는 앱은 DB를 그대로 읽으면 된다. 문제는 배치다 — 일일 생성 배치는
GitHub Actions 러너에서 돌고, 그 러너에는 우리 MySQL이 없다([14](14-llm-batch-automation.md)).
그래서 DB 값을 파일 하나(`generated/_domain-settings.json`)로 내보내고, 그 파일을
**저장소에 커밋**해야 배치가 읽을 수 있다. 주제 대기열(`_topics.json`)이 이미 같은 다리를
쓰고 있어 같은 구조를 그대로 가져왔다 — 다만 분야 설정은 배치가 값을 되쓰지 않는
**읽기 전용** 사본이라는 점이 다르다(대기열은 사용 도장을 되찍어 양방향이다).

### 운영 규칙 — 이거 하나만 지키면 된다

**화면에서 분야 설정을 고쳤으면 `generated/_domain-settings.json`을 커밋한다.**
커밋하지 않으면 배치는 다음 실행에서도 옛 설정 그대로 돈다 — 에러는 안 나고, 그냥
화면에서 켠 분야가 조용히 안 나올 뿐이다. `git status`로 그 파일이 바뀌었는지 확인하고
커밋하는 습관이 필요하다([16 §6](16-llm-pipeline-operations.md#6-스냅샷-파일-3종--db클라우드-단방향-통로)의
다른 스냅샷 셋과 같은 규칙).

내용이 그대로면 파일을 다시 쓰지 않는다(`SnapshotExporter`의 공통 규칙) — 그래서 화면에서
아무것도 안 고치면 `git status`가 깨끗하고, 뭔가 실제로 바뀌었을 때만 diff가 생긴다.
이 성질이 없으면 앱을 켤 때마다 "내보낸 시각"만 바뀐 파일이 매번 변경으로 잡혀,
정작 진짜 바뀐 날을 알아볼 수 없게 된다.

## 순서가 곧 날짜 순환이다

이 화면에서 제일 위험한 조작이 **순서 이동**이다. 배치가 오늘 무엇을 낼지는
`GenerationSchedule.planFor(날짜, 후보목록, 앵커)`가 정하는데, 그 계산은 후보 목록의
**순서**를 그대로 쓴다. 목록 맨 앞이 0번째 분야, 그다음이 1번째… 식으로 날짜에 매핑되므로
한 줄만 올리거나 내려도 앞으로 며칠 동안 무엇이 나올지가 통째로 밀린다.

이 위험이 비유가 아니라 실제로 벌어진 적이 있다. 예전 24칸 순환 계산에서 분야 후보가
8개인데 `cellFor`가 `에포크일 mod 8`로 분야를 고르고 문서일은 `에포크일 mod 4 == 0`으로
정했다. 4는 8의 절반이라, 문서가 걸리는 날은 언제나 `mod 8`이 0 또는 4인 날뿐이었다 —
결과적으로 후보 8개 중 **NETWORK와 SYSTEM_DESIGN 두 개만** 영원히 반복되고, 나머지
여섯 분야는 개념 문서를 한 번도 못 받았다(`DraftGeneratorCli.documentDomain` 주석,
[14 §처음 규칙](14-llm-batch-automation.md#처음-규칙--24칸-순환-지금은-폴백)). 문제일에는
근거 문서 쪽으로 분야를 맞추는 장치까지 있어서 문제까지 그 두 분야에 갇혔고, 매일
"분야를 맞췄습니다" 로그가 정상 동작처럼 보여 발견이 더 늦어졌다.

그래서 이 화면은 저장 버튼 옆에 **"다음 7일 미리보기"**를 항상 띄워 둔다. 체크박스를
끄거나 ▲▼로 순서를 바꾼 그 순간, 아직 저장하지 않은 화면 상태 그대로 `planFor`를 돌려
앞으로 7일이 어떻게 나오는지 보여 준다(`planFor`가 순수 함수라 저장 안 한 목록으로도
그대로 계산할 수 있다). 순환이 다시 두 분야에 갇히는 실수를 **저장한 뒤가 아니라 저장하기
전에** 눈으로 잡기 위한 장치다.

## 왜 이렇게 작았나 — enum을 문자열로 저장한 대가

테이블 설계에서 가장 놀랐던 점 하나는 문서화해 둘 가치가 있다.

`domain_setting.domain` 컬럼은 `Domain` enum을 `@Enumerated(EnumType.STRING)`으로 저장한다
(순서가 바뀌면 데이터가 깨지는 `ORDINAL`은 이 프로젝트 전체에서 금지, [02](02-domain-enums.md)).
그런데 **enum에 없는 이름을 가진 행이 테이블에 단 하나만 있어도, 이 테이블에 대한 모든
일반 JPA 조회가 예외를 던진다** — `EnumType.STRING` 변환이 내부적으로 `Enum.valueOf`를
쓰기 때문에, `findAll()`이든 `findAllByOrderBySortOrderAsc()`든 그 행을 만나는 순간
`IllegalArgumentException`으로 죽는다. 2026-09-21에 `FRONTEND_CS`를 enum에서 실제로
지웠을 때 이 문제를 그대로 겪었다.

여기서 두 가지 결정이 따라 나온다.

- **기동 시 동기화(`DomainSettingSyncRunner` → `DomainSettingService.syncWithEnum`)가
  고아 행을 지울 때는 엔티티 조회를 쓰지 않는다.** 지워야 할 대상을 찾는 조회 자체가
  그 행 때문에 먼저 죽으면 아무것도 할 수 없다. 그래서
  `DomainSettingRepository.findAllDomainNamesNative()`로 변환 없이 문자열만 읽고,
  지우기도 `deleteByDomainNameNative()`로 네이티브 SQL을 쓴다.
- **내보내는 파일(`DomainSettingsFile.Entry`)의 `domain` 필드는 `Domain` enum이 아니라
  일반 문자열이다.** 파일 형식에서까지 같은 함정을 반복하면, 옛 이름 하나가 남아 있는
  파일을 배치가 읽을 때 파싱 전체가 죽는다. 문자열로 받아 두면 `DomainSettings.parseDomain`이
  줄 단위로 걸러 낼 수 있어 — 모르는 이름 한 줄만 버려지고 나머지 분야는 정상으로 읽힌다.

## 테이블

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

숫자 대리키가 아니라 `domain` 자체를 PK로 쓴다. 행 수가 `Domain` enum 상수 수만큼 고정
(현재 11개)이고, 이 테이블을 읽는 쪽은 늘 "NETWORK의 설정"을 찾지 "3번 행"을 찾지 않는다.

**행은 Flyway가 채우지 않는다.** 마이그레이션에는 스키마만 넣는다는 원칙
([11-flyway-migrations](11-flyway-migrations.md))도 있지만, 더 실질적인 이유는 `Domain`
enum에 상수를 더하거나 뺄 때마다 새 마이그레이션을 써야 한다면 깜빡한 상수가 설정 행
없이 조용히 배치에서 빠지기 때문이다. 대신 기동 시 `DomainSettingSyncRunner`
(`@Order(4)`)가 매번 enum을 진실로 삼아 테이블을 맞춘다.

- enum에는 있는데 행이 없으면 → 새로 만든다. `enabled`·`sortOrder`는
  `application.yml`의 `llm.generation.batch-domains`(있으면 그 자리, 없으면 목록 뒤에 이어
  붙임)를 초기값으로 쓰고, `displayName`은 `Domain.getDisplayName()`, `hint`는
  `DomainHints.BUILT_IN`(코드에 박혀 있던 옛 경계 설명)에서 가져온다.
- 행이 있는데 enum에 없으면 → 지운다(2026-09-21의 `FRONTEND_CS`가 이 경우였다).
- **있는 행은 절대 건드리지 않는다.** 관리자가 화면에서 고쳐 둔 값을 기동마다 폴백
  목록 기준으로 되돌리면, 그 화면은 아무도 못 믿게 된다.

**따라서 `Domain` 상수를 추가하거나 빼는 데는 마이그레이션이 필요 없다.** 다음 기동 한 번이면
동기화 러너가 설정 행을 알아서 맞춘다([02-domain-enums](02-domain-enums.md) 참고).

## 읽는 쪽 — 값이 도착하는 세 자리

| 읽는 쪽 | 무엇을 | 경로 |
|---|---|---|
| `LlmProblemService`·`AdminStatsService`·`AdminBatchService` | 배치 후보 분야, "빈 칸" 세는 기준, 배치 현황의 오늘 분야·달력 | `DomainSettingService` 조회(DB), 호출마다 다시 읽음 |
| `DraftGeneratorCli`(클라우드 배치) | 후보 분야 + 힌트 | `_domain-settings.json`. 파일이 없거나 깨졌거나 빈 배열일 때만 `application.yml` 폴백 |
| `Claude*Generator`(앱 안) | 분야 경계 설명(힌트) | 주입받은 `DomainHintsProvider`(= `DomainSettingService`)를 프롬프트 짤 때마다 호출 |
| `Claude*Generator`(CLI·테스트) | 분야 경계 설명(힌트) | 파일에서 읽은 `DomainHints`, 또는 내장값 `BUILT_IN`을 감싼 공급자 |
| `static/js/api.js`, 관리 화면 | 분야 이름 목록 | `GET /api/domains` |

로컬 앱과 클라우드 배치가 서로 다른 경로로 같은 값을 보게 되는 구조라, **둘이 다른
설정을 보는 유일한 순간은 "화면에서 고치고 아직 커밋 안 한 동안"**이다. 그래서 위
운영 규칙이 이 문서에서 가장 먼저 나온다.

이 문장이 참이려면 앱 쪽이 값을 **기동 때 한 번 읽어 굳히면 안 된다.** 2026-09-22 최종
리뷰 전까지는 두 곳이 어겼다. 배치 현황 화면은 yml 순서로 달력을 그렸다. 생성기 빈은
늘 내장 힌트로 만들어져, 관리자 화면의 "생성 실행"·문서 업로드가 고친 힌트를 무시했다.
지금은 둘 다 쓰는 순간 DB를 읽는다. 생성기의 빈 경로는 `ClaudeProblemGeneratorBeanHintTest`가
지킨다 — 다른 프롬프트 테스트는 전부 `new`로 만든 생성기만 써서 이 경로를 밟지 않았다.

## 화면

`/admin/settings.html`. 분야 열한 줄이 순환 순서대로 놓이고, 줄마다 켜짐·이름·힌트를
고친다. 순서 이동(▲▼)은 누르는 즉시 반영되고, 켜짐·이름·힌트는 줄마다 있는 저장
버튼을 눌러야 반영된다 — 일괄 저장 버튼을 두지 않은 이유는 버튼이 하나면 "무엇을
저장했는지"가 흐려지기 때문이다.

힌트 입력칸 아래에는 실제로 프롬프트에 어떻게 실리는지(앞 공백과 괄호까지 포함)를
그대로 보여 준다 — 힌트는 프롬프트에 그대로 이어 붙는 값이라, 화면에서 고치는 순간
**재배포 없이 생성 품질이 바뀐다.** 좋아지는 쪽으로도, 나빠지는 쪽으로도. 500자
상한을 둔 것도 문서를 통째로 붙여 넣는 실수가 매 배치 요금으로 돌아오는 것을 막기
위해서다.

행 자체를 화면에서 새로 만들거나 지우는 기능은 없다. 행 수가 enum 상수 수로 고정이라
"추가"라는 개념이 없고, 있는 행을 고치는 것(`edit`)과 순서를 옮기는 것(`move`)만 있다.

## API

| 메서드 | 경로 | |
|---|---|---|
| GET | `/api/admin/domain-settings` | 목록 — `sortOrder` 순 전체(꺼진 분야도 포함) |
| PUT | `/api/admin/domain-settings/{domain}` | 켜짐·이름·힌트 수정 |
| POST | `/api/admin/domain-settings/{domain}/move` | 이웃과 순서 맞바꾸기(`{"direction":"UP"|"DOWN"}`) |
| GET | `/api/admin/domain-settings/preview?days=7&domains=...` | 저장 전 미리보기. `domains` 생략 시 지금 저장된 순서 사용 |

맨 위에서 더 올리거나 맨 아래에서 더 내려도 200이다 — 오류로 만들면 버튼을 눌러 보는
것 자체가 무서워진다.

## 설정 — `application.yml`의 `batch-domains`는 이제 폴백이다

```yaml
llm:
  generation:
    batch-domains: NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,LANGUAGE_RUNTIME,BACKEND_FRAMEWORK
```

이 값의 자리는 둘로 줄었다.

1. **동기화 러너가 새 설정 행을 만들 때의 초기값.** enum에 새 상수가 생기면 이 목록에
   있는지 여부로 그 행의 첫 `enabled`·`sortOrder`가 정해진다.
2. **`_domain-settings.json` 파일이 없을 때 클라우드 배치가 쓰는 폴백.** 폴백 조건은
   `DomainSettings.isEmpty()` 하나다 — 파일이 없거나, 깨졌거나, 분야 배열이 비었을 때만
   이 값으로 대신 돈다. 파일은 있는데 켜진 분야가 0개면 yml로 가지 **않고** 빈 목록을
   넘겨 `GenerationSchedule`이 enum 전체로 넓힌다 — 앱(`LlmProblemService`)과 같은 결과다.
   yml마저 비어 있을 때도 마찬가지로 enum 전체다.

**분야를 전부 꺼서 배치를 멈출 수는 없다.** 설정 화면과 API가 마지막으로 켜진 분야를
끄는 요청을 거절한다(400, `DOMAIN_002`). 배치를 멈추는 스위치는 `llm.generation.batch-enabled`
하나다(워크플로의 `force`와 짝). 정지 수단이 둘이면 둘의 뜻이 어긋난다 — 실제로 전에는
전부 끄면 CLI는 yml 8개로, 앱은 enum 전체로 돌아 배치가 조용히 계속 돌았다.

**더는 "설정 원본"이 아니다.** DB(`domain_setting`)가 원본이고, 이미 있는 설정 행은
이 값이 바뀌어도 절대 따라 바뀌지 않는다. 지우지 않고 남겨 둔 이유는 파일도 DB 행도
없는 완전히 빈 상태에서 배치가 멈추는 것보다는, 낡았더라도 뭔가 도는 편이 낫기
때문이다.

## 면접 대본 요약

"분야 순환 순서·이름·모델 힌트가 코드와 yml에 박혀 있어서 한 줄 고치려면 배포가
필요했습니다. 관리 화면 → DB로 옮기되, 배치가 도는 GitHub Actions 러너에는 DB가 없다는
제약이 있어서, 주제 대기열이 이미 쓰고 있던 'DB가 원본, 파일은 사본, 커밋으로 다리를
잇는다'는 구조를 그대로 재사용했습니다. 순서가 곧 날짜 순환이라는 게 제일 위험한
지점이었는데, 실제로 예전 계산식에서 8개 후보 중 2개만 영원히 반복되고 나머지 여섯이
개념 문서를 못 받는 사고가 있었어서, 저장 전에 앞으로 7일을 미리 계산해 보여 주는
장치를 넣었습니다. 구현하면서 제일 뜻밖이었던 건, enum을 문자열로 저장하는 테이블에
모르는 이름 하나만 섞여도 그 테이블의 모든 일반 조회가 예외를 던진다는 점이었어요 —
그래서 기동 시 고아 행을 정리하는 동기화는 네이티브 쿼리로 우회하고, 내보내는 파일도
enum이 아니라 문자열로 domain을 담아서 옛 이름 한 줄 때문에 배치 전체가 죽지 않게
했습니다."
