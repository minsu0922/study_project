package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.support.ProblemItemRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 오답 설명 채우기 프롬프트 테스트 — Claude를 부르지 않는다.
 *
 * <p>{@code ClaudeTitleGeneratorPromptTest}와 같은 자리를 지킨다: <b>규칙이 조용히 사라지지 않는 것</b>,
 * 그리고 <b>문제 생성 프롬프트의 [오답 설명] 절과 갈라지지 않는 것</b>.
 *
 * <p>뒤엣것이 핵심이다. 갈라지면 채운 26건과 앞으로 생성될 문제의 설명이 서로 다른 규칙으로 붙어,
 * 같은 화면에 두 세대가 눈에 띄게 다르게 읽힌다. 이 저장소는 "같은 정보가 두 곳에 적혀 있다"로
 * 이미 여러 번 사고를 냈다 — 프롬프트를 통째로 공유할 수는 없으니 <b>같아야 하는 줄만</b> 대조한다.
 */
class ClaudeRationaleGeneratorPromptTest {

    private final ClaudeRationaleGenerator generator = new ClaudeRationaleGenerator("claude-opus-5");

    @Test
    @DisplayName("오답 설명 규칙이 문제 생성 프롬프트와 같다 — 갈라지면 화면에 두 세대가 다르게 읽힌다")
    void keepsTheSameRationaleRulesAsTheProblemPrompt() {
        List<String> shared = List.of(
                "<어떤 오해에서 비롯되는지>를 밝혀라",
                "<다른 보기를 가리키지 마라.>",
                "보기 순서를 섞으므로 번호는 반드시 어긋난다",
                "그 보기 하나만 놓고 읽어도 뜻이 통해야 한다");

        assertThat(shared).allSatisfy(rule -> {
            assertThat(ClaudeRationaleGenerator.SYSTEM_PROMPT)
                    .as("채우기 프롬프트에서 '%s'가 빠졌다", rule).contains(rule);
            assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                    .as("생성 프롬프트에서 '%s'가 빠졌다 — 두 쪽이 갈라졌다", rule).contains(rule);
        });
    }

    /**
     * <b>제목 백필과 정반대인 결정이라 여기서 못 박는다.</b> 저쪽은 정답이 새어 나가는 것을 막으려고
     * 보기와 해설을 감췄다. 여기서는 반대로 다 준다 — 오답이 왜 틀렸는지는 정답이 왜 맞는지를
     * 알아야 쓸 수 있고, 감추면 모델이 정답을 추측한다. 그 추측이 어긋나면 설명 전체가 엉뚱해지는데
     * 그 글은 <b>그럴듯하게</b> 읽혀서 검수에서 걸러지지 않는다.
     *
     * <p>나중에 "제목 쪽처럼 해설을 빼는 게 안전하지 않나" 싶어질 때, 그러면 무엇을 잃는지가
     * 여기 적혀 있다.
     */
    @Test
    @DisplayName("프롬프트에 정답과 해설을 함께 싣는다 — 감추면 모델이 정답을 추측하고, 그 설명은 그럴듯하다")
    void sendsTheCorrectAnswerAndExplanation() {
        String prompt = generator.buildPrompt(List.of(new RationaleGenerator.ProblemWithoutRationale(
                7L, "세션 쿠키에 SameSite를 무엇으로 둘지 고르는 상황이다.",
                "Lax는 최상위 내비게이션의 GET에만 쿠키를 실어 보낸다.",
                "Lax로 두고 상태 변경은 POST로 옮긴다",
                List.of(new RationaleGenerator.ProblemWithoutRationale.WrongChoice(11L, "None으로 두고 Secure를 켠다"),
                        new RationaleGenerator.ProblemWithoutRationale.WrongChoice(12L, "Strict로 두면 로그인이 끊긴다")))));

        assertThat(prompt)
                .as("짝짓기는 이 번호로 한다 — 빠지면 순서에 기대게 되고, 그건 조용히 밀린다")
                .contains("choiceId 11").contains("choiceId 12")
                .as("정답을 알아야 '왜 이것이 아닌지'를 쓸 수 있다")
                .contains("Lax로 두고 상태 변경은 POST로 옮긴다")
                .contains("Lax는 최상위 내비게이션의 GET에만 쿠키를 실어 보낸다.")
                .as("프롬프트가 요구하는 길이와 검증기가 재는 길이는 같아야 한다")
                .contains("%d자 이상".formatted(ProblemItemRule.RATIONALE_MIN));
    }

    /**
     * <b>정답 보기를 오답 목록에 넣지 않는 것이 구조적 방어다.</b> 섞어 놓고 알아서 가려내라고 하면
     * 가끔 정답에도 설명을 단다. 그러면 학습자가 <b>맞혔을 때</b> "왜 틀렸는지"가 뜬다.
     *
     * <p>보기 번호(seq)를 주지 않는 것도 같은 종류의 방어다 — 번호가 보이면 "①번과 달리…"가 나온다.
     */
    @Test
    @DisplayName("오답 목록에 정답 보기가 없고 보기 번호도 없다 — 보여 주지 않는 것이 가장 확실한 방어다")
    void listsOnlyWrongChoicesAndNeverTheirNumbers() {
        String prompt = generator.buildPrompt(List.of(new RationaleGenerator.ProblemWithoutRationale(
                7L, "지문", "해설", "정답 보기 원문",
                List.of(new RationaleGenerator.ProblemWithoutRationale.WrongChoice(11L, "오답 보기 원문")))));

        assertThat(prompt.substring(prompt.indexOf("[설명이 필요한 오답]")))
                .as("정답 보기가 오답 목록에 섞이면 정답에도 설명이 붙는다")
                .doesNotContain("정답 보기 원문");
        assertThat(ClaudeRationaleGenerator.SYSTEM_PROMPT)
                .contains("정답 보기는 목록에 없다");
    }

    /**
     * 짝짓기를 보기 id로 하기로 한 결정은 <b>모델이 그 번호를 돌려줘야</b> 성립한다.
     * 스키마에 필드만 만들어 두고 프롬프트가 침묵하면 모델이 아무 번호나 채우고,
     * 서비스는 그 값을 진짜 id로 믿는다.
     */
    @Test
    @DisplayName("번호를 그대로 돌려주라고 말한다 — 스키마에 필드만 있으면 아무 값이나 채운다")
    void asksTheModelToEchoTheChoiceId() {
        assertThat(ClaudeRationaleGenerator.SYSTEM_PROMPT)
                .contains("choiceId를 <그대로> 돌려준다")
                .as("왜 그런지를 줘야 지켜진다 — 이 저장소의 오랜 방식")
                .contains("아무 오류 없이 지나간다")
                .as("완성된 문제를 통째로 보여 주면 다듬고 싶어진다 — 스키마로 막고 말로도 막는다")
                .contains("고치거나 새로 만들지 마라");
    }

    /**
     * 스키마 검증은 네트워크도 API 키도 쓰지 않는다 — {@code outputConfig(Class)}를 부르는 순간
     * SDK가 클라이언트 쪽에서 만들고 검증한다. 문서 생성 첫 실행이 이 지점에서 죽은 적이 있어서
     * ({@code StructuredOutputSchemaTest}) 새 스키마를 만들 때마다 함께 잰다.
     */
    @Test
    @DisplayName("오답 설명 스키마가 SDK 검증을 통과한다 — 요금 0원으로 잡을 수 있는 사고다")
    void rationaleSchemaIsValid() {
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(1_000L)
                .outputConfig(GeneratedRationale.Batch.class)
                .addUserMessage("스키마 검증용")
                .build())
                .doesNotThrowAnyException();
    }
}
