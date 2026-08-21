package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.support.ProblemItemRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 제목 백필 프롬프트 테스트 — Claude를 부르지 않는다.
 *
 * <p>여기서 지키는 것은 두 가지다: <b>규칙이 조용히 사라지지 않는 것</b>, 그리고
 * <b>문제 생성 프롬프트의 [제목] 절과 갈라지지 않는 것</b>. 뒤엣것이 이 클래스의 핵심이다 —
 * 두 프롬프트가 어긋나면 백필한 33건과 앞으로 생성될 문제의 제목이 서로 다른 규칙으로 붙어,
 * 목록에서 두 세대가 눈에 띄게 다르게 읽힌다.
 *
 * <p>이 저장소는 "같은 정보가 두 곳에 적혀 있다"로 이미 여러 번 사고를 냈다(절 이름이 두 파일에
 * 흩어져 고급 재료가 마를 뻔한 건, 손으로 만든 지문 스냅샷이 없는 문제를 계속 피하던 건).
 * 프롬프트를 통째로 공유할 수는 없으니 — 난이도·오답·해설 규칙은 이름 짓기와 무관하고,
 * 실리면 모델이 문제를 새로 만들려 든다 — <b>같아야 하는 세 줄만</b> 대조한다.
 */
class ClaudeTitleGeneratorPromptTest {

    private final ClaudeTitleGenerator generator = new ClaudeTitleGenerator("claude-opus-5");

    @Test
    @DisplayName("제목 규칙 세 줄이 문제 생성 프롬프트와 같다 — 갈라지면 목록에 두 세대가 다르게 뜬다")
    void keepsTheSameTitleRulesAsTheProblemPrompt() {
        List<String> shared = List.of(
                "물음이 아니라 이름이다",
                "지문의 첫 문장을 옮겨 오지 마라",
                "정답을 제목에 쓰지 마라");

        assertThat(shared).allSatisfy(rule -> {
            assertThat(ClaudeTitleGenerator.SYSTEM_PROMPT)
                    .as("백필 프롬프트에서 '%s'가 빠졌다", rule).contains(rule);
            assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                    .as("생성 프롬프트에서 '%s'가 빠졌다 — 두 쪽이 갈라졌다", rule).contains(rule);
        });
    }

    /**
     * <b>여기가 이 경로에서 가장 위험한 자리다.</b> 문제 생성은 지문을 쓰면서 이름을 짓지만,
     * 백필은 <b>완성된 문제를 보고</b> 짓는다. 보기와 해설까지 주면 정답이 눈앞에 있는 셈이라
     * 모델은 그것을 요약하는 쪽으로 간다 — 규칙으로만 막는 것은 얇다.
     *
     * <p>그래서 구조로도 막는다: 프롬프트에 지문만 싣는다. 이 테스트는 그 결정이 살아 있는지
     * 본다. 나중에 "제목 품질을 올리려면 해설도 주는 게 낫지 않나" 싶어질 때, 그러면 무엇을
     * 잃는지가 여기 적혀 있다.
     */
    @Test
    @DisplayName("프롬프트에 지문만 싣는다 — 정답이 눈앞에 있으면 모델은 그것을 요약한다")
    void sendsOnlyTheQuestionBody() {
        String prompt = generator.buildPrompt(List.of(
                new TitleGenerator.UntitledProblem(7L, "주문 API에서 처리 건수가 970에서 멈춘다."),
                new TitleGenerator.UntitledProblem(8L, "TIME_WAIT 소켓이 수만 개 쌓인다.")));

        assertThat(prompt)
                .as("짝짓기는 이 번호로 한다 — 빠지면 순서에 기대게 되고, 그건 조용히 밀린다")
                .contains("[problemId 7]").contains("[problemId 8]")
                .contains("주문 API에서 처리 건수가 970에서 멈춘다.")
                .as("프롬프트가 요구하는 길이와 검증기가 재는 길이는 같아야 한다")
                .contains("%d자 이내".formatted(ProblemItemRule.TITLE_MAX));
    }

    /**
     * 짝짓기를 id로 하기로 한 결정은 <b>모델이 그 번호를 돌려줘야</b> 성립한다.
     * 스키마에 필드만 만들어 두고 프롬프트가 침묵하면 모델이 아무 번호나 채우고,
     * 서비스는 그 값을 진짜 id로 믿는다 — 검사기가 있는데 아무것도 못 잡는 상태가 된다.
     */
    @Test
    @DisplayName("번호를 그대로 돌려주라고 말한다 — 스키마에 필드만 있으면 아무 값이나 채운다")
    void asksTheModelToEchoTheProblemId() {
        assertThat(ClaudeTitleGenerator.SYSTEM_PROMPT)
                .contains("problemId를 <그대로> 돌려준다")
                .as("왜 그런지를 줘야 지켜진다 — 이 저장소의 오랜 방식")
                .contains("아무 오류 없이 지나간다")
                .as("이름만 붙이라고 못 박지 않으면 멀쩡한 문제를 고쳐 놓는다")
                .contains("문제를 고치거나 새로 만들지 마라");
    }

    /**
     * 스키마 검증은 네트워크도 API 키도 쓰지 않는다 — {@code outputConfig(Class)}를 부르는
     * 순간 SDK가 클라이언트 쪽에서 만들고 검증한다. 문서 생성 첫 실행이 이 지점에서 죽은 적이
     * 있어서({@code StructuredOutputSchemaTest}) 새 스키마를 만들 때마다 함께 잰다.
     */
    @Test
    @DisplayName("제목 스키마가 SDK 검증을 통과한다 — 요금 0원으로 잡을 수 있는 사고다")
    void titleSchemaIsValid() {
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(1_000L)
                .outputConfig(GeneratedTitle.Batch.class)
                .addUserMessage("스키마 검증용")
                .build())
                .doesNotThrowAnyException();
    }
}
