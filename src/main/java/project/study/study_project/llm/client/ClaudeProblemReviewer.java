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
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyAnswer;
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyBatch;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.Judgement;
import project.study.study_project.llm.client.ProblemReview.JudgementBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claude API 기반 객관식 문제 검수기 — docs/22 §3.2~3.4.
 *
 * <p>정답 대조는 코드가 한다. AI에게 "표시된 정답이 맞나"를 물으면 표시를 따라가는 쪽으로 기운다.
 * 그래서 정답 표시와 해설을 가린 채 풀게 하고, 고른 번호를 코드가 표시와 견준다.
 */
@Slf4j
public class ClaudeProblemReviewer implements ProblemReviewer {

    private static final long MAX_TOKENS = 32_000L;

    static final String CHOICE_ONLY_PROMPT = """
            너는 객관식 문제의 보기에 정답 단서가 새어 있는지 보는 검수자다.
            질문은 일부러 가렸다. 보기 네 개만 보고, 글의 모양만으로 정답이 드러나는지 판단한다.

            [단서의 예]
            - 한 보기만 유독 길거나 구체적이다.
            - 한 보기만 조건이나 단서를 덧붙였다.
            - 한 보기만 표현의 결이 다르다(나머지는 짧은 단정, 하나만 설명조 등).
            - 네 보기의 공통 부분을 한 보기만 가장 많이 담았다.

            [하지 말 것]
            - 지식으로 참·거짓을 가려 고르지 마라. 보기 내용이 사실인지는 판단 대상이 아니다.
            - 단서가 없으면 cue는 빈 문자열, choiceNo는 0이다. 확신 없는 단서는 적지 마라.
              정답을 맞히는 것이 목표가 아니다. 모양이 답을 알려 주는지만 본다.
            """;

    static final String JUDGE_PROMPT = """
            너는 CS 학습 서비스의 객관식 문제 검수자다.
            근거 문서와 문제들을 받는다. 정답 표시와 해설은 일부러 가렸다.
            이 문제들은 학습자가 정답을 외우는 재료가 된다. 틀린 정답 하나가 틀린 지식을 만든다.

            문제마다 다음을 한다.
            1. 직접 풀어 answerReason에 이유를 쓰고, chosenNo에 고른 번호를 적는다.
            2. 고른 답 말고도 이 문제의 조건에서 정답으로 방어할 수 있는 보기가 있으면
               otherCorrectNo와 그 이유를 적는다. 없으면 0과 빈 문자열이다.
               학습자가 이의를 제기하면 받아들여야 할 수준일 때만 적는다.
            3. supportQuote에는 정답을 뒷받침하는 문서 문장 하나를 글자 그대로 복사한다.
               문서에 근거가 없으면 빈 문자열이다. 지어내면 코드가 원문과 대조해 걸러 낸다.
            4. revealReason: 질문의 표현을 한 보기만 그대로 되받아, 뜻을 몰라도 문장 비교만으로
               답이 좁혀지면 그 이유를 쓴다. 아니면 빈 문자열이다.
               네 보기가 모두 질문의 주제어를 담는 것은 정상이다.
            5. 난이도는 판정하지 말고 아래 세 가지를 관찰만 한다. 급은 코드가 정한다.
               - asksDefinition: 질문이 용어의 뜻, 또는 뜻에 맞는 용어만 묻는가.
               - conditionQuote: 지문에 답을 가르는 조건이 있으면 그 문장을 글자 그대로 옮긴다.
                 조건은 장면(무엇을 하려다 무엇이 어긋났나)이나 명세(비율, 규모, 유실 허용, 요건)다.
                 질문의 주제어나 "옳은 것은?" 같은 물음은 조건이 아니다.
               - elsewhereCount: 오답마다 "이 오답이 정답이 되는 다른 조건"을 대 본다.
                 오해나 틀린 정의라서 어떤 조건에서도 정답이 될 수 없으면 세지 않는다.
               길이나 문장의 어려움으로 정하지 마라.

            [참고: 난이도 정의]
            %s
            """;

    private final String model;
    private long inputTokens;
    private long outputTokens;

