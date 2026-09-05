# 13. LLM 문제 생성 (B안)

> 결정 배경: [ADR-0006](adr/0006-llm-problem-generation.md) · 관련: [14-llm-batch-automation](14-llm-batch-automation.md)(일일 배치 자동화) · [12-daily-quiz](12-daily-quiz.md)(문제 풀 소비처) · [02-domain-enums](02-domain-enums.md)(BACKEND_FRAMEWORK 신설)
>
> ⚠️ **일일 배치는 이 문서를 벗어났다.** 앱 내부 `@Scheduled`는 제거되고 GitHub Actions로
> 옮겨졌다(2026-08-11) — 개인 PC에서 도는 앱이라 예약 시각에 PC가 꺼져 있으면 배치가
> 통째로 실행되지 않았기 때문. 자동화 전체는 [14번 문서](14-llm-batch-automation.md) 참고.
> 아래 1~4단계(생성 규격·초안 격리·검수)는 그대로 유효하다.
>
> ⚠️ **난이도 정의와 오답 규격은 [17번 문서](17-prompt-quality-and-eval.md)에서 다시 잡았다**(2026-08-17).
> 초·중·고를 "얼마나 어려운가"가 아니라 **무엇을 묻는가**로 갈랐고, 셀 수 있는 품질 규칙
> (해설 분량·지문 길이·보기 길이 편향 등)은 `ProblemItemRule`이 세도록 옮겼다.
> 아래 생성 규격 중 난이도·오답 관련 서술은 17번이 최신이다.

## 쉽게 말하면

오늘의 퀴즈는 문제 창고에서 매일 10문제를 골라 주는 기능인데, 창고가 작으면 금방
바닥난다. 이 기능은 **Claude(AI)에게 문제를 만들게 해서 창고를 채우는 공장**이다. 🏭

단, AI가 만든 문제는 바로 출제되지 않는다. 반드시 이 순서를 거친다:

```
생성(Claude) → 검수 대기(초안) → 관리자 승인 → 정식 문제 → 퀴즈에 출제
                              ↘ 거절 → 이력만 남음
```

AI도 가끔 틀린 문제를 만들기 때문(환각), 사람이 승인해야만 창고에 들어간다.

## 흐름

### 1) 무엇을 만들지 고르기 — 빈 칸 채우기 전략

문제 창고를 "도메인 11 × 난이도 3 = 33칸 서랍장"으로 보고, **가장 비어 있는 칸**부터
채운다. 관리자가 도메인·난이도를 직접 고를 수도 있고, "자동"으로 두면 서비스가
`GROUP BY` 집계로 최소 칸을 찾는다(집계에 없는 칸 = 0문제로 보정).

### 2) 생성 — 구조화 출력(Structured Output)

"JSON으로 줘"라고 프롬프트로 부탁하는 게 아니라, record 클래스에서 파생된
**JSON 스키마를 API에 보내 응답 형식을 강제**한다(`outputConfig(Batch.class)`).
그래서 파싱 실패 처리 코드가 없다 — 형식이 보장되기 때문.

- 형식(스키마)이 못 막는 **내용 규약**(객관식 정답 정확히 1개, OX answer는 O/X)은
  저장 전 정규화에서 걸러서 위반 항목만 건너뛰고(부분 성공), 승인 시
  AdminProblemService가 한 번 더 검증한다(이중 게이트).
- **중복 방지**: 같은 도메인의 기존 문제 + 검수 대기 초안의 질문 목록을 프롬프트에
  실물로 넣는다("비슷한 것 금지"보다 실물 목록이 훨씬 잘 지켜진다).
- **거절 사례 되먹이기**: 검수자가 거절한 문제와 사유(최근 20건)를 프롬프트에 넣어 같은
  실수를 줄인다. 시스템 프롬프트의 금지 규칙이 "일반론"이라면 이건 우리 검수자의 "판례"다.
  → 상세: [14-llm-batch-automation](14-llm-batch-automation.md)
