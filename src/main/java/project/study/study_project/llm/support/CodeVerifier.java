package project.study.study_project.llm.support;

import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;

import java.util.List;
import java.util.function.Function;

/**
 * 코드 문제의 정답을 실행 결과로 확인한다.
 *
 * <p>모델은 실행 결과를 머릿속으로 계산하다 틀린다(정수 나눗셈, 초기화 순서, 캐시 범위).
 * 그 틀린 정답은 그럴듯해서 사람 검수도 놓친다. 그래서 코드를 실제로 돌려 표시된 정답과 견준다.
 *
 * <p>빈칸·고치기 유형은 <b>오답 보기도 하나씩 넣어 돌린다</b>. 오답을 넣었는데 같은 출력이 나오면
 * 정답이 둘인 문제다. 같은 뜻의 코드가 여럿인 유형이라 이 확인이 없으면 낼 수 없다.
 */
public final class CodeVerifier {

    /** 여러 줄 출력을 보기 한 줄에 적을 때 줄 사이에 넣는 표시. 생성 프롬프트와 같은 약속이다. */
    public static final String LINE_JOINER = " / ";

    private final Function<String, CodeRunner.Result> runner;

    public CodeVerifier(Function<String, CodeRunner.Result> runner) {
        this.runner = runner;
    }

    /** @return 이 문제를 버려야 하는 이유. 확인을 통과하면 {@code null} */
    public String defectOf(Item item) {
        return switch (item.kind()) {
            case CODE_OUTPUT -> outputDefect(item);
            case CODE_BLANK -> substitutionDefect(item, choice -> codeWith(item, choice));
            case CODE_FIX -> fixDefect(item);
            case CODE_ERROR -> errorDefect(item);
            case CODE_COMPILE -> compileDefect(item);
        };
    }

    private String outputDefect(Item item) {
        CodeRunner.Result result = runner.apply(item.code());
        String problem = cleanRunDefect(result);
        if (problem != null) {
            return problem;
        }
        String expected = CodeRunner.normalize(item.expectedOutput());
        if (!result.stdout().equals(expected)) {
            return "실제 출력이 예상과 다르다 — 예상 「%s」, 실제 「%s」".formatted(oneLine(expected), oneLine(result.stdout()));
        }
        String answer = correctOf(item).text().strip();
        if (!answer.equals(oneLine(result.stdout()))) {
            return "정답 보기가 실제 출력과 다르다 — 보기 「%s」, 실제 「%s」".formatted(answer, oneLine(result.stdout()));
        }
        return null;
    }

    /** 보기를 코드에 끼워 넣는 유형. 정답은 예상 출력을 내야 하고, 오답은 내면 안 된다. */
    private String substitutionDefect(Item item, Function<Choice, String> codeWith) {
        String expected = CodeRunner.normalize(item.expectedOutput());
        for (int i = 0; i < item.choices().size(); i++) {
            Choice choice = item.choices().get(i);
            CodeRunner.Result result = runner.apply(codeWith.apply(choice));
            boolean producesExpected = result.finishedCleanly() && result.stdout().equals(expected);
            if (choice.correct() && !producesExpected) {
                return "정답을 넣어도 예상 출력이 안 나온다 — %s".formatted(describe(result));
            }
            if (!choice.correct() && producesExpected) {
                return "%d번 오답을 넣어도 같은 출력이 나온다(정답이 둘)".formatted(i + 1);
            }
        }
        return null;
    }

    private String fixDefect(Item item) {
        // 고치기 전 코드가 이미 기대 출력을 내면 고칠 것이 없는 문제다
        CodeRunner.Result original = runner.apply(item.code());
        if (original.finishedCleanly() && original.stdout().equals(CodeRunner.normalize(item.expectedOutput()))) {
            return "고치기 전 코드가 이미 기대 출력을 낸다";
        }
        return substitutionDefect(item, choice -> codeWith(item, choice));
    }

