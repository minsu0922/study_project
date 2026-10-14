package project.study.study_project.llm.support;

import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실행 검증을 통과한 코드 문제의 글을 실행 결과와 맞춰 본다. AI를 부르지 않는다.
 *
 * <p>{@link CodeVerifier}는 정답이 맞는지만 본다. 질문·해설·오답 이유에 적힌 값은 보지 않아서,
 * 정답은 맞는데 "1이 출력된다"고 적힌 오답 이유가 실제로는 0을 내도 그대로 들어온다.
 *
 * <p>글에서 값을 꺼내는 일은 규칙으로 한다. 그래서 숫자·따옴표 친 문자열·true/false/null처럼
 * 값이 분명한 경우만 본다. "올바른 값을 출력한다" 같은 말은 건드리지 않는다.
 * 지적은 검수함에 달리고 들여오기는 막지 않는다. 규칙이 틀려도 문제를 잃지 않게 하려는 것이다.
 */
public final class CodeFactChecker {

    private static final String VALUE = "(?<![\\w.])(\"[^\"\\n]*\"|“[^”\\n]*”|「[^」\\n]*」|'[^'\\n]*'|-?\\d+(?:\\.\\d+)?|true|false|null)";
    private static final String PARTICLE = "\\s*(?:을|를|이|가)\\s*출력";
    /** 정답일 때 나올 출력. "2를 출력해야", "\"같다\"가 출력되도록" */
    private static final Pattern QUESTION_EXPECTED = Pattern.compile(VALUE + PARTICLE + "(?:해야|되도록|되게|하도록|하게|되는가|될까|되려면)");
    /** 고치기 전 코드가 지금 내는 출력. "1을 출력합니다" */
    private static final Pattern QUESTION_CURRENT = Pattern.compile(VALUE + PARTICLE + "(?:합니다|한다|됩니다|된다|하는데|되는데|하고|되고)");
    private static final Pattern CLAIMED_OUTPUT = Pattern.compile(VALUE + PARTICLE + "(?:된다|됩니다|한다|합니다|되어|돼|되고|하고)");
    private static final Pattern LINE_REF = Pattern.compile("(\\d+)행");
    private static final Pattern EXCEPTION_NAME = Pattern.compile("\\b([A-Z]\\w*(?:Exception|Error))\\b");
    private static final Pattern COMPILE_FAILS = Pattern.compile("컴파일되지 않|컴파일 ?오류|컴파일 ?에러|컴파일이 안");
    /** 오답 이유의 앞 문장은 학습자의 오해를 옮긴 것이다. 틀린 말이 맞으니 사실 대조에서 뺀다. */
    private static final Pattern MISBELIEF = Pattern.compile("오해|착각|잘못 알|생각했|믿었");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern LINE_COMMENT = Pattern.compile("//(.*)$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*(.*?)\\*/");
    private static final Pattern REVEALING_WORD = Pattern.compile(
            "[A-Z]\\w*(?:Exception|Error)|NPE|예외|오류|에러|버그|컴파일|정답|고쳐|고칠|틀린|틀렸");

    private final Function<String, CodeRunner.Result> runner;

    /** @param runner {@link CodeVerifier}와 같은 것을 주면 같은 코드를 두 번 돌리지 않게 묶어 쓸 수 있다 */
    public CodeFactChecker(Function<String, CodeRunner.Result> runner) {
        this.runner = runner;
    }

    /** @param index 지적에 적을 문제 번호. 파일 안 위치다 */
    public List<Finding> findingsOf(int index, Item item) {
        List<Finding> findings = new ArrayList<>();
        int lines = (int) item.code().strip().lines().count();
        lineRefFinding(index, FindingType.EXPLANATION_MISMATCH, "해설이 코드에 없는 줄을 가리킵니다",
                item.explanation(), lines, findings);
        lineRefFinding(index, FindingType.QUESTION_MISMATCH, "질문이 코드에 없는 줄을 가리킵니다",
                item.question(), lines, findings);
        if (item.kind().substitutesChoice()) {
            questionFindings(index, item, findings);
            rationaleFindings(index, item, findings);
        }
        commentFinding(index, item, findings);
        return findings;
    }

