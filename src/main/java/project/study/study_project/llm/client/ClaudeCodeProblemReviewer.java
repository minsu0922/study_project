package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Suggestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 자바 코드 문제 AI 검수 — 실행 검증을 통과한 문제를 두 번 본다.
 *
 * <p>A. 모르고 풀기: 정답·해설·실행 결과를 가리고 학습자처럼 풀게 한다. 틀리게 고르거나 모호하다고 하면
 * 함정이 지나치거나 질문이 흐리다는 신호다. 정답 자체는 실행으로 확인했으니 결함으로 보지 않는다.
 *
 * <p>B. 대조 검수: 정답을 보여 주고 <b>실제 실행 결과도 함께 준다</b>. 모델이 코드를 머릿속으로
 * 돌리다 틀리면 맞는 해설을 틀렸다고 지적하게 된다. 결과를 주면 그 계산을 하지 않아도 된다.
 *
 * <p>이 클래스는 코드를 실행하지 않는다. 실행 결과는 키 없는 단계가 글로 적어 넘긴다.
 */
@Slf4j
public class ClaudeCodeProblemReviewer {

    private static final long MAX_TOKENS = 32_000L;

    static final String SYSTEM_PROMPT = Prompts.load("code-problem-review/system.txt");

    /** 검수할 문제 하나와, 키 없는 단계가 그 코드를 돌려 적은 결과. */
    public record Target(Item item, String runFacts) {
    }

    public record RationaleIssue(
            @JsonPropertyDescription("오답 보기 번호(1부터)")
            int choiceNo,
            @JsonPropertyDescription("오답 이유의 어느 부분이 실제와 어떻게 다른지")
            String issue,
            @JsonPropertyDescription("고친 오답 이유 전체")
            String fixedRationale
    ) {
    }

    public record Judgement(
            @JsonPropertyDescription("문제 번호(1부터)")
            int problemNo,
            @JsonPropertyDescription("해설이 실행 결과나 코드와 어긋나는 곳. 없으면 빈 문자열")
            String explanationIssue,
            @JsonPropertyDescription("고친 해설 전체. explanationIssue가 비었으면 빈 문자열")
            String explanationFix,
            @JsonPropertyDescription("오답 이유가 사실과 어긋나는 오답들. 없으면 빈 목록")
            List<RationaleIssue> rationaleIssues,
            @JsonPropertyDescription("실행 환경에 따라 답이 바뀌는 이유. 없으면 빈 문자열")
            String environmentIssue,
            @JsonPropertyDescription("주제를 몰라도 풀리거나 잔재주가 답을 가르는 이유. 없으면 빈 문자열")
            String offTopicReason,
            @JsonPropertyDescription("모양만 보고 지워지는 오답 번호. 없으면 0")
            int giveawayChoiceNo,
            @JsonPropertyDescription("그 오답이 지워지는 이유. 없으면 빈 문자열")
            String giveawayReason,
            @JsonPropertyDescription("어색한 문장을 글자 그대로. 없으면 빈 문자열")
            String awkwardQuote,
            @JsonPropertyDescription("고친 문장. 없으면 빈 문자열")
            String awkwardFix,
            @JsonPropertyDescription("읽다 막히는 곳. 없으면 빈 문자열")
            String readabilityNote
    ) {
    }

    public record JudgementBatch(
            @JsonPropertyDescription("문제마다 하나")
            List<Judgement> problems
    ) {
    }

    /* ── A. 모르고 풀기 ── */

    static final String SOLVE_PROMPT = Prompts.load("code-problem-review/solve.txt").formatted(ClaudeCodeProblemGenerator.DIFFICULTY_GUIDE);

    public record Solve(
            @JsonPropertyDescription("문제 번호(1부터)")
            int problemNo,
            @JsonPropertyDescription("고른 보기 번호(1부터)")
            int chosenNo,
            @JsonPropertyDescription("푼 과정")
            String reason,
            @JsonPropertyDescription("두 가지로 읽히는 곳. 없으면 빈 문자열")
            String ambiguity,
            @JsonPropertyDescription("느낀 난이도")
            Difficulty feltDifficulty,
            @JsonPropertyDescription("그 난이도로 본 근거")
            String difficultyReason
    ) {
    }

    public record SolveBatch(
            @JsonPropertyDescription("문제마다 하나")
            List<Solve> problems
    ) {
    }

    private final Function<String, JudgementBatch> judge;
    /** {@code null}이면 모르고 풀기를 건너뛴다(테스트) */
    private final Function<String, SolveBatch> solver;
    private long inputTokens;
    private long outputTokens;

