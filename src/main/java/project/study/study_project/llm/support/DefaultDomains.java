package project.study.study_project.llm.support;

import project.study.study_project.global.common.DomainCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 옛 {@code Domain} enum이 갖고 있던 11개 기본 분야를 한곳에 모은 것.
 *
 * <p><b>본코드에서 특정 분야 코드(NETWORK, DATABASE, ...)를 아는 곳은 이 클래스 하나뿐이어야
 * 한다.</b> 나머지 코드는 전부 {@link DomainCatalog}를 통해 "지금 등록된 분야가 무엇인지"를
 * 묻는다 — 그래야 관리자가 화면에서 분야를 추가·삭제해도 코드를 고칠 필요가 없다(스펙 8절).
 *
 * <p>이 목록을 정당하게 쓰는 곳은 둘뿐이다.
 * <ol>
 *     <li>빈 DB의 첫 기동 시드 — {@code domain_setting} 테이블에 행이 하나도 없을 때
 *         이 11개로 채운다.</li>
 *     <li>배치의 마지막 폴백 — 설정 파일({@code generated/_domain-settings.json})도 없고
 *         yml 목록도 없을 때만 이 순서를 쓴다.</li>
 * </ol>
 * 그 외의 자리(문제 생성, 화면 목록, 통계)는 전부 {@link DomainCatalog} 구현체를 거쳐야 한다 —
 * 그래야 "코드엔 있는데 화면에선 지운 분야"가 계속 살아나는 일이 없다(DomainHints의 교훈,
 * 지운 값이 조용히 되살아나면 그 화면을 아무도 믿지 않게 된다).
 */
public final class DefaultDomains {

    /**
     * 예전 {@code Domain} enum 선언 순서 그대로다. 이 순서가 배치 날짜 순환의 폴백 순서였기
     * 때문에 임의로 바꾸면 안 된다 — 운영 중이던 순환 주기가 흔들린다.
     */
    private static final List<DomainCode> CODES = List.of(
            DomainCode.of("NETWORK"),
            DomainCode.of("OS"),
            DomainCode.of("DATABASE"),
            DomainCode.of("DS_ALGORITHM"),
            DomainCode.of("SYSTEM_DESIGN"),
            // 소프트웨어공학: 요구사항 분석·UML·디자인 패턴·테스트 기법·형상관리·개발방법론.
            // 정보처리기사 과목을 훑다 세 군데가 어느 칸에도 안 들어간다는 것이 드러나 신설했다 —
            // "소프트웨어 설계"의 요구사항·UML, "소프트웨어 개발"의 테스트·형상관리,
            // "정보시스템 구축관리"의 개발방법론. 셋을 한 칸으로 묶은 이유는 성격이 같아서다:
            // <코드가 아니라 절차를 배우는> 주제들이다.
            //
            // SYSTEM_DESIGN과의 경계가 이 칸의 유일한 위험이다(둘 다 "설계"라는 말을 쓴다).
            // 기준은 <무엇이 돌아가는가>다 — 부하·확장·장애처럼 실행 중인 시스템을 다루면
            // SYSTEM_DESIGN, 사람이 코드를 만들고 관리하는 절차를 다루면 여기다.
            // 그 경계는 모델에게도 말해 줘야 한다(아래 HINTS, 2026-09-21부터 관리자 화면
            // "분야 설정"에서 고치고 domain_setting 테이블에 저장된다, docs/21) —
            // BACKEND_FRAMEWORK↔LANGUAGE_RUNTIME에서 이미 치른 비용이고, 칸이 늘수록 늘어난다.
            DomainCode.of("SOFTWARE_ENGINEERING"),
            DomainCode.of("SECURITY"),
            DomainCode.of("LANGUAGE_RUNTIME"),
            // 스프링·백엔드: Spring DI/AOP/트랜잭션, JPA 영속성 컨텍스트/N+1, 커넥션 풀 등
            // "프레임워크 동작 원리". 순수 Java/JVM(GC·메모리·동시성)은 LANGUAGE_RUNTIME 유지 —
            // 사용자(백엔드 개발자)의 주력 스택을 별도 칸으로 둬야 "Spring만 골라 복습"이 가능하다.
            DomainCode.of("BACKEND_FRAMEWORK"),
            DomainCode.of("CLOUD_INFRA"),
            // 프론트엔드CS(브라우저·렌더링)는 2026-09-21에 뺐다. 이 프로젝트의 목적은 백엔드 면접
            // 대비인데 그 칸은 배치 후보에서도 빠져 있어(application.yml batch-domains) 자동 생성이
            // 한 번도 안 돌았고, 실제로 6개 테이블 전부 해당 행이 0건이었다 — 그래서 지워도
            // 깨지는 조회가 없었다. 칸을 남겨 두면 관리자 화면의 필터·통계 목록에 계속 끼어들어
            // "채울 계획이 없는 빈 칸"을 만든다(AdminStatsService 주석의 그 문제).
            //
            // 컬럼은 enum 시절부터 상수명 문자열(VARCHAR(30))이라, 코드를 더하거나 빼도
            // 기존 데이터에는 마이그레이션이 필요 없다(ORDINAL 숫자로 저장했다면 순서가 밀렸다).
            DomainCode.of("INTEGRATED"));

