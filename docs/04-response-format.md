# 04. 공통 응답 포맷 & 에러 코드

> **첫 API를 만들기 전에 확정**하는 약속(계약). 모든 컨트롤러 응답은 이 envelope(봉투)를 따른다.
> 목적: 나중에 API가 수십 개로 늘어난 뒤 응답 모양을 바꾸려면 전부 뜯어고쳐야 하므로, 처음부터 고정한다.

## 쉽게 말하면

답장을 항상 **같은 모양의 봉투**에 담아 보내기로 정하는 것이다. 📮
성공이든 실패든 봉투 겉모습(필드 3개: `success`, `data`, `error`)은 똑같고, 안에 든 내용만 다르다.
이렇게 해두면 앱(클라이언트) 쪽에서 "성공이면 data를 보고, 실패면 error.code로 분기"라는 규칙 하나로 모든 API를 처리할 수 있다.

---

## 응답 Envelope

### 성공
```json
{
  "success": true,
  "data": { "...": "..." },
  "error": null
}
```

### 실패
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AUTH_001",
    "message": "이미 사용 중인 이메일입니다.",
    "fieldErrors": [
      { "field": "email", "reason": "이미 사용 중인 이메일입니다." }
    ]
  }
}
```

- `fieldErrors`는 검증 오류일 때만 채워지고, 그 외엔 생략(또는 `null`).
- 목록 응답의 `data`는 페이지 구조를 담는다(아래).

### 페이지 응답 (`data` 내부 규격)
```json
{
  "success": true,
  "data": {
    "content": [ { "...": "..." } ],
    "page": 0,
    "size": 20,
    "totalElements": 137,
    "totalPages": 7,
    "hasNext": true
  },
  "error": null
}
```

Java 표현:
```java
public record ApiResponse<T>(boolean success, T data, ApiError error) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data, null); }
    // 실패 응답엔 담을 data가 없으므로 타입을 Void로 고정 → 예외 핸들러의 반환 타입이 깔끔해진다.
    public static ApiResponse<Void> fail(ApiError error) { return new ApiResponse<>(false, null, error); }
}
// fieldErrors는 검증 오류일 때만 채우고, 그 외엔 응답에서 아예 생략(@JsonInclude(NON_NULL)).
public record ApiError(String code, String message, List<FieldError> fieldErrors) {}
public record FieldError(String field, String reason) {}
```

> 실제 구현은 `global/response/`에 있고, 목록 응답용 `PageResponse<T>`(Spring `Page`를 고정된 모양으로 변환)도 함께 둔다.
> Spring의 `Page`를 그대로 응답에 노출하지 않는 이유: `Page`의 JSON 모양은 버전에 따라 바뀔 수 있어 API 계약으로 쓰기 위험하기 때문.

---

## 에러 코드 체계

- 형식: `{도메인}_{3자리}`. HTTP 상태와 함께 매핑.
- 클라이언트는 `code`로 분기, `message`는 사용자 표시용(한글).

| code | HTTP | 의미 |
|---|---|---|
| `COMMON_001` | 400 | 잘못된 요청(파싱 실패 등) |
| `VALIDATION_ERROR` | 400 | 입력 검증 실패 (fieldErrors 포함) |
| `COMMON_404` | 404 | 리소스 없음 |
| `COMMON_429` | 429 | 요청 횟수 초과(rate limit) — `Retry-After` 헤더 동반, [09](09-rate-limiting.md) |
| `COMMON_500` | 500 | 서버 내부 오류 |
| `AUTH_001` | 409 | 이메일 중복 |
| `AUTH_002` | 401 | 로그인 실패(이메일/비번 불일치) |
| `AUTH_003` | 401 | 토큰 없음/만료/위조 |
| `AUTH_004` | 403 | 권한 부족 |
| `AUTH_005` | 401 | 리프레시 토큰이 유효하지 않음 → 다시 로그인 ([06](06-security-jwt.md)) |
| `QUIZ_001` | 404 | 문제 없음 |
| `QUIZ_002` | 400 | 지원하지 않는 문제 타입 채점 요청(예 ESSAY) |
| `QUIZ_003` | 409 | 제출 이력이 있는 문제는 삭제 불가 (관리자) |
| `QUIZ_004` | 400 | 문제 유형별 입력 규칙 위반 (관리자·AI 승인 공용) |
| `DOC_001` | 404 | 문서(slug) 없음 |
| `DOC_002` | 409 | slug 중복 |
| `LLM_001` | 404 | 생성 초안 없음 |
| `LLM_002` | 409 | 이미 처리된 초안(승인/거절 두 번) |
| `LLM_003` | 502 | Claude API 실패 |
| `LLM_004` | 503 | `ANTHROPIC_API_KEY` 미설정 |
| `LLM_005` | 409 | 자동 검증에 걸린 문서 초안은 승인 불가 ([15](15-llm-concept-documents.md)) |

> 새 에러는 이 표에 추가하고 코드로 `enum ErrorCode`(code, httpStatus, defaultMessage) 관리.

### 상태 코드를 고를 때의 기준

- **404 vs 409**: "없다"는 404, "있는데 지금 그 동작을 할 수 없다"는 409.
  `LLM_002`(이미 처리된 초안)가 409인 이유 — 초안은 존재하고, 상태가 안 맞을 뿐이다.
- **502 vs 503**: `LLM_003`은 **남의 서버가 실패**(외부 API 오류)라 502,
  `LLM_004`는 **우리 쪽 준비가 안 됨**(키 미설정)이라 503이다.
  둘 다 500으로 뭉치면 "내 잘못인가 남의 잘못인가"를 로그를 뒤져야 알 수 있다.
- **`QUIZ_004`는 두 입구가 공유한다** — 관리자 손등록과 AI 초안 승인이 같은 검증을 지나므로
  에러 코드도 같다. 코드가 갈리면 "같은 규칙인데 다른 에러"가 되어 클라이언트가 두 번 분기해야 한다.

---

## 전역 예외처리

- `@RestControllerAdvice` 하나로 집약.
- 처리 대상:
  - `MethodArgumentNotValidException` / `ConstraintViolationException` → `VALIDATION_ERROR` + fieldErrors
  - 커스텀 `BusinessException(ErrorCode)` → 해당 code/status
  - `HttpMessageNotReadableException` → `COMMON_001`
  - `NoResourceFoundException` / 미매핑 → `COMMON_404`
  - 그 외 `Exception` → `COMMON_500` (스택트레이스는 로깅만, 응답엔 미노출)
- Spring Security 인증/인가 실패(`AuthenticationEntryPoint`/`AccessDeniedHandler`)도 동일 envelope로 변환 → `AUTH_003`/`AUTH_004`.
