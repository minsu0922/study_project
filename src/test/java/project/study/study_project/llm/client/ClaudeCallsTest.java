package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.ClaudeCalls.BatchState;
import project.study.study_project.llm.client.ClaudeCalls.Raw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude 호출 창구 테스트 — 실제 API 대신 가짜 전송기로 돈다.
 * 배치가 늦거나 실패해도 그날 생성물은 나와야 하고, 비용 기록은 실제 요금 방식과 맞아야 한다.
 */
class ClaudeCallsTest {

    private static final String JSON = "{\"titles\":[{\"problemId\":1,\"title\":\"스키마의 뜻\"}]}";

    @AfterEach
    void reset() {
        ClaudeCalls.resetForTest();
    }

    private static StructuredMessageCreateParams<GeneratedTitle.Batch> params() {
        return MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(100L)
                .outputConfig(GeneratedTitle.Batch.class)
                .addUserMessage("제목")
                .build();
    }

    /** 배치가 {@code pollsUntilEnd}번째 확인에서 끝나는 가짜. 결과가 null이면 실패한 요청이다. */
    static final class FakeTransport implements ClaudeCalls.Transport {
        final List<String> calls = new ArrayList<>();
        int polls;
        int pollsUntilEnd = 2;
        Raw batchResult = new Raw(JSON, 1000, 200, 0, false);

        @Override
        public Raw send(MessageCreateParams params) {
            calls.add("send");
            return new Raw(JSON, 1000, 200, 0, false);
        }

        @Override
        public String submit(MessageCreateParams params) {
            calls.add("submit");
            return "msgbatch_1";
        }

        @Override
        public BatchState poll(String batchId) {
            polls++;
            return polls >= pollsUntilEnd ? BatchState.ENDED : BatchState.IN_PROGRESS;
        }

        @Override
        public Raw result(String batchId) {
            calls.add("result");
            return batchResult;
        }

        @Override
        public void cancel(String batchId) {
            calls.add("cancel");
        }
    }

    @Test
    @DisplayName("배치 모드가 꺼져 있으면 바로 부르고, 정가로 기록한다")
    void directByDefault() {
        FakeTransport fake = new FakeTransport();
        ClaudeCalls.useTransport(fake, ms -> { });

        var result = ClaudeCalls.create("제목 생성", params());

        assertThat(fake.calls).containsExactly("send");
        assertThat(result.value().titles()).extracting(GeneratedTitle::title).containsExactly("스키마의 뜻");
        assertThat(ClaudeCalls.ledger()).singleElement()
                .satisfies(u -> assertThat(u.batch()).isFalse());
    }

    @Test
    @DisplayName("배치 모드면 제출하고 끝날 때까지 기다린 뒤 결과를 읽고, 반값으로 기록한다")
    void batchSubmitsPollsAndReads() {
        FakeTransport fake = new FakeTransport();
        List<Long> sleeps = new ArrayList<>();
        ClaudeCalls.useTransport(fake, sleeps::add);
        ClaudeCalls.useBatch(true);

        var result = ClaudeCalls.create("제목 생성", params());

        assertThat(fake.calls).containsExactly("submit", "result");
        assertThat(sleeps).hasSize(1);
        assertThat(result.value().titles()).hasSize(1);
        assertThat(ClaudeCalls.ledger()).singleElement().satisfies(u -> {
            assertThat(u.batch()).isTrue();
            assertThat(u.label()).isEqualTo("제목 생성");
            assertThat(u.model()).isEqualTo("claude-opus-5");
        });
    }

    @Test
    @DisplayName("배치가 기한 안에 안 끝나면 취소하고 바로 불러 그날 결과를 낸다")
    void batchTimeoutFallsBackToDirect() {
        FakeTransport fake = new FakeTransport();
        fake.pollsUntilEnd = Integer.MAX_VALUE;
        ClaudeCalls.useTransport(fake, ms -> { });
        ClaudeCalls.useBatch(true);

        var result = ClaudeCalls.create("제목 생성", params());

        assertThat(fake.calls).containsExactly("submit", "cancel", "send");
        assertThat(result.value().titles()).hasSize(1);
        assertThat(ClaudeCalls.ledger()).singleElement().satisfies(u -> assertThat(u.batch()).isFalse());
        long expectedPolls = ClaudeCalls.BATCH_DEADLINE.toMillis() / ClaudeCalls.POLL_INTERVAL.toMillis() + 1;
        assertThat(fake.polls).isEqualTo((int) expectedPolls);
    }

