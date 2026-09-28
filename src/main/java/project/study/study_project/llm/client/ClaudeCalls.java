package project.study.study_project.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchResultsParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude API를 부르는 단 하나의 창구 — 바로 호출과 Batch API 호출을 가른다(docs/23).
 *
 * <p><b>배치 모드는 배치 실행에서만 켠다.</b> Batch API는 모든 토큰이 반값인 대신 답이 늦다
 * (대부분 1시간 안, 최대 24시간). 새벽 배치는 기다리는 사람이 없지만, 관리자 화면의
 * "문서로 문제 만들기"는 사람이 기다린다. 그래서 기본은 바로 호출이고 {@code DraftGeneratorCli}만 켠다.
 *
 * <p><b>호출 하나를 배치 하나로 보낸다.</b> 생성 → 검사 → 검수가 앞 결과를 받아야 다음을 보내는
 * 사슬이라, 여러 호출을 한 배치로 묶으면 배치 흐름을 다시 짜야 한다. 할인은 요청 수와 무관하게 같다.
 *
 * <p><b>두 경로 모두 응답 JSON을 여기서 읽는다.</b> SDK의 구조화 결과({@code StructuredMessage})는
 * 생성자가 막혀 있어 배치 결과로 만들 수 없다. 한쪽만 SDK가 읽으면 두 경로가 다르게 읽을 수 있어
 * 바로 호출도 원문 JSON을 받아 같은 방식으로 읽는다.
 */
@Slf4j
public final class ClaudeCalls {

    /** 배치가 이 시간 안에 끝나지 않으면 취소하고 정가로 바로 부른다. 결과가 하루 밀리는 것보다 낫다. */
    static final Duration BATCH_DEADLINE = Duration.ofMinutes(45);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private static volatile boolean batchMode;
    private static volatile Transport transport = new SdkTransport();
    private static volatile Sleeper sleeper = Thread::sleep;
    private static final List<Usage> LEDGER = new ArrayList<>();

    private ClaudeCalls() {
    }

    /**
     * @param value      응답 JSON을 읽은 값. 텍스트 블록이 없으면 null
     * @param pauseTurn  서버 도구(웹 검색)가 길어져 중간에 멈췄는지
     */
    public record Result<T>(T value, long inputTokens, long outputTokens, long webSearches, boolean pauseTurn) {
    }

    /** 호출 한 건의 사용량. {@code batch}는 실제로 배치 요금이 적용됐는지다(시간 초과로 바로 부르면 false). */
    public record Usage(String label, String model, long inputTokens, long outputTokens, long webSearches,
                        boolean batch) {
    }

    /** 전송 결과 중 우리가 쓰는 것만. SDK의 Message를 테스트에서 만들 수 있게 얇게 옮겨 둔다. */
    record Raw(String lastText, long inputTokens, long outputTokens, long webSearches, boolean pauseTurn) {
    }

    enum BatchState { IN_PROGRESS, ENDED }

    /** 실제 API와 테스트 가짜를 가르는 경계. */
    interface Transport {
        Raw send(MessageCreateParams params);

        String submit(MessageCreateParams params);

        BatchState poll(String batchId);

        /** @return 성공 결과. 실패·만료·취소면 null */
        Raw result(String batchId);

        void cancel(String batchId);
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public static void useBatch(boolean on) {
        batchMode = on;
    }

    public static boolean batchMode() {
        return batchMode;
    }

    static void useTransport(Transport t, Sleeper s) {
        transport = t;
        sleeper = s;
    }

    static void resetForTest() {
        batchMode = false;
        transport = new SdkTransport();
        sleeper = Thread::sleep;
        synchronized (LEDGER) {
            LEDGER.clear();
        }
    }

    /** 이번 실행에서 부른 호출들. 배치 요약에 비용 합계를 남길 때 쓴다. */
    public static List<Usage> ledger() {
        synchronized (LEDGER) {
            return List.copyOf(LEDGER);
        }
    }

    /**
     * 부르고 응답 JSON을 {@code params}의 출력 형식으로 읽는다.
     * API 예외(AnthropicServiceException 등)는 그대로 던진다. 호출부마다 안내 문구가 달라서다.
     *
     * @param label 비용 요약에 찍힐 이름("문서 생성", "사실 검수" 등)
     */
    public static <T> Result<T> create(String label, StructuredMessageCreateParams<T> params) {
        MessageCreateParams raw = params.rawParams();
        boolean batched = batchMode;
        Raw response = batched ? viaBatch(label, raw) : null;
        if (response == null) {
            batched = false;
            response = transport.send(raw);
        }
        record(new Usage(label, raw.model().asString(), response.inputTokens(), response.outputTokens(),
                response.webSearches(), batched));
        return new Result<>(parse(response.lastText(), params.outputType()),
                response.inputTokens(), response.outputTokens(), response.webSearches(), response.pauseTurn());
    }

