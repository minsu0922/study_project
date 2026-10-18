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
import project.study.study_project.llm.client.ProblemReview.AfterSolve;
import project.study.study_project.llm.client.ProblemReview.AfterSolveFix;
import project.study.study_project.llm.client.ProblemReview.AfterSolveBatch;
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyAnswer;
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyBatch;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Judgement;
import project.study.study_project.llm.client.ProblemReview.JudgementBatch;
import project.study.study_project.llm.client.ProblemReview.Suggestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;
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
    static final int CONDITION_QUOTE_MIN = 8;
    static final double CUE_LENGTH_RATIO = 1.3;

    static final String CHOICE_ONLY_PROMPT = Prompts.load("problem-review/choice-only.txt");

    static final String JUDGE_PROMPT = Prompts.load("problem-review/judge.txt");

    static final String AFTER_SOLVE_PROMPT = Prompts.load("problem-review/after-solve.txt");

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

        // 보기 단서 누설은 정답이 길이로 튈 때만 지적한다(answerStandsOutByLength). 그 문제가 없으면
        // 물어도 버릴 답이라 호출을 건너뛴다
        String choiceOnlyPrompt = buildChoiceOnlyPrompt(shown);
        ChoiceOnlyBatch choiceOnly = choiceOnlyPrompt == null ? null
                : call(CHOICE_ONLY_PROMPT, choiceOnlyPrompt, ChoiceOnlyBatch.class, false);
        JudgementBatch judged = call(judgePrompt(), buildJudgePrompt(shown, source), JudgementBatch.class, true);
        List<Finding> findings = compare(shown, choiceOnly, judged, labeled, source.contentMd());

        List<Finding> all = new ArrayList<>(afterSolve(shown, judged, findings));
        all.addAll(opinionsOf(shown, judged));
        return all;
    }

    /**
     * 해설 검수와 고친 문장 제안. 실패해도 앞의 두 호출이 낸 지적은 살린다.
     * 문서를 다시 보내지 않으려고 B의 풀이를 기준으로 삼는다. 문서가 입력의 대부분이다.
     */
    private List<Finding> afterSolve(List<Shown> shown, JudgementBatch judged, List<Finding> findings) {
        try {
            AfterSolveBatch after = call(AFTER_SOLVE_PROMPT, buildAfterSolvePrompt(shown, judged, findings),
                    AfterSolveBatch.class, false);
            return applyAfterSolve(shown, judged, findings, after);
        } catch (BusinessException e) {
            log.warn("해설 검수·고친 문장 제안을 건너뜁니다: {}", e.getMessage());
            return findings;
        }
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

        /**
         * AI가 쓴 글 속 "N번"을 원래 보기 순서의 번호로 바꾼다.
         *
         * <p>AI는 섞인 순서로 보기를 봤으므로 "4번만 길다"의 4번은 검수함에 보이는 4번이 아니다.
         * 프롬프트로 번호를 못 쓰게 하지 않는 이유: 프롬프트를 바꾸면 적발률을 다시 재야 한다(docs/22 §4).
         *
         * <p>횟수를 뜻하는 "3번 재전송"도 바뀐다. 보기 번호와 가를 방법이 없어 감수한다.
         * 숫자가 이어지는 "12번"과 보기 범위 밖 "5번"은 건드리지 않는다.
         */
        String inOriginalNumbers(String text) {
            if (text == null) {
                return "";
            }
            return CHOICE_NO.matcher(text.strip()).replaceAll(
                    m -> (order.get(Integer.parseInt(m.group(1)) - 1) + 1) + "번");
        }
    }

    private static final Pattern CHOICE_NO = Pattern.compile("(?<![0-9])([1-4])번");

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

    /**
     * 정답이 길이로 튀는 문제만 싣는다. 번호는 전체 목록 안의 위치를 그대로 써서 compare가 짝을 찾는다.
     *
     * @return 실을 문제가 없으면 {@code null}
     */
    static String buildChoiceOnlyPrompt(List<Shown> shown) {
        StringBuilder sb = new StringBuilder("아래 문제들의 보기만 보여 준다.\n\n");
        boolean any = false;
        for (int k = 0; k < shown.size(); k++) {
            if (!answerStandsOutByLength(shown.get(k).item())) {
                continue;
            }
            any = true;
            sb.append("[problemNo ").append(k + 1).append("]\n");
            appendChoices(sb, shown.get(k));
        }
        return any ? sb.toString() : null;
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

            // 지적은 결론(message)과 근거(detail)로 나눈다. 결론은 코드가 정한 문장이라 검수함에서
            // 훑어 읽히고, AI가 쓴 긴 글은 근거로 내린다. 근거 속 보기 번호는 원래 순서로 바꾼다(inOriginalNumbers)
            ChoiceOnlyAnswer a = answers.get(k + 1);
            if (a != null && a.choiceNo() == correct && !isBlank(a.cue()) && answerStandsOutByLength(s.item())) {
                findings.add(new Finding(s.index(), FindingType.CHOICE_CUE_LEAK,
                        "질문을 가리고 보기만 봐도 정답을 고를 수 있습니다",
                        s.inOriginalNumbers(a.cue())));
            }

            Judgement j = judgements.get(k + 1);
            if (j == null) {
                continue;
            }
            if (j.chosenNo() != correct) {
                findings.add(new Finding(s.index(), FindingType.ANSWER_MISMATCH,
                        "검수 AI는 표시된 정답이 아닌 보기를 골랐습니다",
                        "AI가 고른 보기: 「%s」\n고른 이유: %s".formatted(
                                s.choiceText(j.chosenNo()), s.inOriginalNumbers(j.answerReason()))));
            } else if (j.otherCorrectNo() >= 1 && j.otherCorrectNo() <= 4 && j.otherCorrectNo() != correct) {
                findings.add(new Finding(s.index(), FindingType.OTHER_CORRECT,
                        "정답으로 볼 수 있는 보기가 하나 더 있습니다",
                        "그 보기: 「%s」\n맞다고 본 이유: %s".formatted(
                                s.choiceText(j.otherCorrectNo()), s.inOriginalNumbers(j.otherCorrectReason()))));
            }
            if (!isBlank(j.revealReason())) {
                findings.add(new Finding(s.index(), FindingType.QUESTION_REVEALS,
                        "내용을 몰라도 질문과 보기의 문장만 견줘 답을 좁힐 수 있습니다",
                        s.inOriginalNumbers(j.revealReason())));
            }
            String quote = ClaudeDocumentFactChecker.normalize(j.supportQuote());
            if (quote.isEmpty() || !doc.contains(quote)) {
                findings.add(new Finding(s.index(), FindingType.NO_SUPPORT,
                        quote.isEmpty() ? "정답을 뒷받침하는 문장을 근거 문서에서 찾지 못했습니다"
                                : "검수 AI가 든 근거 문장이 문서에 없습니다(지어낸 인용)",
                        quote.isEmpty() ? null : "AI가 든 문장: 「%s」".formatted(j.supportQuote().strip())));
            }
            // 중급 지문에 조건(장면)만 있는 것은 지적하지 않는다. 진짜 중급 16개 중 약 6개가 배경 장면으로
            // 시작해 헛경보가 된다(docs/22 §9). 급은 오답 성격과 함께 볼 때만 갈린다
            boolean condition = hasCondition(s.item().question(), j);
            Difficulty judgedLevel = levelOf(condition, j);
            // 물음 문장을 조건으로 옮겨 온 경우는 조건인지 아닌지 가를 수 없다. 조건이 아니라고 봤을 때
            // 낸 급과 같아지면 지적하지 않는다(2026-10-10 #7325: 물음을 조건으로 쳐 중급을 고급으로 봤다)
            boolean unsure = condition && quotesQuestionSentence(j) && levelOf(false, j) == labeled;
            if (judgedLevel != labeled && !unsure) {
                // 급 이름(초급·중급·고급)은 모두 받침으로 끝나 조사가 늘 "으로"다
                findings.add(new Finding(s.index(), FindingType.DIFFICULTY_MISMATCH,
                        "%s으로 냈지만 %s으로 보입니다".formatted(
                                labeled.getDisplayName(), judgedLevel.getDisplayName()),
                        "지문에 답을 가르는 조건: %s\n조건이 달랐다면 정답이 되는 오답: %d개\n보기별 판단: %s".formatted(
                                conditionLine(condition, j),
                                elsewhereCountOf(j), s.inOriginalNumbers(j.elsewhereReason()))));
            }
        }
        return findings;
    }

    /* ── 정답과 해설을 열어 본 뒤 ── */

    /**
     * C 호출의 입력. 보기는 섞지 않고 원래 순서로 보여 준다. 정답을 이미 알려 주는 호출이라 가릴 것이 없고,
     * 지적 근거 속 번호(원래 순서)와 맞아야 한다.
     */
    static String buildAfterSolvePrompt(List<Shown> shown, JudgementBatch judged, List<Finding> findings) {
        Map<Integer, Judgement> judgements = byNo(judged == null ? null : judged.judgements(), Judgement::problemNo);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            GeneratedProblemItem item = s.item();
            sb.append("[problemNo ").append(k + 1).append("]\n질문: ").append(item.question()).append('\n');
            for (int n = 0; n < item.choices().size(); n++) {
                GeneratedProblemItem.GeneratedChoice c = item.choices().get(n);
                sb.append(n + 1).append(") ").append(c.text()).append(c.correct() ? "  ← 표시된 정답" : "").append('\n');
                if (!c.correct() && !isBlank(c.rationale())) {
                    sb.append("   오답 풀이: ").append(c.rationale().strip()).append('\n');
                }
            }
            sb.append("해설: ").append(isBlank(item.explanation()) ? "없음" : item.explanation().strip()).append('\n');
            Judgement j = judgements.get(k + 1);
            sb.append("검수자 풀이: ").append(solvedAsMarked(s, j) ? s.inOriginalNumbers(j.answerReason()) : "없음")
                    .append('\n');
            String flags = findings.stream()
                    .filter(f -> f.problemIndex() == s.index() && fixable(f.type()))
                    .map(f -> "[%s] %s".formatted(f.type().label(), f.detail() == null ? "" : f.detail()))
                    .collect(Collectors.joining(" / "));
            sb.append("지적: ").append(flags.isEmpty() ? "없음" : flags).append("\n\n");
        }
        return sb.toString();
    }

    private static boolean fixable(FindingType type) {
        return type == FindingType.CHOICE_CUE_LEAK || type == FindingType.QUESTION_REVEALS;
    }

    /** 검수자가 표시된 정답을 골랐을 때만 그 풀이를 기준으로 쓴다. 다른 보기를 골랐다면 이미 "정답 의심"이다. */
    private static boolean solvedAsMarked(Shown s, Judgement j) {
        return j != null && j.chosenNo() == s.correctNo() && !isBlank(j.answerReason());
    }

    /** C 응답을 지적에 얹는다. API 없이 테스트할 수 있게 순수 함수로 뒀다. */
    static List<Finding> applyAfterSolve(List<Shown> shown, JudgementBatch judged, List<Finding> findings,
                                         AfterSolveBatch after) {
        Map<Integer, Judgement> judgements = byNo(judged == null ? null : judged.judgements(), Judgement::problemNo);
        Map<Integer, AfterSolve> reviews = byNo(after == null ? null : after.reviews(), AfterSolve::problemNo);
        List<Finding> result = new ArrayList<>(findings);
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            AfterSolve a = reviews.get(k + 1);
            if (a == null) {
                continue;
            }
            if (!isBlank(a.explanationIssue()) && solvedAsMarked(s, judgements.get(k + 1))) {
                result.add(new Finding(s.index(), FindingType.EXPLANATION_MISMATCH,
                        "해설이 표시된 정답이나 검수 AI의 풀이와 어긋납니다", a.explanationIssue().strip()));
            }
            Suggestion suggestion = suggestionOf(s.item(), a);
            if (suggestion == null) {
                continue;
            }
            FindingType target = suggestion.target() == FixTarget.CHOICE
                    ? FindingType.CHOICE_CUE_LEAK : FindingType.QUESTION_REVEALS;
            for (int i = 0; i < result.size(); i++) {
                Finding f = result.get(i);
                if (f.problemIndex() == s.index() && f.type() == target) {
                    result.set(i, f.withSuggestion(suggestion));
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 받아들일 제안만 돌려준다. 보기 제안은 고친 뒤에도 정답이 길이로 튀면 버린다 —
     * 지적을 없애지 못하는 제안을 검수자에게 누르게 할 수는 없다.
     */
    static Suggestion suggestionOf(GeneratedProblemItem item, AfterSolve a) {
        if (a.fixTarget() == null || a.fixTarget() == AfterSolveFix.NONE || isBlank(a.fixText())) {
            return null;
        }
        String text = a.fixText().strip();
        if (a.fixTarget() == AfterSolveFix.QUESTION) {
            return text.equals(item.question().strip()) ? null : new Suggestion(FixTarget.QUESTION, null, text);
        }
        int index = a.fixChoiceNo() - 1;
        if (index < 0 || index >= item.choices().size() || text.equals(item.choices().get(index).text().strip())) {
            return null;
        }
        List<GeneratedProblemItem.GeneratedChoice> fixed = new ArrayList<>(item.choices());
        GeneratedProblemItem.GeneratedChoice c = fixed.get(index);
        fixed.set(index, new GeneratedProblemItem.GeneratedChoice(text, c.correct(), c.rationale(), c.matchText()));
        return answerStandsOutByLength(fixed) ? null : new Suggestion(FixTarget.CHOICE, index, text);
    }

    /* ── 의견 ── */

    /**
     * B가 남긴 의견을 지적 목록에 실을 형태로 바꾼다. 등급이 의견이라 검수함은 지적 수에 세지 않는다.
     * 풀이는 표시된 정답을 골랐을 때만 싣는다. 다른 보기를 골랐으면 "정답 의심"의 근거에 이미 있다.
     */
    static List<Finding> opinionsOf(List<Shown> shown, JudgementBatch judged) {
        Map<Integer, Judgement> judgements = byNo(judged == null ? null : judged.judgements(), Judgement::problemNo);
        List<Finding> opinions = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            Judgement j = judgements.get(k + 1);
            if (j == null) {
                continue;
            }
            if (solvedAsMarked(s, j)) {
                opinions.add(new Finding(s.index(), FindingType.SOLUTION, "검수 AI가 문서를 보고 푼 과정입니다",
                        s.inOriginalNumbers(j.answerReason())));
            }
            // 어색하다는 문장이 문제에 실제로 있어야 싣는다. 지어낸 인용은 검수자가 찾을 수 없다
            if (appearsIn(s.item(), j.awkwardQuote())) {
                opinions.add(new Finding(s.index(), FindingType.AWKWARD_SENTENCE, "어색하거나 뜻이 흐린 문장이 있습니다",
                        "지금: 「%s」".formatted(j.awkwardQuote().strip())
                                + (isBlank(j.awkwardFix()) ? "" : "\n고치면: " + j.awkwardFix().strip())));
            }
            if (!isBlank(j.readabilityNote())) {
                opinions.add(new Finding(s.index(), FindingType.READABILITY, "읽다가 막히는 곳이 있습니다",
                        s.inOriginalNumbers(j.readabilityNote())));
            }
            if (!isBlank(j.overallNote())) {
                opinions.add(new Finding(s.index(), FindingType.OVERALL, "풀어 본 총평입니다",
                        s.inOriginalNumbers(j.overallNote())));
            }
        }
        return opinions;
    }

    private static boolean appearsIn(GeneratedProblemItem item, String quote) {
        String q = ClaudeDocumentFactChecker.normalize(quote);
        if (q.isEmpty()) {
            return false;
        }
        return ClaudeDocumentFactChecker.normalize(item.question()).contains(q)
                || item.choices().stream().anyMatch(c -> ClaudeDocumentFactChecker.normalize(c.text()).contains(q));
    }

    /**
     * 정답이 오답 평균보다 {@link #CUE_LENGTH_RATIO}배 이상 길 때만 보기 단서 누설로 친다.
     * 2026-10-07~12 지적 21건을 문제와 견줘 보니 수긍되는 12건은 모두 1.3배 이상이었고,
     * 그 아래 9건은 "결이 다르다" 같은 이유뿐이라 검수자가 그대로 승인했다(docs/22 §6).
     * 프롬프트는 그대로 두고 코드에서 거른다. 프롬프트를 바꾸면 적발률을 다시 재야 한다.
     */
    static boolean answerStandsOutByLength(GeneratedProblemItem item) {
        return answerStandsOutByLength(item.choices());
    }

    static boolean answerStandsOutByLength(List<GeneratedProblemItem.GeneratedChoice> choices) {
        int answer = 0;
        int others = 0;
        for (GeneratedProblemItem.GeneratedChoice c : choices) {
            if (c.correct()) {
                answer = c.text().length();
            } else {
                others += c.text().length();
            }
        }
        // 오답은 셋이다(showable이 보기 4개·정답 1개만 통과시킨다)
        return answer >= CUE_LENGTH_RATIO * others / 3.0;
    }

    /**
     * 관찰 셋으로 급을 정한다. 생성 프롬프트의 [고급에는 조건이 반드시 있어야 한다]와
     * "오답은 조건이 달랐다면 옳았을 대응"을 옮긴 것이다.
     * AI에게 급을 바로 물으면 고급 문제도 중급으로 봤다(docs/22 §9, 0/2).
     * 오답 셋 중 둘이면 고급으로 본다. 셋 다를 요구하면 오답 하나를 약하게 쓴 고급을 놓친다.
     */
    static Difficulty levelOf(String question, Judgement j) {
        return levelOf(hasCondition(question, j), j);
    }

    static Difficulty levelOf(boolean condition, Judgement j) {
        if (condition && elsewhereCountOf(j) >= 2) {
            return Difficulty.ADVANCED;
        }
        if (j.asksDefinition() && !condition) {
            return Difficulty.BEGINNER;
        }
        return Difficulty.INTERMEDIATE;
    }

    /**
     * 옮겨 적은 조건 문장이 지문에 실제로 있어야 조건으로 친다. 지어낸 조건으로 고급이 되지 않게 한다.
     * 8자 미만은 버린다. "이때", "이 조건에서" 같은 조각도 지문에는 있기 때문이다.
     * 물음 문장 통째("이 조건에서 옳은 판단은?")는 길이로 못 거른다. 그 경우는 compare가 따로 본다.
     */
    static boolean hasCondition(String question, Judgement j) {
        String quote = ClaudeDocumentFactChecker.normalize(j.conditionQuote());
        return quote.length() >= CONDITION_QUOTE_MIN
                && ClaudeDocumentFactChecker.normalize(question).contains(quote);
    }

    static boolean quotesQuestionSentence(Judgement j) {
        return ClaudeDocumentFactChecker.normalize(j.conditionQuote()).endsWith("?");
    }

    /**
     * 지적 근거의 조건 줄. AI가 조건 문장을 댔는데 지문에 없어 버린 경우는 그 문장을 남긴다.
     * 상황 지문인데 "조건 없음"으로 나온 지적(2026-10-08 고급 3건)이 AI가 비운 탓인지
     * 옮겨 적으며 바꾼 탓인지 가르려면 버린 문장이 보여야 한다.
     */
    static String conditionLine(boolean condition, Judgement j) {
        if (condition) {
            return "「" + j.conditionQuote().strip() + "」";
        }
        return isBlank(j.conditionQuote()) ? "없음"
                : "없음 (AI가 든 문장 「%s」은 지문에 그대로 없거나 너무 짧아 버림)".formatted(j.conditionQuote().strip());
    }

    /** 오답은 셋뿐이다. 정답까지 세어 4를 적는 응답이 있어도 급이 흔들리지 않게 자른다. */
    static int elsewhereCountOf(Judgement j) {
        return Math.max(0, Math.min(3, j.elsewhereCount()));
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
            var response = ClaudeCalls.create("문제 검수", params);
            inputTokens += response.inputTokens();
            outputTokens += response.outputTokens();
            return java.util.Optional.ofNullable(response.value())
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
