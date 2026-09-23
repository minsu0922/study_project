# 21. 분야 설정 관리창

> 관련: [02-domain-enums](02-domain-enums.md)(분야 등록부·`DomainCode`) ·
> [13-llm-problem-generation](13-llm-problem-generation.md)(힌트가 프롬프트에 실리는 자리) ·
> [14-llm-batch-automation](14-llm-batch-automation.md)(날짜 순환·과거 사고) ·
> [16-llm-pipeline-operations](16-llm-pipeline-operations.md)(설정 표·운영 절차)
>
> 이 문서는 **지금 무엇이 있는가**를 남긴다. "왜 이 구조를 골랐나"의 논거는
> `docs/superpowers/specs/2026-09-21-domain-settings-cms-design.md`(관리창 자체)와
> `docs/superpowers/specs/2026-09-22-domain-registry-design.md`(화면에서 분야 추가·삭제,
> `Domain` enum → `DomainCode` + 외래키)에 더 자세히 있다.

## 쉽게 말하면

배치가 매일 어떤 분야(네트워크·운영체제·…) 문제를 낼지, 그 분야를 모델에게 어디까지라고
설명해 줄지를 정하는 화면이다. 예전에는 이 값이 코드와 `application.yml`에 박혀 있어서
한 줄 고치려면 배포를 해야 했다. 지금은 관리 콘솔 **분야 설정** 화면에서 체크박스·▲▼
버튼·글자 입력으로 고치고, 분야 자체를 추가하거나 지우고, 저장하면 바로 DB에 반영된다.

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

## 왜 값 타입 + 외래키로 바꿨나 — enum을 문자열로 저장한 대가

**2026-09-21까지**는 `domain_setting.domain` 컬럼이 `Domain` enum을
`@Enumerated(EnumType.STRING)`으로 저장했다(순서가 바뀌면 데이터가 깨지는 `ORDINAL`은
이 프로젝트 전체에서 금지, [02](02-domain-enums.md)). 그런데 **enum에 없는 이름을 가진
행이 테이블에 단 하나만 있어도, 이 테이블에 대한 모든 일반 JPA 조회가 예외를 던졌다** —
`EnumType.STRING` 변환이 내부적으로 `Enum.valueOf`를 쓰기 때문에, `findAll()`이든
`findAllByOrderBySortOrderAsc()`든 그 행을 만나는 순간 `IllegalArgumentException`으로
죽었다. 2026-09-21에 `FRONTEND_CS`를 enum에서 실제로 지웠을 때 이 문제를 그대로 겪었고,
그때는 두 가지 우회로 버텼다 — 고아 행을 지우는 동기화는 네이티브 쿼리로 변환을 피하고,
내보내는 파일의 `domain` 필드는 애초에 enum이 아니라 문자열로 받아 옛 이름 한 줄이
파싱 전체를 죽이지 않게 했다.

**그 우회는 임시방편이었다.** 진짜 문제는 "분야를 아는 곳이 하나뿐이어야 하는데 코드에도
있고 DB에도 있어서, 둘이 어긋나는 순간 읽기가 통째로 죽는다"는 구조 자체였다. 6번 작업
(등록부 추가·삭제)에서 이 구조를 바꿨다.

- **식별자가 `Domain` enum에서 `DomainCode`(코드 문자열 하나를 감싼 값 타입,
  `global/common/DomainCode.java`)로 바뀌었다.** `DomainCode`는 형식(대문자로 시작하는
  대문자·숫자·밑줄 2~30자)만 검사하고 **존재는 검사하지 않는다** — "그런 분야가 실제로
  있나"는 DB의 사실이라 값 타입이 알 수 없다. 변환이 아예 없으니, 모르는 코드를 가진
  행이 있어도 **읽기는 절대 죽지 않는다**. `Enum.valueOf`가 사라졌기 때문이다.
- **존재 확인은 외래키(V20)가 넘겨받았다.** 문제·문서·생성 문제 초안·생성 문서 초안·
  주제 대기열, 이 다섯 표가 `domain_setting.domain`을 참조한다(아래 "외래키" 절). 모르는
  코드는 **쓰는 시점**에 거부되므로, 있는 행이 나중에 못 읽히는 일 자체가 생기지 않는다.
- 그 결과 2026-09-21의 두 우회(`findAllDomainNamesNative`·`deleteByDomainNameNative`,
  파일의 `domain`을 굳이 문자열로 받던 방어)는 **더 이상 필요 없어 코드에서 지웠다.**
  고아 행을 찾아 지우는 절차 자체가 사라졌기 때문이다(아래 "추가·삭제"). 내보내는 파일의
  `domain` 필드는 여전히 문자열이지만, 이제 그 이유는 "파싱이 안 죽게"가 아니라
  "`DomainCode`가 애초에 `@JsonValue`로 문자열 하나로 직렬화되기 때문"이다.