    /** @return 배치 결과. 시간 초과·실패면 null — 호출부가 바로 호출로 대신한다 */
    private static Raw viaBatch(String label, MessageCreateParams raw) {
        String batchId = transport.submit(raw);
        log.info("{}: 배치 {} 제출", label, batchId);
        long waited = 0;
        try {
            while (transport.poll(batchId) != BatchState.ENDED) {
                if (waited >= BATCH_DEADLINE.toMillis()) {
                    transport.cancel(batchId);
                    log.warn("{}: 배치 {}가 {}분 안에 끝나지 않아 취소하고 정가로 바로 부릅니다",
                            label, batchId, BATCH_DEADLINE.toMinutes());
                    return null;
                }
                sleeper.sleep(POLL_INTERVAL.toMillis());
                waited += POLL_INTERVAL.toMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transport.cancel(batchId);
            return null;
        }
        Raw result = transport.result(batchId);
        if (result == null) {
            log.warn("{}: 배치 {} 요청이 실패·만료돼 정가로 바로 부릅니다", label, batchId);
        } else {
            log.info("{}: 배치 {} 완료({}초 대기)", label, batchId, waited / 1000);
        }
        return result;
    }

    static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_003, "모델 응답을 읽지 못했습니다: " + e.getMessage());
        }
    }

    private static void record(Usage usage) {
        synchronized (LEDGER) {
            LEDGER.add(usage);
        }
    }

    /* ── 실제 API ── */

    static final class SdkTransport implements Transport {

        private static final String CUSTOM_ID = "only";

        @Override
        public Raw send(MessageCreateParams params) {
            return toRaw(client().messages().create(params));
        }

        @Override
        public String submit(MessageCreateParams params) {
            return client().messages().batches().create(BatchCreateParams.builder()
                    .addRequest(BatchCreateParams.Request.builder()
                            .customId(CUSTOM_ID)
                            .params(toBatchParams(params))
                            .build())
                    .build()).id();
        }

        /**
         * 배치 요청의 params는 메시지 요청 본문과 같은 JSON이다. 필드를 하나씩 옮기면
         * 나중에 붙인 설정(도구, 사고 등)을 빠뜨리기 쉬워 본문을 통째로 옮긴다.
         */
        static BatchCreateParams.Request.Params toBatchParams(MessageCreateParams params) {
            return ObjectMappers.jsonMapper().convertValue(params._body(), BatchCreateParams.Request.Params.class);
        }

        @Override
        public BatchState poll(String batchId) {
            MessageBatch batch = client().messages().batches().retrieve(batchId);
            return batch.processingStatus().equals(MessageBatch.ProcessingStatus.ENDED)
                    ? BatchState.ENDED : BatchState.IN_PROGRESS;
        }

        @Override
        public Raw result(String batchId) {
            try (StreamResponse<MessageBatchIndividualResponse> stream = client().messages().batches()
                    .resultsStreaming(BatchResultsParams.builder().messageBatchId(batchId).build())) {
                return stream.stream()
                        .filter(r -> r.result().isSucceeded())
                        .findFirst()
                        .map(r -> toRaw(r.result().asSucceeded().message()))
                        .orElse(null);
            }
        }

        @Override
        public void cancel(String batchId) {
            try {
                client().messages().batches().cancel(batchId);
            } catch (RuntimeException e) {
                // 이미 끝난 배치를 취소하면 오류가 난다. 어느 쪽이든 결과는 바로 호출로 얻는다
                log.info("배치 {} 취소 실패(무시): {}", batchId, e.getMessage());
            }
        }

        private static AnthropicClient client() {
            return AnthropicClientHolder.get();
        }

        private static Raw toRaw(Message m) {
            // 검색을 켜면 텍스트 블록이 검색 앞뒤로 나뉠 수 있다. 결과 JSON은 마지막 블록에 온다
            String lastText = m.content().stream()
                    .flatMap(block -> block.text().stream())
                    .reduce((first, second) -> second)
                    .map(t -> t.text())
                    .orElse(null);
            return new Raw(lastText, m.usage().inputTokens(), m.usage().outputTokens(),
                    m.usage().serverToolUse().map(u -> u.webSearchRequests()).orElse(0L),
                    m.stopReason().filter(r -> r.equals(StopReason.PAUSE_TURN)).isPresent());
        }
    }
}