    /**
     * 보기를 끼워 넣은 코드. 빈칸·고치기 유형에서만 뜻이 있다.
     * {@link CodeFactChecker}도 이 함수로 만든다. 글자가 같아야 실행 결과를 다시 쓸 수 있다.
     */
    static String codeWith(Item item, Choice choice) {
        return item.kind() == CodeProblem.Kind.CODE_BLANK
                ? item.code().replace(CodeProblem.BLANK, choice.text().strip())
                : replaceLine(item.code(), item.targetLine(), choice.text());
    }

    private String errorDefect(Item item) {
        CodeRunner.Result result = runner.apply(item.code());
        if (!result.compiled()) {
            return "컴파일되지 않는다(%d행)".formatted(result.compileErrorLine());
        }
        if (result.timedOut()) {
            return "시간 안에 끝나지 않는다";
        }
        String type = item.errorType() == null ? "" : item.errorType().strip();
        if (!result.exceptionType().equals(type) || result.exceptionLine() != item.targetLine()) {
            return "예외가 예상과 다르다 — 예상 %s, 실제 %s".formatted(
                    describeError(type, item.targetLine()), describeError(result.exceptionType(), result.exceptionLine()));
        }
        // 보기는 자유 문장이라 뜻까지 대조하지 못한다. 정답 보기가 그 예외 이름과 줄 번호를 담았는지만 본다
        String answer = correctOf(item).text();
        if (!type.isEmpty() && !(answer.contains(type) && answer.contains(item.targetLine() + "행"))) {
            return "정답 보기에 예외 이름과 줄 번호가 없다 — 「%s」".formatted(answer);
        }
        return null;
    }

    private String compileDefect(Item item) {
        CodeRunner.Result result = runner.apply(item.code());
        if (result.compileErrorLine() != item.targetLine()) {
            return "컴파일 결과가 예상과 다르다 — 예상 %s, 실제 %s".formatted(
                    describeCompile(item.targetLine()), describeCompile(result.compileErrorLine()));
        }
        String answer = correctOf(item).text();
        if (item.targetLine() > 0 && !answer.contains(item.targetLine() + "행")) {
            return "정답 보기에 오류가 나는 줄 번호가 없다 — 「%s」".formatted(answer);
        }
        return null;
    }

    private static String cleanRunDefect(CodeRunner.Result result) {
        return result.finishedCleanly() ? null : "코드가 제대로 끝나지 않는다 — " + describe(result);
    }

    /** 들여쓰기는 원래 줄의 것을 쓴다. 보기에는 들여쓰기 없이 코드만 적힌다. */
    static String replaceLine(String code, int line, String replacement) {
        List<String> lines = new java.util.ArrayList<>(code.strip().lines().toList());
        String original = lines.get(line - 1);
        String indent = original.substring(0, original.length() - original.stripLeading().length());
        lines.set(line - 1, indent + replacement.strip());
        return String.join("\n", lines);
    }

    private static Choice correctOf(Item item) {
        return item.choices().stream().filter(Choice::correct).findFirst().orElseThrow();
    }

    static String oneLine(String output) {
        return String.join(LINE_JOINER, output.lines().toList());
    }

    static String describe(CodeRunner.Result result) {
        if (!result.compiled()) {
            return "컴파일 오류(%d행)".formatted(result.compileErrorLine());
        }
        if (result.timedOut()) {
            return "시간 초과";
        }
        if (!result.exceptionType().isEmpty()) {
            return describeError(result.exceptionType(), result.exceptionLine());
        }
        return "출력 「%s」".formatted(oneLine(result.stdout()));
    }

    private static String describeError(String type, int line) {
        return type.isEmpty() ? "예외 없음" : "%d행 %s".formatted(line, type);
    }

    private static String describeCompile(int line) {
        return line == 0 ? "컴파일됨" : "%d행 오류".formatted(line);
    }
}