    /**
     * AI 검수에 넘길 실행 결과. 키를 쥔 단계는 코드를 돌리지 않으므로, 여기서 글로 적어 넘긴다.
     * 빈칸·고치기는 보기마다 넣어 돌린 결과까지 적는다. 오답 이유를 견주려면 그게 있어야 한다.
     */
    public String runFacts(Item item) {
        List<String> facts = new ArrayList<>();
        if (item.kind().substitutesChoice()) {
            if (item.kind() == Kind.CODE_FIX) {
                facts.add("- 고치기 전 코드: " + describeFully(runner.apply(item.code())));
            }
            String verb = item.kind() == Kind.CODE_FIX ? "%d번%s으로 바꾸면" : "%d번%s을 넣으면";
            for (int i = 0; i < item.choices().size(); i++) {
                Choice choice = item.choices().get(i);
                facts.add("- " + verb.formatted(i + 1, choice.correct() ? "(정답)" : "") + ": "
                        + describeFully(runner.apply(CodeVerifier.codeWith(item, choice))));
            }
        } else {
            facts.add("- 이 코드: " + describeFully(runner.apply(item.code())));
        }
        return String.join("\n", facts);
    }

    /** 예외로 끝나도 그 전에 나온 출력은 적는다. 해설이 그 출력을 말할 수 있다. */
    private static String describeFully(CodeRunner.Result result) {
        String outcome = CodeVerifier.describe(result);
        if (result.compiled() && !result.finishedCleanly() && !result.stdout().isEmpty()) {
            return "출력 「%s」 뒤 %s".formatted(CodeVerifier.oneLine(result.stdout()), outcome);
        }
        return outcome;
    }

    private static void lineRefFinding(int index, FindingType type, String message, String text, int lines,
                                       List<Finding> findings) {
        Set<Integer> outside = new LinkedHashSet<>();
        Matcher m = LINE_REF.matcher(text == null ? "" : text);
        while (m.find()) {
            int line = Integer.parseInt(m.group(1));
            if (line < 1 || line > lines) {
                outside.add(line);
            }
        }
        if (!outside.isEmpty()) {
            findings.add(new Finding(index, type, message,
                    "%s — 코드는 %d줄입니다".formatted(String.join(", ", outside.stream().map(l -> l + "행").toList()), lines)));
        }
    }

    private void questionFindings(int index, Item item, List<Finding> findings) {
        List<String> details = new ArrayList<>();
        String expected = CodeRunner.normalize(item.expectedOutput());
        for (String value : valuesOf(QUESTION_EXPECTED, item.question())) {
            if (!printed(value, expected)) {
                details.add("질문은 정답일 때 「%s」가 나온다고 하지만, 정답을 넣은 실제 출력은 「%s」입니다"
                        .formatted(value, CodeVerifier.oneLine(expected)));
            }
        }
        if (item.kind() == Kind.CODE_FIX) {
            List<String> current = valuesOf(QUESTION_CURRENT, item.question());
            if (!current.isEmpty()) {
                CodeRunner.Result original = runner.apply(item.code());
                for (String value : current) {
                    if (!original.finishedCleanly() || !printed(value, original.stdout())) {
                        details.add("질문은 지금 코드가 「%s」를 낸다고 하지만, 실제로는 %s입니다"
                                .formatted(value, CodeVerifier.describe(original)));
                    }
                }
            }
        }
        if (!details.isEmpty()) {
            findings.add(new Finding(index, FindingType.QUESTION_MISMATCH, "질문에 적힌 출력이 실제 실행과 다릅니다",
                    String.join("\n", details)));
        }
    }

