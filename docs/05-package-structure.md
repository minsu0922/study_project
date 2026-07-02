# 05. 패키지 구조

> 루트: `project.study.study_project` (Initializr 스캐폴드 유지)
> 원칙: **도메인(기능)별 수직 분할**. 계층(controller/service/repository)은 도메인 패키지 안에서 나눈다.
> `global`은 횡단 관심사(공통 응답·예외·설정·보안) 전용.

```
project.study.study_project
├── StudyProjectApplication.java
│
├── auth/
│   ├── controller/      AuthController
│   ├── service/         AuthService
│   ├── jwt/             JwtTokenProvider, JwtAuthenticationFilter
│   └── dto/             SignupRequest, LoginRequest, LoginResponse, ...
│
├── user/
│   ├── domain/          User (엔티티), Role (enum)
│   ├── repository/      UserRepository
│   └── service/         (필요 시) UserService
│
├── document/
│   ├── domain/          Document, DocumentTag
│   ├── repository/      DocumentRepository
│   ├── service/         DocumentService
│   ├── controller/      DocumentController
│   └── dto/             DocumentListItem, DocumentDetailResponse
│
├── problem/
│   ├── domain/          Problem, Choice
│   ├── repository/      ProblemRepository, ChoiceRepository
│   ├── service/         ProblemService
│   ├── controller/      QuizController (조회: GET /api/quiz)
│   └── dto/             QuizProblemResponse, ChoiceResponse
│
├── quiz/                (submission + scoring)
│   ├── domain/          Submission
│   ├── repository/      SubmissionRepository
│   ├── service/         SubmissionService, ScoringService
│   ├── controller/      SubmissionController (POST /submit, GET /me/wrong-answers)
│   └── dto/             SubmitRequest, SubmitResponse, WrongAnswerItem
│
├── tag/
│   ├── domain/          Tag, ProblemTag
│   └── repository/      TagRepository
│
└── global/
    ├── common/          Domain(enum), Difficulty(enum), ProblemType(enum)
    ├── response/        ApiResponse, ApiError, FieldError, PageResponse
    ├── exception/       BusinessException, ErrorCode(enum), GlobalExceptionHandler
    └── config/          SecurityConfig, SwaggerConfig, JpaAuditingConfig
```

## 배치 판단 기준
- **엔티티는 각 도메인의 `domain/`** 에 둔다(예 `problem/domain/Problem`). `Choice`는 Problem에 종속이라 `problem/domain`.
- **연결 엔티티(DocumentTag/ProblemTag)** 는 소유 측 애그리거트에 둔다: DocumentTag→document, ProblemTag→tag(또는 problem). 여기선 `document/`, `tag/`에 각각 배치.
- **채점(`ScoringService`)** 은 quiz 패키지. type별 전략이 커지면 `quiz/scoring/`에 전략 클래스 분리.
- **enum 3종(Domain/Difficulty/ProblemType)** 은 여러 도메인이 공유하므로 `global/common`.
- QueryDSL 도입(로드맵 1) 시 `*/repository/`에 `XxxRepositoryCustom` + `XxxRepositoryImpl` 추가.

## 계층 의존 방향
```
controller → service → repository → domain(엔티티)
             ↘ dto ↗
global(response/exception/config)은 전 계층에서 참조 가능(역참조 금지)
```
- 컨트롤러는 엔티티를 직접 반환하지 않는다 — 항상 DTO 변환.
- 서비스 간 호출은 같은 계층 참조 허용하되 순환 의존 금지.
