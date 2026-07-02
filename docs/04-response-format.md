# 04. 공통 응답 포맷 & 에러 코드

> **첫 API를 만들기 전에 확정**하는 계약. 모든 컨트롤러 응답은 이 envelope를 따른다.
> 목적: 로드맵 3("표준 에러 응답")에서 전면 리팩터링하지 않도록 처음부터 고정.

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
    public static ApiResponse<?> fail(ApiError error) { return new ApiResponse<>(false, null, error); }
}
public record ApiError(String code, String message, List<FieldError> fieldErrors) {}
public record FieldError(String field, String reason) {}
```

---

## 에러 코드 체계

- 형식: `{도메인}_{3자리}`. HTTP 상태와 함께 매핑.
- 클라이언트는 `code`로 분기, `message`는 사용자 표시용(한글).

| code | HTTP | 의미 |
|---|---|---|
| `COMMON_001` | 400 | 잘못된 요청(파싱 실패 등) |
| `VALIDATION_ERROR` | 400 | 입력 검증 실패 (fieldErrors 포함) |
| `COMMON_404` | 404 | 리소스 없음 |
| `COMMON_500` | 500 | 서버 내부 오류 |
| `AUTH_001` | 409 | 이메일 중복 |
| `AUTH_002` | 401 | 로그인 실패(이메일/비번 불일치) |
| `AUTH_003` | 401 | 토큰 없음/만료/위조 |
| `AUTH_004` | 403 | 권한 부족 |
| `QUIZ_001` | 404 | 문제 없음 |
| `QUIZ_002` | 400 | 지원하지 않는 문제 타입 채점 요청(예 ESSAY) |
| `DOC_001` | 404 | 문서(slug) 없음 |

> 새 에러는 이 표에 추가하고 코드로 `enum ErrorCode`(code, httpStatus, defaultMessage) 관리.

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
