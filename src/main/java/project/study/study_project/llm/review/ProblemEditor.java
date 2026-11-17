package project.study.study_project.llm.review;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Suggestion;
import project.study.study_project.llm.client.Prompts;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.review.ProblemSolver.Solve;
import project.study.study_project.llm.support.ExplanationFormRule;
import project.study.study_project.llm.support.ExplanationFormRule.FormIssue;
import project.study.study_project.llm.support.ProblemItemRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 편집 호출 — 정답을 아는 AI가 해설·오답 설명을 기준표(docs/superpowers/specs/2026-11-16-explanation-review-design.md)로
 * 보고 고친 글을 낸다. 고친 글은 코드가 다시 검사해, 통과한 해설·오답 설명만 "고쳐 넣음"으로 내보낸다.
 * 질문 고침은 정답을 바꿀 수 있어 늘 제안으로만 둔다.
 */
public final class ProblemEditor {

    /** 해설·오답 설명 지침은 생성 프롬프트에서 잘라 붙인다. 따로 적으면 생성과 검수의 기준이 어긋난다 */
    static final String PROMPT = Prompts.load("problem-review/edit.txt") + "\n" + generationGuide();

    private static final Set<String> CRITERIA = Set.of(
            "A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4", "B5", "B6", "C1", "C2", "C3", "C4");
    /** 오답 설명에 관한 기준. 이 둘만 걸렸으면 해설을 고칠 일이 없다 */
    private static final Set<String> RATIONALE_CRITERIA = Set.of("C2", "C3");
    private static final Pattern QUOTE = Pattern.compile("「([^」]+)」");
    private static final Pattern WORD = Pattern.compile("[가-힣A-Za-z0-9_.]+");
    /** 낱말 끝에서 떼어 보는 조사. 길이가 긴 것부터 뗀다 */
    private static final List<String> JOSA = List.of("으로", "이다", "은", "는", "이", "가", "을", "를", "의", "에", "로", "와", "과", "도", "다");

    public record RationaleFix(
            @JsonPropertyDescription("고친 오답 보기의 번호. 입력에 적힌 보기 번호 1~4")
            int choiceNo,
            @JsonPropertyDescription("고친 오답 설명 전체")
            String text
    ) {
    }

    @JsonClassDescription("문제 하나의 해설·오답 설명 검수와 고친 글")
    public record Edit(
            @JsonPropertyDescription("문제 번호. 입력으로 준 번호를 그대로 돌려준다")
            int problemNo,
            @JsonPropertyDescription("걸린 기준마다 한 줄: '기준: 「해설이나 오답 설명에서 글자 그대로 옮긴 문장」 — 이유'. 없으면 빈 문자열")
            String criteriaReason,
            @JsonPropertyDescription("걸린 기준 번호 목록. 예: [\"A2\", \"B3\"]. 없으면 빈 목록")
            List<String> failedCriteria,
            @JsonPropertyDescription("해설 기준에 걸렸을 때만 고친 해설 전체. 정답과 보기는 그대로 두고 문서에 없는 사실을 더하지 않는다. 아니면 빈 문자열")
            String rewrittenExplanation,
            @JsonPropertyDescription("C2·C3에 걸린 오답 보기의 고친 오답 설명. 정답 보기는 넣지 않는다. 없으면 빈 목록")
            List<RationaleFix> rewrittenRationales,
            @JsonPropertyDescription("'질문에 적힌 정답 구절'이 주어진 문제만, 그 구절을 걷어 낸 질문 전체. 아니면 빈 문자열")
            String questionFix,
            @JsonPropertyDescription("[자료]가 사실과 다르거나 정답을 뒷받침하지 않으면 그 점. 아니면 빈 문자열")
            String exhibitIssue
    ) {
    }

    public record EditBatch(
            @JsonPropertyDescription("문제별 편집")
            List<Edit> edits
    ) {
    }

    private ProblemEditor() {
    }

