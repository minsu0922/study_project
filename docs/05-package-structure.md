# 05. 패키지 구조

> 루트: `project.study.study_project` (Initializr 스캐폴드 유지)
> 원칙: **도메인(기능)별 수직 분할**. 계층(controller/service/repository)은 도메인 패키지 안에서 나눈다.
> `global`은 횡단 관심사(공통 응답·예외·설정·보안) 전용.

## 쉽게 말하면

코드를 **어떤 폴더에 나눠 담을지** 정하는 문서다. 🗂️
방법은 크게 둘인데 — (A) "컨트롤러는 컨트롤러끼리, 서비스는 서비스끼리" 계층별로 모으거나,
(B) "회원 관련은 회원 폴더에, 문제 관련은 문제 폴더에" **기능(도메인)별**로 모으거나.
우리는 **(B) 기능별**로 나눈다. 한 기능을 고칠 때 폴더 하나만 열면 돼서 찾기 쉽고, 기능이 늘어도 구조가 안 무너진다.
단, 여러 기능이 공통으로 쓰는 것(응답 봉투·예외·보안 설정)은 `global` 폴더 한 곳에 모은다.
인증/보안 코드(`auth`, `auth/config/SecurityConfig`)의 구체적인 동작은 [06-security-jwt](06-security-jwt.md) 참고.

```
project.study.study_project
├── StudyProjectApplication.java
│
├── auth/                로그인·토큰
│   ├── controller/      AuthController
│   ├── service/         AuthService, RefreshTokenStore
│   ├── jwt/             JwtTokenProvider, JwtAuthenticationFilter
│   ├── cookie/          RefreshTokenCookie
│   ├── config/          SecurityConfig
│   └── dto/             SignupRequest, LoginRequest, LoginResponse, RefreshRequest, ...
│
├── user/
│   ├── domain/          User, Role(enum)
│   ├── repository/      UserRepository
│   ├── service/         AccountService, AccountDataCleaner(탈퇴 때 기록을 지우는 쪽이 구현), PasswordChanged(이벤트)
│   └── config/          AdminAccountInitializer
│
├── domainsetting/       분야 등록부 — 여러 기능이 읽는다
│   ├── domain/          DomainSetting
│   ├── repository/      DomainSettingRepository
│   ├── support/         DomainCatalog, DomainEntry, DomainHints, DefaultDomains
│   └── dto/             DomainTitle
│
├── revision/            문제·문서의 수정 이력 (ContentRevision, ContentRevisionService)
│
├── document/            개념 문서 (공개 조회)
│   ├── domain/          Document
│   ├── repository/      DocumentRepository, ...Custom, ...Impl   ← QueryDSL
│   ├── service/         DocumentService, AdminDocumentService(등록·수정·삭제)
│   ├── controller/      DocumentController
│   ├── config/          DocumentCacheConfig
│   └── dto/             DocumentListItem, DocumentDetailResponse, AdminDocumentRequest
│
├── quiz/                문제 + 제출 + 채점 + 오답노트
│   ├── domain/          Problem, Choice, Submission
│   ├── repository/      ProblemRepository, SubmissionRepository
│   ├── service/         QuizService, WrongAnswerService, AnswerDisplay, AdminProblemService(등록·수정·삭제),
│   │                    SubmissionListener·DueReviewCounter(복습·데일리가 구현)
│   ├── controller/      QuizController, WrongAnswerController
│   └── dto/             QuizResponse, QuizSubmitRequest/Response, WrongAnswerItem, ...
│
├── review/              복습 사다리 (로드맵 4)
│   ├── domain/          ReviewItem, ReviewStatus(enum)
│   └── ...              repository / service / controller / dto
│
├── dailyquiz/           오늘의 퀴즈 (로드맵 6)
│   ├── domain/          DailyQuiz, DailyQuizItem, DailyQuizSource(enum)
│   └── ...
│
├── llm/                 AI 생성·검수 (로드맵 7)
│   ├── cli/             DraftGeneratorCli          ← 유일하게 Spring 밖에서 도는 코드
│   ├── client/          ClaudeProblemGenerator, ClaudeDocumentGenerator, ...
│   ├── domain/          GeneratedProblemDraft, GeneratedDocumentDraft, ImportedDraftFile
│   ├── repository/      ...
│   ├── service/         DraftImportRunner/Service, LlmProblemService, 내보내기 3종
│   ├── support/         GenerationSchedule, DocumentDraftValidator, DocumentCheck
│   └── dto/             파일 형식 DTO들
│
├── admin/               관리 API의 입구 (컨트롤러, 대시보드, 처리 기록)
│   ├── controller/      AdminProblem/Document/LlmProblem/LlmDocument/StatsController
│   ├── service/         AdminStatsService, AdminUserService, AdminBatchService
│   ├── audit/           AdminAuditInterceptor, AdminAccessDeniedRecorder
│   └── dto/             AdminDashboardResponse, ...
│
├── tag/                 Tag, TagRepository, TagService(find-or-create)
│
└── global/              횡단 관심사
    ├── common/          Domain, Difficulty, ProblemType (enum)
    ├── response/        ApiResponse, ApiError, FieldError, PageResponse
    ├── exception/       BusinessException, ErrorCode, GlobalExceptionHandler
    ├── ratelimit/       RateLimitFilter, TokenBucketRateLimiter, ...   ← 로드맵 3
    ├── event/           ReviewCompleted
    └── config/          CacheConfig, OpenApiConfig, WebMvcConfig, JwtAuthenticationEntryPoint, ...
```

