package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 자바 코드 문제 생성 — 주제 하나로 코드 문제 여러 개를 낸다.
 *
 * <p>개념 문제 생성({@link ClaudeProblemGenerator})과 달리 근거 문서를 받지 않는다.
 * 정답의 근거는 실행 결과이고, 배치가 생성 직후 코드를 실제로 돌려 대조한다.
 * 그래서 프롬프트는 "맞는 문제를 써라"보다 "기계가 대조할 수 있게 써라"에 무게를 둔다.
 */
@Slf4j
public class ClaudeCodeProblemGenerator {

    private static final long MAX_TOKENS = 16_000L;

    /** 코드 문제 난이도의 정의. 검수의 "모르고 풀기"도 같은 정의로 급을 매긴다. */
    static final String DIFFICULTY_GUIDE = Prompts.load("code-problem/difficulty-guide.txt");

    static final String SYSTEM_PROMPT = Prompts.load("code-problem/system.txt").formatted(DIFFICULTY_GUIDE);

    private final String model;

    public ClaudeCodeProblemGenerator(String model) {
        this.model = model;
    }

    /**
     * @param topic      코드 문제 주제(예: "==과 equals, Integer 캐시")
     * @param plan       난이도별 문제 수. 순서대로 낸다
     * @param avoidCodes 같은 주제로 전에 낸 코드. 주제가 되풀이되므로 겹치지 않게 보여 준다
     */
    public List<CodeProblem.Item> generate(String topic, Map<Difficulty, Integer> plan, List<String> avoidCodes) {
        StructuredMessageCreateParams<CodeProblem.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 실행 결과를 한 줄씩 따라가야 하는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(CodeProblem.Batch.class)
                .addUserMessage(buildPrompt(topic, plan, avoidCodes))
                .build();
        try {
            return java.util.Optional.ofNullable(ClaudeCalls.create("코드 문제 생성", params).value())
                    .map(CodeProblem.Batch::problems)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 문제 목록이 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(코드 문제 생성): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(코드 문제 생성): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 생성에 실패했습니다.");
        }
    }

    /** 요청 글. 테스트가 다른 패키지에서 부른다. */
    public static String buildPrompt(String topic, Map<Difficulty, Integer> plan, List<String> avoidCodes) {
        int total = plan.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder("주제: ").append(topic).append('\n')
                .append("문제 수: ").append(total).append("개 (");
        plan.forEach((difficulty, count) -> sb.append(difficulty.getDisplayName()).append(' ').append(count).append("개, "));
        sb.setLength(sb.length() - 2);
        sb.append(")\n유형은 세 가지 이상 섞고, 이 주제에 맞지 않는 유형은 억지로 쓰지 않는다.\n");
        if (avoidCodes != null && !avoidCodes.isEmpty()) {
            sb.append("\n같은 주제로 전에 낸 코드다. 같은 함정을 다시 쓰지 말고 다른 지점을 물어라.\n");
            for (int i = 0; i < avoidCodes.size(); i++) {
                sb.append("\n[전에 낸 코드 ").append(i + 1).append("]\n").append(avoidCodes.get(i).strip()).append('\n');
            }
        }
        return sb.toString();
    }
}