    static String generationGuide() {
        String prompt = Prompts.load("problem/system.txt");
        int start = prompt.indexOf("\n[해설]\n");
        int end = prompt.indexOf("\n[제목]");
        if (start < 0 || end <= start) {
            throw new IllegalStateException("생성 프롬프트에서 [해설]·[오답 설명] 절을 찾지 못했습니다.");
        }
        return prompt.substring(start + 1, end).strip();
    }

    /**
     * @param solves     풀이 결과. 키는 {@link Shown#index()}
     * @param formIssues 코드 검사 결과. 키는 {@link Shown#index()}
     */
    static String buildPrompt(List<Shown> toEdit, Map<Integer, Solve> solves, SourceDocument source,
                              Map<Integer, List<FormIssue>> formIssues) {
        StringBuilder sb = new StringBuilder("# 근거 문서\n").append(source.contentMd()).append("\n\n# 문제\n\n");
        for (int k = 0; k < toEdit.size(); k++) {
            Shown s = toEdit.get(k);
            GeneratedProblemItem item = s.item();
            sb.append("[problemNo ").append(k + 1).append("]\n").append(item.questionWithExhibit()).append('\n');
            List<GeneratedChoice> choices = item.choices();
            for (int i = 0; i < choices.size(); i++) {
                GeneratedChoice c = choices.get(i);
                sb.append(i + 1).append(") ").append(c.text()).append(c.correct() ? " ← 정답" : "").append('\n');
                if (!c.correct()) {
                    sb.append("   오답 설명: ").append(blankToEmpty(c.rationale())).append('\n');
                }
            }
            sb.append("해설:\n").append(blankToEmpty(item.explanation())).append('\n');
            Solve solve = solves.get(s.index());
            if (solve != null) {
                sb.append("검수자 풀이: ").append(s.inOriginalNumbers(solve.answerReason())).append('\n');
                if (QuoteMatch.in(solve.answerInQuestionQuote(), item.question())) {
                    sb.append("질문에 적힌 정답 구절: 「").append(solve.answerInQuestionQuote().strip()).append("」\n");
                }
            }
            for (FormIssue issue : formIssues.getOrDefault(s.index(), List.of())) {
                sb.append("코드 검사: [").append(issue.criterion()).append("] ").append(issue.message()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 편집 응답을 재검사해 지적으로 바꾼다. 순수 함수다 */
    static List<Finding> findingsOf(List<Shown> toEdit, EditBatch batch, SourceDocument source,
                                    Map<Integer, Solve> solves) {
        Map<Integer, Edit> edits = batch == null || batch.edits() == null ? Map.of()
                : batch.edits().stream().collect(Collectors.toMap(Edit::problemNo, Function.identity(), (x, y) -> x));
        List<Finding> findings = new ArrayList<>();
        for (int k = 0; k < toEdit.size(); k++) {
            Edit e = edits.get(k + 1);
            if (e != null) {
                findingsOfOne(toEdit.get(k), e, source, solves.get(toEdit.get(k).index()), findings);
            }
        }
        return findings;
    }

    private static void findingsOfOne(Shown s, Edit e, SourceDocument source, Solve solve, List<Finding> out) {
        GeneratedProblemItem item = s.item();
        String slug = source.slug();
        String md = source.contentMd();

        List<FormIssue> form = ExplanationFormRule.issuesOf(item, slug, md);
        List<String> reasons = trustedReasons(e.criteriaReason(), item);
        Set<String> failed = new LinkedHashSet<>();
        if (e.failedCriteria() != null) {
            e.failedCriteria().stream().map(String::strip).map(String::toUpperCase).filter(CRITERIA::contains)
                    .forEach(failed::add);
        }
        form.forEach(i -> failed.add(i.criterion()));
        // 근거를 하나도 대지 못한 AI 판정은 믿지 않는다. 코드 검사가 건 기준만 남는다
        if (reasons.isEmpty()) {
            failed.retainAll(form.stream().map(FormIssue::criterion).collect(Collectors.toSet()));
        }
        Set<String> explanationFailed = new LinkedHashSet<>(failed);
        explanationFailed.removeAll(RATIONALE_CRITERIA);
        boolean failedA = explanationFailed.stream().anyMatch(c -> c.startsWith("A"));

        List<String> detail = new ArrayList<>(reasons);
        form.forEach(i -> detail.add("코드 검사 [%s] %s".formatted(i.criterion(), i.message())));
        String label = String.join("·", explanationFailed);

        // 오답 설명 고침부터 본다. 고친 해설의 재검사(C2)에 함께 반영해야 한다.
        // 오답 설명은 근거 줄을 요구하지 않는다. 길이·보기 검사로 충분하고 옛 글이 지적에 남는다
        List<GeneratedChoice> choices = new ArrayList<>(item.choices());
        if (e.rewrittenRationales() != null) {
            for (RationaleFix fix : e.rewrittenRationales()) {
                int idx = fix.choiceNo() - 1;
                String text = fix.text() == null ? "" : fix.text().strip();
                if (idx < 0 || idx >= choices.size() || choices.get(idx).correct()
                        || text.length() < ProblemItemRule.RATIONALE_MIN || text.equals(blankToEmpty(choices.get(idx).rationale()).strip())) {
                    continue;
                }
                GeneratedChoice old = choices.get(idx);
                choices.set(idx, new GeneratedChoice(old.text(), false, text, old.matchText()));
                out.add(new Finding(s.index(), FindingType.RATIONALE_REWRITTEN,
                        "AI가 %d번째 보기의 오답 설명을 고쳐 넣었습니다".formatted(idx + 1),
                        "옛 오답 설명: " + blankToEmpty(old.rationale()),
                        new Suggestion(FixTarget.RATIONALE, idx, text)));
            }
        }

        String original = blankToEmpty(item.explanation()).strip();
        String rewritten = e.rewrittenExplanation() == null ? "" : e.rewrittenExplanation().strip();
        if (!explanationFailed.isEmpty() && !rewritten.isEmpty() && !rewritten.equals(original)) {
            GeneratedProblemItem candidate = withExplanationAndChoices(item, rewritten, choices);
            String why = rejectionOf(candidate, slug, md, correctText(item));
            if (why == null) {
                out.add(new Finding(s.index(), FindingType.EXPLANATION_REWRITTEN,
                        "AI가 해설을 고쳐 넣었습니다 (기준 %s)".formatted(label),
                        joinDetail(detail, "옛 해설:\n" + original),
                        new Suggestion(FixTarget.EXPLANATION, null, rewritten)));
            } else {
                out.add(new Finding(s.index(), failedA ? FindingType.EXPLANATION_MISMATCH : FindingType.EXPLANATION_SUGGESTED,
                        "해설이 기준 %s에 걸렸습니다. AI가 고친 안은 넣지 않았습니다".formatted(label),
                        joinDetail(detail, "넣지 않은 이유: " + why),
                        new Suggestion(FixTarget.EXPLANATION, null, rewritten)));
            }
        } else if (!explanationFailed.isEmpty() && !original.isEmpty()) {
            out.add(new Finding(s.index(), failedA ? FindingType.EXPLANATION_MISMATCH : FindingType.EXPLANATION_SUGGESTED,
                    "해설이 기준 %s에 걸렸지만 고친 글이 없습니다".formatted(label),
                    joinDetail(detail, null)));
        }

        String questionFix = e.questionFix() == null ? "" : e.questionFix().strip();
        boolean revealed = solve != null && QuoteMatch.in(solve.answerInQuestionQuote(), item.question());
        if (revealed && !questionFix.isEmpty() && !questionFix.equals(item.question().strip())
                && ProblemItemRule.defectOf(withQuestion(item, questionFix), ProblemType.MULTIPLE_CHOICE) == null) {
            out.add(new Finding(s.index(), FindingType.QUESTION_REVEALS,
                    "질문 안에 정답이 그대로 적혀 있습니다",
                    "질문의 구절: 「%s」".formatted(solve.answerInQuestionQuote().strip()),
                    new Suggestion(FixTarget.QUESTION, null, questionFix)));
        }

        if (e.exhibitIssue() != null && !e.exhibitIssue().isBlank()) {
            out.add(new Finding(s.index(), FindingType.EXHIBIT_MISMATCH,
                    "자료가 사실과 다르거나 정답을 뒷받침하지 않습니다", e.exhibitIssue().strip()));
        }
    }

    /** 근거 줄 중 「」 인용이 모두 해설·오답 설명에 실제로 있는 줄만 남긴다 */
    private static List<String> trustedReasons(String criteriaReason, GeneratedProblemItem item) {
        if (criteriaReason == null || criteriaReason.isBlank()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        texts.add(item.explanation());
        item.choices().forEach(c -> texts.add(c.rationale()));
        String[] haystack = texts.toArray(String[]::new);
        List<String> kept = new ArrayList<>();
        for (String line : criteriaReason.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            Matcher m = QUOTE.matcher(line);
            boolean any = false;
            boolean allFound = true;
            while (m.find()) {
                any = true;
                allFound &= QuoteMatch.in(m.group(1), haystack);
            }
            if (any && allFound) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** @return 넣어도 되면 {@code null}, 아니면 이유 */
    private static String rejectionOf(GeneratedProblemItem candidate, String slug, String md, String answerText) {
        List<FormIssue> issues = ExplanationFormRule.issuesOf(candidate, slug, md);
        if (!issues.isEmpty()) {
            return issues.stream().map(i -> "[%s] %s".formatted(i.criterion(), i.message()))
                    .collect(Collectors.joining(", "));
        }
        if (!conclusionPointsTo(candidate.explanation(), answerText)) {
            return "\"그래서\" 줄이 정답 보기의 낱말을 담지 않음";
        }
        String defect = ProblemItemRule.defectOf(candidate, ProblemType.MULTIPLE_CHOICE);
        return defect;
    }

    /** 마지막 "그래서" 줄이 정답 보기 글의 낱말(2자 이상, 조사 뗀 것) 하나 이상을 담는가 */
    static boolean conclusionPointsTo(String explanation, String answerText) {
        String conclusion = explanation.strip().lines().map(String::strip)
                .filter(l -> l.startsWith("그래서")).reduce((a, b) -> b).orElse("");
        Matcher m = WORD.matcher(answerText);
        while (m.find()) {
            String word = stripJosa(m.group());
            if (word.length() >= 2 && conclusion.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String stripJosa(String word) {
        for (String j : JOSA) {
            if (word.length() > j.length() + 1 && word.endsWith(j)) {
                return word.substring(0, word.length() - j.length());
            }
        }
        return word;
    }

    private static String correctText(GeneratedProblemItem item) {
        return item.choices().stream().filter(GeneratedChoice::correct).map(GeneratedChoice::text).findFirst().orElse("");
    }

    private static GeneratedProblemItem withExplanationAndChoices(GeneratedProblemItem p, String explanation,
                                                                  List<GeneratedChoice> choices) {
        return new GeneratedProblemItem(p.question(), p.exhibit(), p.answer(), explanation, choices, p.sourceQuote(),
                p.title(), p.questionKind(), p.skipReason());
    }

    private static GeneratedProblemItem withQuestion(GeneratedProblemItem p, String question) {
        return new GeneratedProblemItem(question, p.exhibit(), p.answer(), p.explanation(), p.choices(), p.sourceQuote(),
                p.title(), p.questionKind(), p.skipReason());
    }

    private static String joinDetail(List<String> lines, String tail) {
        List<String> all = new ArrayList<>(lines);
        if (tail != null) {
            all.add(tail);
        }
        return all.isEmpty() ? null : String.join("\n", all);
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }
}
