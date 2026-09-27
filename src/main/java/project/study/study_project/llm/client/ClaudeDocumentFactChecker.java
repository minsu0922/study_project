package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.List;

/**
 * Claude API 기반 문서 사실 검수기 — docs/22 §3.1.
 *
 * <p>아직 배치에 연결하지 않았다. 적발률을 재 문턱을 넘은 뒤에 붙인다(docs/22 §4).
 * 그래서 스프링 빈이 아니라 평가 CLI가 직접 만든다.
 */
@Slf4j
public class ClaudeDocumentFactChecker implements DocumentFactChecker {

    /** 사고 토큰까지 들어가는 상한. 지적 목록 자체는 수천 토큰이면 충분하다. */
    private static final long MAX_TOKENS = 32_000L;

    /**
     * 저자가 아니라 반박자로 부른다. 같은 모델이 쓴 글을 저자 입장에서 다시 읽으면
     * 자기 설명을 그대로 믿는 쪽으로 기운다(docs/22 §5).
     */
    static final String SYSTEM_PROMPT = """
            너는 백엔드 CS 개념 문서의 사실 검수자다.
            이 문서는 다른 모델이 썼고, 학습 서비스에 올라가 시험 문제의 근거가 된다.
            문서에 틀린 주장이 하나 있으면, 그 주장을 정답으로 외우는 학습자가 생긴다.
            너의 일은 이 글을 믿지 않고 읽으며 틀린 곳을 찾아내는 것이다.

            [찾을 것 — 이 두 가지만]
            1. FACT_ERROR: 사실과 다른 주장.
               기술의 동작, 기본값, 명령·옵션의 의미, 에러 이름, 표준의 내용이 실제와 다른 곳.
               코드 블록의 주석과 출력 예시도 본문과 똑같이 검사한다.
            2. INTERNAL_MISMATCH: 같은 문서 안에서 서로 어긋나는 곳.
               한 곳에서 1초라고 하고 다른 곳에서 1분이라고 하는 식이다.
               비유나 요약 절이 본문과 다른 수치를 쓰는 경우를 특히 본다.

            [찾지 말 것]
            - 문체, 구성, 길이, 난이도, 설명 순서. 다른 검사가 맡는다.
            - 입문자를 위해 일부러 단순하게 쓴 설명. 단순화는 오류가 아니다.
              다만 단순화한 결과가 사실과 반대가 되면 오류다.
            - 비유가 정확하지 않은 것. 비유 안의 수치가 본문과 다를 때만 INTERNAL_MISMATCH다.
            - "더 정확히 쓰면 좋겠다" 수준의 보충. 틀린 것만 적는다.
            - 버전·설정마다 다른 동작을 문서가 그렇다고 밝혀 둔 곳.
              한 버전에서만 맞는 동작을 모든 경우의 사실처럼 단정했을 때만 지적한다.

            [적는 법]
            - quote에는 틀린 내용이 든 문장을 문서에서 글자 그대로 복사한다. 한 문장만 옮긴다.
              고쳐 쓰거나 요약하면 그 지적은 버려진다. 코드가 원문과 대조한다.
            - reason에는 왜 틀렸는지 쓴다. INTERNAL_MISMATCH면 어긋나는 상대 문장도 적는다.
            - correction에는 올바른 내용을 쓴다.
            - 확신하면 HIGH, 의심되지만 확신하지 못하면 LOW로 둔다.
            - 틀린 곳이 없으면 빈 목록을 낸다. 목록을 채우려고 억지로 찾지 마라.
              잘못된 지적은 검수자가 경고를 안 읽게 만든다.
            """;

    private final String model;

    /** 이 인스턴스가 쓴 토큰 누계. 사고 토큰은 출력 쪽에 들어간다. */
    private long inputTokens;
    private long outputTokens;

    public ClaudeDocumentFactChecker(String model) {
        this.model = model;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    @Override
    public List<FactCheckFinding> check(String title, String contentMd) {
        StructuredMessageCreateParams<FactCheckFinding.Result> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 사실 대조는 "이 동작이 정말 그런가"를 따져 봐야 하는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(FactCheckFinding.Result.class)
                .addUserMessage(buildPrompt(title, contentMd))
                .build();

        List<FactCheckFinding> raw;
        try {
            var response = AnthropicClientHolder.get().messages().create(params);
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();
            raw = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text().findings())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 검수 결과가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(사실 검수): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(사실 검수): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 사실 검수에 실패했습니다.");
        }

        List<FactCheckFinding> kept = keepQuotedInDocument(raw == null ? List.of() : raw, contentMd);
        if (raw != null && kept.size() < raw.size()) {
            log.info("원문에 없는 인용 {}건을 버렸습니다.", raw.size() - kept.size());
        }
        return kept;
    }

    static String buildPrompt(String title, String contentMd) {
        return "아래 문서를 검수하라.\n\n# 제목\n" + title + "\n\n# 본문\n" + contentMd;
    }

    /** 인용이 원문에 있는 지적만 남긴다. 모델이 지어낸 인용은 검수자가 찾을 수 없다. */
    public static List<FactCheckFinding> keepQuotedInDocument(List<FactCheckFinding> findings, String contentMd) {
        String doc = normalize(contentMd);
        return findings.stream()
                .filter(f -> f.quote() != null)
                .filter(f -> {
                    String q = normalize(f.quote());
                    return !q.isEmpty() && doc.contains(q);
                })
                .toList();
    }

    /**
     * 대조용 정규화 — 강조·코드 표시를 걷고 공백을 한 칸으로 줄인다.
     * 모델은 인용하면서 `**`와 백틱을 자주 빼먹는데, 그건 내용을 바꾼 게 아니다.
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("*", "").replace("`", "").replaceAll("\\s+", " ").trim();
    }
}
