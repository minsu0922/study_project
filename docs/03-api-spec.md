# 03. API 명세 (MVP)

> 모든 응답은 `04-response-format.md`의 envelope를 따른다(아래 예시는 `data` 내부만 표기하거나 축약).
> 인증: MVP는 **access 토큰만**. 보호 리소스는 `Authorization: Bearer <accessToken>`.
> Base URL: `/api`

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 → JWT 발급 |
| GET | `/api/documents` | ✕ | 문서 목록(도메인/태그 필터, 페이징) |
| GET | `/api/documents/{slug}` | ✕ | 문서 단건 |
| GET | `/api/quiz` | ✕ | 필터로 문제 N개 |
| POST | `/api/quiz/submit` | ✓ | 답안 제출 → 채점 + 해설 |
| GET | `/api/me/wrong-answers` | ✓ | 오답노트 |

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
로그인 → access 토큰 발급.

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
    "tokenType": "Bearer",
    "expiresIn": 3600
  },
  "error": null
}
```
- `expiresIn`: 초 단위(예 3600 = 1시간).
- refresh 토큰은 MVP 미발급(로드맵 2, Redis 도입 시).

**에러**: `AUTH_002` 인증 실패(401)

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
  "submissionId": 5001
}
```
- `correctAnswer`: 객관식=정답 Choice text, OX=`O`/`X`, 단답형=대표 정답(첫 `|` 토큰).
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
      "explanation": "...", "lastSubmittedAt": "2026-07-01T12:30:00"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```
- 같은 문제를 여러 번 틀렸어도 **문제당 1행(최신 오답 기준)** 으로 집계 → 이 집계 로직이 무거워지면 `ReviewItem` 분리(ADR-0002).
