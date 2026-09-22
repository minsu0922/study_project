package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;
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
    @DisplayName("내장 힌트 4개가 옮겨 왔다 — 지금 DomainHints.BUILT_IN과 글자까지 같다")
    void carriesBuiltInHints() {
        assertThat(DefaultDomains.hints()).containsOnlyKeys(
                DomainCode.of("BACKEND_FRAMEWORK"), DomainCode.of("LANGUAGE_RUNTIME"),
                DomainCode.of("SOFTWARE_ENGINEERING"), DomainCode.of("SYSTEM_DESIGN"));
        assertThat(DefaultDomains.hints().get(DomainCode.of("BACKEND_FRAMEWORK")))
                .isEqualTo(DomainHints.BUILT_IN.rawHintFor(Domain.BACKEND_FRAMEWORK));
    }
}