- 프롬프트 구조: 역할·품질 기준은 system(불변), 도메인·개수 등 가변값은 user —
  프롬프트 캐시가 앞부분을 재사용할 수 있는 배치.

### 3) 저장 — 초안 테이블 격리

`generated_problem_draft`(V6)에 PENDING으로 저장한다. problem에 status를 얹지 않은
이유는 ADR-0006 — 기존 모든 조회에 필터가 번지는 구조 대신, 미검수 문제가
problem에 아예 존재하지 않는 구조를 택했다. 보기는 JSON 문자열(`choices_json`)로
저장한다(초안은 채점에 안 쓰이므로 정규화 이득 없음).

### 4) 검수 — 관리자 승인/거절

- **승인**: 초안 → `AdminProblemRequest` 변환 → **AdminProblemService.create 재사용**
  (손 등록과 같은 검증 통과 필수) → 생성된 problem.id를 초안에 이력으로 기록.
- **거절**: 상태만 REJECTED로 바꾸고 사유를 남긴다(삭제하지 않음 — 프롬프트 개선 재료).
- 상태 전이는 단방향 `PENDING → APPROVED | REJECTED`. 중복 처리(승인 버튼 2번)는
  서비스 선제 검사 + 엔티티 전이 규칙(LLM_002)의 이중 방어.

### 5) 트리거 — 수동 버튼 + 일일 배치(GitHub Actions)

- **수동**: 관리자 화면 "생성" 탭에서 **근거를 먼저 고르고** 도메인·난이도·유형·개수를 정해 즉시 생성.

  | 근거 | 보내는 곳 | 개념 문서 읽기 링크 | 재료 검사 | "자동" 칸 |
  |---|---|---|---|---|
  | 등록된 문서 | `/generate-from-document` | 붙음 | 걸림 | 없음(직접 골라야 함) |
  | 붙여넣기·파일 | `/generate-from-document` | 안 붙음 | 안 걸림 | 없음 |
  | 없음 | `/generate` | 안 붙음 | 대상 아님 | **있음** — 가장 부족한 칸 자동 선택 |

  근거가 "없음"일 때만 DB 집계 기반 **"가장 부족한 칸" 자동 선택**이 뜻을 가진다. 문서를 줬는데
  분야를 자동으로 고르면 문서와 무관한 분야가 붙으므로, 그 경우 화면이 "자동" 선택지를 아예 없앤다.

  2026-09-01에 카드 둘("AI 문제 생성" + "문서로 문제 만들기")을 **하나로 합쳤다.** 갈라 놓았더니
  위 카드가 먼저 눈에 들어와 *근거 없는 생성이 기본값*처럼 동작했다 — 문서를 쓸 수 있는 주제인데도
  위에서 눌러 놓고 나중에 "이건 왜 문서 링크가 없지"를 묻게 된다. 근거마다 폼을 따로 두는 대안은
  분야·난이도·유형·개수 네 칸을 세 벌 복사하게 되어 버렸다. 다른 것은 근거 하나뿐이므로 그 한 줄만
  고르게 하고, 고른 것만 펼친다. 덕분에 "등록 문서·파일·붙여넣기 중 하나만" 오류가 **날 수 없게** 됐다.

  근거 없는 생성을 지우자는 안도 있었으나 보류했다. 2026-09-01 기준 개념 문서가 있는 분야는
  **6/12**라(운영체제·소프트웨어공학·스프링·백엔드·클라우드·인프라·프론트엔드CS·통합시나리오 없음),
  지금 지우면 저 여섯 분야에는 문제를 한 개도 만들 수 없다. 12분야에 문서가 다 생기면 그때 지운다.
