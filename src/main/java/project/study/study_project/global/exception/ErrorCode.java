package project.study.study_project.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 체계 — 문서 04-response-format의 에러표 기준.
 * <p>형식: {@code {도메인}_{식별자}}. 클라이언트는 {@link #getCode()}로 분기,
 * {@link #getDefaultMessage()}는 사용자 표시용(한글).
 * 새 에러는 문서 04 표에 추가한 뒤 여기에 상수를 더한다.
 */
public enum ErrorCode {

    // 공통
    COMMON_001("COMMON_001", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다."),
    COMMON_404("COMMON_404", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    // 로드맵 3(요청 제한): RateLimitFilter가 사용. 응답에 Retry-After 헤더(재시도 가능 시점) 동반
    COMMON_429("COMMON_429", HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    COMMON_500("COMMON_500", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 인증/인가
    AUTH_001("AUTH_001", HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    AUTH_002("AUTH_002", HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    AUTH_003("AUTH_003", HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다."),
    AUTH_004("AUTH_004", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    // 로드맵 2(refresh 토큰): 만료·이미 사용(회전됨)·위조된 refresh — 클라이언트는 재로그인 유도
    AUTH_005("AUTH_005", HttpStatus.UNAUTHORIZED, "리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요."),

    // 퀴즈
    QUIZ_001("QUIZ_001", HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다."),
    QUIZ_002("QUIZ_002", HttpStatus.BAD_REQUEST, "지원하지 않는 문제 타입입니다."),
    // 관리자 문제 관리(콘텐츠 등록 기능)에서 사용
    QUIZ_003("QUIZ_003", HttpStatus.CONFLICT, "제출 이력이 있는 문제는 삭제할 수 없습니다."),
    QUIZ_004("QUIZ_004", HttpStatus.BAD_REQUEST, "문제 유형별 입력 규칙에 맞지 않습니다."),

    // 문서
    DOC_001("DOC_001", HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),
    DOC_002("DOC_002", HttpStatus.CONFLICT, "이미 사용 중인 slug입니다."),

    // LLM 문제 생성 (B안 — docs/13, ADR-0006)
    LLM_001("LLM_001", HttpStatus.NOT_FOUND, "생성 초안을 찾을 수 없습니다."),
    LLM_002("LLM_002", HttpStatus.CONFLICT, "이미 처리된 초안입니다."),
    // 외부 API 실패는 우리 서버 잘못(500)이 아니라 상류 문제라는 의미로 502(BAD_GATEWAY)
    LLM_003("LLM_003", HttpStatus.BAD_GATEWAY, "문제 생성 요청에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    LLM_004("LLM_004", HttpStatus.SERVICE_UNAVAILABLE, "ANTHROPIC_API_KEY가 설정되지 않아 문제 생성을 사용할 수 없습니다."),

    // 개념 문서 검수(docs/15). 400이 아니라 409인 이유: 요청 형식은 멀쩡하고
    // "지금 이 초안의 상태로는 승인할 수 없다"는 자원 쪽 사정이라 충돌에 가깝다.
    // 구체적으로 어떤 검증에 걸렸는지는 BusinessException의 상세 메시지로 함께 내려보낸다.
    LLM_005("LLM_005", HttpStatus.CONFLICT, "자동 검증에 걸린 초안은 승인할 수 없습니다."),

    // 문서 주제 대기열(V10). LLM_00x에 얹지 않고 분리한 이유: 저 코드들은 "생성된 결과물"에
    // 대한 것이고, 이쪽은 생성 <전에> 사람이 정해 두는 입력이라 실패의 성격이 다르다.
    TOPIC_001("TOPIC_001", HttpStatus.NOT_FOUND, "주제 대기열 항목을 찾을 수 없습니다."),
    // 409인 이유는 DOC_002와 같다 — 요청 자체는 멀쩡하고 지금 상태와 부딪힐 뿐이다.
    TOPIC_002("TOPIC_002", HttpStatus.CONFLICT, "같은 분야에 같은 주제 범위가 이미 있습니다."),

    // 분야 설정(V19). syncWithDefaults가 기동마다 기본 분야(DefaultDomains) 전체에 행을 맞춰 두므로 정상 경로에서는
    // 나지 않는다 — 그래도 404로 막아 두는 이유는, 동기화 전(부팅 도중)이나 enum 변환 자체가
    // 막힌 상태에서 관리 화면이 이 API를 부르면 NPE 대신 뜻이 분명한 응답을 받게 하기 위해서다.
    DOMAIN_001("DOMAIN_001", HttpStatus.NOT_FOUND, "분야 설정을 찾을 수 없습니다."),
    // 마지막으로 켜진 분야는 끌 수 없다(최종 리뷰 Important 3). "전부 끄기"를 막는 이유는 배치를
    // 멈추는 스위치가 이미 따로 있기 때문이다 — 분야를 다 끄는 것까지 정지 수단으로 인정하면 정지
    // 수단이 둘이 되고, 실제로 CLI는 yml 8개로·앱은 enum 전체로 서로 다르게 읽어 배치가 조용히
    // 계속 돌았다. 메시지에 진짜 스위치 이름을 적어 화면이 곧장 다음 행동을 안내하게 한다.
    // 409가 아니라 400인 이유: 지금 상태와의 충돌이 아니라, 어떤 상태에서도 허용하지 않는 요청이다.
    DOMAIN_002("DOMAIN_002", HttpStatus.BAD_REQUEST,
            "마지막으로 켜진 분야는 끌 수 없습니다. 배치를 멈추려면 분야를 끄지 말고 "
                    + "llm.generation.batch-enabled를 false로 두세요."),
    // 5번 작업(외래키 + 쓰기 경로 확인). enum이 지워지면서 "NETWORK", "FRONTEND_CS"처럼 형식만
    // 맞고 등록되지 않은 코드가 HTTP 파라미터·경로변수·JSON 바디를 그냥 통과하게 됐다 — 예전
    // enum이라면 컴파일도 안 됐을 값이다. DB 외래키(V20)가 최후의 방어선이지만 그건 500(DB 오류)
    // 으로 나온다. 여기서 저장 "전에" DomainCatalog.exists로 먼저 확인해 400으로 바꿔 준다 —
    // 사용자가 고칠 수 있는 실수(오타·지운 분야)와 서버 결함(500)을 가르는 것이 이 코드의 목적이다.
    DOMAIN_003("DOMAIN_003", HttpStatus.BAD_REQUEST, "등록되지 않은 분야입니다."),

    // 문제 오류 제보(V17). 404가 아니라 409가 둘인 이유는 이 기능의 실패가 대부분
    // "없는 것을 만졌다"가 아니라 "이미 그렇게 돼 있다"이기 때문 — 화면은 이 둘을
    // 오류가 아니라 안내로 보여 준다(제보자에게는 실패가 아니라 확인이다).
    REPORT_001("REPORT_001", HttpStatus.CONFLICT, "이미 제보한 문제입니다."),
    REPORT_002("REPORT_002", HttpStatus.NOT_FOUND, "제보를 찾을 수 없습니다."),
    REPORT_003("REPORT_003", HttpStatus.CONFLICT, "이미 처리된 제보입니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
