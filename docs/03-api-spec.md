# 03. API 명세

> 모든 응답은 [04-response-format](04-response-format.md)의 envelope를 따른다(아래 예시는 `data` 내부만 표기하거나 축약).
> 인증: `Authorization: Bearer <accessToken>`. 재발급은 [`POST /api/auth/refresh`](#post-apiauthrefresh).
> Base URL: `/api` · 🔒 = 로그인 필요 · 🛡️ = **ADMIN 전용**

## 전체 엔드포인트 (48개 — 사용자 15 · 관리자 33)

| 묶음 | 엔드포인트 | |
|---|---|---|
| **인증** | `POST /api/auth/signup` · `login` · `refresh` · `logout` | [↓](#post-apiauthsignup) |
| **문서** | `GET /api/documents` · `/{slug}` | [↓](#get-apidocuments) |
| **퀴즈** | `GET /api/quiz` · `/{problemId}` · `POST /api/quiz/submit` 🔒 | [↓](#get-apiquiz) |
| **문제 목록** | `GET /api/problems` 🔒 · `GET /api/me/study-summary` 🔒 | [↓](#get-apiproblems-) |
| **내 학습** | `GET /api/me/wrong-answers` 🔒<br>`GET /api/me/reviews` · `/reviews/today` 🔒<br>`GET /api/me/daily-quiz` 🔒 | [↓](#get-apimewrong-answers-) |
| **관리자·문제** | `GET`·`POST /api/admin/problems` · `GET`·`PUT`·`DELETE /{id}` · 제목/근거 백필 🛡️ | [↓](#관리자-api-️) |
| **관리자·문서** | `POST /api/admin/documents` · `PUT`·`DELETE /{id}` 🛡️ | [↓](#관리자-api-️) |
| **관리자·통계** | `GET /api/admin/dashboard` 🛡️ | [↓](#관리자-api-️) |
| **관리자·주제 대기열** | `GET`·`POST /api/admin/topic-queue` · `DELETE /{id}` · `/{id}/move` 🛡️ | [↓](#ai-검수-api-️) |
| **AI 검수** | `/api/admin/llm-problems`(9) · `/llm-documents`(5) 🛡️ | [↓](#ai-검수-api-️) |

> 관리자·AI 검수 API는 **경로 접두사 `/api/admin/**` 전체에 `hasRole(ADMIN)`이 한 줄로 걸린다.**
> 컨트롤러마다 권한을 적지 않는 이유는 하나만 빠뜨려도 뚫리기 때문이다 — 경로가 곧 경계다.

## 쉽게 말하면

API는 **앱과 서버가 주고받는 창구 목록**이다. 🪟
"어떤 주소로, 어떤 방식(GET/POST)으로 요청하면, 무엇을 돌려주는지"를 미리 약속해 둔 것.
아래 표의 🔒(자물쇠)가 붙은 창구는 **로그인(토큰)이 있어야** 이용할 수 있고, 나머지는 누구나 쓸 수 있다.
로그인이 실제로 어떻게 동작하는지는 [06-security-jwt](06-security-jwt.md)에 있다.

사용자가 쓰는 창구는 이 15개다. 나머지 33개는 **관리자 전용**이라 평소엔 안 보인다.

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 → 토큰 발급 |
| POST | `/api/auth/refresh` | ✕ | 토큰 재발급(1회용 회전) |
| POST | `/api/auth/logout` | ✕ | refresh 폐기 |
| GET | `/api/documents` | ✕ | 문서 목록(도메인/태그 필터, 페이징) |
| GET | `/api/documents/{slug}` | ✕ | 문서 단건 |
| GET | `/api/quiz` | ✕ | 필터로 문제 N개 |
| GET | `/api/quiz/{problemId}` | ✕ | 문제 단건(목록에서 고른 한 문제를 푼다) |
| POST | `/api/quiz/submit` | ✓ | 답안 제출 → 채점 + 해설 |
| GET | `/api/problems` | ✓ | 문제 목록 화면 — 개인화·필터·페이징 ([18](18-problem-list-ui.md)) |
| GET | `/api/me/study-summary` | ✓ | 통계 카드 4칸 + 분야별 진척 ([18](18-problem-list-ui.md)) |
| GET | `/api/me/wrong-answers` | ✓ | 오답노트 |
| GET | `/api/me/reviews/today` | ✓ | 오늘의 복습 (로드맵 4 — [10](10-review-recommendation.md)) |
| GET | `/api/me/reviews` | ✓ | 내 복습 현황 전체 (로드맵 4) |
| GET | `/api/me/daily-quiz` | ✓ | 오늘의 퀴즈 세트 (로드맵 6 — [12](12-daily-quiz.md)) |

---

## POST /api/auth/signup
회원가입.

**Request**
```json
{ "email": "user@example.com", "password": "abcd1234" }
```
- `email`: 이메일 형식, 필수, 중복 불가
- `password`: 8자 이상, 영문+숫자 포함

**Response 201**
```json
{ "success": true, "data": { "id": 1, "email": "user@example.com", "role": "USER" }, "error": null }
```
**에러**: `VALIDATION_ERROR`(400), `AUTH_001` 이메일 중복(409)

---

## POST /api/auth/login
로그인 → access + refresh 토큰 발급.

**Request**
```json
{ "email": "user@example.com", "password": "abcd1234" }
```
**Response 200**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "b3f1c8e2-...",
    "tokenType": "Bearer",
    "expiresIn": 3600
  },
  "error": null
}
```
- `expiresIn`: 초 단위(예 3600 = 1시간).
- **`refreshToken`은 Redis가 죽어 있으면 `null`로 나간다**(fail-open). 로그인 자체는 성공시킨다 —
  자동 재로그인이 안 되는 불편과 아예 못 쓰는 장애는 무게가 다르다. 자세히는 [06](06-security-jwt.md).

**에러**: `AUTH_002` 인증 실패(401)

---

## POST /api/auth/refresh
access 토큰 재발급. **refresh 토큰은 1회용**이라 쓰는 순간 새것으로 교체된다(회전).

**Request**
```json
{ "refreshToken": "b3f1c8e2-..." }
```
**Response 200** — `login`과 같은 모양(새 access + **새 refresh**)

- 옛 refresh를 다시 쓰면 `AUTH_005`(401). Redis에서 `GETDEL`로 **읽으면서 지우기** 때문이다.
  탈취된 토큰이 재사용되면 그 시점에 막힌다.

**에러**: `AUTH_005` refresh 무효/만료(401) → 다시 로그인

---

## POST /api/auth/logout
refresh 토큰을 폐기한다.

- access 토큰은 **서버가 취소할 수 없다**(JWT는 서명만 보고 검증하므로). 만료까지는 유효하다.
  그래서 로그아웃은 "다음 재발급을 막는 것"까지가 최선이고, 그 대신 access 수명을 짧게 둔다.
- 이미 없는 토큰으로 호출해도 **200**이다. 로그아웃은 멱등해야 한다 — 두 번 눌렀다고 에러를 보여 줄 이유가 없다.

---

## GET /api/documents
문서 목록. 도메인/태그 필터 + 페이징.

**Query params**
| 이름 | 필수 | 예 | 설명 |
|---|---|---|---|
| `domain` | ✕ | `NETWORK` | Domain enum |
| `tag` | ✕ | `tcp` | 태그명(복수: `?tag=tcp&tag=osi`) |
| `page` | ✕ | `0` | 0-base, 기본 0 |
| `size` | ✕ | `20` | 기본 20, 최대 100 |
| `sort` | ✕ | `createdAt,desc` | 기본 `createdAt,desc` |

**Response 200** (`data`는 페이지 구조)
```json
{
  "content": [
    { "id": 10, "domain": "NETWORK", "domainLabel": "네트워크",
      "title": "OSI 7계층", "slug": "osi-7-layer",
      "tags": ["osi", "network"], "updatedAt": "2026-07-01T12:00:00" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```
- 목록은 `content_md`(본문) 제외 — 단건에서만 반환.
- 태그 조인 N+1은 로드맵 1(QueryDSL+fetch join)에서 최적화 → 지금은 기능 우선.

---

## GET /api/documents/{slug}
문서 단건(본문 포함).

**Response 200**
```json
{
  "id": 10, "domain": "NETWORK", "domainLabel": "네트워크",
  "title": "OSI 7계층", "slug": "osi-7-layer",
  "contentMd": "# OSI 7계층\n...", "source": "https://...",
  "tags": ["osi", "network"],
  "createdAt": "2026-06-01T09:00:00", "updatedAt": "2026-07-01T12:00:00"
}
```
**에러**: `DOC_001` slug 없음(404)

---

## GET /api/quiz
필터로 문제 N개 조회(풀이용, 정답/해설 미포함).

**Query params**
| 이름 | 필수 | 예 | 설명 |
|---|---|---|---|
| `domain` | ✕ | `NETWORK` | Domain enum |
| `level` | ✕ | `BEGINNER` | Difficulty enum |
| `type` | ✕ | `MULTIPLE_CHOICE` | ProblemType (ESSAY 제외) |
| `size` | ✕ | `10` | 반환 문제 수, 기본 10, 최대 50 |

**Response 200**
```json
{
  "problems": [
    {
      "id": 100, "domain": "NETWORK", "difficulty": "BEGINNER", "type": "MULTIPLE_CHOICE",
      "question": "TCP는 몇 계층 프로토콜인가?",
      "choices": [
        { "id": 1, "seq": 1, "text": "물리 계층" },
        { "id": 2, "seq": 2, "text": "전송 계층" }
      ]
    },
    {
      "id": 101, "type": "OX", "difficulty": "BEGINNER", "domain": "NETWORK",
      "question": "UDP는 연결지향적이다.", "choices": []
    }
  ]
}
```
- **정답(`is_correct`)·`answer`·`explanation`은 절대 미노출** (채점 시에만 반환).
- 객관식만 `choices` 채움, OX/단답형은 빈 배열.

---

## GET /api/problems  🔒
문제 목록 화면(`problems.html`)의 한 판. 설계 배경은 [18-problem-list-ui](18-problem-list-ui.md).

**왜 로그인을 요구하나**: 이 목록의 모든 줄이 "나와의 관계"(맞혔나·언제 풀었나·복습할 때인가)를
달고 있다. 그걸 뺀 목록은 그냥 카탈로그이고, 그건 이미 `GET /api/quiz`가 한다.

**Query params**
| 이름 | 필수 | 예 | 설명 |
|---|---|---|---|
| `domain` | ✕ | `NETWORK` | Domain enum |
| `difficulty` | ✕ | `BEGINNER` | Difficulty enum (`/api/quiz`는 `level`, 여기는 `difficulty`) |
| `state` | ✕ | `UNSOLVED` | `UNSOLVED`/`CORRECT`/`WRONG`. 없으면 전체 |
| `reviewDue` | ✕ | `true` | 지금 복습 차례인 문제만 |
| `page`·`size` | ✕ | `0`·`20` | 기본 20개 |

**Response 200**
```json
{
  "content": [
    {
      "id": 100, "title": "TCP 3-way 핸드셰이크의 목적",
      "domain": "NETWORK", "domainLabel": "네트워크",
      "difficulty": "BEGINNER", "type": "MULTIPLE_CHOICE",
      "lastAttemptedAt": "2026-08-28T21:14:00", "state": "CORRECT", "reviewDue": false
    }
  ],
  "page": 0, "size": 20, "totalElements": 128, "totalPages": 7, "hasNext": true
}
```
- **정렬은 고정**(분야 → 난이도 → id)이고 파라미터로 받지 않는다. 첫 스펙의 "미풀이 우선"은
  한 문제를 풀고 돌아올 때마다 그 줄이 뒤로 밀려 목록 전체가 한 칸 당겨진다 — 무한 스크롤을
  뺀 이유와 같은 문제다. 그 정렬이 하려던 일은 `state=UNSOLVED` 필터가 이미 한다.
- `state`는 **"한 번이라도 맞혔나"**로 판정한다(`CORRECT`는 마지막 시도가 오답이어도 유지).
  "지금도 아는지"는 `reviewDue`가 따로 답한다.
- `lastAttemptedAt`은 한 번도 안 풀었으면 `null`. "안 풀었다"와 "오래전에 풀었다"는 다른 말이라
  0이나 빈 문자열로 뭉개지 않는다.
- `title`이 비어 있으면 **서버가** 지문 앞부분으로 채워 내린다(화면마다 대체하면 규칙이 갈라진다).
  단 관리 화면은 예외로 `null`을 그대로 받는다 — 거기서는 "제목 없음"이 고쳐야 할 정보다.

---

## GET /api/me/study-summary  🔒
문제 목록 화면의 통계 카드 4칸 + 분야별 진척. 화면을 열 때 한 번만 부른다.

**Response 200**
```json
{
  "stats": { "solvedTotal": 128, "correctRate": 64, "solvedThisWeek": 14, "reviewDue": 9 },
  "domains": [ { "domain": "NETWORK", "label": "네트워크", "solved": 18 } ]
}
```
- **목록과 API를 가른 이유**: 목록은 필터·쪽을 바꿀 때마다 다시 부르는데 통계는 그때마다
  바뀌지 않는다. 한 응답에 담으면 필터를 만질 때마다 집계 쿼리 넷이 같이 돈다.
- `correctRate`는 제출이 하나도 없으면 `null` — 0%(다 틀렸다)와 "아직 안 풀었다"는 정반대 신호다.
- `domains`는 **맞힌 개수만** 주고 분모(전체 문제 수)를 주지 않는다. 배치가 매일 문제를 더해
  분모가 커지므로, 비율로 보이면 어제 40%가 오늘 37%가 된다 — 아무것도 잘못하지 않았는데
  뒷걸음질친 것처럼 보인다.
- 스트릭(연속 일수)은 없다. `solvedThisWeek`가 그 자리를 대신한다(2026-08-29 제거).

---

## POST /api/quiz/submit  🔒
답안 제출 → 즉시 채점 + 해설 반환. `Submission` 저장.

**Request**
```json
{ "problemId": 100, "userAnswer": "2" }
```
- `userAnswer` 규칙(type별):
  - MULTIPLE_CHOICE → 선택한 `choiceId`의 문자열(예 `"2"`)
  - OX → `"O"` / `"X"`
  - SHORT_ANSWER → 자유 텍스트(예 `"전송 계층"`)

**Response 200**
```json
{
  "problemId": 100,
  "correct": true,
  "correctAnswer": "전송 계층",
  "explanation": "TCP/UDP는 전송(Transport) 계층 프로토콜이다.",
  "submissionId": 5001,
  "documentSlug": "osi-7-layer"
}
```
- `correctAnswer`: 객관식=정답 Choice text, OX=`O`/`X`, 단답형=대표 정답(첫 `|` 토큰).
- `documentSlug`: 이 문제의 **근거 개념 문서**. 프론트가 "이 개념 문서 읽기" 링크를 띄운다.
  근거 문서가 없거나 **그 문서가 아직 승인되지 않았으면 `null`** — 서버가 존재를 확인하고
  없으면 아예 안 내려보낸다. 링크를 눌렀는데 404가 나는 것이 링크가 없는 것보다 나쁘기 때문이다.
- **채점 규칙**은 `01-data-model.md`의 "채점 로직 요약" 참조.

**에러**:
- `QUIZ_001` 문제 없음(404)
- `QUIZ_002` ESSAY 등 미지원 타입(400)
- `AUTH_003` 미인증(401)

---

## GET /api/me/wrong-answers  🔒
로그인 사용자의 오답노트. `Submission where is_correct=false` 기반(ADR-0002).

**Query params**: `domain`(선택), `page`(기본0), `size`(기본20)

**Response 200** (페이지 구조)
```json
{
  "content": [
    {
      "problemId": 100, "domain": "NETWORK", "difficulty": "BEGINNER", "type": "MULTIPLE_CHOICE",
      "question": "TCP는 몇 계층 프로토콜인가?",
      "myAnswer": "물리 계층", "correctAnswer": "전송 계층",
      "explanation": "...", "lastSubmittedAt": "2026-07-01T12:30:00",
      "documentSlug": "osi-7-layer"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```
- 같은 문제를 여러 번 틀렸어도 **문제당 1행(최신 오답 기준)** 으로 집계 → 이 집계 로직이 무거워지면 `ReviewItem` 분리(ADR-0002).
- `documentSlug`도 **존재하는 문서만** 내려간다. 목록이라 문서를 한 건씩 확인하면 N+1이 되므로,
  slug를 모아 `IN` 한 번으로 확인한다.

---

## GET /api/me/reviews/today  🔒
오늘 복습할 문제 목록 — 간격 사다리에서 복습 예정 시각이 지난 문제만, 오래 밀린 순.
설계·상태 전이 규칙은 [10-review-recommendation](10-review-recommendation.md)(로드맵 4, ADR-0004).

**Query params**: `page`(기본 0), `size`(기본 20, 최대 50)

**Response 200** (페이지 구조)
```json
{
  "content": [
    {
      "problemId": 42, "domain": "NETWORK", "domainLabel": "네트워크",
      "difficulty": "INTERMEDIATE", "type": "MULTIPLE_CHOICE",
      "question": "TCP 3-way handshake의 순서로 옳은 것은?",
      "choices": [ { "id": 170, "seq": 1, "text": "SYN → SYN+ACK → ACK" } ],
      "stage": 1, "nextReviewAt": "2026-07-07T09:00:00", "reviewCount": 2
    }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "hasNext": false
}
```
- 문제 부분은 `GET /api/quiz`의 풀이용 형태와 동일(**정답·해설 없음** — 복습도 "풀어 보는" 것).
- 답 제출은 별도 API 없이 `POST /api/quiz/submit` 그대로 — 채점 결과가 사다리 승급/리셋에 자동 반영된다.
- 빈 목록은 에러가 아니라 정상(오늘 복습할 게 없음).

---

## GET /api/me/reviews  🔒
내 복습 현황 전체(졸업 포함) — 대시보드/진척 확인용.

**Query params**: `status`(선택 — `LEARNING`/`GRADUATED`), `page`(기본 0), `size`(기본 20, 최대 50)

**Response 200** (페이지 구조)
```json
{
  "content": [
    { "problemId": 42, "question": "TCP 3-way ...", "domain": "NETWORK", "domainLabel": "네트워크",
      "stage": 1, "status": "LEARNING", "reviewCount": 2,
      "nextReviewAt": "2026-07-10T09:00:00", "due": false }
  ],
  "page": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false
}
```
- `due`(지금 풀 때가 됐는지)는 저장값이 아니라 **응답 조립 시 계산**(시간으로 파생되는 값은 저장하지 않는다 — docs/10).
- 정렬은 `nextReviewAt asc` 고정(급한 복습부터).

---

## GET /api/me/daily-quiz  🔒
오늘의 퀴즈 세트. 배합 규칙과 지연 생성은 [12-daily-quiz](12-daily-quiz.md).

**Response 200**
```json
{
  "quizDate": "2026-08-13",
  "completed": false,
  "progress": { "total": 10, "solved": 3 },
  "items": [
    { "seq": 1, "source": "REVIEW", "solved": true,
      "problem": { "problemId": 42, "question": "...", "choices": [ ... ] } }
  ]
}
```
- **호출하는 순간 세트가 만들어진다**(지연 생성). 배치로 미리 만들지 않는 이유:
  안 들어오는 사용자 몫까지 매일 만들면 대부분이 버려진다.
- 같은 날 여러 번 불러도 **같은 세트**가 온다. DB의 `uk_dailyquiz_user_date`가 보장한다.
- `source`는 그 문항이 어느 배합 칸에서 왔는지(`REVIEW`/`WEAK`/`NEW`/`GENERAL`).
- 답 제출은 `POST /api/quiz/submit` 그대로 — 한 트랜잭션에서 채점·사다리·세트 진행이 함께 반영된다.

---

## 관리자 API  🛡️

문제·문서를 손으로 등록/수정/삭제한다. **AI 승인도 결국 이 서비스를 재사용한다**(단일 경로 원칙).

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/api/admin/problems` | 목록(페이지) |
| GET | `/api/admin/problems/{id}` | 상세(정답·해설 포함) |
| POST | `/api/admin/problems` | 등록 → 201 |
| PUT | `/api/admin/problems/{id}` | 수정 |
| DELETE | `/api/admin/problems/{id}` | 삭제 — 제출 이력이 있으면 `QUIZ_003`(409) |
| POST | `/api/admin/documents` | 등록 → 201. slug 중복 시 `DOC_002`(409) |
| PUT | `/api/admin/documents/{id}` | 수정 |
| DELETE | `/api/admin/documents/{id}` | 삭제 |
| GET | `/api/admin/dashboard` | 통계 한 방(아래) |

- **삭제를 막는 쪽을 택했다.** 제출 이력이 있는 문제를 지우면 남의 오답노트·복습 항목이
  같이 사라진다. "지울 수 있는데 데이터가 날아가는" 것보다 "못 지운다"가 낫다.
- 문제 등록/수정은 유형별 입력 규칙을 검증한다(객관식 정답 정확히 1개 등) → 위반 시 `QUIZ_004`.
  같은 검증을 AI 초안 승인도 통과한다.

**`GET /api/admin/dashboard`** — 화면 한 번에 필요한 것을 한 API로 묶었다(요청 3번을 조립하는 것보다 단순).

```json
{
  "totals":        { "users": 3, "documents": 2, "problems": 10, "submissions": 41 },
  "problemMatrix": [ { "domain": "NETWORK", "difficulty": "BEGINNER", "count": 0 } ],
  "domainStats":   [ { "domain": "NETWORK", "accuracyPct": 62.5, "...": "..." } ],
  "problemStats":  [ { "problemId": 100, "attempts": 12, "correctRate": 58 } ],
  "llmReview":     { "problems": [ { "model": "claude-opus-5", "pending": 5,
                                     "approved": 10, "rejected": 0, "approvalRate": 100 } ],
                     "documents": [ ... ] }
}
```

- `correctRate`·`approvalRate`는 **모수가 0이면 `null`** — "0%"(전부 틀림/전부 거절)와
  "데이터 없음"은 정반대 신호라 뭉개면 안 된다.
- `domainStats`는 DB 뷰(`domain_stats`)를 그대로 읽는다. 집계를 뷰가 갖고 있어야
  앱·DB 콘솔 어디서 봐도 같은 값이 나온다.
- `llmReview`는 모델별 승인율 — 모델·프롬프트를 바꾸기 전에 기준값을 남기는 용도([14](14-llm-batch-automation.md)).

---

## AI 검수 API  🛡️

생성된 초안을 사람이 승인/거절한다. 문제와 문서가 **같은 모양**이다.

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/api/admin/llm-problems?status=PENDING` | 목록. 오래된 순 |
| GET | `/api/admin/llm-problems/pending-count` | 탭 배지용 |
| POST | `/api/admin/llm-problems/generate` | **즉시 생성**(관리자 버튼) |
| POST | `/api/admin/llm-problems/{id}/approve` | 승인 → 201 + 만들어진 문제 |
| POST | `/api/admin/llm-problems/{id}/reject` | 거절. 바디 `{"reason": "..."}` 선택 |
| POST | `/api/admin/llm-problems/{id}/restore` | 거절한 것을 검수 대기로 되돌림 |
| — | `/api/admin/llm-documents/...` | 위와 동일한 6개. 단 **`generate` 없음** |

- **문서에 `generate`가 없는 이유**: 한 편에 1~2분·$0.2가 든다. 화면에 버튼이 있으면
  실수로 누르기 쉽고, 누른 뒤엔 응답이 올 때까지 화면이 멈춰야 한다. 문서 생성은
  GitHub Actions 워크플로를 수동 실행하는 쪽이 주제·날짜까지 지정할 수 있어 더 낫다.
- 목록 기본 `size`가 문제는 20, **문서는 5**다 — 문서는 응답에 본문이 통째로 실린다.
- `restore`는 `REJECTED`만 되돌린다. 승인된 것을 되돌리면 정식 문제는 이미 만들어진 뒤라
  "초안은 대기인데 문제는 공개된" 어긋난 상태가 된다.

**에러**: `LLM_001` 초안 없음(404) · `LLM_002` 이미 처리됨(409) · `LLM_003` API 실패(502) ·
`LLM_004` 키 미설정(503) · `LLM_005` 자동 검증에 걸린 문서(409)
