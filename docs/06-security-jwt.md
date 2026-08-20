# 06. 보안 & JWT — 로그인은 어떻게 동작하나

> 이 문서는 "로그인/인증을 실제로 어떻게 만들지"를 못 박아 두는 곳이다.
> 데이터 모델(01)이나 API 스펙(03)만 봐서는 "토큰 안에 뭐가 들었는지",
> "어떤 주소는 로그인 없이 되고 어떤 주소는 막히는지"를 알 수 없어서 따로 정리한다.
> 결정 배경은 [ADR-0001](adr/0001-access-token-only-for-mvp.md)(access 토큰만 쓰는 이유) 참고.

---

## 먼저, 아주 쉽게

로그인은 **놀이공원 입장 도장**이라고 생각하면 쉽다. 🎢

1. 매표소(로그인 API)에서 이메일·비밀번호를 보여주면,
2. 직원이 확인하고 손등에 **도장(JWT 토큰)**을 찍어 준다.
3. 이후 놀이기구(보호된 API)를 탈 때마다 손등 도장을 보여주면, 다시 표를 사지 않아도 통과된다.
4. 도장은 **1시간 뒤에 지워진다**(만료). 그러면 매표소에서 다시 찍어야 한다.

이 도장이 특별한 이유: **위조가 안 된다.** 도장에는 우리 서버만 아는 비밀 열쇠로 만든
"서명"이 들어 있어서, 누가 몰래 고치면 서버가 바로 알아챈다.

---

## JWT는 어떻게 생겼나

JWT는 점(`.`)으로 나뉜 세 덩어리다: `헤더.내용.서명`

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwicm9sZSI6IlVTRVIi...} . 3f8a...(서명)
```

- **헤더**: "이 도장은 HS256 방식으로 서명했어" 같은 메타정보.
- **내용(payload/claims)**: 실제로 담는 정보. 우리가 정한 규칙은 아래 표.
- **서명**: 헤더+내용을 우리 서버의 비밀키로 암호화한 값. 위조 방지용.

> ⚠️ 내용(payload)은 **암호화가 아니라 인코딩**일 뿐이다. 누구나 열어 볼 수 있다.
> 그래서 **비밀번호 같은 민감정보는 절대 토큰에 넣지 않는다.**

### 우리가 토큰에 담는 것 (claims)

| claim | 값 | 왜 담나 |
|---|---|---|
| `sub` (subject) | 사용자 id (예 `"1"`) | 누구인지 식별. **이메일이 아니라 id를 쓴다** — 이메일은 바뀔 수 있지만 id(PK)는 안 바뀌고, 매 요청마다 DB를 다시 조회하지 않아도 되기 때문. |
| `role` | `"USER"` / `"ADMIN"` | 권한 판단용. 이것도 토큰에 있으면 관리자 API 접근을 DB 조회 없이 막을 수 있다. |
| `iat` (issued at) | 발급 시각 | 표준 클레임. |
| `exp` (expiration) | 만료 시각 = 발급 + 3600초 | 1시간 뒤 자동 만료. ([ADR-0001](adr/0001-access-token-only-for-mvp.md)) |

- 이메일은 토큰에 넣지 않는다. 화면에 이메일이 필요하면 id로 조회한다.

### 서명 방식

- 알고리즘: **HS256 계열(HMAC-SHA)**. 하나의 비밀키로 서명·검증하는 대칭키 방식.
  - jjwt의 `signWith(key)`는 **키 길이에 맞춰 HS256/HS384/HS512를 자동 선택**한다. 즉 알고리즘은 키 세기에 따라 결정된다.
    현재 로컬 기본 시크릿은 60바이트(≈480비트)라 실제로는 **HS384**가 선택된다(48바이트=384비트 이상이면 HS384). 32바이트짜리 키면 HS256이 된다.
  - 왜 RS256(공개키/개인키) 대신 HS? 우리 서버가 발급도 검증도 다 하는 단일 서비스라 대칭키로 충분하다. 외부에 검증을 맡길 일이 생기면 그때 RS256을 검토(→ ADR 대상).
- 비밀키: `application.yml`의 `jwt.secret`. **256비트(32바이트) 이상**이어야 한다(HS256 요구사항).
- 만료: `jwt.access-token-validity-seconds`(기본 3600).
- 라이브러리: `jjwt` 0.12.x (`build.gradle` 참고). 0.12부터 API가 바뀌었으니(`Jwts.builder().signWith(key)`) 구버전 예제와 헷갈리지 말 것.

---

## 로그인 → 요청 흐름 (그림)

```
[회원가입]  POST /api/auth/signup
  비밀번호를 BCrypt로 해시 → User 저장 (원문 비번은 어디에도 저장 안 함)

