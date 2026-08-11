package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 구조화 출력 스키마가 SDK 검증을 통과하는지 본다 — docs/15.
 *
 * <p><b>왜 이 테스트가 생겼나.</b> 문서 생성 첫 실행이 이 지점에서 죽었다:
 * <pre>
 *   Local validation failed for JSON schema derived from GeneratedDocumentItem$Envelope:
 *    - #/properties/document: Expected exactly one of 'type' or 'anyOf' or '$ref'
 * </pre>
 * 문제 생성 코드를 그대로 흉내 내며 봉투(Envelope) record를 만든 게 원인이었다. 문제는 <b>목록</b>이라
 * 봉투가 필요했지만(JSON 최상위는 객체여야 하므로), 문서는 그 자체가 이미 객체라 봉투가 불필요했고,
 * 단일 객체 필드에 설명을 붙이면 스키마 생성기가 SDK 검증기가 거부하는 형태(allOf)를 만든다.
 *
 * <p><b>이 검증은 네트워크도 API 키도 쓰지 않는다.</b> {@code outputConfig(Class)}를 호출하는
 * 순간 SDK가 클라이언트 쪽에서 스키마를 만들고 검증한다. 즉 <b>요금 0원으로 잡을 수 있는 사고를
 * 실제 배치 실행에서 잡았던</b> 셈이다 — 그래서 테스트로 내렸다.
 *
 * <p>스키마 모양은 우리가 record를 고칠 때뿐 아니라 <b>SDK를 올릴 때도</b> 깨질 수 있다.
 * 그때도 이 테스트가 먼저 알려 준다.
 */
class StructuredOutputSchemaTest {

    @Test
    @DisplayName("개념 문서 스키마가 SDK 검증을 통과한다 — 봉투를 씌우면 여기서 막힌다")
    void documentSchemaIsValid() {
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(1_000L)
                .outputConfig(GeneratedDocumentItem.class)
                .addUserMessage("스키마 검증용")
                .build())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("문제 목록 스키마가 SDK 검증을 통과한다 — 이미 돌고 있는 쪽의 회귀 방지")
    void problemBatchSchemaIsValid() {
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(1_000L)
                .outputConfig(GeneratedProblemItem.Batch.class)
                .addUserMessage("스키마 검증용")
                .build())
                .doesNotThrowAnyException();
    }
}
