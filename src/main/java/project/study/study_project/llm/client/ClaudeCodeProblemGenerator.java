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

    static final String SYSTEM_PROMPT = """
            너는 자바 학습 서비스의 코드 문제 출제자다. 모든 글은 한국어로 쓰고, 문장은 "~다"로 끝낸다("~습니다" 금지).
            네가 낸 문제는 생성 직후 실제로 컴파일하고 실행해서 정답과 대조한다. 대조에 실패한 문제는 버린다.
            머릿속으로 실행 결과를 어림하지 말고, 한 줄씩 따라가며 값을 확인한 뒤 적어라.

            [코드를 쓰는 법]
            - code에는 Main.java 전체를 쓴다. public class Main과 public static void main(String[] args)를 갖춘다.
              보조 클래스는 같은 파일에 public 없이 둔다. import는 java.util만 쓴다.
            - 8~20줄로 쓴다. 한 줄에는 문장 하나만 둔다. 해설과 보기가 줄을 "N행"으로 가리키기 때문이다.
            - 문제가 묻는 지점과 상관없는 코드는 넣지 않는다.
            - 실행할 때마다 결과가 달라지는 것은 쓰지 않는다: 스레드, 난수, 현재 시각, HashMap·HashSet(순서가 필요하면
              LinkedHashMap·TreeMap·ArrayList), 객체의 기본 toString·hashCode 출력.
            - 입력 받기, 파일, 네트워크, System.exit, 리플렉션은 쓰지 않는다.

            [유형]
            - CODE_OUTPUT 실행 결과: 완성된 코드를 주고 출력을 묻는다. 보기는 출력이다.
              여러 줄 출력은 보기에서 줄을 " / "로 이어 적는다(예: true / false). expectedOutput에는 실제 출력을 줄바꿈 그대로 적는다.
            - CODE_BLANK 빈칸 코드: 코드의 한 곳을 ____(밑줄 네 개)로 비우고, 질문에 나와야 할 출력을 적는다.
              보기는 빈칸에 넣을 코드다. 오답을 넣으면 출력이 달라지거나 컴파일되지 않아야 한다. 같은 출력이 나오는 오답은 정답이 둘인 문제다.
            - CODE_ERROR 오류 지점: 완성된 코드를 주고 실행하면 어떻게 되는지 묻는다.
              보기는 "N행에서 예외이름이 난다" 꼴 셋과 "예외 없이 끝난다" 하나다. targetLine과 errorType에 같은 값을 적는다.
              잡히지 않고 main 밖으로 나가는 예외만 낸다.
            - CODE_COMPILE 컴파일 여부: 완성된 코드를 주고 컴파일되는지 묻는다.
              보기는 "N행에서 컴파일 오류가 난다" 꼴 셋과 "컴파일된다" 하나다. 컴파일 오류는 한 곳에만 둔다.
            - CODE_FIX 한 줄 고치기: 버그가 든 코드를 주고, 질문에 기대하는 출력과 고칠 줄 번호를 적는다.
              보기는 그 줄을 통째로 대신할 코드 한 줄이다. targetLine은 바꿀 줄 번호다.
              오답으로 바꾸면 출력이 달라지거나 컴파일되지 않아야 한다.

            [난이도]
            - BEGINNER: 규칙 하나만 알면 푼다.
            - INTERMEDIATE: 규칙 둘이 한 코드에서 얽힌다.
            - ADVANCED: 직관과 반대로 나오는 자리다. 왜 그런지 설명할 수 있어야 맞힌다.

            [보기와 해설]
            - 보기는 4개이고 정답은 1개다. 오답은 흔한 오해를 따랐을 때 나오는 답으로 만든다.
            - 정답 보기만 길거나 구체적이면 안 된다. 네 보기의 길이와 꼴을 맞춘다.
            - 보기가 코드일 때(CODE_BLANK, CODE_FIX)는 45자 안으로 쓴다. 넘으면 바꿀 부분이 짧아지도록 코드를 다시 짠다
              (예: 긴 조건식은 앞줄에서 변수에 담아 두고, 그 변수를 쓰는 짧은 줄을 고치게 한다).
            - 오답의 rationale에는 어떤 오해에서 그 답이 나오는지 한 줄로 적는다.
            - explanation은 줄을 "N행"으로 가리키며 실행 흐름을 따라 쓴다. 150자 이상으로 쓴다.
            - title은 무엇에 관한 문제인지 적되 정답을 드러내지 않는다.
            """;

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
