package project.study.study_project.llm.support;

import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 코드 문제를 실행해 보기 전에 모양과 소재를 거른다.
 *
 * <p>실행 검증이 뒤에 있는데 여기서 먼저 거르는 이유는 둘이다. 모양이 틀린 문제는 돌려 볼 수조차 없고,
 * 금지 소재는 <b>돌리기 전에</b> 막아야 한다. 배치는 모델이 쓴 코드를 실제로 실행한다.
 */
public final class CodeProblemRule {

    /** 화면 한 장에 들어올 길이. 넘으면 문제가 아니라 과제가 된다. */
    static final int MAX_LINES = 30;

    private static final Pattern MAIN_CLASS = Pattern.compile("\\bclass\\s+Main\\b");

    /**
     * 코드에 나오면 안 되는 것과 그 이유. 앞의 넷은 실행할 때마다 결과가 달라지는 소재이고,
     * 나머지는 문제를 내는 데 필요 없으면서 실행 환경을 건드릴 수 있는 것이다.
     */
    private static final List<Banned> BANNED = List.of(
            new Banned("\\bThread\\b|\\bExecutor|\\bCompletableFuture\\b|\\bparallel", "스레드 실행 순서는 실행마다 달라진다"),
            new Banned("\\bRandom\\b|Math\\.random|\\bUUID\\b", "난수는 실행마다 달라진다"),
            new Banned("currentTimeMillis|nanoTime|\\.now\\(|new\\s+Date\\b", "현재 시각은 실행마다 달라진다"),
            new Banned("\\bHashMap\\b|\\bHashSet\\b|identityHashCode", "순회 순서와 해시값은 정해져 있지 않다"),
            new Banned("System\\.in\\b|\\bScanner\\b|\\bConsole\\b", "입력을 받는 코드는 실행 검증에서 멈춘다"),
            new Banned("java\\.io\\.|java\\.nio\\.|java\\.net\\.|\\bFiles?\\b|\\bSocket\\b|\\bURL\\b", "파일과 네트워크는 쓰지 않는다"),
            new Banned("\\bRuntime\\b|ProcessBuilder|System\\.exit|System\\.getenv|System\\.getProperty|"
                    + "java\\.lang\\.reflect|\\.getDeclared|setAccessible|\\bClassLoader\\b|\\bUnsafe\\b",
                    "실행 환경을 건드리는 코드는 쓰지 않는다"));

    private record Banned(String regex, String reason) {
        boolean in(String code) {
            return Pattern.compile(regex).matcher(code).find();
        }
    }

    private CodeProblemRule() {
    }

    /** @return 이 문제를 버려야 하는 이유. 문제가 없으면 {@code null} */
    public static String defectOf(Item item) {
        if (item == null || item.kind() == null) {
            return "유형이 없다";
        }
        if (item.difficulty() == null) {
            return "난이도가 없다";
        }
        if (isBlank(item.question()) || isBlank(item.explanation())) {
            return "질문이나 해설이 비었다";
        }
        String shape = choiceDefectOf(item);
        if (shape != null) {
            return shape;
        }
        String code = item.code();
        if (isBlank(code)) {
            return "코드가 비었다";
        }
        if (!MAIN_CLASS.matcher(code).find() || !code.contains("void main")) {
            return "Main 클래스와 main 메서드가 없다";
        }
        int lines = lineCount(code);
        if (lines > MAX_LINES) {
            return "코드가 %d줄이다(기준 %d줄)".formatted(lines, MAX_LINES);
        }
        for (Banned banned : BANNED) {
            if (banned.in(code) || item.choices().stream().anyMatch(c -> banned.in(c.text()))) {
                return "금지 소재: " + banned.reason();
            }
        }
        return kindDefectOf(item, lines);
    }

    private static String choiceDefectOf(Item item) {
        if (item.choices() == null || item.choices().size() != 4) {
            return "보기가 4개가 아니다";
        }
        if (item.choices().stream().anyMatch(c -> c == null || isBlank(c.text()))) {
            return "빈 보기가 있다";
        }
        if (item.choices().stream().filter(CodeProblem.Choice::correct).count() != 1) {
            return "정답이 1개가 아니다";
        }
        if (item.choices().stream().map(c -> c.text().strip()).distinct().count() != 4) {
            return "같은 보기가 있다";
        }
        return null;
    }

    private static String kindDefectOf(Item item, int lines) {
        Kind kind = item.kind();
        long blanks = countOf(item.code(), CodeProblem.BLANK);
        if (kind == Kind.CODE_BLANK ? blanks != 1 : blanks != 0) {
            return kind == Kind.CODE_BLANK ? "빈칸 표시(____)가 정확히 하나가 아니다" : "빈칸 유형이 아닌데 빈칸 표시가 있다";
        }
        boolean needsOutput = kind == Kind.CODE_OUTPUT || kind.substitutesChoice();
        if (needsOutput && isBlank(item.expectedOutput())) {
            return "예상 출력이 비었다";
        }
        if (item.targetLine() < 0 || item.targetLine() > lines) {
            return "줄 번호 %d가 코드 범위(%d줄) 밖이다".formatted(item.targetLine(), lines);
        }
        if (kind == Kind.CODE_FIX && item.targetLine() == 0) {
            return "바꿀 줄 번호가 없다";
        }
        // 예외가 난다고 해 놓고 줄이 없거나, 줄은 있는데 예외 이름이 없으면 대조할 값이 반쪽이다
        if (kind == Kind.CODE_ERROR && (item.targetLine() == 0) != isBlank(item.errorType())) {
            return "예외가 나는 줄과 예외 이름이 짝이 맞지 않는다";
        }
        return null;
    }

    public static int lineCount(String code) {
        return (int) code.strip().lines().count();
    }

    private static long countOf(String text, String needle) {
        long n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
