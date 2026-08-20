# 16. LLM 파이프라인 운영 매뉴얼 (사용법 · 핵심 코드 지도)

> **이 문서의 성격이 다르다.** [13](13-llm-problem-generation.md)·[14](14-llm-batch-automation.md)·[15](15-llm-concept-documents.md)는
> **"왜 이렇게 설계했나"**를 남긴 문서다(면접 대본용). 이 문서는 **"그래서 어떻게 쓰나"**만 다룬다.
> 설정을 바꾸거나, 배치가 돌았는지 확인하거나, 어디를 고쳐야 할지 찾을 때 여기부터 본다.
>
> 판단의 근거가 궁금하면 각 절의 링크를 따라가면 된다.

---

## 1. 한 장으로 보는 전체 흐름

```
 ┌─ 클라우드 (GitHub Actions) ───────────────────────────────┐
 │  매일 06:17 KST · 내 PC가 꺼져 있어도 돈다 · API 키는 여기에만  │
 │                                                          │
 │   DraftGeneratorCli  ──(오늘 뭘 만들지 날짜로 결정)──┐        │
 │        │                                          │       │
 │        ├─ 문서일 → ClaudeDocumentGenerator          │       │
 │        └─ 문제일 → ClaudeProblemGenerator ←─근거 문서 읽기    │
 │                          ↓                               │
 │              generated/*.json 으로 저장 → git commit & push │
 └──────────────────────────┬───────────────────────────────┘
                            │  (저장소가 곧 택배함)
 ┌──────────────────────────▼───────────────────────────────┐
 │─ 내 PC ─  git pull 후 앱 기동                              │
 │                                                          │
 │   DraftImportRunner @Order(10)   파일 → 검수 대기 초안       │
 │   RejectionNotesExporter  @Order(20) ┐                    │
 │   ExistingDocumentsExporter @Order(30) ├ DB → 스냅샷 파일    │
 │   ExistingQuestionsExporter @Order(40) ┘  (기동 시점의 상태)   │
 │                          ↓                               │
 │   관리자 화면에서 승인/거절  →  problem / document 테이블      │
 │          │               ↓                               │
 │          │         사용자에게 공개                          │
 │          └─ ReviewCompleted 이벤트 ─→ 스냅샷 다시 찍기        │
 │             (커밋 직후 · 커밋하면 다음 배치가 읽음)             │
 └──────────────────────────────────────────────────────────┘
```

