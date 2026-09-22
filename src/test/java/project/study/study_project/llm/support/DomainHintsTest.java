package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분야 경계 설명의 <b>단일 원본</b>을 지킨다.
 *
 * <p>예전에는 이 문자열이 ClaudeProblemGenerator와 ClaudeDocumentGenerator의 switch 두 벌에
 * 복사돼 있었다. 한쪽만 고치면 "문서는 절차를 썼는데 문제는 부하를 묻는" 어긋남이 생기고,
 * 그건 근거 문서를 준 목적을 통째로 무너뜨린다.
 */
class DomainHintsTest {

    @Test
    @DisplayName("내장 힌트는 프롬프트에 실릴 꼴로 — 앞 공백과 괄호까지 붙여 준다")
    void builtInHintIsWrappedForPrompt() {
        assertThat(DomainHints.BUILT_IN.hintFor(TestDomains.BACKEND_FRAMEWORK))
                .startsWith(" (")
                .endsWith(")")
                .contains("Spring DI/IoC");
    }

    @Test
    @DisplayName("힌트가 없는 분야는 빈 문자열 — 지금까지와 같다")
    void missingHintIsEmpty() {
        assertThat(DomainHints.BUILT_IN.hintFor(TestDomains.NETWORK)).isEmpty();
        assertThat(DomainHints.BUILT_IN.rawHintFor(TestDomains.NETWORK)).isEmpty();
    }

    @Test
    @DisplayName("설정에서 받은 힌트가 내장값을 대신한다")
    void givenHintsReplaceBuiltIn() {
        DomainHints hints = DomainHints.of(Map.of(TestDomains.NETWORK, "TCP·HTTP 위주"));

        assertThat(hints.rawHintFor(TestDomains.NETWORK)).isEqualTo("TCP·HTTP 위주");
        assertThat(hints.hintFor(TestDomains.NETWORK)).isEqualTo(" (TCP·HTTP 위주)");
        // 설정에 없는 분야는 내장값으로 돌아가지 않는다 — 화면에서 지운 힌트가 되살아나면
        // "지웠는데 왜 그대로인가"가 되고, 그 혼란이 힌트를 못 믿게 만든다.
        assertThat(hints.hintFor(TestDomains.BACKEND_FRAMEWORK)).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열·공백만 있는 힌트는 없는 것으로 본다")
    void blankHintCountsAsMissing() {
        DomainHints hints = DomainHints.of(Map.of(TestDomains.OS, "   "));

        assertThat(hints.hintFor(TestDomains.OS)).isEmpty();
    }
}