## 배치 판단 기준

- **엔티티는 각 도메인의 `domain/`** 에 둔다. `Choice`는 `Problem`에 종속이라 `quiz/domain`.
- **enum 3종(Domain/Difficulty/ProblemType)** 은 여러 도메인이 공유하므로 `global/common`.
  반대로 한 도메인만 쓰는 enum(`ReviewStatus`, `DailyQuizSource`, `DraftStatus`)은 그 도메인 안에 둔다 —
  공용 폴더에 넣으면 "누가 쓰는지" 정보가 사라진다.
- **연결 엔티티**는 소유 측에. 태그 연결은 JPA `@ManyToMany`가 아니라 각 애그리거트가 들고 있다.
- QueryDSL(로드맵 1)은 `*/repository/`에 `XxxRepositoryCustom` + `XxxRepositoryImpl`.
  실물은 `document/repository/DocumentRepositoryImpl` 하나 — **필요한 곳에만 넣었다.**

### 실제로 판단이 갈렸던 자리

**`problem/`을 따로 두지 않고 `quiz/`에 합쳤다.** 원래 계획은 나누는 것이었는데,
문제·보기·제출·채점이 **거의 항상 같이 바뀌었다.** 나눠 두면 기능 하나 고칠 때 폴더 두 개를
왕복하게 된다. "같이 변하는 것은 같이 둔다"가 도메인 분할의 원래 목적이라 합치는 쪽이 맞았다.

**`admin/`은 기능이 아니라 사용자 종류로 나눈 예외다.** 관리자 화면은 문제·문서·AI 검수·통계를
전부 건드려서 어느 도메인에도 속하지 않는다. 더 중요한 이유는 **보안 경계**다 —
`/api/admin/**` 전체에 관리자 권한을 한 줄로 거는데, 컨트롤러가 흩어져 있으면
"이 컨트롤러가 관리자용이었나?"를 매번 확인해야 한다. 그래서 **컨트롤러는 전부 `admin/controller`** 에 둔다.

문제·문서를 등록하고 고치는 서비스(`AdminProblemService`, `AdminDocumentService`)는 2026-10-09에
`quiz`·`document`로 옮겼다. `admin`에 두면 초안을 승인하는 `llm`이 `admin`을 부르고 `admin`은 다시
`llm`을 불러 두 패키지가 서로 기댔다. 경계는 주소(`/api/admin/**`)가 지키므로 서비스 위치와는 무관하다.

**`llm/cli/`만 Spring 밖에서 돈다.** 같은 모듈에 있지만 `DraftGeneratorCli`는
`main()`으로 실행되고 스프링 컨텍스트를 안 띄운다(GitHub Actions에서 도므로 DB가 없다).
모듈을 분리하는 대안도 있었지만, 그러면 `ClaudeProblemGenerator`·`GenerationSchedule`을
공유하려고 모듈을 하나 더 만들어야 한다 — 클래스 두어 개 때문에 빌드가 복잡해지는 손해가 크다.

## 계층 의존 방향
```
controller → service → repository → domain(엔티티)
             ↘ dto ↗
global(response/exception/config)은 전 계층에서 참조 가능(역참조 금지)
```
- 컨트롤러는 엔티티를 직접 반환하지 않는다 — 항상 DTO 변환.
- 서비스 간 호출은 같은 계층 참조 허용하되 순환 의존 금지.

## 패키지 의존 방향

최상위 패키지는 아래에서 위로만 기댄다. `PackageDependencyTest`가 소스의 import를 읽어 순환을 잡는다.

```
admin
llm · discussion
report · bookmark · dailyquiz
review
quiz
document
revision · auth · tag · notification
user · domainsetting
global
```

위쪽이 아래쪽을 불러야 하는데 방향이 거꾸로 필요할 때는 둘 중 하나를 쓴다.

| 방법 | 쓴 곳 | 언제 |
|---|---|---|
| 불리는 쪽에 인터페이스를 둔다 | `SubmissionListener`, `DueReviewCounter`, `AccountDataCleaner` | 같은 트랜잭션 안에서 끝나야 할 때 |
| 이벤트로 알린다 | `PasswordChanged`, `AdminAccessDenied`, `ReviewCompleted` | 알리는 쪽이 결과를 기다릴 필요가 없을 때 |
