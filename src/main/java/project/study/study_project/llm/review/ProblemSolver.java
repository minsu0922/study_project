package project.study.study_project.llm.review;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.Prompts;
import project.study.study_project.llm.client.SourceDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 풀이 호출 — 정답 표시와 해설을 가린 채 근거 문서로 문제를 푼다. 고른 번호는 코드가 표시와 견준다.
 * AI에게 "표시된 정답이 맞나"를 물으면 표시를 따라가는 쪽으로 기울어서다.
 */
public final class ProblemSolver {

    static final String PROMPT = Prompts.load("problem-review/solve.txt");

    @JsonClassDescription("문서를 보고 문제 하나를 직접 푼 결과")
    public record Solve(
            @JsonPropertyDescription("문제 번호. 입력으로 준 번호를 그대로 돌려준다")
            int problemNo,
            @JsonPropertyDescription("이 문제를 직접 푼 과정. 어느 보기가 왜 맞는지")
            String answerReason,
            @JsonPropertyDescription("정답으로 고른 보기 번호 1~4")
            int chosenNo,
            @JsonPropertyDescription("고른 답 말고도 이 문제의 조건에서 정답으로 방어할 수 있는 보기 번호. 없으면 0")
            int otherCorrectNo,
            @JsonPropertyDescription("otherCorrectNo를 적었다면 그 보기도 맞는 이유. 없으면 빈 문자열")
            String otherCorrectReason,
            @JsonPropertyDescription("정답을 뒷받침하는 문서 문장 하나. 문서에서 글자 그대로 복사한다. 없으면 빈 문자열")
            String supportQuote,
            @JsonPropertyDescription("질문 안에 정답이 그대로 적혀 있으면 그 구절. 질문에서 글자 그대로 복사한다. 아니면 빈 문자열")
            String answerInQuestionQuote
    ) {
    }

    public record SolveBatch(
            @JsonPropertyDescription("문제별 풀이")
            List<Solve> solves
    ) {
    }

    private ProblemSolver() {
    }

    static String buildPrompt(List<Shown> shown, SourceDocument source) {
        StringBuilder sb = new StringBuilder("# 근거 문서\n").append(source.contentMd()).append("\n\n# 문제\n\n");
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            sb.append("[problemNo ").append(k + 1).append("]\n").append(s.item().questionWithExhibit()).append('\n');
            for (int n = 1; n <= 4; n++) {
                sb.append(n).append(") ").append(s.choiceText(n)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 풀이를 표시된 정답·문서와 견준다. 지적의 결론은 코드가 정한 문장, AI 글은 근거(detail)로 내린다 */
    static List<Finding> findingsOf(List<Shown> shown, SolveBatch batch, String documentMd) {
        Map<Integer, Solve> solves = byNo(batch);
        List<Finding> findings = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            Shown s = shown.get(k);
            Solve j = solves.get(k + 1);
            if (j == null) {
                continue;
            }
            int correct = s.correctNo();
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
            String quote = j.supportQuote() == null ? "" : j.supportQuote().strip();
            if (!QuoteMatch.in(quote, documentMd)) {
                findings.add(new Finding(s.index(), FindingType.NO_SUPPORT,
                        quote.isEmpty() ? "정답을 뒷받침하는 문장을 근거 문서에서 찾지 못했습니다"
                                : "검수 AI가 든 근거 문장이 문서에 없습니다(지어낸 인용)",
                        quote.isEmpty() ? null : "AI가 든 문장: 「%s」".formatted(quote)));
            }
            String revealed = j.answerInQuestionQuote() == null ? "" : j.answerInQuestionQuote().strip();
            if (QuoteMatch.in(revealed, s.item().question())) {
                findings.add(new Finding(s.index(), FindingType.QUESTION_REVEALS,
                        "질문 안에 정답이 그대로 적혀 있습니다",
                        "질문의 구절: 「%s」".formatted(revealed)));
            }
        }
        return findings;
    }

    static boolean solvedAsMarked(Shown s, Solve solve) {
        return solve != null && solve.chosenNo() == s.correctNo();
    }

    /** 같은 번호가 두 번 오면 앞의 것을 쓴다. 번호로 짝지으므로 순서가 밀려도 엉뚱한 문제에 붙지 않는다 */
    static Map<Integer, Solve> byNo(SolveBatch batch) {
        if (batch == null || batch.solves() == null) {
            return Map.of();
        }
        return batch.solves().stream().collect(Collectors.toMap(Solve::problemNo, Function.identity(), (x, y) -> x));
    }
}