## 테이블 — 이제 이 표가 곧 등록부다

```sql
-- V19__domain_setting.sql
CREATE TABLE domain_setting (
    domain       VARCHAR(30)  NOT NULL,   -- 분야 코드(DomainCode). 그대로 PK
    enabled      BOOLEAN      NOT NULL,   -- 배치 자동 선택 후보인가
    sort_order   INT          NOT NULL,   -- 날짜 순환 순서
    display_name VARCHAR(40)  NOT NULL,   -- 화면에 뜨는 이름
    hint         TEXT         NULL,       -- 모델에게 주는 경계 설명
    PRIMARY KEY (domain)
);
```

숫자 대리키가 아니라 `domain` 자체를 PK로 쓴다. 이 테이블을 읽는 쪽은 늘 "NETWORK의
설정"을 찾지 "3번 행"을 찾지 않는다.

**행 수는 더 이상 고정이 아니다.** 예전에는 `Domain` enum 상수 수만큼(11개) 고정이었지만,
6번 작업(등록부 추가·삭제)부터는 "분야가 몇 개인지"의 진실이 코드가 아니라 이 표다 —
관리자가 화면에서 직접 행을 늘리고 줄인다(아래 "추가·삭제").

### 외래키 — 존재를 지키는 DB 수준의 약속

```sql
-- V20__domain_foreign_keys.sql
ALTER TABLE problem                  ADD CONSTRAINT fk_problem_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE document                 ADD CONSTRAINT fk_document_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_problem_draft  ADD CONSTRAINT fk_gpd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_document_draft ADD CONSTRAINT fk_gdd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE topic_queue              ADD CONSTRAINT fk_topic_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
```

문제·문서·생성 문제 초안·생성 문서 초안·주제 대기열, 이 다섯 표가 `domain_setting.domain`을
가리킨다. `ON DELETE`·`ON UPDATE` 둘 다 기본값(RESTRICT)이다 — 코드는 바꾸지 않으므로
(아래 "추가·삭제") 연쇄 수정이 필요 없고, 내용이 있는 분야를 지우려는 시도는 애플리케이션이
막지 않아도 **DB가 최종적으로 거부한다.** [02-domain-enums](02-domain-enums.md)가 적어 둔
"엉뚱한 값은 타입이, 없는 값은 외래키가" 원칙이 실제로 이 다섯 표에서 동작하는 자리다.

V20이 외래키를 걸기 **전에** 먼저 하는 일이 하나 있다 — 다섯 내용 표에 나오는 코드 중
`domain_setting`에 없는 것을 꺼진 행으로 채운다(이름 = 코드 문자열 그대로). V19만 적용되고
앱이 한 번도 안 뜬 DB는 `domain_setting`이 비어 있는데, 그 상태에서 내용 표에 행이
있으면 외래키 추가 자체가 실패하기 때문이다. 콘텐츠를 새로 심는 것이 아니라 이미 있는
데이터가 스스로 일관되게 만드는 것이라 "Flyway엔 스키마만"([11-flyway-migrations](11-flyway-migrations.md))의
취지와 어긋나지 않는다. 2026-09-22 실측으로 로컬 DB에는 채울 행이 0건이었다.

### 빈 표만 시드한다 — `syncWithEnum`이 `seedIfEmpty`가 된 이유

**행은 Flyway가 채우지 않는다**(스키마만 담는다는 원칙은 그대로). 예전에는 기동 시
`DomainSettingSyncRunner`가 `Domain` enum을 진실로 삼아 **매번** 표를 맞췄다 — enum에는
있는데 행이 없으면 만들고, 행이 있는데 enum에 없으면 지웠다(`syncWithEnum`). 그 동작을
지금 그대로 두면 안 된다. 관리자가 화면에서 새로 추가한 분야는 **정의상 코드 어디에도
없는 이름**인데, 다음 기동에 "기본 목록에 없다"는 이유로 조용히 지워지면 등록부라는
말이 무색해진다.

그래서 이 러너의 메서드 이름과 역할이 바뀌었다 — `DomainSettingService.seedIfEmpty()`는
**표가 완전히 비어 있을 때만** 기본 11개(`DefaultDomains`)로 채우고, **행이 하나라도
있으면 아무것도 하지 않는다.** 새로 만드는 것도, 지우는 것도 없다. 지우는 것은 이제
관리자가 화면에서 명시적으로 삭제를 요청할 때뿐이다(아래 "추가·삭제"). `enabled`·
`sortOrder`는(시드가 실제로 도는 순간에 한해) `application.yml`의
`llm.generation.batch-domains`를 초기값으로 쓰고, `displayName`·`hint`는
`DefaultDomains`·`DomainHints.BUILT_IN`(코드에 박혀 있던 옛 값)에서 가져온다. 그 값이
행으로 태어난 **다음부터는** 이 값이 바뀌어도 기존 행은 절대 따라 바뀌지 않는다 —
관리자가 화면에서 고쳐 둔 값을 기동마다 되돌리면 그 화면은 아무도 못 믿게 된다.