[로그인]    POST /api/auth/login
  이메일로 User 조회 → BCrypt.matches(입력비번, 저장해시) 확인
  성공 시 JWT 발급 → { accessToken, tokenType: "Bearer", expiresIn: 3600 }

[보호된 요청]  POST /api/quiz/submit  (헤더: Authorization: Bearer <token>)
  JwtAuthenticationFilter 가 토큰을 꺼내 서명·만료 검증
  → 통과하면 SecurityContext 에 "인증된 사용자(id, role)" 를 심음
  → 컨트롤러에서 그 id 로 "누가 제출했는지" 판단
```

---

## 비밀번호는 어떻게 저장하나

- **BCrypt**로 해시해서 저장한다(`password_hash` 컬럼). 원문 비밀번호는 DB에도 로그에도 남기지 않는다.
- Spring Security의 `BCryptPasswordEncoder`(기본 강도 10)를 `@Bean`으로 등록해 쓴다.
- 왜 BCrypt? 일부러 느리게 설계된 해시라서, 유출돼도 무차별 대입(brute-force)이 어렵다. 매번 다른 salt가 자동으로 붙어 같은 비밀번호도 해시값이 달라진다.

### 비밀번호 형식 규칙 (가입 시 검증)

- **8자 이상, 영문·숫자를 모두 포함.**
- 정규식: `^(?=.*[A-Za-z])(?=.*\d).{8,}$`
- 검증 위치: 요청 DTO(`SignupRequest`)에 `@Pattern`으로. 실패하면 전역 예외처리가 `VALIDATION_ERROR`(400)로 변환(→ [04-response-format](04-response-format.md)).

---

## 어떤 주소가 열려 있고, 어떤 주소가 막혀 있나

`SecurityConfig`에서 아래 규칙으로 설정한다.

| 경로 | 접근 |
|---|---|
| `/api/auth/**` (signup·login·refresh·logout) | 🔓 누구나 |
| `GET /api/documents/**`, `GET /api/quiz` | 🔓 누구나 (읽기 공개) |
| `/swagger-ui/**`, `/v3/api-docs/**` | 🔓 누구나 (API 문서) |
| `POST /api/quiz/submit` | 🔒 로그인 필요 |
| `GET /api/me/**` | 🔒 로그인 필요 |
| **`/api/admin/**`** | 🛡️ **`hasRole("ADMIN")`** |
| 그 외 | 🔒 기본은 인증 요구 |

**관리자 경로를 한 줄로 묶은 것이 의도다.** 컨트롤러마다 `@PreAuthorize`를 붙이는 방법도 있지만,
**하나만 빠뜨리면 그 API가 뚫린다.** 지금은 관리자 API가 21개인데(문제·문서 CRUD, AI 검수, 통계)
전부 `/api/admin/**` 아래 있으므로 규칙 한 줄이 전부를 덮는다 — **경로가 곧 보안 경계**다.
새 관리자 기능을 만들 때 경로만 맞추면 권한은 저절로 따라온다.

> `role`은 access 토큰의 클레임에 들어 있어 **권한 검사에 DB를 안 본다.**
> 대신 권한을 뺏어도 토큰 만료(1시간)까지는 유효하다는 뜻이기도 하다 — 위 로그아웃과 같은 타협.

### 세션은 쓰지 않는다 (Stateless)

- `SessionCreationPolicy.STATELESS` — 서버가 로그인 상태를 기억하지 않는다. 매 요청의 토큰만으로 판단한다.
- 그래서 **CSRF 보호는 끈다**(`csrf.disable()`). CSRF 공격은 브라우저가 자동으로 쿠키/세션을 실어 보내기 때문에 생기는데, 우리는 세션 쿠키가 아니라 헤더의 토큰을 쓰므로 해당되지 않는다.
- 로그인 폼/기본 로그인 화면(`formLogin`, `httpBasic`)도 끈다. 우리는 JSON API로만 로그인한다.

---

## refresh 토큰 — 나중에 붙인 것 (로드맵 2)

MVP는 **access 토큰만** 썼다(ADR-0001). 1시간짜리 토큰이 만료되면 그냥 다시 로그인하는 방식이다.
Redis를 도입하면서 예고대로 refresh를 붙였다.

| | access 토큰 | refresh 토큰 |
|---|---|---|
| 형태 | **JWT**(서명된 자기서술 토큰) | **불투명 랜덤 문자열**(UUID 2개) |
| 저장 | 서버에 저장 안 함 | **Redis**에 `refresh:{토큰} → userId` |
| 수명 | 1시간 | 14일 (Redis TTL) |
| 회수 | **불가능** | 가능(지우면 끝) |

### 왜 하나는 JWT고 하나는 아닌가 — 단골 질문

**존재 이유가 정반대**이기 때문이다.

- access 토큰이 JWT인 이유는 **"서버가 아무것도 기억하지 않고 서명만 검증"**하려는 것이다.
  매 요청마다 DB·Redis를 안 봐도 되니 빠르고, 서버를 늘려도 공유할 상태가 없다.
- refresh 토큰의 존재 이유는 정반대로 **"서버가 회수할 수 있어야 한다"**이다.
  어차피 저장소를 봐야 한다면 자기서술적인 JWT일 이유가 없고, **내용이 없는 랜덤 값이
  오히려 안전하다**(디코딩해도 나오는 정보가 없다).

### 회전(rotation) — 한 번 쓰면 버린다

```
[재발급 요청]  refresh=A
      ↓  Redis GETDEL "refresh:A"   ← 읽으면서 동시에 삭제
   userId 확보 → 새 access + 새 refresh=B 발급
      ↓
[A를 다시 쓰면]  Redis에 없음 → AUTH_005(401) → 재로그인
```

**`GETDEL`(읽기+삭제를 한 명령으로)이 핵심이다.** 읽고 나서 따로 지우면 그 틈에 같은 토큰이
두 번 통과할 수 있다. 한 명령이면 Redis가 원자적으로 처리한다.

이렇게 하면 **탈취가 "영원한 출입증"이 되지 못한다.** 탈취범과 주인이 같은 토큰을 쓰다가
한쪽이 먼저 재발급하는 순간 다른 쪽이 무효가 되고, 주인이 재로그인하며 이상을 눈치챈다.

### Redis가 죽으면 — fail-open

| 상황 | 동작 | 왜 |
|---|---|---|
| 로그인 중 Redis 장애 | `refreshToken: null`로 **로그인은 성공** | 자동 재로그인이 안 되는 불편 < 아예 못 쓰는 장애 |
| 재발급 중 Redis 장애 | **무효 토큰과 동일 취급**(401) | 클라이언트가 할 일이 어차피 "재로그인"으로 같다 |

두 번째가 중요하다. "서버가 지금 확인할 수 없음"과 "토큰이 무효함"을 구분해서 알려 줘도
**클라이언트가 취할 행동이 달라지지 않는다.** 그럴 땐 이미 처리할 줄 아는 쪽으로 접는 게 낫다.

> ⚠️ 이 처리는 **처음엔 없었다.** Redis 장애 시 예외가 그대로 새어 나가 500이 되는 버그를
> 테스트를 추가하다 발견했다. 로컬에선 Redis가 늘 떠 있어 안 보였고 **CI에서만 재현**됐다.

### 로그아웃은 어디까지 되나

refresh를 지우는 것까지다. **access 토큰은 서버가 취소할 수 없다** — 서명만 보고 검증하므로
"이건 로그아웃된 토큰"이라는 걸 알 방법이 없다. 그래서 최대 1시간은 유효하다.

- 막으려면 매 요청마다 블랙리스트를 조회해야 하는데, 그러면 **JWT를 쓰는 이유(무상태)가 사라진다.**
- 대신 **access 수명을 짧게** 두는 것으로 위험을 시간으로 제한한다. 이게 JWT의 표준 타협이다.

---

## 인증/인가 실패도 같은 응답 봉투로

Spring Security가 막는 경우에도 응답 모양이 다른 API와 똑같아야 한다(→ [04-response-format](04-response-format.md)의 envelope).

| 상황 | 처리기 | 결과 |
|---|---|---|
| 토큰이 없음/만료/위조 (미인증) | `AuthenticationEntryPoint` | `401` + `AUTH_003` |
| 로그인은 했지만 권한 부족 | `AccessDeniedHandler` | `403` + `AUTH_004` |

- 이 두 처리기는 직접 만들어 `SecurityConfig`에 등록한다. 내부에서 `ApiResponse.fail(...)`을 JSON으로 직접 써 준다.
  (이 지점은 `@RestControllerAdvice`가 못 잡는다 — 예외가 컨트롤러 이전, 필터 단계에서 발생하기 때문.)

---

## 컨트롤러에서 "로그인한 사용자"를 꺼내는 법

- `JwtAuthenticationFilter`가 토큰을 검증한 뒤, 사용자 id를 담은 principal을 `SecurityContext`에 넣는다.
- 컨트롤러에서는 `@AuthenticationPrincipal`(또는 커스텀 argument resolver)로 그 id를 받아
  "이 제출은 누구 것"인지 판단한다. 클라이언트가 보낸 값이 아니라 **토큰에서 꺼낸 id**를 신뢰한다
  (요청 본문의 userId를 믿으면 남의 계정으로 조작 가능).

---

## 구현 체크리스트

**MVP (Step 5)**

- [x] `PasswordEncoder`(BCrypt) `@Bean`
- [x] `JwtTokenProvider` — 발급(`createToken`)·검증(`validateToken`)·클레임 추출
- [x] `JwtAuthenticationFilter` — `Authorization` 헤더 파싱 → SecurityContext 세팅
- [x] `SecurityConfig` — 경로 규칙 + STATELESS + CSRF off + 두 실패 처리기 등록
- [x] `AuthService` — 회원가입(중복 검사 → AUTH_001), 로그인(불일치 → AUTH_002)
- [x] `AuthController` — `/signup`, `/login`

**로드맵 2에서 추가**

- [x] `RefreshTokenStore` — Redis `GETDEL` 회전, 장애 시 fail-open
- [x] `/refresh`, `/logout` + `AUTH_005`
- [x] `AdminAccountInitializer` — 초기 관리자 계정 자동 생성

## 설정값

```yaml
jwt:
  access-token-validity-seconds: 3600       # 1시간 — 짧게. 탈취 피해를 시간으로 제한
  refresh-token-validity-seconds: 1209600   # 14일 — Redis TTL로 자동 청소
```

**시크릿 키는 여기 없다.** 환경변수로 주입한다(로컬은 `application-local.properties`).
설정 파일에 넣으면 저장소에 그대로 올라가고, 그 키로 아무나 토큰을 위조할 수 있다.

---

## 면접 대본 요약

"인증은 JWT access 토큰만으로 시작했습니다. 토큰 subject에는 이메일이 아니라 사용자 id를 넣었는데,
이메일은 변경 가능하지만 id는 불변이고 매 요청마다 DB를 다시 조회하지 않아도 되기 때문입니다.
role도 클레임에 담아 관리자 권한 검사를 DB 없이 처리했고요. 세션을 쓰지 않는 stateless 구조라
CSRF는 비활성화했습니다 — 토큰을 쿠키가 아닌 Authorization 헤더로 주고받으니 CSRF 표면 자체가 없어서입니다.
인증·인가 실패는 필터 단계에서 발생해 @RestControllerAdvice가 못 잡기 때문에,
EntryPoint/AccessDeniedHandler를 따로 만들어 나머지 API와 동일한 응답 봉투(AUTH_003/004)로 통일했습니다."

**refresh 토큰까지 물어보면:**

"나중에 Redis를 도입하면서 refresh 토큰을 붙였는데, **access는 JWT로 두고 refresh는 불투명한
랜덤 문자열로** 만들었습니다. 둘의 존재 이유가 정반대여서입니다 — access는 '서버가 상태를 안
들고 서명만 검증'하려는 것이고, refresh는 '서버가 회수할 수 있어야 한다'는 것이거든요.
어차피 Redis를 봐야 한다면 자기서술적인 JWT일 이유가 없고, 내용 없는 랜덤 값이 오히려
디코딩해도 나올 정보가 없어 안전합니다.

회전은 Redis `GETDEL`로 구현했습니다. 읽고 나서 따로 지우면 그 사이에 같은 토큰이 두 번
통과할 수 있는데, 한 명령이면 원자적으로 처리되니까요. 덕분에 탈취된 토큰이 영구 출입증이
되지 못하고, 주인이 재발급하는 순간 무효가 됩니다.

Redis 장애 처리에서 실수가 하나 있었습니다. 예외가 그대로 새어 나가 500이 되고 있었는데,
로컬에선 Redis가 늘 떠 있어서 안 보이고 **CI에서만 재현**됐습니다. 지금은 무효 토큰과 같게
취급해서 401로 내려보냅니다 — 클라이언트가 할 일이 어차피 재로그인으로 같기 때문입니다."