    public ClaudeProblemReviewer(String model) {
        this.model = model;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    @Override
    public List<Finding> review(List<GeneratedProblemItem> problems, Difficulty labeled, SourceDocument source) {
        List<Shown> shown = showable(problems);
        if (shown.isEmpty()) {
            return List.of();
        }

        ChoiceOnlyBatch choiceOnly = call(CHOICE_ONLY_PROMPT, buildChoiceOnlyPrompt(shown), ChoiceOnlyBatch.class, false);
        JudgementBatch judged = call(judgePrompt(), buildJudgePrompt(shown, source), JudgementBatch.class, true);
        return compare(shown, choiceOnly, judged, labeled, source.contentMd());
    }

    /* ── 보여 줄 형태 ── */

    /**
     * 검수에 보여 줄 문제 하나. order[n]은 화면 n+1번 보기가 원래 몇 번째 보기인지다.
     * 생성기가 정답을 늘 첫 보기에 두므로, 섞지 않으면 위치만으로 답을 고를 수 있다.
     */
    record Shown(int index, GeneratedProblemItem item, List<Integer> order) {

        int correctNo() {
            for (int n = 0; n < order.size(); n++) {
                if (item.choices().get(order.get(n)).correct()) {
                    return n + 1;
                }
            }
            return 0;
        }

        String choiceText(int no) {
            return no >= 1 && no <= order.size() ? item.choices().get(order.get(no - 1)).text() : "";
        }
    }

    static List<Shown> showable(List<GeneratedProblemItem> problems) {
        List<Shown> shown = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            GeneratedProblemItem p = problems.get(i);
            if (p.question() == null || p.question().isBlank() || p.choices() == null || p.choices().size() != 4
                    || p.choices().stream().filter(GeneratedProblemItem.GeneratedChoice::correct).count() != 1) {
                continue;
            }
            List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
            // 질문으로 씨앗을 정해 같은 문제는 늘 같은 순서로 섞는다. 측정을 다시 돌려도 비교가 된다
            Collections.shuffle(order, new Random(p.question().hashCode()));
            shown.add(new Shown(i, p, order));
        }
        return shown;
    }

    static String buildChoiceOnlyPrompt(List<Shown> shown) {
        StringBuilder sb = new StringBuilder("아래 문제들의 보기만 보여 준다.\n\n");
        for (int k = 0; k < shown.size(); k++) {
            sb.append("[problemNo ").append(k + 1).append("]\n");
            appendChoices(sb, shown.get(k));
        }
        return sb.toString();
    }

    static String buildJudgePrompt(List<Shown> shown, SourceDocument source) {
        StringBuilder sb = new StringBuilder("# 근거 문서\n").append(source.contentMd()).append("\n\n# 문제\n\n");
        for (int k = 0; k < shown.size(); k++) {
            sb.append("[problemNo ").append(k + 1).append("]\n").append(shown.get(k).item().question()).append('\n');
            appendChoices(sb, shown.get(k));
        }
        return sb.toString();
    }

    private static void appendChoices(StringBuilder sb, Shown s) {
        for (int n = 1; n <= 4; n++) {
            sb.append(n).append(") ").append(s.choiceText(n)).append('\n');
        }
        sb.append('\n');
    }

    /** 난이도 정의는 생성 프롬프트에서 잘라 쓴다. 따로 적으면 두 곳의 정의가 어긋난다. */
    static String difficultyDefinition() {
        String prompt = ClaudeProblemGenerator.SYSTEM_PROMPT;
        int start = prompt.indexOf("[난이도가 뜻하는 것");
        int end = prompt.indexOf("[중급이 묻는 네 형태]");
        if (start < 0 || end <= start) {
            throw new IllegalStateException("생성 프롬프트에서 난이도 정의 절을 찾지 못했습니다.");
        }
        return prompt.substring(start, end).strip();
    }

    static String judgePrompt() {
        return JUDGE_PROMPT.formatted(difficultyDefinition());
    }

    /* ── 대조 ── */