### 추가·삭제 — 등록부를 실제로 등록부답게 만드는 절차

- **추가**(`DomainSettingService.create`, `POST /api/admin/domain-settings`): 코드·이름을
  받아 새 행을 만든다. 코드 형식은 `DomainCode`가 역직렬화 단계에서 이미 막으므로 서비스가
  다시 확인하는 것은 "이미 쓰는 코드인가"뿐이다(중복이면 400, `DOMAIN_004`). **새 행은
  항상 꺼진 채로, 순서는 맨 끝에** 생긴다 — 켜진 채로 태어나면 관리자가 힌트도 못 적어 둔
  분야가 바로 다음 배치 순환에 끼어들고, 순서를 중간에 끼워 넣으면 이미 굳어진 날짜
  순환에서 기존 분야들의 자리가 밀린다.
- **삭제**(`DomainSettingService.delete`, `DELETE /api/admin/domain-settings/{domain}`):
  **문제·문서·생성 문제 초안·생성 문서 초안(거절된 것 포함)·주제 대기열, 이 다섯 표 중
  하나라도 그 코드를 쓰는 행이 있으면 400(`DOMAIN_005`)으로 거절한다** — 메시지에 어느
  표에 몇 건인지가 그대로 실린다. 옮기거나 숨기는 방법은 없다. 후보는 셋이었다 — 옮기고
  삭제, 내용 있으면 막기, 숨김(보관). "막기"를 골랐다(2026-09-22 결정). 가장 단순하고,
  숨김처럼 모든 목록 조회에 "숨김 제외" 조건을 붙일 필요가 없기 때문이다 — 숨김 방식은
  조건 하나를 빠뜨리면 지워진 분야가 그 화면에만 조용히 다시 보이는 위험을 늘 안고 간다.
  내용이 쌓인 분야는 **끄기**(`enabled=false`)로 순환에서 빼면 된다.
  거절된 초안도 세는 이유는, 거절 사유가 다음 생성 프롬프트에 되먹이는 학습 자료라 분야를
  지우면 그 되먹임 근거까지 함께 사라지기 때문이다. 마지막으로 켜진 분야는(내용이 없어도)
  400(`DOMAIN_002`)으로 막고, 없는 분야는 404(`DOMAIN_001`)다. **기본 11개도 특별 취급하지
  않는다** — 내용이 없으면 기본 분야든 나중에 추가한 분야든 같은 규칙으로 지워진다. 특별
  취급을 넣는 순간 "이 열한 개는 못 지운다"는 목록이 다시 코드에 박히고, 등록부가 아니라
  "enum + 추가분"으로 되돌아간다.
- **경쟁 조건.** "내용 건수 확인 → 삭제" 사이에 다른 요청이 그 분야로 문제를 만들면
  외래키가 삭제를 최종적으로 거부한다. 관리자가 한 명이라 사실상 일어나지 않고, 일어나도
  DB가 막으니 데이터는 안전하다 — 다만 그 경우엔 오류가 500(DB 제약 위반)으로 보일 수
  있는데, 여러 명이 쓰게 되면 그때 409로 바꾸는 처리기를 붙이기로 하고 지금은 받아들인다.

**따라서 분야를 늘리거나 줄이는 데는 마이그레이션이 필요 없다.** 화면에서 추가·삭제
버튼을 누르는 것으로 끝난다([02-domain-enums](02-domain-enums.md) 참고).

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

`/admin/settings.html`. 분야가 순환 순서대로 한 줄씩 놓이고, 줄마다 켜짐·이름·힌트를
고친다. 순서 이동(▲▼)은 누르는 즉시 반영되고, 켜짐·이름·힌트는 줄마다 있는 저장
버튼을 눌러야 반영된다 — 일괄 저장 버튼을 두지 않은 이유는 버튼이 하나면 "무엇을
저장했는지"가 흐려지기 때문이다.

힌트 입력칸 아래에는 실제로 프롬프트에 어떻게 실리는지(앞 공백과 괄호까지 포함)를
그대로 보여 준다 — 힌트는 프롬프트에 그대로 이어 붙는 값이라, 화면에서 고치는 순간
**재배포 없이 생성 품질이 바뀐다.** 좋아지는 쪽으로도, 나빠지는 쪽으로도. 500자
상한을 둔 것도 문서를 통째로 붙여 넣는 실수가 매 배치 요금으로 돌아오는 것을 막기
위해서다.

