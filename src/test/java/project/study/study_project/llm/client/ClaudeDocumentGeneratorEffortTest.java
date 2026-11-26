package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.client.ClaudeCalls.Raw;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 생성은 사고 수준 high로 부른다(2026-11-26). Opus 5.5는 정하지 않으면 medium이라,
 * 10-10 모델을 바꾼 뒤로 high에서 맞춘 프롬프트가 medium으로 돌고 있었다.
 */
class ClaudeDocumentGeneratorEffortTest {

    private static final String BODY = "# 이름 풀이\n\n## 무엇인가\n\n리졸버는 대신 묻는 서버다.\n";

    @AfterEach
    void reset() {
        ClaudeCalls.resetForTest();
    }

    private static ClaudeDocumentPolisherTest.Fake install() {
        String json = "{\"title\":\"이름 풀이\",\"slug\":\"name-resolution\",\"contentMd\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(BODY) + ",\"tags\":[\"network\"]}";
        ClaudeDocumentPolisherTest.Fake fake = new ClaudeDocumentPolisherTest.Fake(new Raw(json, 1000, 500, 0, false, "", false));
        ClaudeCalls.useTransport(fake, ms -> { });
        return fake;
    }

    @Test
    @DisplayName("입문편·심화편 요청 모두 effort high와 출력 형식을 함께 싣는다 — 하나만 실리면 다른 하나가 지워진 것이다")
    void bothEditionsSendHighEffortWithFormat() {
        ClaudeDocumentPolisherTest.Fake fake = install();
        ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator("claude-opus-5-5");

        GeneratedDocumentItem beginner = generator.generate(TestDomains.NETWORK, "이름 풀이", List.of(), List.of());
        generator.generateAdvanced(TestDomains.NETWORK, beginner, List.of());

        assertThat(fake.sent).hasSize(2).allSatisfy((MessageCreateParams sent) -> {
            OutputConfig config = sent.outputConfig().orElseThrow();
            assertThat(config.effort()).contains(OutputConfig.Effort.HIGH);
            assertThat(config.format()).isPresent();
        });
        assertThat(beginner.contentMd()).isEqualTo(BODY);
    }
}
