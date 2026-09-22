package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDomainsTest {

    @Test
    @DisplayName("기본 11개는 예전 enum 선언 순서 그대로다 — 이 순서가 날짜 순환의 폴백 순서였다")
    void keepsEnumDeclarationOrder() {
        assertThat(DefaultDomains.codes()).extracting(DomainCode::value).containsExactly(
                "NETWORK", "OS", "DATABASE", "DS_ALGORITHM", "SYSTEM_DESIGN", "SOFTWARE_ENGINEERING",
                "SECURITY", "LANGUAGE_RUNTIME", "BACKEND_FRAMEWORK", "CLOUD_INFRA", "INTEGRATED");
    }

    @Test
    @DisplayName("이름은 예전 enum의 displayName 그대로다")
    void keepsEnumDisplayNames() {
        assertThat(DefaultDomains.displayName(DomainCode.of("DS_ALGORITHM"))).isEqualTo("자료구조·알고리즘");
        assertThat(DefaultDomains.displayName(DomainCode.of("BACKEND_FRAMEWORK"))).isEqualTo("스프링·백엔드");
    }

    @Test
    @DisplayName("모르는 코드의 이름은 코드 글자 그대로 — 화면에 빈칸이 뜨는 것보다 낫다")
    void unknownCodeFallsBackToItself() {
        assertThat(DefaultDomains.displayName(DomainCode.of("MESSAGING"))).isEqualTo("MESSAGING");
        assertThat(DefaultDomains.isKnown(DomainCode.of("MESSAGING"))).isFalse();
    }

    @Test
    @DisplayName("내장 힌트 4개가 옮겨 왔다 — 글자가 실수로 바뀌면 앞부분 고정 문자열이 잡는다")
    void carriesBuiltInHints() {
        // 예전엔 DomainHints.BUILT_IN과 비교했다. 이제 BUILT_IN이 DefaultDomains.hints()로
        // 만들어지므로 그 비교는 자기 자신과 비교하는 동어반복이 된다 — 누가 힌트 글자를
        // 고쳐도 통과한다. 그래서 옛 builtInMap()의 원문 앞부분을 여기 박아 두고 대조한다.
        // 전문을 박지 않은 것은 의도된 문구 수정(관리 화면이 아니라 기본값 자체를 바꿀 때)의
        // 비용을 줄이려는 것이고, 앞 20자 정도면 "다른 분야 힌트와 뒤바뀜"·"실수로 지움"은 잡힌다.
        assertThat(DefaultDomains.hints()).containsOnlyKeys(
                DomainCode.of("BACKEND_FRAMEWORK"), DomainCode.of("LANGUAGE_RUNTIME"),
                DomainCode.of("SOFTWARE_ENGINEERING"), DomainCode.of("SYSTEM_DESIGN"));
        assertThat(DefaultDomains.hints().get(TestDomains.BACKEND_FRAMEWORK))
                .startsWith("Spring DI/IoC·Bean 생명주기")
                .endsWith("순수 JVM/GC 주제는 제외");
        assertThat(DefaultDomains.hints().get(TestDomains.LANGUAGE_RUNTIME))
                .startsWith("Java 언어·JVM 내부: 메모리 구조")
                .endsWith("Spring/JPA 등 프레임워크 주제는 제외");
        assertThat(DefaultDomains.hints().get(TestDomains.SOFTWARE_ENGINEERING))
                .startsWith("요구사항 분석·UML·디자인 패턴")
                .endsWith("부하·확장·장애처럼 돌아가는 시스템을 다루는 주제는 제외");
        assertThat(DefaultDomains.hints().get(TestDomains.SYSTEM_DESIGN))
                .startsWith("돌아가는 시스템의 구조: 부하 분산")
                .endsWith("요구사항·UML·테스트 기법 같은 개발 절차 주제는 제외");
    }

    @Test
    @DisplayName("DomainHints.BUILT_IN이 비어 있지 않다 — 두 클래스 사이 static 초기화 순환이 없다는 증거")
    void builtInHintsAreNotEmptyAfterInit() {
        // DefaultDomains를 먼저 건드린 뒤 BUILT_IN을 읽는다. 두 클래스가 서로의 static 필드를
        // 부르는 순환이 다시 생기면, 먼저 초기화된 쪽이 상대를 null로 읽어 여기서 빈 힌트나
        // NPE가 난다(DefaultDomains.HINTS Javadoc 참고).
        assertThat(DefaultDomains.codes()).hasSize(11);
        assertThat(DomainHints.BUILT_IN.rawHintFor(TestDomains.BACKEND_FRAMEWORK))
                .isEqualTo(DefaultDomains.hints().get(TestDomains.BACKEND_FRAMEWORK));
    }
}
