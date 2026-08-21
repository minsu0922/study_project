package project.study.study_project.global.common;

/**
 * CS 학습 도메인(대분류) — 문서 02-domain-enums 기준.
 * <p>상수명(영문)은 DB/API 표기, {@link #getDisplayName()}은 화면 표기(한글).
 * DB 저장은 {@code @Enumerated(EnumType.STRING)}으로 상수명을 그대로 쓴다(ORDINAL 금지).
 */
public enum Domain {
    NETWORK("네트워크"),
    OS("운영체제"),
    DATABASE("데이터베이스"),
    DS_ALGORITHM("자료구조·알고리즘"),
    SYSTEM_DESIGN("시스템설계"),
    // 소프트웨어공학: 요구사항 분석·UML·디자인 패턴·테스트 기법·형상관리·개발방법론.
    // 정보처리기사 과목을 훑다 세 군데가 어느 칸에도 안 들어간다는 것이 드러나 신설했다 —
    // "소프트웨어 설계"의 요구사항·UML, "소프트웨어 개발"의 테스트·형상관리,
    // "정보시스템 구축관리"의 개발방법론. 셋을 한 칸으로 묶은 이유는 성격이 같아서다:
    // <코드가 아니라 절차를 배우는> 주제들이다.
    //
    // SYSTEM_DESIGN과의 경계가 이 칸의 유일한 위험이다(둘 다 "설계"라는 말을 쓴다).
    // 기준은 <무엇이 돌아가는가>다 — 부하·확장·장애처럼 실행 중인 시스템을 다루면
    // SYSTEM_DESIGN, 사람이 코드를 만들고 관리하는 절차를 다루면 여기다.
    // 그 경계는 모델에게도 말해 줘야 한다(ClaudeProblemGenerator.domainHint) —
    // BACKEND_FRAMEWORK↔LANGUAGE_RUNTIME에서 이미 치른 비용이고, 칸이 늘수록 늘어난다.
    //
    // STRING 저장이라 상수 추가는 기존 데이터에 영향 없음(마이그레이션 불필요).
    SOFTWARE_ENGINEERING("소프트웨어공학"),
    SECURITY("보안"),
    LANGUAGE_RUNTIME("언어·런타임"),
    // 스프링·백엔드: Spring DI/AOP/트랜잭션, JPA 영속성 컨텍스트/N+1, 커넥션 풀 등
    // "프레임워크 동작 원리". 순수 Java/JVM(GC·메모리·동시성)은 LANGUAGE_RUNTIME 유지 —
    // 사용자(백엔드 개발자)의 주력 스택을 별도 칸으로 둬야 "Spring만 골라 복습"이 가능하다.
    // STRING 저장이라 상수 추가는 기존 데이터에 영향 없음(마이그레이션 불필요).
    BACKEND_FRAMEWORK("스프링·백엔드"),
    CLOUD_INFRA("클라우드·인프라"),
    FRONTEND_CS("프론트엔드CS"),
    INTEGRATED("통합시나리오");

    private final String displayName;

    Domain(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
