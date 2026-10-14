package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Suggestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 자바 코드 문제 AI 검수 — 실행 검증을 통과한 문제의 글을 본다.
 *
 * <p>개념 문제 검수({@link ClaudeProblemReviewer})는 정답을 가리고 풀게 한다. 여기서는 정답을 이미
 * 실행으로 확인했으니 정답을 보여 주고, <b>실제 실행 결과도 함께 준다</b>. 모델이 코드를 머릿속으로
 * 돌리다 틀리면 맞는 해설을 틀렸다고 지적하게 된다. 결과를 주면 그 계산을 하지 않아도 된다.
 *
 * <p>이 클래스는 코드를 실행하지 않는다. 실행 결과는 키 없는 단계가 글로 적어 넘긴다.
 */
@Slf4j
public class ClaudeCodeProblemReviewer {

    private static final long MAX_TOKENS = 32_000L;

    static final String SYSTEM_PROMPT = """
            너는 자바 학습 서비스의 코드 문제 검수자다. 모든 글은 한국어로 쓴다.
            문제마다 코드, 질문, 보기(정답 표시와 오답 이유), 해설, 그리고 배치가 코드를 실제로 컴파일하고 실행한 결과를 받는다.
            정답은 실행으로 이미 확인했다. 정답이 맞는지는 다시 따지지 않는다.
            실행 결과는 사실이다. 머릿속으로 코드를 다시 돌려 실행 결과와 다르게 판단하지 마라.

            문제마다 아래를 본다. 해당이 없으면 글은 빈 문자열, 번호는 0, 목록은 빈 목록이다.
            확신이 없으면 적지 마라. 이 지적은 사람이 하나씩 확인한다.

            1. explanationIssue: 해설이 실행 결과나 코드와 어긋나는 곳.
               출력값·예외·실행 순서를 실제와 다르게 적었다, "N행"이 그 일을 하지 않는 줄을 가리킨다
               (코드 앞의 줄 번호로 확인한다), 정답이 아닌 보기를 정답처럼 설명한다.
               어긋난 문장을 옮기고 실제로는 어떤지 쓴다. 덜 자세하거나 표현이 다른 것은 어긋남이 아니다.
               explanationFix: explanationIssue를 적었으면 고친 해설 전체를 쓴다. 어긋난 곳만 실행 결과에 맞게 고치고
               나머지 문장은 그대로 둔다. 줄은 "N행"으로 가리키고 문장은 "~다"로 끝낸다.
            2. rationaleIssues: 오답 이유가 사실과 어긋나는 오답.
               이유의 앞부분은 학습자의 오해를 옮긴 것이라 틀린 말이 맞다. 그 뒤에 적은 결과
               ("1이 출력된다", "컴파일되지 않는다" 등)나 설명이 실제와 다를 때만 적는다.
               빈칸·고치기 유형은 그 오답을 넣어 돌린 결과가 실행 결과에 있다.
               fixedRationale에는 고친 오답 이유 전체를 쓴다. 오해 문장은 그대로 두고 결과만 실제에 맞춘다.
            3. environmentIssue: 답이 JVM 옵션, JDK 버전, 운영체제, 기본 문자 인코딩, 로캘에 따라 바뀔 수 있는데
               문제가 그 전제를 밝히지 않았으면, 무엇에 따라 어떻게 바뀌는지 쓴다.
               자바 언어 명세나 API 문서가 보장하는 동작은 해당하지 않는다.
            4. offTopicReason: 주제의 개념을 몰라도 풀리거나, 주제와 상관없는 잔재주
               (연산자 우선순위 함정, 오타 찾기, 줄 번호 세기 등)가 답을 가르면 그 이유를 쓴다.
            5. giveawayChoiceNo, giveawayReason: 코드를 따라가지 않아도 모양만 보고 지워지는 오답.
               문법이 한눈에 틀렸거나 질문이 요구하는 꼴과 다른 보기다. 흔한 오해에서 나온 오답은 해당하지 않는다.
            6. 의견(참고용, 결함 판정이 아니다):
               awkwardQuote에 질문·해설·오답 이유에서 어색하거나 뜻이 흐린 부분을 글자 그대로 옮기고,
               awkwardFix에 그 부분을 대신할 글을 쓴다. 뜻과 값은 바꾸지 않는다. 코드와 보기의 코드는 고치지 않는다.
               readabilityNote에는 코드나 보기를 읽다 막히는 곳을 쓴다. 취향 차이는 적지 않는다.

            보기 번호는 받은 그대로 쓴다.
            """;

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

    private final Function<String, JudgementBatch> caller;
    private long inputTokens;
    private long outputTokens;

    public ClaudeCodeProblemReviewer(String model) {
        this.caller = user -> call(model, user);
    }

    /** 테스트가 모델 대신 답을 넣는다. 받는 글은 요청 글이다. */
    public ClaudeCodeProblemReviewer(Function<String, JudgementBatch> caller) {
        this.caller = caller;
    }

    /** @return 지적. {@code problemIndex}는 {@code targets} 안 위치다 */
    public List<Finding> review(String topic, List<Target> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        return findingsOf(targets, caller.apply(buildPrompt(topic, targets)));
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
                    .append("질문: ").append(item.question().strip()).append('\n')
                    .append("코드:\n");
            List<String> lines = item.code().strip().lines().toList();
            for (int n = 0; n < lines.size(); n++) {
                sb.append("%3d | %s%n".formatted(n + 1, lines.get(n)));
            }
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

    private JudgementBatch call(String model, String user) {
        StructuredMessageCreateParams<JudgementBatch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 해설의 실행 흐름을 줄마다 실행 결과와 맞춰 봐야 하는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(JudgementBatch.class)
                .addUserMessage(user)
                .build();
        try {
            var response = ClaudeCalls.create("코드 문제 검수", params);
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
