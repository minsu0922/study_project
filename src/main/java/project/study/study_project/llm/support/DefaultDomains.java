package project.study.study_project.llm.support;

import project.study.study_project.global.common.Domain;
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
            DomainCode.of("SOFTWARE_ENGINEERING"),
            DomainCode.of("SECURITY"),
            DomainCode.of("LANGUAGE_RUNTIME"),
            DomainCode.of("BACKEND_FRAMEWORK"),
            DomainCode.of("CLOUD_INFRA"),
            DomainCode.of("INTEGRATED"));

    /** 예전 {@code Domain#getDisplayName()} 그대로 — 글자 하나도 바꾸지 않았다. */
    private static final Map<DomainCode, String> DISPLAY_NAMES = buildDisplayNames();

    /**
     * 예전 {@link DomainHints#builtInMap()}의 4개를 그대로 옮긴 것.
     * 문자열은 다시 타이핑하지 않고 {@code DomainHints.BUILT_IN.rawHintFor(...)}에서 읽어
     * 복사했다 — 한 글자라도 다르면 생성 프롬프트가 조용히 바뀌기 때문이다. 이 태스크에서는
     * {@code DomainHints}를 건드리지 않는다(다음 태스크가 {@code BUILT_IN}을 이쪽으로 돌린다).
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
        Map<DomainCode, String> map = new LinkedHashMap<>();
        map.put(DomainCode.of("BACKEND_FRAMEWORK"), DomainHints.BUILT_IN.rawHintFor(Domain.BACKEND_FRAMEWORK));
        map.put(DomainCode.of("LANGUAGE_RUNTIME"), DomainHints.BUILT_IN.rawHintFor(Domain.LANGUAGE_RUNTIME));
        map.put(DomainCode.of("SOFTWARE_ENGINEERING"), DomainHints.BUILT_IN.rawHintFor(Domain.SOFTWARE_ENGINEERING));
        map.put(DomainCode.of("SYSTEM_DESIGN"), DomainHints.BUILT_IN.rawHintFor(Domain.SYSTEM_DESIGN));
        return Map.copyOf(map);
    }
}