> 스냅샷이 찍히는 시점이 **둘**이다 — 앱 기동 시, 그리고 **검수 직후**.
> 기동 시점만 있던 때는 검수한 내용이 다음 부팅까지 파일에 안 남았다(→ [§6](#6-스냅샷-파일-3종--dbrarr클라우드-단방향-통로)).

**핵심 제약 하나만 기억하면 된다: 클라우드에는 DB가 없다.**
러너는 몇 분 뒤 사라지는 임시 컴퓨터라 내 MySQL에 접근할 수 없다. 그래서
"기존 문제 목록", "거절 사유", "거절된 문서" 같은 **DB에만 있는 정보는 파일로 실어 보낸다**(→ [§6 스냅샷](#6-스냅샷-파일-3종--dbrarr클라우드-단방향-통로)).

---

## 2. 4일 주기 — 오늘 무엇이 나오는가

```
0일차  개념 문서 1편          ← 교과서
1일차  ↑ 그 문서로 초급 문제 5개  ┐
2일차  ↑ 같은 문서로 중급 문제 5개 ├ 문제집
3일차  ↑ 같은 문서로 고급 문제 5개 ┘
        → 다음 분야로 넘어감
```

계산은 **날짜만으로** 끝난다(`GenerationSchedule.planFor`).

```
dayInCycle   = 에포크일 mod 4        → 0이면 문서일, 1·2·3이면 초·중·고급
cycleIndex   = 에포크일 / 4          → 주기 번호
분야          = 후보도메인[cycleIndex mod 8]
근거 문서 날짜 = 오늘 - dayInCycle    → generated/documents/{그날짜}.json
```

후보 도메인이 8개이므로 **한 바퀴에 32일**(8분야 × 4일)이 걸린다.

### 직접 세어 보려면

```powershell
# 오늘이 주기 며칠차인지
[int]((Get-Date).Date - (Get-Date "1970-01-01")).TotalDays % 4
# 0=문서일 · 1=초급 · 2=중급 · 3=고급
```

실제 확인된 예 — 2026-08-13은 `2일차`라서 **중급 문제**가 나왔고, 근거 문서는
`8/13 - 2 = 8/11`의 캐시 전략 문서였다. 생성된 파일의 머리말이 이를 그대로 보여 준다.

```json
"domain": "SYSTEM_DESIGN", "difficulty": "INTERMEDIATE",
"documentSlug": "caching-strategies-and-failures"
```

### 근거 문서를 못 찾으면 — 폴백

문서 생성이 실패했거나 검수에서 **거절**된 주기에는 근거가 없다. 그래도 그날 문제는 나와야 하므로
**옛 규칙(24칸 순환, `GenerationSchedule.cellFor`)으로 돌아가** 분야·난이도를 매일 바꿔 가며 만든다.
프롬프트에 문서 블록이 빠질 뿐 생성 자체는 정상 동작한다.

로그에 이렇게 찍힌다.

```
근거 문서 없음, 폴백으로 생성합니다: generated/documents/2026-08-11.json
근거 문서가 검수에서 거절돼 폴백으로 생성합니다: caching-strategies-and-failures
```

> 폴백이 자주 보이면 **문서 검수가 밀렸다는 신호**다. 문제 품질이 2단계 이전으로 돌아간다.

---

## 3. 설정 — `application.yml`

건드릴 값은 실질적으로 **네 개**다.

```yaml
llm:
  generation:
    model: claude-opus-5      # CLI도 이 값을 읽는다(설정의 단일 출처)
    batch-enabled: true       # ① 전체 중단 스위치
    batch-type: auto          # ② 무엇을 만들지 (auto | problem | document)
    batch-count: 5            # 문제일 1회당 생성 개수
    batch-domains: NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,LANGUAGE_RUNTIME,BACKEND_FRAMEWORK
  import:
    enabled: true             # ③ 기동 시 흡수 여부
    dir: generated            # Actions가 커밋하는 위치와 반드시 같아야 한다
```

### ② `batch-type` — 한쪽만 돌리고 싶을 때

| 값 | 문서일(0일차) | 문제일(1·2·3일차) | 언제 쓰나 |
|---|---|---|---|
| `auto` (기본) | 문서 | 문제 | 평소 |
| `problem` | **문제** | 문제 | 문서 없이 문제만 쌓고 싶을 때 |
| `document` | 문서 | **아무것도 안 함**(요금 0) | 문서만 모으고 싶을 때 |

- `document`가 "매일 문서"가 아닌 이유: 매일 만들면 **요금이 네 배**다. 이건 "한쪽만 보고 싶다"는
  스위치이지 주기를 바꾸는 도구가 아니다.
- `problem`의 부작용: 문서를 안 만들면 근거 문서가 안 생겨 **배치가 계속 폴백으로 돈다.**
  결국 문제들이 서로 남남이던 2단계 이전 상태로 돌아간다 — 그게 문서를 도입한 이유였다.
- **모르는 값(오타)은 `auto`로 본다.** 오타 하나로 배치가 통째로 멈추는 것보다 평소대로 도는 편이 낫다.

> ⚠️ 이 설정은 **커밋해야 반영된다.** 배치는 클라우드에서 저장소의 `application.yml`을 읽는다.
> 로컬에서만 바꾸면 아무 일도 일어나지 않는다.

### 우선순위

```
수동 실행의 type 입력   >   application.yml의 batch-type   >   날짜 주기
```

사람이 워크플로에서 "오늘 문서 뽑아"라고 눌렀는데 설정이 막으면 버그처럼 보인다.
수동 실행은 **그 한 번만 유효**해서 되돌리기를 잊는 사고도 없다.

### API 키

설정 파일에 **두지 않는다.** SDK가 `ANTHROPIC_API_KEY` 환경변수를 직접 읽고,
그 값은 GitHub 암호화 시크릿에만 있다. 키가 없어도 앱은 정상 기동하며 생성 기능만 `LLM_004`로 안내한다.

---

## 4. 실행 방법

### 예약 실행 (평소)

아무것도 안 해도 된다. 매일 **06:17 KST**에 자동으로 돈다.

> **실측 지연 48~50분** (2026-08-12·13 두 번 관측 → 07:05, 07:07 시작).
> GitHub의 `schedule`은 보장이 아니라 최선노력이라 혼잡하면 밀린다. 무해하므로 cron은 건드리지 않는다.

### 수동 실행 (GitHub 화면)

`Actions 탭 → "LLM 일일 생성" → Run workflow`

| 입력 | 비우면 | 쓸 때 |
|---|---|---|
| `type` | `auto` = 주기가 결정 | `document` / `problem` 강제 |
| `topic` | 모델이 기존 제목 피해 자동 선택 | 문서 주제 지정 (`type=document`일 때만) |
| `domain` | 날짜 주기 | `NETWORK` 등 분야 강제 |
| `difficulty` | 날짜 주기 | `BEGINNER`/`INTERMEDIATE`/`ADVANCED` |
| `count` | `batch-count`(5) | 개수 강제 |
| `date` | 오늘(한국 시간) | 특정 날짜로 생성 |
| `force` | 꺼짐 | `batch-enabled=false`여도 이번 한 번만 생성 |

**같은 날짜 파일이 이미 있으면 아무것도 하지 않는다**(멱등성). 두 번 눌러도 요금이 두 번 나가지 않는다.
다시 만들고 싶으면 그 파일을 지우고 실행한다.

### 로컬 실행

```powershell
.\gradlew.bat generateDrafts --console=plain -PdraftArgs="--domain=NETWORK --count=1"
```

⚠️ **이 PC에는 API 키가 없다**(일부러 그렇게 뒀다 — 윈도우 사용자 환경변수는 레지스트리에 평문으로
저장돼 내 계정으로 도는 아무 프로그램이나 읽을 수 있다). 실제 호출까지 하려면 키가 필요하므로,
평소에는 **워크플로 수동 실행 쪽이 정답**이다.

### 결과 받아 오기

```powershell
git pull                 # 배치가 커밋한 generated/*.json 내려받기
.\gradlew.bat bootRun    # 기동하면서 검수 대기함으로 흡수
```

**앱을 켜기 전에 `git pull`을 해야 한다.** 파일이 없으면 흡수할 것도 없다.
(오늘 겪은 그대로 — 앱은 떠 있었지만 파일을 안 받아서 검수함이 비어 있었다.)

### 검수한 뒤 — 스냅샷 커밋

```powershell
git status                       # generated/_*.json 이 바뀌었는지 본다
git add generated/ && git commit -m "chore(llm): 스냅샷 갱신"
git push
```

**검수한 날만** 하면 된다. 배치가 초안을 만드는 것만으로는 스냅샷이 안 바뀐다 —
초안은 검수함에 쌓일 뿐 정식 테이블을 안 건드리기 때문이다.
2026-08-17부터 **검수 직후 바로 갱신**되므로 앱을 껐다 켤 필요는 없다(§6).

### 프롬프트 평가 (`evalPrompt`)

프롬프트를 고쳤을 때 나아졌는지 **몇 분 만에** 재는 도구다. 예약 실행과 무관하게
사람이 직접 돌린다. 자세한 배경은 **docs/17**.

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-api03-..."   # 그 창에서만 유효
.\gradlew.bat evalPrompt -PevalArgs="--count=3 --label=after-fix"
```

| 인자 | 기본값 | 뜻 |
|---|---|---|
| `--count` | 3 | 난이도당 생성 개수 |
| `--document` | `generated/documents`의 최신 파일 | 근거 문서 고정 |
| `--domain` | 문서에 기록된 분야 | 분야 강제 |
| `--label` | 없음 | 보고서 파일명 꼬리표 |
| `--out` | `eval` | 보고서 디렉터리 |

`eval/` 아래 보고서(`.md`)와 **생성 원본**(`.json`)이 함께 남는다. 원본이 있으면
검증기를 고친 뒤 **API 재호출 없이** 다시 채점할 수 있다.

⚠️ 실제 API를 부른다. `--count=3`이면 9문제로 **약 1,000원**, `--count=1`이면 약 400원.
`check`/`build`에 엮여 있지 않으므로 평소 빌드에서는 요금이 나가지 않는다.
배치 중단 스위치(`batch-enabled`)도 **보지 않는다** — 배치를 꺼 둔 채 프롬프트를 고치는
상황이 바로 이 도구가 필요한 때이기 때문이다.

---

## 5. 배치를 멈추고 되살리는 법

| 무엇을 멈추나 | 방법 | 반영 속도 | 언제 |
|---|---|---|---|
| 생성 전체 | Actions 탭 → **Disable workflow** | 즉시 | **급할 때** |
| 생성 전체 | `batch-enabled: false` + 커밋 | 다음 실행부터 | **오래 꺼둘 때**(이유가 이력에 남는다) |
| 한쪽만 | `batch-type: problem` 또는 `document` | 다음 실행부터 | 문서/문제 중 하나만 |
| 흡수 | `import.enabled: false` | 앱 재기동 | 파일은 쌓되 검수함은 비워 둘 때 |
| 진행 중인 1회 | Actions에서 Cancel | 즉시 | 이미 호출했다면 요금은 나간다 |

**버튼과 설정을 둘 다 두는 이유**: 버튼은 빠르지만 GitHub 화면 깊숙한 곳에 있어서, 몇 주 뒤
"왜 요즘 문제가 안 들어오지?" 할 때 원인을 찾기 어렵다. 설정은 느리지만 **왜 껐는지가 커밋 메시지에 남는다.**

꺼진 상태에서 한 번만 돌리려면 수동 실행의 `force=true`를 쓴다 — 설정을 켰다가 되돌리는 것을 잊어
**끈 줄 알았던 배치가 계속 도는** 사고를 막는 구멍이다.

> `batch-enabled: false`일 때 CLI는 **종료 코드 0으로 정상 종료**한다.
> 실패로 죽이면 매일 알림 메일이 오고, 그러면 진짜 사고 알림까지 무시하게 된다.

---

## 6. 스냅샷 파일 3종 — DB→클라우드 단방향 통로

클라우드는 DB를 못 보므로, DB에만 있는 정보를 **파일로 내보내고 사용자가 커밋한다.**

| 파일 | 내보내는 클래스 | 담는 것 | 배치가 쓰는 곳 |
|---|---|---|---|
| `_existing-questions.json` | `ExistingQuestionsExporter` `@Order(40)` | 정식 문제 지문(분야별 최근 50건) | 중복 회피 목록 |
| `_rejection-notes.json` | `RejectionNotesExporter` `@Order(20)` | 거절 지문 + 사유(최근 20건) | "이런 실수 하지 마라" 판례 |
| `_existing-documents.json` | `ExistingDocumentsExporter` `@Order(30)` | 문서 제목·태그·**거절된 slug** | 주제 중복 회피 / 태그 재사용 / 거절 문서 배제 |

### 언제 찍히나 — 두 시점 (2026-08-17 변경)

| 시점 | 무엇이 담기나 |
|---|---|
| **앱 기동 시** (`ApplicationRunner`) | 그때까지의 DB 상태 |
| **검수 직후** (`@TransactionalEventListener(AFTER_COMMIT)`) | 방금 승인·거절·복구한 것까지 |

원래는 기동 시점 하나뿐이었다. 그런데 **검수는 앱이 켜진 뒤에** 한다.

```
앱 켬  → 스냅샷이 찍힌다 (아직 옛날 상태)
승인·거절 → DB가 바뀐다
앱 끔  → 파일은 그대로.  ← 바뀐 내용이 어디에도 안 남는다
```

다음에 앱을 한 번 더 켜야 반영됐다. **2026-08-17에 실제로 겪었다** — 파일에는 08-14가
찍혀 있고 DB는 08-17이었으며, 그 사이 승인한 4문항이 회피 목록에 없었다. 그대로 배치가
돌았다면 **방금 승인한 문제를 다시 만들어** 냈을 것이다.

`LlmProblemService`·`LlmDocumentService`의 승인·거절·복구 **6곳**에서
`ReviewCompleted` 이벤트를 발행하고, 내보내기 셋이 듣는다.

- **왜 이벤트인가**: 검수 서비스가 파일 경로·직렬화·변경 비교를 알 필요가 없다.
  직접 부르면 스냅샷이 하나 늘 때마다 검수 코드를 고치게 된다.
- **왜 하필 `AFTER_COMMIT`인가**: 그냥 `@EventListener`면 **커밋 전에** 실행되어 아직
  반영되지 않은 상태를 찍는다 — 고치려던 "한 박자 늦음"이 그대로 남는다. 게다가 뒤이어
  롤백되면 **일어나지도 않은 승인이 파일에 남는다.**
- **실패해도 검수는 성공으로 남는다.** 커밋이 끝난 뒤라 되돌릴 게 없다.

> **문서 복구가 특히 중요하다.** 거절한 문서를 되돌리면 배치의 "쓰지 마라"
> 목록(`rejectedSlugs`)에서 빠진다. 안 찍으면 배치가 **거절한 문서를 다시 근거로 삼아
> 사흘 치 문제를 만든다.**

### 세 가지 공통 규칙

1. **흡수(`@Order(10)`)보다 뒤에 돈다.** 방금 들어온 초안의 제목까지 스냅샷에 담기게 하기 위함이다.
   순서가 없으면 스프링이 러너를 **맨 뒤**로 돌려 "하루 늦게 반영"되는 조용한 버그가 생긴다
   (실제로 그렇게 돼 있었고, `@Order`를 붙여 고쳤다).
2. **내용이 같으면 파일을 다시 쓰지 않는다.** 매 부팅마다 "내보낸 시각"만 바뀐 파일을 쓰면
   앱을 켤 때마다 git이 변경으로 인식해서 **진짜 바뀐 날을 알아볼 수 없다.**
3. **커밋해야 반영된다.** 앱이 직접 `git push`를 하지 않는다 — 애플리케이션이 자기 소스 저장소에
   쓰기를 시작하면 권한·인증·충돌 처리가 줄줄이 따라온다.

```
스냅샷 갱신: generated\_rejection-notes.json (3건) — 커밋하면 다음 배치부터 반영됩니다
```

이 로그가 보이면 **커밋할 것.**

### 잊으면 배치가 알려 준다

커밋을 잊으면 배치가 옛 목록으로 중복 회피를 해서 **이미 있는 문제가 또 나온다.** 에러는 안 난다.

그래서 CLI가 스냅샷의 `exportedAt`을 보고 **14일 넘게 그대로면 Actions 요약 화면에 경고**한다.

```
⚠️ 스냅샷이 14일 넘게 그대로입니다 — 2026-08-30
- `_existing-questions.json` (2026-08-10)
→ 로컬에서 앱을 한 번 켜고 git add generated/ 후 커밋하세요.
```

**앱이 아니라 배치가 알리는 이유**: 앱이 "이 파일 커밋 안 됐다"를 알려면 앱 안에 git을 심어야
하는데, docs/14에서 일부러 피한 방향이다. 반면 배치는 **커밋된 파일만** 보므로
`exportedAt`이 곧 마지막 커밋 시점이다 — git 없이 같은 것을 알 수 있고,
**문제가 실제로 터지는 자리**에서 알린다.

- **14일인 이유**: 스냅샷은 내용이 바뀔 때만 갱신되므로 며칠 그대로인 건 정상이다.
  짧게 잡으면 매일 울리고, 그러면 사람이 경고를 무시하게 된다.
- **날짜를 못 읽으면 경고하지 않는다.** 오탐이 쌓이면 진짜 경고까지 함께 묻힌다.
- **job을 실패시키지 않는다.** 중복이 날 수 있다는 뜻이지 생성이 불가능한 건 아니다.

---

## 7. 핵심 코드 지도

### 클라우드에서 도는 것 (Spring 없음)

| 클래스 | 하는 일 | 특히 볼 곳 |
|---|---|---|
| `llm/cli/DraftGeneratorCli` | **배치 진입점.** 설정 읽기 → 중단 스위치 → 오늘 할 일 결정 → 근거 문서 찾기 → 호출 → 파일 저장 | `main`의 ①~⑧ 단계 주석 |
| ㄴ `decideAction` | `auto`/`problem`/`document` 판단 (수동 > 설정 > 주기) | 진리표는 `DraftGeneratorCliTest` |
| ㄴ `findSourceDocument` | 근거 문서 읽기. 없거나 거절이면 `null`(폴백) | |
| ㄴ `hasMaterialFor` | **오늘 난이도가 캘 절이 문서에 있는지** — 없으면 폴백 | 파일·본문·거절만 보던 세 관문이 다 통과하는데 재료만 없는 문서가 실제로 있었다(docs/17) |
| ㄴ `checkYield` / `reportYield` | 수확량 + **품질 경고 6종** | 경고는 세기만 하고 버리지 않는다 |
| ㄴ `documentDomain` | 문서를 만들 분야 | **여기서 버그가 났다** — 옛 규칙을 쓰면 8개 중 2개만 돈다 |
| ㄴ `alignDomainWithDocument` | 주기 분야 ≠ 문서 분야면 **문서 쪽으로 맞춤** | 모순된 프롬프트를 막는 장치. **매일 발동하면 본선 고장 신호** |
| ㄴ `isStaleSnapshot` | 스냅샷이 14일 넘게 그대로인지 | 못 읽으면 경고 안 함(오탐 방지) |
| `llm/support/GenerationSchedule` | `planFor` = 4일 주기 / `cellFor` = 24칸 폴백 | `floorMod`/`floorDiv`를 쓰는 이유 |
| `llm/client/ClaudeProblemGenerator` | 프롬프트 조립 + Claude 호출(구조화 출력) | `appendSourceDocument`, `sourceFocus` |
| ㄴ `SOURCE_SECTIONS` | 난이도별로 캘 절 — **절 이름의 단일 출처** | 두 파일에 흩어져 있어 사고가 났다(docs/17) |
| ㄴ `difficultyExample` | 난이도별 통 예시(보기 + 판정 근거) | 지문 한 줄로는 오답 설계가 전달되지 않는다 |
| `llm/cli/PromptEvalCli` | **평가 하네스** — 고정 문서로 3난이도 생성·채점 | 요금이 나가므로 `check`에 안 엮여 있다 |
| `llm/client/ClaudeDocumentGenerator` | 개념 문서 생성. `REQUIRED_SECTIONS` 보유 | 검증기가 이 목록을 공유한다 |
| `llm/client/SourceDocument` | 프롬프트에 실릴 문서(slug·제목·본문) | 분야를 안 담는다(중복이라) |

`DraftGeneratorCli`는 **Spring을 띄우지 않는다.** 필요한 건 Claude 호출뿐이라 DB도 웹서버도 없어도 되고,
띄우면 `ddl-auto: validate`가 MySQL을 요구해 러너에 DB 컨테이너를 붙여야 한다.

### 내 PC에서 도는 것 (Spring)

| 클래스 | 하는 일 | 특히 볼 곳 |
|---|---|---|
| `llm/service/DraftImportRunner` `@Order(10)` | 기동 시 `generated/`와 `generated/documents/` 스캔 | 두 폴더를 한 러너가 훑는다 |
| `llm/service/DraftImportService` | 문제 파일 1개 = 트랜잭션 1개 | 스캔/흡수를 다른 빈으로 나눈 이유 |
| `llm/service/DocumentImportService` | 문서 파일 흡수. `importKey`가 `documents/` 접두 | **파일명 충돌 지뢰** 참고 |
| `llm/service/LlmProblemService` | 문제 초안 저장(규약 검증)·승인·거절·복구 | 승인은 `AdminProblemService.create` 재사용 |
| `llm/service/LlmDocumentService` | 문서 초안 저장·승인·거절·복구 | 승인은 `AdminDocumentService.create` 재사용 |
| `llm/service/ReviewCompleted` | 검수 완료 신호 — 스냅샷 내보내기를 깨운다 | `AFTER_COMMIT`이 핵심(§6) |
| `llm/support/ProblemItemRule` | 문제 규약 판정(`defectOf`) + **품질 경고**(`qualityWarningsOf`) | 버릴 것과 알릴 것을 나눈 이유 |
| `llm/support/DocumentDraftValidator` | 문서 자동 검증(차단/경고) | 코드블록 예외가 없으면 보안 문서를 영영 못 쓴다 |
| `admin/service/AdminStatsService` | 대시보드 — **모델별 승인율** 포함 | `toModelStats`의 "검수 0건 = null" 규칙 |

**단일 경로 원칙**: 승인은 반드시 관리자가 손으로 등록할 때와 **같은 문**을 지난다.
직접 저장하면 slug 중복 검사·태그 find-or-create·캐시 무효화를 다시 구현해야 하고,
나중에 검색 색인 갱신이 추가되면 **AI가 승인한 것만 검색에 안 잡히는** 버그가 된다.

### 데이터가 지나가는 표

| 테이블 | 마이그레이션 | 역할 |
|---|---|---|
| `generated_problem_draft` | V6 (+V9 `document_slug`) | 문제 검수 대기함 |
| `generated_document_draft` | V8 | 문서 검수 대기함 |
| `imported_draft_file` | V7 | **파일명이 PK** — 중복 흡수 방지 |
| `problem.document_slug` | V9 | 문제 → 근거 문서 느슨한 연결(FK 아님) |

> `document_slug`를 FK로 걸지 않은 이유: 문제 초안이 **문서 승인보다 먼저** 존재할 수 있다.
> FK면 그 순간 저장이 실패한다.

### 관리자 API

| 메서드 | 경로 | |
|---|---|---|
| GET | `/api/admin/llm-problems?status=PENDING` | 초안 목록 |
| GET | `/api/admin/llm-problems/pending-count` | 탭 배지 |
| POST | `/api/admin/llm-problems/generate` | 즉시 생성(DB가 옆에 있어 **집계 기반 자동 선택**) |
| POST | `/api/admin/llm-problems/{id}/approve` \| `/reject` \| `/restore` | 검수 |
| GET | `/api/admin/llm-documents?status=PENDING` | 문서 초안 목록(기본 5건 — 본문이 크다) |
| GET | `/api/admin/llm-documents/pending-count` | 탭 배지 |
| POST | `/api/admin/llm-documents/{id}/approve` \| `/reject` \| `/restore` | 검수 |

문서 쪽에는 **`/generate`가 없다.** 한 편에 1~2분·$0.2가 드는 작업이라 화면에 버튼이 있으면
실수로 누르기 쉽고, 누른 뒤에는 응답이 올 때까지 화면이 멈춰 있어야 한다.

에러 코드: `LLM_001`(초안 없음 404) · `LLM_002`(이미 처리됨 409) · `LLM_003`(API 실패 502) ·
`LLM_004`(키 미설정 503) · `LLM_005`(자동 검증 차단 409)

---

## 8. 점검 절차 — "오늘 배치 돌았나?"

### ① 클라우드에서 만들어졌나

```powershell
git fetch origin
git log --oneline -3 origin/main
# → "chore(llm): 2026-08-13 문제 초안 자동 생성" 이 보이면 성공
```

커밋 메시지가 **"개념 문서 초안"인지 "문제 초안"인지**로 그날 무엇이 나왔는지 알 수 있다.
판단 근거는 실제로 추가된 파일 경로라 주기 계산과 어긋나지 않는다.

실행 상태를 더 자세히 보려면 GitHub `Actions` 탭. 실패하면 **소유자에게 메일이 온다** —
조용히 죽지 않는 것이 `@Scheduled`에서 옮겨 온 가장 큰 이득이다.

### ② 내 PC로 흡수됐나

```powershell
git pull
.\gradlew.bat bootRun --console=plain
```

기동 로그에서 확인:

```
초안 흡수: 2026-08-13.json — 5건 저장 (SYSTEM_DESIGN × INTERMEDIATE)
생성 결과 흡수 완료: 파일 1개에서 문제 초안 5건 + 문서 초안 0건
```

### ③ DB에 들어왔나

```powershell
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
"SELECT status, COUNT(*) FROM generated_problem_draft GROUP BY status;
 SELECT filename, draft_count FROM imported_draft_file;" |
  & $mysql -h 127.0.0.1 -P 3306 -ucsquiz -p csquiz -t
```

> 계정·비밀번호는 `docker-compose.yml`에 있다(로컬 개발용).
> `-p` **뒤를 띄우면** 비밀번호를 물어본다 — 붙여 쓰면(`-p비밀번호`) 셸 히스토리에 남는다.
> 마지막 `csquiz`는 DB 이름이다.

`imported_draft_file`에서 확인할 것:

```
2026-08-11.json             ← 문제
documents/2026-08-11.json   ← 문서  (폴더 접두사로 구분!)
```

**두 줄이 따로 있어야 정상이다.** 같은 이름인데 접두사가 없으면 문서가 "이미 처리함"으로
**예외도 로그도 없이** 건너뛰어진다 — 이 프로젝트가 가장 경계하는 조용한 실패다.

### ④ 스냅샷이 갱신됐나

```powershell
git status --short generated/
```

`_existing-*.json`이 변경으로 잡히면 **커밋해야 다음 배치에 반영된다.**

### ⑤ 생성 품질이 나빠지지 않았나

관리자 대시보드 → **AI 초안 승인율**.

| 종류 | 모델 | 검수 대기 | 승인 | 거절 | 승인율 |
|---|---|---|---|---|---|
| 문제 | claude-opus-5 | 5 | 10 | 0 | 100% |
| 문서 | claude-opus-5 | 0 | 2 | 0 | 100% |

- **검수 대기는 분모에서 뺀다.** 아직 판정 안 난 것을 실패로 세면 부지런히 만들수록
  승인율이 떨어지는 이상한 지표가 된다.
- **"검수 전"과 0%는 다르다.** 전자는 아직 아무도 안 본 것, 후자는 전부 거절당한 것이다.
- 모델·프롬프트를 바꾸기 **전에 이 값을 적어 두는 것**이 원칙이다(docs/14 "측정 없이 바꾸지 않는다").
  문서와 문제를 나눠 놓은 이유도 "모델을 바꿨더니 문서만 나빠졌다"를 보기 위해서다.

---

## 9. 배치가 실패한 날 복구하기

**빠진 날은 저절로 채워지지 않는다.** 멱등성 검사가 *오늘* 날짜 파일만 보기 때문에,
어제 실패했어도 오늘 실행은 어제를 만들지 않는다. 수동 복구가 필요하다.

### ① 어느 날이 빠졌는지 찾기

```powershell
cd C:\Users\박민수\IdeaProjects\study_project
git pull

# 최근 14일 중 파일이 없는 날 = 빠진 날
0..13 | ForEach-Object {
  $d = (Get-Date).AddDays(-$_).ToString("yyyy-MM-dd")
  $cycleDay = [int]((Get-Date).AddDays(-$_).Date - (Get-Date "1970-01-01")).TotalDays % 4
  $expected = if ($cycleDay -eq 0) { "generated\documents\$d.json" } else { "generated\$d.json" }
  if (-not (Test-Path $expected)) { "빠짐: $d (주기 $cycleDay 일차) → $expected" }
}
```

주기 0일차면 **문서**, 1·2·3일차면 **문제**가 있어야 정상이다.

### ② 그날을 다시 만들기

`Actions → LLM 일일 생성 → Run workflow`에서 **`date`에 빠진 날짜만** 넣는다.

| 입력 | 값 |
|---|---|
| `date` | `2026-08-15` ← 빠진 날 |
| 나머지 | **전부 비움** |

날짜만 주면 분야·난이도·문서/문제 여부를 **그날 기준으로 다시 계산**한다. `type`이나 `domain`을
같이 넣으면 오히려 주기와 어긋나므로 **건드리지 않는 것이 맞다.**

### ③ 문서일이 빠졌다면 서둘러야 한다

우선순위가 다르다.

| 빠진 날 | 영향 | 급한가 |
|---|---|---|
| 문제일(1·2·3일차) | 그날 5문제만 없음 | 여유 있음 |
| **문서일(0일차)** | **그 주기 나흘이 통째로 폴백** — 근거 없는 문제가 사흘 나온다 | **다음 날 안에** |

문서일이 빠지면 이후 사흘 배치가 `근거 문서 없음, 폴백으로 생성합니다`를 찍으며 돈다.
그 사흘치를 이미 만들어 버렸다면, 문서를 복구한 뒤 **문제 파일도 지우고 다시 만들어야**
근거 문서가 붙는다.

```powershell
Remove-Item generated\2026-08-16.json   # 폴백으로 만들어진 것
# → 문서 복구 후 date=2026-08-16으로 재실행
```

### 왜 자동 재시도를 안 넣었나

넣을 수 있었지만 **손해가 더 크다고 봤다.**

- 실패 원인의 대부분(API 키 만료, 모델 은퇴, 프롬프트 오류)은 **재시도해도 똑같이 실패**한다.
  그러면 요금만 두 배로 나가고 실패 메일도 두 통 온다.
- 자동 재시도가 있으면 **실패가 눈에 덜 띈다.** 이 프로젝트가 겪은 사고는
  "안 도는 걸 몇 주 뒤에 알아차린" 쪽이었다 — 조용해지는 방향의 장치는 신중해야 한다.
- 실패하면 **메일이 온다.** 사람이 하루 안에 알아차릴 수 있는 구조라면, 수동 복구 한 번이
  자동 재시도의 복잡도보다 싸다.

---

## 10. 자주 겪는 상황

| 증상 | 원인 | 대응 |
|---|---|---|
| 검수함이 비었는데 저장소엔 파일이 있다 | 앱을 켜기 전에 `git pull`을 안 함 | pull 후 재기동 |
| 로그에 "폴백으로 생성합니다"가 계속 뜬다 | 근거 문서가 없거나 거절됨 | 문서 검수를 밀지 말 것 |
| 설정을 바꿨는데 배치가 그대로다 | 커밋을 안 함 (배치는 저장소 파일을 읽는다) | commit & push |
| 배치가 매일 아무것도 안 한다 | `batch-type: document`인데 문서일이 아님 / `batch-enabled: false` | 설정 확인 |
| 승인했는데 📖 링크가 안 뜬다 | 그 문제의 `document_slug`가 NULL(2단계 이전 파일) | 정상. 이후 배치분부터 붙는다 |
| 같은 날짜로 다시 생성하고 싶다 | 멱등성 보호로 건너뜀 | `generated/{날짜}.json` 삭제 후 실행 |
| 거절한 걸 되살리고 싶다 | — | `POST .../{id}/restore` (REJECTED만 가능) |

| 배치가 어제 실패했다 | 빠진 날은 저절로 안 채워진다 | [§9 복구](#9-배치가-실패한-날-복구하기) |

---

## 11. 알려진 한계

- **60일 무활동 시 예약이 자동 해제된다**(공개 저장소 정책). 봇의 매일 커밋이 이 타이머를
  리셋해 주는지는 확인되지 않았다 — **두 달쯤 뒤 Actions 탭에서 한 번 확인할 것.**
- **예약 시각은 보장이 아니다.** 실측 48~50분 지연. 정확한 시각이 필요한 작업이라면
  Actions가 아니라 k8s CronJob이나 Cloud Scheduler를 써야 한다.
- **스냅샷은 사람이 커밋해야 최신이 된다.** 며칠 안 켜면 그만큼 낡는다(14일 넘으면 배치가
  경고한다). 다만 낡음의 영향은 제한적이다 — 승인된 문제는 원래 `generated/*.json`에서 온
  것이라 이미 회피 목록에 있다.
- **빠진 날은 자동으로 채워지지 않는다.** 수동 복구가 필요하다([§9](#9-배치가-실패한-날-복구하기)).
  자동 재시도를 일부러 넣지 않은 이유도 거기 적어 두었다.
- **`GITHUB_TOKEN`으로 만든 커밋은 다른 워크플로를 트리거하지 않는다.** 무한 루프가 원천
  차단되는 좋은 성질이고, 덕분에 데이터 파일 커밋으로 `ci-cd.yml`이 헛돌지 않는다.
