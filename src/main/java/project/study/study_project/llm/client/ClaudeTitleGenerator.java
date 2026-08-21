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
 * Claude API 기반 제목 생성기 — 제목 컬럼(V13)이 생기기 전에 만들어진 문제들을 메우는 데 쓴다.
 *
 * <h2>왜 별도 생성기인가</h2>
 *
 * <p>앞으로 만들어지는 문제는 {@link ClaudeProblemGenerator}가 지문을 쓰면서 제목까지 함께 낸다 —
 * 자기가 방금 만든 문제를 보고 이름을 짓는 것이 가장 정확하기 때문이다. 이 클래스는 그 방법을
 * 쓸 수 없는 <b>과거의 33건</b>을 위한 것이고, 앞으로도 손으로 등록하다 제목을 비워 둔 문제가
 * 생기면 여기가 메운다.
 *
 * <p><b>일회용 스크립트로 만들지 않은 이유</b>: 스크립트로 한 번 돌리고 지우면, 다음에 제목 없는
 * 문제가 생겼을 때 아무 장치도 남지 않는다. 실제로 그렇게 될 여지가 있다 — 관리자 등록 폼의
 * 제목 칸은 선택이고, 모델도 가끔 빈 값을 낸다(그래서 {@code ProblemItemRule}이 경고한다).
 * 관리 화면 버튼으로 남겨 두면 "제목 없음"이 목록에 보일 때마다 한 번 누르면 된다.
 *
 * <h2>사고 위험이 한 군데 있다 — 짝짓기</h2>
 *
 * <p>여러 문제의 제목을 한 번에 받으므로 <b>어느 제목이 어느 문제 것인지</b>가 어긋날 수 있다.
 * 배열 순서로 짝지으면 모델이 한 건을 빠뜨리는 순간 전부 한 칸씩 밀리고, 그 상태는 오류를
 * 내지 않는다 — 33건을 사람이 하나씩 읽어야만 드러난다. 그래서 스키마가 {@code problemId}를
 * 함께 받고({@link GeneratedTitle}), 짝짓기는 그 값으로 한다.
 *
 * <p>한 번에 몰아 부르는 것 자체는 의도다. 문제마다 한 번씩 부르면 33번 왕복이고, 게다가
 * 서로를 못 봐서 <b>비슷한 제목이 여러 개</b> 나온다. 목록에서 서로 구분되는 것이 제목의
 * 목적이므로 함께 보고 짓는 편이 낫다.
 */
@Slf4j
@Component
public class ClaudeTitleGenerator implements TitleGenerator {

    /**
     * 한 번에 제목을 붙일 문제 수 상한.
     *
     * <p>지문이 최대 250자 남짓이고 출력은 제목 한 줄씩이라 40건은 넉넉히 한 번에 들어간다.
     * 상한을 두는 이유는 토큰이 아니라 <b>출력 길이</b>다 — 건수가 많아지면 모델이 뒤쪽에서
     * 대충 짓거나 몇 건을 빠뜨리는데, 빠뜨린 것은 다음 실행이 다시 집어 온다(제목이 여전히
     * NULL이므로). 나눠 부르는 쪽이 조용히 나빠지는 것보다 낫다.
     */
    public static final int BATCH_SIZE = 40;

