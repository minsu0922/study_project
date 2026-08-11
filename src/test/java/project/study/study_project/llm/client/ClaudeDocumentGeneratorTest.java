package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 생성 프롬프트 조립 테스트 — docs/15.
 *
 * <p><b>Claude를 부르지 않는다.</b> 검증 대상은 "모델이 좋은 문서를 쓰는가"가 아니라
 * <b>우리가 준 재료가 실제로 프롬프트에 실리는가</b>다. 이건 API 없이 확인할 수 있고,
 * 실제 문서 품질은 사람이 검수하면서 판단할 몫이다.
 *
 * <p>이 테스트가 지키는 것은 <b>조용히 무력해지는 실패</b>다. 주제를 지정했는데 프롬프트에
 * 안 실리면 모델은 그냥 아무 주제나 쓰고, 회피 목록이 안 실리면 이미 있는 문서를 또 쓴다.
 * 둘 다 오류 없이 그럴듯한 결과가 나와서 <b>검수 단계에서도 눈치채기 어렵다</b> —
 * "왜 내가 지정한 주제가 아니지?"를 몇 번 겪고서야 알게 되는 종류다.
 */
class ClaudeDocumentGeneratorTest {

    private final ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator("claude-opus-5");

    @Test
    @DisplayName("주제를 지정하면 그 주제로 쓰라는 지시가 프롬프트에 실린다")
    void includesGivenTopic() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "TCP 혼잡 제어", List.of(), List.of());

        assertThat(prompt).contains("TCP 혼잡 제어");
        assertThat(prompt).as("지정 주제일 때는 '고르라'가 아니라 '이 주제로 쓰라'여야 한다")
                .contains("이 주제로 문서를 써라");
    }

    @Test
    @DisplayName("주제를 비우면 모델이 직접 고르도록 지시한다")
    void asksModelToPickWhenTopicOmitted() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
        assertThat(prompt).doesNotContain("이 주제로 문서를 써라");
    }

    @Test
    @DisplayName("공백만 있는 주제는 지정하지 않은 것으로 본다 — 워크플로 입력이 비면 그렇게 온다")
    void treatsBlankTopicAsUnspecified() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "   ", List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
    }

    @Test
    @DisplayName("기존 문서 제목이 중복 회피 목록으로 실린다 — 없으면 있는 주제를 또 쓴다")
    void includesAvoidTitles() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null,
                List.of("TCP 3-way 핸드셰이크와 연결 종료", "HTTP와 HTTPS — 무엇이 다른가"), List.of());

        assertThat(prompt).contains("이미 문서가 있는 주제");
        assertThat(prompt).contains("TCP 3-way 핸드셰이크와 연결 종료");
        assertThat(prompt).contains("HTTP와 HTTPS — 무엇이 다른가");
    }

    @Test
    @DisplayName("기존 태그가 실린다 — 없으면 tcp/TCP/tcp-handshake처럼 비슷한 태그가 계속 늘어난다")
    void includesPreferredTags() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of("tcp", "http"));

        assertThat(prompt).contains("기존 태그");
        assertThat(prompt).contains("tcp, http");
    }

    @Test
    @DisplayName("목록이 비어 있으면 그 블록 자체를 넣지 않는다 — 빈 제목만 남으면 토큰 낭비다")
    void omitsEmptyBlocks() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).doesNotContain("이미 문서가 있는 주제");
        assertThat(prompt).doesNotContain("기존 태그");
    }

    @Test
    @DisplayName("스프링·백엔드와 언어·런타임은 경계 힌트가 붙는다 — 둘 다 'Java 관련'이라 헷갈린다")
    void addsDomainHintForConfusablePair() {
        assertThat(generator.buildPrompt(Domain.BACKEND_FRAMEWORK, null, List.of(), List.of()))
                .contains("순수 JVM/GC 주제는 제외");
        assertThat(generator.buildPrompt(Domain.LANGUAGE_RUNTIME, null, List.of(), List.of()))
                .contains("프레임워크 주제는 제외");
        assertThat(generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of()))
                .as("경계가 헷갈리지 않는 분야에는 군더더기를 붙이지 않는다")
                .doesNotContain("제외)");
    }
}