    public ClaudeCodeProblemReviewer(String model) {
        this.judge = user -> call(model, SYSTEM_PROMPT, user, JudgementBatch.class, "코드 문제 검수");
        this.solver = user -> call(model, SOLVE_PROMPT, user, SolveBatch.class, "코드 문제 풀기");
    }

    /** 테스트가 모델 대신 답을 넣는다. 받는 글은 요청 글이다. */
    public ClaudeCodeProblemReviewer(Function<String, JudgementBatch> judge) {
        this(judge, null);
    }

    public ClaudeCodeProblemReviewer(Function<String, JudgementBatch> judge, Function<String, SolveBatch> solver) {
        this.judge = judge;
        this.solver = solver;
    }

    /**
     * 정답을 가리고 풀게 한 뒤(A), 정답과 실행 결과를 보여 글을 대조한다(B). 순서가 바뀌면 A가 답을 본다.
     * 한쪽이 실패해도 다른 쪽 지적은 낸다. 둘 다 실패하면 예외를 던진다.
     *
     * @return 지적. {@code problemIndex}는 {@code targets} 안 위치다
     */
    public List<Finding> review(String topic, List<Target> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        RuntimeException failure = null;
        boolean anyDone = false;
        if (solver != null) {
            List<List<Integer>> orders = ordersOf(targets);
            try {
                findings.addAll(solveFindings(targets, orders, solver.apply(buildSolvePrompt(targets, orders))));
                anyDone = true;
            } catch (RuntimeException e) {
                log.warn("코드 문제 모르고 풀기 실패: {}", e.getMessage());
                failure = e;
            }
        }
        try {
            findings.addAll(findingsOf(targets, judge.apply(buildPrompt(topic, targets))));
            anyDone = true;
        } catch (RuntimeException e) {
            log.warn("코드 문제 대조 검수 실패: {}", e.getMessage());
            failure = e;
        }
        if (!anyDone) {
            throw failure;
        }
        return findings;
    }

    /**
     * 보기를 섞는 순서. 생성된 코드 문제는 정답이 1번에 몰려 있어, 그대로 보이면 번호로 고를 수 있다.
     * 코드로 씨앗을 정해 같은 문제는 늘 같은 순서로 섞는다.
     */
    static List<List<Integer>> ordersOf(List<Target> targets) {
        List<List<Integer>> orders = new ArrayList<>();
        for (Target t : targets) {
            List<Integer> order = new ArrayList<>();
            for (int c = 0; c < t.item().choices().size(); c++) {
                order.add(c);
            }
            Collections.shuffle(order, new Random(t.item().code().hashCode()));
            orders.add(order);
        }
        return orders;
    }