    /** 빈칸·고치기의 오답은 실제로 넣어 돌려 볼 수 있다. 오답 이유가 말한 결과와 견준다. */
    private void rationaleFindings(int index, Item item, List<Finding> findings) {
        List<String> details = new ArrayList<>();
        for (int i = 0; i < item.choices().size(); i++) {
            Choice choice = item.choices().get(i);
            if (choice.correct() || choice.rationale() == null || choice.rationale().isBlank()) {
                continue;
            }
            String facts = factualPart(choice.rationale());
            if (facts.isEmpty()) {
                continue;
            }
            CodeRunner.Result result = runner.apply(CodeVerifier.codeWith(item, choice));
            String wrong = claimMismatch(facts, result);
            if (wrong != null) {
                details.add("%d번 「%s」: 이유는 %s, 실제는 %s".formatted(
                        i + 1, choice.text().strip(), wrong, CodeVerifier.describe(result)));
            }
        }
        if (!details.isEmpty()) {
            findings.add(new Finding(index, FindingType.RATIONALE_MISMATCH, "오답 이유가 그 보기의 실제 실행과 다릅니다",
                    String.join("\n", details)));
        }
    }

    /** @return 어긋난 주장. 맞거나 확인할 주장이 없으면 {@code null} */
    static String claimMismatch(String facts, CodeRunner.Result result) {
        if (COMPILE_FAILS.matcher(facts).find()) {
            return result.compiled() ? "'컴파일되지 않는다'" : null;
        }
        Matcher exception = EXCEPTION_NAME.matcher(facts);
        if (exception.find() && !exception.group(1).equals(result.exceptionType())) {
            return "'%s'".formatted(exception.group(1));
        }
        for (String value : valuesOf(CLAIMED_OUTPUT, facts)) {
            if (!result.compiled() || !printed(value, result.stdout())) {
                return "'「%s」 출력'".formatted(value);
            }
        }
        return null;
    }

    /** 오해를 옮긴 문장을 뺀 나머지. 예: "X라고 오해했다. 그래서 1이 출력된다." → "그래서 1이 출력된다." */
    static String factualPart(String rationale) {
        List<String> kept = new ArrayList<>();
        for (String sentence : rationale.strip().split("(?<=\\.)\\s+")) {
            if (!MISBELIEF.matcher(sentence).find()) {
                kept.add(sentence);
            }
        }
        return String.join(" ", kept);
    }

    private static void commentFinding(int index, Item item, List<Finding> findings) {
        List<String> code = item.code().strip().lines().toList();
        Set<String> outputLines = item.kind() == Kind.CODE_OUTPUT
                ? Set.copyOf(CodeRunner.normalize(item.expectedOutput()).lines().map(String::strip).toList())
                : Set.of();
        List<String> details = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            for (String comment : commentsOf(code.get(i))) {
                if (REVEALING_WORD.matcher(comment).find() || outputLines.contains(comment)) {
                    details.add("%d행: %s".formatted(i + 1, code.get(i).strip()));
                    break;
                }
            }
        }
        if (!details.isEmpty()) {
            findings.add(new Finding(index, FindingType.CODE_COMMENT_REVEALS, "코드 주석이 답의 실마리를 줍니다",
                    String.join("\n", details)));
        }
    }

    /** 한 줄 안의 주석. 문자열 안의 {@code //}(예: URL)는 주석이 아니라 먼저 지운다. */
    static List<String> commentsOf(String line) {
        String code = STRING_LITERAL.matcher(line).replaceAll("\"\"");
        List<String> comments = new ArrayList<>();
        Matcher block = BLOCK_COMMENT.matcher(code);
        while (block.find()) {
            comments.add(block.group(1).strip());
        }
        Matcher lineComment = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll(""));
        if (lineComment.find()) {
            comments.add(lineComment.group(1).strip());
        }
        return comments;
    }

    static List<String> valuesOf(Pattern pattern, String text) {
        List<String> values = new ArrayList<>();
        Matcher m = pattern.matcher(text == null ? "" : text);
        while (m.find()) {
            values.add(unquote(m.group(1)));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && "\"“「'".indexOf(value.charAt(0)) >= 0) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 여러 줄 출력이면 글이 그중 한 줄을 가리킬 수 있다. 한 줄로 이은 모양도 받는다. */
    private static boolean printed(String value, String stdout) {
        String output = CodeRunner.normalize(stdout);
        return output.equals(value) || CodeVerifier.oneLine(output).equals(value)
                || output.lines().map(String::strip).anyMatch(value::equals);
    }
}