**목록 위에 "분야 추가" 폼이 있다.** 코드·이름 두 칸과 추가 버튼뿐이다 — 힌트는 여기서
같이 받지 않는다. 추가한 직후 관리자가 가장 먼저 할 일은 "이 분야가 뭘 다루나"를 적는
것이고, 그 자리는 이미 있다(줄마다 펼치는 편집칸의 힌트 입력). 추가에 성공하면 그 칸이
자동으로 펼쳐져 힌트 입력에 초점이 간다. 새 줄은 **꺼진 채로 맨 끝에** 나타난다.

**삭제는 줄에 늘 떠 있지 않고, 편집칸을 펼쳐야 보인다.** 자주 쓰지 않고 되돌릴 수 없는
동작이라 한 걸음 물려 뒀다. 누르면 브라우저 확인창이 한 번 더 뜨고, 서버가 거절하면
(`DOMAIN_005`) "어느 표에 몇 건" 메시지를 화면이 그대로 보여준다.

## API

| 메서드 | 경로 | |
|---|---|---|
| GET | `/api/admin/domain-settings` | 목록 — `sortOrder` 순 전체(꺼진 분야도 포함) |
| POST | `/api/admin/domain-settings` | 분야 추가(코드·이름·힌트). 꺼진 채 맨 끝에 생성, 201 |
| DELETE | `/api/admin/domain-settings/{domain}` | 분야 삭제. 다섯 표 중 하나라도 내용이 있으면 400(`DOMAIN_005`) |
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

1. **표가 완전히 비어 있을 때, 시드(`seedIfEmpty`)가 기본 11개 행을 만들며 쓰는 초기값.**
   그 목록에 있으면 첫 `enabled`가 `true`로, 없으면 `false`로 행이 태어난다. 행이 하나라도
   이미 있으면 이 값은 읽히지 않는다 — enum 시절처럼 "새 상수가 생길 때마다" 참고하는
   값이 아니다(위 "빈 표만 시드한다" 참고).
2. **`_domain-settings.json` 파일이 없을 때 클라우드 배치가 쓰는 폴백.** 폴백 조건은
   `DomainSettings.isEmpty()` 하나다 — 파일이 없거나, 깨졌거나, 분야 배열이 비었을 때만
   이 값으로 대신 돈다. 그것마저 비어 있으면 `DefaultDomains`의 기본 11개로 한 번 더
   물러난다. 파일은 있는데 켜진 분야가 0개면 yml로 가지 **않고** 파일의 모든 항목(꺼진
   것 포함)으로 넓힌다 — 파일이 배치의 등록부이므로, 등록되지 않은 분야로 넓힐 수는
   없기 때문이다.

### "전체 분야"의 뜻 — 자리마다 다르다

`Domain.values()`가 있던 시절에는 "전체"가 하나였다. 지금은 자리마다 다르고, 순서대로
넓혀 간다.

| 어디서 | "전체" = |
|---|---|
| 앱(`LlmProblemService` 등) | `domain_setting`의 **모든 행**(꺼진 것 포함) |
| 배치 — 설정 파일 있음 | 그 파일의 **모든 항목** |
| 배치 — 파일 없음(깨졌거나 빈 배열 포함) | `application.yml`의 `batch-domains` |
| 배치 — 파일도 yml도 없음 | `DefaultDomains`의 기본 11개 |

마지막 줄이 있어야 관리 화면을 한 번도 안 쓴 새 저장소(설정 파일이 아예 커밋된 적 없는
경우)에서도 배치가 돈다. 앱 쪽 "전체"가 등록부의 모든 행이어야 하는 이유는, 관리자가
화면에서 분야를 막 추가만 하고 아직 하나도 안 켠 순간(켜진 분야 0개)에도 그 새 분야가
"빈 칸" 통계·직접 지정 같은 자리에서 빠지면 안 되기 때문이다 — 기본 11개로 넓히면
새로 추가한 분야는 계속 안 보인다.

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
그래서 나중에 분야를 화면에서 직접 추가·삭제하게 만들 때, 식별자를 enum에서 형식만
검사하는 값 타입(`DomainCode`)으로 바꾸고 '존재하는가'는 외래키에 맡겼습니다. 그리고
삭제는 숨김 처리 대신 내용이 있으면 거절하는 쪽을 골랐어요 — 숨김이었다면 모든 목록
조회에 '숨김 제외' 조건을 붙여야 했고, 그중 하나만 빠뜨려도 지운 분야가 학습자 화면에
다시 보이는 사고로 이어졌을 겁니다."