    /** 두 응답을 표시된 정답·문서와 견준다. API 없이 테스트할 수 있게 순수 함수로 뒀다. */
    static List<Finding> compare(List<Shown> shown, ChoiceOnlyBatch choiceOnly, JudgementBatch judged,
                                 Difficulty labeled, String documentMd) {
        Map<Integer, ChoiceOnlyAnswer> answers = byNo(choiceOnly == null ? null : choiceOnly.answers(),
                ChoiceOnlyAnswer::problemNo);
        Map<Integer, Judgement> judgements = byNo(judged == null ? null : judged.judgements(), Judgement::problemNo);
        String doc = ClaudeDocumentFactChecker.normalize(documentMd);

        List<Finding> findings = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            int correct = s.correctNo();

            ChoiceOnlyAnswer a = answers.get(k + 1);
            if (a != null && a.choiceNo() == correct && !isBlank(a.cue())) {
                findings.add(new Finding(s.index(), FindingType.CHOICE_CUE_LEAK,
                        "질문 없이 보기만 보고 정답을 골랐다: " + a.cue()));
            }

            Judgement j = judgements.get(k + 1);
            if (j == null) {
                continue;
            }
            if (j.chosenNo() != correct) {
                findings.add(new Finding(s.index(), FindingType.ANSWER_MISMATCH,
                        "검수 AI는 \"%s\"를 골랐다: %s".formatted(s.choiceText(j.chosenNo()), j.answerReason())));
            } else if (j.otherCorrectNo() >= 1 && j.otherCorrectNo() <= 4 && j.otherCorrectNo() != correct) {
                findings.add(new Finding(s.index(), FindingType.OTHER_CORRECT,
                        "\"%s\"도 정답일 수 있다: %s".formatted(s.choiceText(j.otherCorrectNo()), j.otherCorrectReason())));
            }
            if (!isBlank(j.revealReason())) {
                findings.add(new Finding(s.index(), FindingType.QUESTION_REVEALS, j.revealReason()));
            }
            String quote = ClaudeDocumentFactChecker.normalize(j.supportQuote());
            if (quote.isEmpty() || !doc.contains(quote)) {
                findings.add(new Finding(s.index(), FindingType.NO_SUPPORT,
                        quote.isEmpty() ? "정답을 뒷받침하는 문장을 문서에서 찾지 못했다"
                                : "검수 AI가 든 근거 문장이 문서에 없다(지어낸 인용)"));
            }
            Difficulty judgedLevel = levelOf(s.item().question(), j);
            if (judgedLevel != labeled) {
                boolean condition = hasCondition(s.item().question(), j);
                findings.add(new Finding(s.index(), FindingType.DIFFICULTY_MISMATCH,
                        "%s로 냈지만 %s로 보인다: 지문 조건 %s, 다른 조건에서 맞는 오답 %d개. %s".formatted(
                                labeled.getDisplayName(), judgedLevel.getDisplayName(),
                                condition ? "「" + j.conditionQuote() + "」" : "없음",
                                j.elsewhereCount(), j.elsewhereReason())));
            }
        }
        return findings;
    }

    /**
     * 관찰 셋으로 급을 정한다. 생성 프롬프트의 [고급에는 조건이 반드시 있어야 한다]와
     * "오답은 조건이 달랐다면 옳았을 대응"을 옮긴 것이다.
     * AI에게 급을 바로 물으면 고급 문제도 중급으로 봤다(docs/22 §9, 0/2).
     * 오답 셋 중 둘이면 고급으로 본다. 셋 다를 요구하면 오답 하나를 약하게 쓴 고급을 놓친다.
     */
    static Difficulty levelOf(String question, Judgement j) {
        boolean condition = hasCondition(question, j);
        if (condition && j.elsewhereCount() >= 2) {
            return Difficulty.ADVANCED;
        }
        if (j.asksDefinition() && !condition) {
            return Difficulty.BEGINNER;
        }
        return Difficulty.INTERMEDIATE;
    }

    /** 옮겨 적은 조건 문장이 지문에 실제로 있어야 조건으로 친다. 지어낸 조건으로 고급이 되지 않게 한다. */
    static boolean hasCondition(String question, Judgement j) {
        String quote = ClaudeDocumentFactChecker.normalize(j.conditionQuote());
        return !quote.isEmpty() && ClaudeDocumentFactChecker.normalize(question).contains(quote);
    }

    private static <T> Map<Integer, T> byNo(List<T> items, Function<T, Integer> no) {
        if (items == null) {
            return Map.of();
        }
        // 같은 번호가 두 번 오면 앞의 것을 쓴다. 번호로 짝지으므로 순서가 밀려도 엉뚱한 문제에 붙지 않는다
        return items.stream().collect(Collectors.toMap(no, Function.identity(), (x, y) -> x));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /* ── 호출 ── */

    private <T> T call(String system, String user, Class<T> schema, boolean thinking) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(system)
                .addUserMessage(user);
        if (thinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }
        StructuredMessageCreateParams<T> params = builder.outputConfig(schema).build();
        try {
            var response = AnthropicClientHolder.get().messages().create(params);
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 검수 결과가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(문제 검수): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(문제 검수): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 문제 검수에 실패했습니다.");
        }
    }
}