    /**
     * 시스템 프롬프트 — <b>{@code ClaudeProblemGenerator.SYSTEM_PROMPT}의 [제목] 절과 같은 것을
     * 요구해야 한다.</b> 갈라지면 백필한 33건과 앞으로 생성될 문제의 제목이 서로 다른 규칙으로
     * 붙어, 목록에서 두 세대가 눈에 띄게 다르게 읽힌다.
     *
     * <p>그렇다고 저쪽 프롬프트를 통째로 가져올 수는 없다 — 난이도 정의·오답 조건·해설 규칙은
     * 제목을 짓는 일과 아무 상관이 없고, 그것들이 실리면 모델이 <b>문제를 새로 만들려</b> 든다.
     * 규칙 세 줄만 옮겨 오고, 그 셋이 어긋나지 않는지는 테스트가 두 프롬프트를 대조한다
     * ({@code ClaudeTitleGeneratorPromptTest}).
     *
     * <p><b>"정답을 제목에 쓰지 마라"가 여기서 더 위험하다.</b> 저쪽은 문제를 만들면서 이름을
     * 짓지만 여기는 <b>완성된 문제와 정답을 다 보고</b> 짓는다. 정답이 눈앞에 있으면 그걸 요약하는
     * 것이 가장 자연스러운 선택이라, 규칙이 없으면 거의 확실히 그렇게 된다.
     * 그래서 아래 프롬프트는 애초에 <b>보기와 해설을 주지 않는다</b> — 규칙과 구조 양쪽으로 막는다.
     */
    static final String SYSTEM_PROMPT = """
            너는 CS 학습 서비스의 문제 목록에 붙일 제목을 짓는다.
            학습자는 이 목록에서 <다음에 풀 문제 하나>를 고른다. 제목은 그 판단에 쓰는 유일한 단서다.

            [제목 규칙]
            - 이 문제가 무엇에 관한 것인지를 40자 이내의 <명사구>로 적는다.
              물음이 아니라 이름이다.
            - 지문의 첫 문장을 옮겨 오지 마라. 상황 서술로 시작하는 지문이 많은데,
              상황은 그 문제에만 있는 소품이지 주제가 아니다.
            - 정답을 제목에 쓰지 마라. 목록만 읽고도 답이 보이면 그 문제는 죽는다.
              무엇을 다루는 문제인지까지만 말하고 이유·결론은 말하지 않는다.

            (X) 이 상황의 원인으로 가장 적절한 것은?
                → 물음이라 목록에 열 줄 늘어서면 서로 구분되지 않는다.
            (X) 주문 API의 처리 건수가 1,000건 중 970건에서 멈춘 상황
                → 지문 요약이다. 상황은 주제가 아니다.
            (O) static 필드에 여러 쓰레드가 동시에 쓸 때의 값 유실

            [함께 지켜야 할 것]
            - 여러 문제를 한 번에 받는다. 서로 구분되는 제목을 지어라.
              목록에서 나란히 놓였을 때 어느 것이 어느 것인지 알 수 있어야 한다.
            - 받은 문제마다 problemId를 <그대로> 돌려준다. 이 값으로 짝을 짓는다.
              번호를 바꾸면 엉뚱한 문제에 제목이 붙고, 그건 아무 오류 없이 지나간다.
            - 문제를 고치거나 새로 만들지 마라. 너는 이름만 붙인다.
            """;

    private final String model;

    public ClaudeTitleGenerator(@Value("${llm.generation.model:claude-opus-5}") String model) {
        this.model = model;
    }

    @Override
    public List<GeneratedTitle> generateTitles(List<UntitledProblem> problems) {
        if (problems.isEmpty()) {
            return List.of(); // 부를 이유가 없다 — 빈 요청에 요금을 낼 필요는 더욱 없다
        }

        // 사고(thinking)를 켜지 않았다. 문제 출제는 "그럴듯한 오답 만들기"가 어려워 켰지만
        // (ClaudeProblemGenerator 주석) 이름 짓기는 지문을 읽으면 곧바로 나오는 일이다.
        // 사고 토큰은 전부 출력 요금이라, 값을 못 하는 곳에 켜 두면 비용만 는다.
        StructuredMessageCreateParams<GeneratedTitle.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedTitle.Batch.class)
                .addUserMessage(buildPrompt(problems))
                .build();

        try {
            return AnthropicClientHolder.get().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text().titles())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 제목 목록이 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(제목 생성): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(제목 생성): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 제목 생성에 실패했습니다.");
        }
    }

    /**
     * 사용자 메시지 — id와 지문만 나열한다.
     *
     * <p><b>보기와 해설을 일부러 넣지 않는다.</b> 정답이 눈앞에 있으면 모델은 그것을 요약하는
     * 쪽으로 간다(클래스 주석). 규칙으로도 막지만, 애초에 보여 주지 않는 편이 확실하다.
     * 이름을 짓는 데 지문이면 충분하기도 하다.
     */
    String buildPrompt(List<UntitledProblem> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 문제 ").append(problems.size()).append("개에 목록 제목을 지어라.\n")
                .append("제목은 ").append(ProblemItemRule.TITLE_MAX).append("자 이내다.\n\n");
        for (UntitledProblem p : problems) {
            sb.append("[problemId ").append(p.id()).append("]\n")
                    .append(p.question()).append("\n\n");
        }
        return sb.toString();
    }
}
