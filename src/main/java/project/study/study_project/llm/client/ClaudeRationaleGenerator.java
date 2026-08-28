package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.support.ProblemItemRule;

import java.util.List;

/**
 * Claude API 기반 오답 설명 생성기 — 오답 설명 칸(V15)이 생기기 전에 승인된 문제들을 채우는 데 쓴다.
 *
 * <h2>왜 별도 생성기인가</h2>
 *
 * <p>앞으로 만들어지는 문제는 {@link ClaudeProblemGenerator}가 오답을 지으면서 "왜 틀렸는지"까지
 * 함께 낸다 — 자기가 방금 만든 오답이라 어떤 오해를 노렸는지 가장 잘 안다. 이 클래스는 그 방법을
 * 쓸 수 없는 <b>과거의 26건</b>을 위한 것이다.
 *
 * <p>{@link ClaudeTitleGenerator}와 판박이 구조인데, 그건 우연이 아니다. 스키마에 칸을 늘리면
 * 그 전에 저장된 행은 비어 있고, 칸을 추가한다고 옛 행이 채워지지는 않는다. 그래서 이 일은
 * 앞으로도 또 생긴다. 두 클래스가 같은 모양이면 세 번째를 만들 때 고민할 것이 없다.
 *
 * <h2>제목 짓기와 정반대인 지점 하나 — 해설을 보여 준다</h2>
 *
 * <p>{@link ClaudeTitleGenerator}는 보기와 해설을 <b>일부러 감춘다</b>. 정답이 눈앞에 있으면
 * 그것을 요약하는 것이 가장 자연스러운 선택이라, 제목에 답이 새어 나가기 때문이다.
 *
 * <p>여기서는 반대로 <b>정답과 해설을 다 준다</b>. 오답이 왜 틀렸는지는 정답이 왜 맞는지를
 * 알아야만 쓸 수 있다. 감추면 모델이 정답을 자기 나름대로 추측하고, 그 추측이 어긋나면
 * 오답 설명 전체가 엉뚱한 방향으로 나간다 — 게다가 그 설명은 <b>그럴듯하게</b> 읽혀서
 * 검수에서 걸러지지 않는다. 여기서 새어 나갈 곳도 없다: 설명은 <b>틀린 뒤에</b> 보이는 글이라,
 * 정답을 알고 쓴 티가 나는 편이 오히려 맞다.
 *
 * <h2>사고 위험은 짝짓기 한 군데</h2>
 *
 * <p>여러 문제의 여러 보기를 한 번에 받으므로 어느 설명이 어느 보기 것인지가 어긋날 수 있다.
 * 그래서 스키마가 {@code choiceId}를 함께 받고({@link GeneratedRationale}), 짝짓기는 그 값으로 한다.
 */
@Slf4j
@Component
public class ClaudeRationaleGenerator implements RationaleGenerator {

    /**
     * 한 번에 설명을 붙일 <b>문제</b> 수 상한 — 보기 수가 아니다.
     *
     * <p>제목 백필(40건)보다 훨씬 작다. 제목은 입력이 지문 한 토막이고 출력이 한 줄이지만,
     * 여기는 문제당 지문 + 보기 4개 + 해설(평균 500자)이 들어가고 오답 3개분 설명이 나온다.
     * 문제 하나가 대략 10배 무겁다.
     *
     * <p>10건으로 정한 진짜 이유는 토큰이 아니라 <b>손해를 되돌릴 수 있는 크기</b>다.
     * 이 작업은 사람 확인 없이 DB에 값을 넣는다. 프롬프트가 잘못돼 이상한 설명이 나오고 있다면
     * 26건을 다 망친 뒤에 아는 것보다 10건에서 멈추고 눈으로 보는 쪽이 낫다.
     * 26건이면 세 번 누르면 되고, 첫 번째 결과를 보고 그만둘 수도 있다.
     */
    public static final int BATCH_SIZE = 10;