    @Test
    @DisplayName("배치 요청이 실패·만료되면 바로 불러 대신한다")
    void failedBatchRequestFallsBackToDirect() {
        FakeTransport fake = new FakeTransport();
        fake.batchResult = null;
        ClaudeCalls.useTransport(fake, ms -> { });
        ClaudeCalls.useBatch(true);

        ClaudeCalls.create("제목 생성", params());

        assertThat(fake.calls).containsExactly("submit", "result", "send");
    }

    @Test
    @DisplayName("응답에 텍스트가 없으면 값은 null이다 — 호출부가 자기 문구로 알린다")
    void blankResponseIsNull() {
        assertThat(ClaudeCalls.parse(null, GeneratedTitle.Batch.class)).isNull();
        assertThat(ClaudeCalls.parse(" ", GeneratedTitle.Batch.class)).isNull();
    }

    @Test
    @DisplayName("배치 요청으로 옮겨도 모델·시스템 프롬프트·사고·출력 스키마가 그대로다")
    void batchParamsKeepEverySetting() {
        StructuredMessageCreateParams<FactCheckFinding.Result> original = MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(32_000L)
                .thinking(com.anthropic.models.messages.ThinkingConfigAdaptive.builder().build())
                .system("너는 검수자다")
                .outputConfig(FactCheckFinding.Result.class)
                .addUserMessage("본문")
                .build();

        var batch = ClaudeCalls.SdkTransport.toBatchParams(original.rawParams());

        assertThat(batch.model().asString()).isEqualTo("claude-opus-5");
        assertThat(batch.maxTokens()).isEqualTo(32_000L);
        assertThat(batch.system()).isPresent();
        assertThat(batch.thinking()).isPresent();
        assertThat(batch.outputConfig()).isPresent();
        assertThat(batch.outputConfig().get().toString()).contains("findings", "confidence");
        assertThat(batch.messages()).hasSize(1);
    }

    /* ── SDK 대신 우리가 읽어도 같은 값이 나오는가 ── */

    @Test
    @DisplayName("실제 생성 파일의 문제 JSON을 문제 스키마로 읽는다")
    void parsesRealProblemBatch() throws Exception {
        JsonNode file = new ObjectMapper().readTree(Path.of("generated/2026-09-28.json").toFile());
        String json = "{\"problems\":" + file.get("problems") + "}";

        GeneratedProblemItem.Batch batch = ClaudeCalls.parse(json, GeneratedProblemItem.Batch.class);

        assertThat(batch.problems()).hasSize(file.get("problems").size());
        GeneratedProblemItem first = batch.problems().get(0);
        assertThat(first.question()).isEqualTo(file.get("problems").get(0).get("question").asText());
        assertThat(first.choices()).hasSize(4);
        assertThat(first.questionKind()).isNotNull();
    }

    @Test
    @DisplayName("실제 생성 파일의 문서 JSON을 문서 스키마로 읽는다")
    void parsesRealDocument() throws Exception {
        JsonNode file = new ObjectMapper().readTree(Path.of("generated/documents/2026-09-27.json").toFile());

        GeneratedDocumentItem doc = ClaudeCalls.parse(file.get("document").toString(), GeneratedDocumentItem.class);

        assertThat(doc.title()).isEqualTo(file.get("document").get("title").asText());
        assertThat(doc.contentMd()).isEqualTo(file.get("document").get("contentMd").asText());
    }

    @Test
    @DisplayName("검수 응답의 열거형 값을 읽는다")
    void parsesReviewEnums() {
        String json = """
                {"findings":[{"quote":"q","kind":"FACT_ERROR","reason":"r","correction":"c",
                 "sourceUrl":"","confidence":"HIGH"}]}""";

        FactCheckFinding.Result result = ClaudeCalls.parse(json, FactCheckFinding.Result.class);

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FactCheckFinding.Kind.FACT_ERROR);
            assertThat(f.confidence()).isEqualTo(FactCheckFinding.Confidence.HIGH);
        });
    }

    @Test
    @DisplayName("생성 파일이 저장소에 있다 — 없으면 위 두 테스트가 빈 확인이 된다")
    void realFilesExist() {
        assertThat(Files.exists(Path.of("generated/2026-09-28.json"))).isTrue();
        assertThat(Files.exists(Path.of("generated/documents/2026-09-27.json"))).isTrue();
    }
}
