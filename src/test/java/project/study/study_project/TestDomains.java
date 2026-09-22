package project.study.study_project;

import project.study.study_project.global.common.DomainCode;

/**
 * 테스트에서 쓰는 11개 기본 분야 코드 상수 — 옛 {@code Domain} enum의 자리를 잇는다.
 *
 * <p>테스트마다 {@code DomainCode.of("NETWORK")}를 반복해 타이핑하면 오타가 나도 컴파일이
 * 통과해 버린다(형식만 맞으면 {@link DomainCode#of}가 받아 준다). 상수로 한 번만 만들어 두면
 * 오타는 이 파일 하나에서만 날 수 있고, IDE 자동완성도 된다. 프로덕션 코드는 이 상수를 쓰지
 * 않는다 — 본코드가 알아도 되는 분야 코드 출처는 {@code DefaultDomains} 하나뿐이다.
 */
public final class TestDomains {

    public static final DomainCode NETWORK = DomainCode.of("NETWORK");
    public static final DomainCode OS = DomainCode.of("OS");
    public static final DomainCode DATABASE = DomainCode.of("DATABASE");
    public static final DomainCode DS_ALGORITHM = DomainCode.of("DS_ALGORITHM");
    public static final DomainCode SYSTEM_DESIGN = DomainCode.of("SYSTEM_DESIGN");
    public static final DomainCode SOFTWARE_ENGINEERING = DomainCode.of("SOFTWARE_ENGINEERING");
    public static final DomainCode SECURITY = DomainCode.of("SECURITY");
    public static final DomainCode LANGUAGE_RUNTIME = DomainCode.of("LANGUAGE_RUNTIME");
    public static final DomainCode BACKEND_FRAMEWORK = DomainCode.of("BACKEND_FRAMEWORK");
    public static final DomainCode CLOUD_INFRA = DomainCode.of("CLOUD_INFRA");
    public static final DomainCode INTEGRATED = DomainCode.of("INTEGRATED");

    private TestDomains() {
    }
}