    /**
     * 시스템 프롬프트 — <b>{@code ClaudeProblemGenerator.SYSTEM_PROMPT}의 [오답 설명] 절과 같은 것을
     * 요구해야 한다.</b> 갈라지면 채운 26건과 앞으로 생성될 문제의 설명이 서로 다른 규칙으로 붙어,
     * 같은 화면에 두 세대가 눈에 띄게 다르게 읽힌다.
     *
     * <p>저쪽 프롬프트를 통째로 가져오지는 않는다 — 난이도 정의·상황 지문 쓰는 법·중복 회피는
     * 설명을 다는 일과 상관이 없고, 그것들이 실리면 모델이 <b>문제를 새로 만들려</b> 든다
     * ({@link ClaudeTitleGenerator}가 겪은 것과 같은 위험이다).
     * 옮겨 오는 것은 오답 설명 규칙뿐이고, 그 규칙이 어긋나지 않는지는 테스트가 두 프롬프트를
     * 대조한다({@code ClaudeRationaleGeneratorPromptTest}).
     *
     * <p><b>"문제를 고치지 마라"를 여기서 한 번 더 못 박는다.</b> 모델에게 완성된 문제를 통째로
     * 보여 주면서 "설명만 달아라"라고 하는 것은 자연스러운 요구가 아니다. 지문이 어색하면 다듬고
     * 싶고, 해설이 길면 줄이고 싶어진다. 스키마에 담을 자리가 없어 <b>구조적으로</b> 막혀 있지만,
     * 규칙으로도 말해 두면 모델이 엉뚱한 곳에 힘을 쓰지 않는다.
     */
    static final String SYSTEM_PROMPT = """
            너는 CS 학습 서비스에서, 이미 만들어진 객관식 문제의 <오답 보기>에
            "왜 이것이 틀렸는지"를 한 줄씩 다는 일을 한다.

            학습자는 문제를 틀린 <직후에> 이 글을 읽는다. 자기가 고른 보기 아래에 붙어 나온다.
            그래서 이 글의 목적은 채점이 아니라 <오해를 짚어 주는 것>이다.

            [오답 설명 규칙]
            - 그 오답이 <어떤 오해에서 비롯되는지>를 밝혀라.
              "틀렸다"가 아니라 "무엇과 헷갈린 것이다"라고 써라.
            - 30자 이상으로 적는다. 한 문장으로 끝나도 되지만 "아니다" 한마디는 안 된다.
              왜 그렇게 생각하기 쉬운지까지 짚어야 다음에 안 틀린다.
            - <다른 보기를 가리키지 마라.> 특히 번호로 가리키는 것은 금지다.
              학습자에게 내보낼 때 보기 순서를 섞으므로 번호는 반드시 어긋난다.
              이것은 권고가 아니다 — 번호로 가리킨 설명은 저장 단계에서 통째로 버려진다.
              (X) ②번과 달리 이쪽은 읽기 차단을 말한다
              (X) 두 번째 보기는 MVCC를 락 기반과 혼동한 것이다
              (O) MVCC를 락 기반과 혼동한 설명이다. MVCC는 읽기에 공유 락을 걸지 않는다.
            - 그 보기 하나만 놓고 읽어도 뜻이 통해야 한다.
              화면에서 보기 바로 아래 한 줄씩 떨어져 나오기 때문이다.
            - 해설에 이미 있는 말을 그대로 옮기지 마라.
              해설은 <왜 정답인지>를 맡는다. 너는 <왜 이것이 아닌지>를 맡는다.

            [건드리지 말 것]
            - 지문·보기·정답·해설을 고치거나 새로 만들지 마라. 너는 설명만 단다.
              어색해 보여도 그대로 둔다. 그 판단은 사람이 한다.
            - 받은 보기마다 choiceId를 <그대로> 돌려준다. 이 값으로 짝을 짓는다.
              번호를 바꾸면 엉뚱한 보기에 설명이 붙고, 그건 아무 오류 없이 지나간다.
            - 오답 보기로 준 것만 설명한다. 정답 보기는 목록에 없다 — 만들어 넣지 마라.
            """;

    private final String model;

    public ClaudeRationaleGenerator(@Value("${llm.generation.model:claude-opus-5}") String model) {
        this.model = model;
    }

    @Override
    public List<GeneratedRationale> generateRationales(List<ProblemWithoutRationale> problems) {
        if (problems.isEmpty()) {
            return List.of(); // 부를 이유가 없다 — 빈 요청에 요금을 낼 필요는 더욱 없다
        }

        // 사고(thinking)를 켜지 않았다. 문제 출제는 "그럴듯한 오답을 지어내는" 일이라 켰지만
        // (ClaudeProblemGenerator 주석), 여기는 <이미 있는 오답>이 무엇과 헷갈린 것인지를
        // 말하는 일이다. 정답과 해설을 함께 주므로 근거가 이미 눈앞에 있다.
        // 사고 토큰은 전부 출력 요금이라, 값을 못 하는 곳에 켜 두면 비용만 는다.
        StructuredMessageCreateParams<GeneratedRationale.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedRationale.Batch.class)
                .addUserMessage(buildPrompt(problems))
                .build();

        try {
            return AnthropicClientHolder.get().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text().rationales())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 오답 설명 목록이 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(오답 설명 생성): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(오답 설명 생성): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 오답 설명 생성에 실패했습니다.");
        }
    }

    /**
     * 사용자 메시지 — 문제마다 지문·정답·해설을 보여 주고, 설명이 필요한 오답만 id와 함께 나열한다.
     *
     * <p><b>정답 보기를 "정답"이라고 이름 붙여 따로 보여 준다.</b> 오답 목록에 섞어 놓고
     * 알아서 가려내라고 하면 가끔 정답에도 설명을 단다. 그러면 정답 보기에 "왜 틀렸는지"가
     * 붙어 나가는데, 이건 학습자가 <b>맞혔을 때</b> 보게 되는 화면이라 특히 나쁘다.
     * 애초에 목록에 넣지 않으면 그 사고가 나지 않는다.
     *
     * <p><b>보기 번호(seq)는 주지 않는다.</b> 번호가 보이면 "①번과 달리…" 같은 문장이 나오는데,
     * 학습자 화면에서는 순서가 다시 섞여 반드시 어긋난다. 규칙으로도 막지만, 애초에 번호를
     * 보여 주지 않는 편이 확실하다 — 규칙과 구조 양쪽으로 막는 것은 제목 생성기와 같은 방식이다.
     */
    String buildPrompt(List<ProblemWithoutRationale> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 문제 ").append(problems.size()).append("개의 오답 보기에 설명을 달아라.\n")
                .append("설명은 보기 하나당 ").append(ProblemItemRule.RATIONALE_MIN).append("자 이상이다.\n\n");
        for (ProblemWithoutRationale p : problems) {
            sb.append("═══ problemId ").append(p.problemId()).append(" ═══\n")
                    .append("[지문]\n").append(p.question()).append("\n")
                    .append("[정답 보기] ").append(p.correctChoiceText()).append("\n")
                    .append("[해설] ").append(p.explanation()).append("\n")
                    .append("[설명이 필요한 오답]\n");
            for (ProblemWithoutRationale.WrongChoice c : p.wrongChoices()) {
                sb.append("  - choiceId ").append(c.choiceId()).append(": ").append(c.text()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