- **배치**: GitHub Actions가 매일 06:17에 생성해 저장소에 커밋하고, 앱이 기동할 때
  검수 대기함으로 들여온다. 러너에는 DB가 없어 대상 선택은 **날짜 기반 4일 주기**를 쓴다
  (0일차 개념 문서 → 1·2·3일차 그 문서를 근거로 초·중·고급 문제. 근거 문서를 못 찾으면
  옛 24칸 순환으로 폴백). → 상세: [14](14-llm-batch-automation.md)·[15](15-llm-concept-documents.md),
  사용법은 [16](16-llm-pipeline-operations.md)
- ADR-0005(오늘의 퀴즈)는 배치를 버렸는데 여기서는 채택한 이유: 실패 비용의 차이.
  생성 배치는 하루 안 돌아도 사용자가 아무것도 잃지 않는다. **다만 "하루쯤 건너뛴다"와
  "한 번도 안 돈다"는 다른 이야기였고**, 개인 PC의 `@Scheduled`는 후자였다(14번 문서).

## API (전부 /api/admin/** — ADMIN 전용)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/admin/llm-problems/generate` | 생성 트리거. `{domain?, difficulty?, type?, count}` — domain/difficulty null이면 자동 선택 |
| GET | `/api/admin/llm-problems?status=PENDING` | 초안 목록(기본 PENDING, 오래된 순) |
| GET | `/api/admin/llm-problems/pending-count` | 검수 대기 건수(탭 배지용) |
| POST | `/api/admin/llm-problems/{id}/approve` | 승인 → 201 + 생성된 문제 상세 |
| POST | `/api/admin/llm-problems/{id}/reject` | 거절. `{"reason": "..."}` (선택) |

에러 코드: `LLM_001`(초안 없음 404) · `LLM_002`(이미 처리됨 409) ·
`LLM_003`(Claude API 실패 502) · `LLM_004`(API 키 미설정 503)

## 설정

```yaml
llm:
  generation:
    model: claude-opus-5       # 초안의 model 컬럼에 기록 — 모델별 승인율 비교 재료
    batch-enabled: true        # 중단 스위치
    batch-type: auto           # auto | problem | document — 한쪽만 돌리고 싶을 때
    batch-count: 5             # 폴백 — 아래 난이도별 값이 없을 때만
    batch-count-by-difficulty: BEGINNER=7,INTERMEDIATE=5,ADVANCED=3   # 난이도별 생성 개수
    batch-domains: NETWORK,OS,DATABASE,...   # 배치 후보 도메인(= 날짜 순환 순서)
  import:
    enabled: true              # 기동 시 generated/*.json 들여오기
    dir: generated
```

`batch-cron`은 제거됐다 — 예약의 주체가 앱에서 GitHub Actions로 옮겨졌으므로 시각은
워크플로의 `schedule`이 정한다. `batch-enabled`는 남아 있지만 이제 **CLI가 읽는 중단 스위치**다
(앱의 스케줄러가 아니라). → [16 §3](16-llm-pipeline-operations.md#3-설정--applicationyml)

API 키는 설정 파일에 두지 않는다 — SDK가 `ANTHROPIC_API_KEY` 환경변수를 직접 읽는다.
키가 없어도 앱은 정상 기동하고(클라이언트 지연 생성), 생성 기능만 LLM_004로 안내된다.

## 구현 메모 (왜 이렇게 했나)

- **ProblemGenerator 인터페이스 분리**: 실제 Claude 호출(ClaudeProblemGenerator)과
  서비스 로직을 가른다. 테스트는 가짜 생성기를 주입해 API 비용 없이 로직만 검증
  (LlmProblemServiceTest). 외부 세계 경계에 인터페이스를 두는 포트-어댑터 패턴.
- **Claude 호출은 트랜잭션 밖**: 수십 초짜리 외부 I/O가 DB 커넥션을 점유하면
  커넥션 풀 고갈 — 호출이 끝난 뒤 저장만 짧은 트랜잭션으로.
- **테스트가 잡은 버그**: 승인 시 "이미 처리됨" 검사를 등록 뒤에 하던 순서 결함을
  approveTwiceFails 테스트가 발견 → 검사를 등록 앞으로 이동.