    /** 예전 {@code Domain#getDisplayName()} 그대로 — 글자 하나도 바꾸지 않았다. */
    private static final Map<DomainCode, String> DISPLAY_NAMES = buildDisplayNames();

    /**
     * 예전 {@code DomainHints.builtInMap()}의 4개를 그대로 옮긴 것 — 이제 이 문자열의 원본은
     * 여기 하나뿐이고, {@link DomainHints#BUILT_IN}이 이 맵을 읽어 만들어진다.
     *
     * <p><b>왜 문자열을 여기에 직접 적었나.</b> 처음(Task 2)에는 {@code DomainHints.BUILT_IN}에서
     * 읽어 복사했는데, {@code BUILT_IN}이 거꾸로 이 맵을 읽게 되면서 두 클래스의 static 필드가
     * 서로를 부르는 순환이 생긴다. 자바는 그 순환을 컴파일 때 막지 않고, 먼저 초기화되는 쪽이
     * 상대 필드를 {@code null}로 읽는다 — 힌트가 조용히 비어 모든 프롬프트에서 경계 설명이
     * 빠진다. 그래서 의존을 {@code DomainHints → DefaultDomains} 한 방향으로 끊고, 글자는
     * 옛 {@code builtInMap()}에서 그대로 복사해 왔다(한 글자라도 다르면 생성 프롬프트가 조용히
     * 바뀐다 — {@code DefaultDomainsTest.carriesBuiltInHints}가 앞부분을 고정 문자열로 지킨다).
     */
    private static final Map<DomainCode, String> HINTS = buildHints();

    private DefaultDomains() {
    }

    public static List<DomainCode> codes() {
        return CODES;
    }

    /** 모르는 코드면 코드 문자열 그대로 — 화면에 빈칸이 뜨는 것보다 낫다. */
    public static String displayName(DomainCode code) {
        return DISPLAY_NAMES.getOrDefault(code, code.value());
    }

    public static boolean isKnown(DomainCode code) {
        return DISPLAY_NAMES.containsKey(code);
    }

    public static Map<DomainCode, String> hints() {
        return HINTS;
    }

    private static Map<DomainCode, String> buildDisplayNames() {
        // LinkedHashMap으로 CODES 순서를 유지 — 순서 자체는 의미 없지만 디버깅(toString) 때
        // enum 선언 순서로 눈에 익은 모양이 나오게 해 둔다.
        Map<DomainCode, String> map = new LinkedHashMap<>();
        map.put(DomainCode.of("NETWORK"), "네트워크");
        map.put(DomainCode.of("OS"), "운영체제");
        map.put(DomainCode.of("DATABASE"), "데이터베이스");
        map.put(DomainCode.of("DS_ALGORITHM"), "자료구조·알고리즘");
        map.put(DomainCode.of("SYSTEM_DESIGN"), "시스템설계");
        map.put(DomainCode.of("SOFTWARE_ENGINEERING"), "소프트웨어공학");
        map.put(DomainCode.of("SECURITY"), "보안");
        map.put(DomainCode.of("LANGUAGE_RUNTIME"), "언어·런타임");
        map.put(DomainCode.of("BACKEND_FRAMEWORK"), "스프링·백엔드");
        map.put(DomainCode.of("CLOUD_INFRA"), "클라우드·인프라");
        map.put(DomainCode.of("INTEGRATED"), "통합시나리오");
        return Map.copyOf(map);
    }

    private static Map<DomainCode, String> buildHints() {
        // 이 메서드는 DomainHints를 절대 부르면 안 된다 — HINTS 필드 Javadoc의 초기화 순환 참고.
        Map<DomainCode, String> map = new LinkedHashMap<>();
        // BACKEND_FRAMEWORK ↔ LANGUAGE_RUNTIME: Spring/JPA는 여기, 순수 JVM/GC는 저쪽.
        map.put(DomainCode.of("BACKEND_FRAMEWORK"),
                "Spring DI/IoC·Bean 생명주기·AOP·@Transactional 전파·MVC 흐름, "
                        + "JPA 영속성 컨텍스트·지연 로딩·N+1, 커넥션 풀·서블릿 컨테이너. "
                        + "순수 JVM/GC 주제는 제외");
        map.put(DomainCode.of("LANGUAGE_RUNTIME"),
                "Java 언어·JVM 내부: 메모리 구조·GC·클래스로딩·동시성. "
                        + "Spring/JPA 등 프레임워크 주제는 제외");
        // SOFTWARE_ENGINEERING ↔ SYSTEM_DESIGN: 둘 다 "설계"라는 말을 써서 경계를 모델에게
        // 말해 줘야 한다(CODES의 SOFTWARE_ENGINEERING 주석 참고).
        map.put(DomainCode.of("SOFTWARE_ENGINEERING"),
                "요구사항 분석·UML·디자인 패턴·테스트 기법·형상관리·개발방법론. "
                        + "즉 사람이 코드를 만들고 관리하는 절차. "
                        + "부하·확장·장애처럼 돌아가는 시스템을 다루는 주제는 제외");
        map.put(DomainCode.of("SYSTEM_DESIGN"),
                "돌아가는 시스템의 구조: 부하 분산·캐시 계층·확장·장애 대응·데이터 흐름. "
                        + "요구사항·UML·테스트 기법 같은 개발 절차 주제는 제외");
        return Map.copyOf(map);
    }
}