    static String buildSolvePrompt(List<Target> targets, List<List<Integer>> orders) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            Item item = targets.get(i).item();
            sb.append(i == 0 ? "" : "\n").append("[문제 ").append(i + 1).append("] 유형: ").append(item.kind().label()).append('\n')
                    .append("질문: ").append(item.question().strip()).append('\n');
            appendCode(sb, item);
            sb.append("보기:\n");
            List<Integer> order = orders.get(i);
            for (int n = 0; n < order.size(); n++) {
                sb.append(n + 1).append(". ").append(item.choices().get(order.get(n)).text().strip()).append('\n');
            }
        }
        return sb.toString();
    }

    static List<Finding> solveFindings(List<Target> targets, List<List<Integer>> orders, SolveBatch batch) {
        Map<Integer, Solve> byNo = new HashMap<>();
        if (batch != null && batch.problems() != null) {
            for (Solve s : batch.problems()) {
                if (s != null) {
                    byNo.putIfAbsent(s.problemNo(), s);
                }
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Solve s = byNo.get(i + 1);
            List<Integer> order = orders.get(i);
            if (s == null || s.chosenNo() < 1 || s.chosenNo() > order.size()) {
                continue;
            }
            Item item = targets.get(i).item();
            int chosen = order.get(s.chosenNo() - 1);
            String reason = inOriginalNumbers(s.reason(), order);
            if (item.choices().get(chosen).correct()) {
                findings.add(new Finding(i, FindingType.SOLUTION, "검수 AI가 정답을 가린 채 푼 과정입니다", reason));
            } else {
                findings.add(new Finding(i, FindingType.MISSOLVED, "검수 AI가 정답이 아닌 보기를 골랐습니다",
                        "AI가 고른 보기: %d번 「%s」\n푼 과정: %s".formatted(
                                chosen + 1, item.choices().get(chosen).text().strip(), reason)));
            }
            if (!isBlank(s.ambiguity())) {
                findings.add(new Finding(i, FindingType.AMBIGUOUS_QUESTION, "질문이나 코드가 두 가지로 읽힐 수 있습니다",
                        inOriginalNumbers(s.ambiguity(), order)));
            }
            // 한 단계 차이는 정의의 경계에서 흔히 갈린다. 두 단계(초급↔고급)만 지적한다
            if (s.feltDifficulty() != null && Math.abs(s.feltDifficulty().ordinal() - item.difficulty().ordinal()) >= 2) {
                findings.add(new Finding(i, FindingType.DIFFICULTY_MISMATCH, "검수 AI가 느낀 난이도가 매긴 급과 두 단계 다릅니다",
                        "매긴 급: %s → 느낀 급: %s%s".formatted(item.difficulty().getDisplayName(),
                                s.feltDifficulty().getDisplayName(),
                                isBlank(s.difficultyReason()) ? "" : "\n근거: " + inOriginalNumbers(s.difficultyReason(), order))));
            }
        }
        return findings;
    }

    private static final Pattern CHOICE_NO = Pattern.compile("(?<![0-9])([1-4])번");

    /** AI가 섞인 순서로 적은 "2번"을 검수함에 보이는 원래 번호로 바꾼다. */
    static String inOriginalNumbers(String text, List<Integer> order) {
        if (text == null) {
            return "";
        }
        return CHOICE_NO.matcher(text.strip()).replaceAll(m -> {
            int no = Integer.parseInt(m.group(1));
            return no <= order.size() ? (order.get(no - 1) + 1) + "번" : m.group();
        });
    }

    private static void appendCode(StringBuilder sb, Item item) {
        sb.append("코드:\n");
        List<String> lines = item.code().strip().lines().toList();
        for (int n = 0; n < lines.size(); n++) {
            sb.append("%3d | %s".formatted(n + 1, lines.get(n))).append('\n');
        }
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    static String buildPrompt(String topic, List<Target> targets) {
        StringBuilder sb = new StringBuilder("주제: ").append(topic).append("\n");
        for (int i = 0; i < targets.size(); i++) {
            Item item = targets.get(i).item();
            sb.append("\n[문제 ").append(i + 1).append("] 유형: ").append(item.kind().label())
                    .append(" · 난이도: ").append(item.difficulty().getDisplayName()).append('\n')
                    .append("질문: ").append(item.question().strip()).append('\n');
            appendCode(sb, item);
            sb.append("보기:\n");
            for (int c = 0; c < item.choices().size(); c++) {
                Choice choice = item.choices().get(c);
                sb.append(c + 1).append(". ").append(choice.correct() ? "(정답) " : "").append(choice.text().strip());
                if (!choice.correct() && !isBlank(choice.rationale())) {
                    sb.append("\n   오답 이유: ").append(choice.rationale().strip());
                }
                sb.append('\n');
            }
            sb.append("해설: ").append(item.explanation().strip()).append('\n')
                    .append("실행 결과:\n").append(targets.get(i).runFacts().strip()).append('\n');
        }
        return sb.toString();
    }

    /** 결론 문장은 종류마다 코드가 정한다. 모델이 쓴 글은 근거 칸에만 싣는다. */
    static List<Finding> findingsOf(List<Target> targets, JudgementBatch batch) {
        Map<Integer, Judgement> byNo = new HashMap<>();
        if (batch != null && batch.problems() != null) {
            for (Judgement j : batch.problems()) {
                if (j != null) {
                    byNo.putIfAbsent(j.problemNo(), j);
                }
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Judgement j = byNo.get(i + 1);
            if (j == null) {
                continue;
            }
            Item item = targets.get(i).item();
            if (!isBlank(j.explanationIssue())) {
                findings.add(new Finding(i, FindingType.EXPLANATION_MISMATCH, "해설이 실제 실행과 어긋납니다",
                        j.explanationIssue().strip(), replacing(item.explanation(), j.explanationFix(), FixTarget.EXPLANATION, null)));
            }
            // 오답마다 따로 단다. 제안 하나가 오답 이유 하나를 고치기 때문이다
            for (RationaleIssue r : j.rationaleIssues() == null ? List.<RationaleIssue>of() : j.rationaleIssues()) {
                if (r != null && isWrongChoice(item, r.choiceNo()) && !isBlank(r.issue())) {
                    int index = r.choiceNo() - 1;
                    findings.add(new Finding(i, FindingType.RATIONALE_MISMATCH, "오답 이유가 실제 실행과 어긋납니다",
                            "%d번: %s".formatted(r.choiceNo(), r.issue().strip()),
                            replacing(item.choices().get(index).rationale(), r.fixedRationale(), FixTarget.RATIONALE, index)));
                }
            }
            if (!isBlank(j.environmentIssue())) {
                findings.add(new Finding(i, FindingType.ENVIRONMENT_DEPENDENT, "실행 환경에 따라 답이 바뀔 수 있습니다",
                        j.environmentIssue().strip()));
            }
            if (!isBlank(j.offTopicReason())) {
                findings.add(new Finding(i, FindingType.OFF_TOPIC, "주제를 몰라도 풀리거나 다른 것이 답을 가릅니다",
                        j.offTopicReason().strip()));
            }
            if (isWrongChoice(item, j.giveawayChoiceNo()) && !isBlank(j.giveawayReason())) {
                findings.add(new Finding(i, FindingType.CHOICE_CUE_LEAK, "코드를 따라가지 않아도 지워지는 오답이 있습니다",
                        "%d번: %s".formatted(j.giveawayChoiceNo(), j.giveawayReason().strip())));
            }
            // 어색하다는 문장이 문제에 실제로 있어야 싣는다. 지어낸 인용은 검수자가 찾을 수 없다
            if (appearsIn(item, j.awkwardQuote())) {
                findings.add(new Finding(i, FindingType.AWKWARD_SENTENCE, "어색하거나 뜻이 흐린 문장이 있습니다",
                        "지금: 「%s」".formatted(j.awkwardQuote().strip())
                                + (isBlank(j.awkwardFix()) ? "" : "\n고치면: " + j.awkwardFix().strip()),
                        awkwardSuggestion(item, j.awkwardQuote(), j.awkwardFix())));
            }
            if (!isBlank(j.readabilityNote())) {
                findings.add(new Finding(i, FindingType.READABILITY, "읽다가 막히는 곳이 있습니다",
                        j.readabilityNote().strip()));
            }
        }
        return findings;
    }

    /** 고친 글이 있고 지금과 다를 때만 제안한다. 같은 글을 적용하라는 버튼은 뜻이 없다. */
    private static Suggestion replacing(String now, String fixed, FixTarget target, Integer choiceIndex) {
        if (isBlank(fixed) || fixed.strip().equals(now == null ? "" : now.strip())) {
            return null;
        }
        return new Suggestion(target, choiceIndex, fixed.strip());
    }

    /**
     * 어색한 부분만 바꾼 글 전체를 제안한다. 인용이 글자 그대로 있어야 바꿀 수 있다.
     * 보기 글은 고치지 않는다. 코드 문제의 보기는 출력이나 코드라, 바꾸면 실행 검증을 다시 해야 한다.
     */
    static Suggestion awkwardSuggestion(Item item, String quote, String fix) {
        if (isBlank(quote) || isBlank(fix)) {
            return null;
        }
        String q = quote.strip();
        if (item.question() != null && item.question().contains(q)) {
            return replacing(item.question(), item.question().replace(q, fix.strip()), FixTarget.QUESTION, null);
        }
        if (item.explanation() != null && item.explanation().contains(q)) {
            return replacing(item.explanation(), item.explanation().replace(q, fix.strip()), FixTarget.EXPLANATION, null);
        }
        for (int c = 0; c < item.choices().size(); c++) {
            String rationale = item.choices().get(c).rationale();
            if (rationale != null && rationale.contains(q)) {
                return replacing(rationale, rationale.replace(q, fix.strip()), FixTarget.RATIONALE, c);
            }
        }
        return null;
    }

    private static boolean isWrongChoice(Item item, int no) {
        return no >= 1 && no <= item.choices().size() && !item.choices().get(no - 1).correct();
    }

    private static boolean appearsIn(Item item, String quote) {
        String q = ClaudeDocumentFactChecker.normalize(quote);
        if (q.isEmpty()) {
            return false;
        }
        List<String> texts = new ArrayList<>(List.of(item.question(), item.explanation()));
        for (Choice c : item.choices()) {
            texts.add(c.text());
            texts.add(c.rationale());
        }
        return texts.stream().anyMatch(t -> t != null && ClaudeDocumentFactChecker.normalize(t).contains(q));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private <T> T call(String model, String system, String user, Class<T> schema, String label) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 둘 다 코드를 줄마다 따라가야 하는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(system)
                .outputConfig(schema)
                .addUserMessage(user)
                .build();
        try {
            var response = ClaudeCalls.create(label, params);
            inputTokens += response.inputTokens();
            outputTokens += response.outputTokens();
            return java.util.Optional.ofNullable(response.value())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 검수 결과가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(코드 문제 검수): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(코드 문제 검수): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 코드 문제 검수에 실패했습니다.");
        }
    }
}
