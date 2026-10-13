package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드 문제를 <b>실제로 컴파일하고 실행해</b> 정답을 확인하는지 본다. 가짜 실행기를 쓰지 않는다 —
 * 예외의 줄 번호, 컴파일 오류의 줄, 줄바꿈 같은 것은 진짜 JDK를 거쳐야만 맞는지 알 수 있다.
 */
class CodeVerifierTest {

    private final CodeVerifier verifier = new CodeVerifier(new CodeRunner()::run);

    private static String main(String... body) {
        StringBuilder sb = new StringBuilder("import java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n");
        for (String line : body) {
            sb.append("        ").append(line).append('\n');
        }
        return sb.append("    }\n}").toString();
    }

    private static Item item(Kind kind, String code, String expectedOutput, int targetLine, String errorType,
                             String... choices) {
        List<Choice> list = new java.util.ArrayList<>();
        for (int i = 0; i < choices.length; i++) {
            list.add(new Choice(choices[i], i == 0, i == 0 ? "" : "오해"));   // 첫 보기가 정답
        }
        return new Item(kind, Difficulty.BEGINNER, "제목", "질문", code, expectedOutput, targetLine, errorType,
                list, "해설");
    }

    /* ── 실행 결과 ── */

    @Test
    @DisplayName("실행 결과: 실제 출력이 예상·정답 보기와 같으면 통과하고, 모델이 계산을 틀렸으면 걸린다")
    void outputProblem() {
        String code = main("Integer a = 127, b = 127;", "Integer c = 128, d = 128;",
                "System.out.println(a == b);", "System.out.println(c == d);");

        assertThat(verifier.defectOf(item(Kind.CODE_OUTPUT, code, "true\nfalse", 0, "",
                "true / false", "true / true", "false / false", "컴파일 오류"))).isNull();
        assertThat(verifier.defectOf(item(Kind.CODE_OUTPUT, code, "true\ntrue", 0, "",
                "true / true", "true / false", "false / false", "컴파일 오류")))
                .as("128은 캐시 범위 밖이다").contains("실제 출력이 예상과 다르다");
        assertThat(verifier.defectOf(item(Kind.CODE_OUTPUT, code, "true\nfalse", 0, "",
                "true, false", "true / true", "false / false", "컴파일 오류")))
                .as("예상 출력은 맞지만 정답 보기를 다르게 적었다").contains("정답 보기가 실제 출력과 다르다");
    }

    /* ── 빈칸 코드 ── */

    @Test
    @DisplayName("빈칸 코드: 오답을 넣어도 같은 출력이 나오면 정답이 둘인 문제라 버린다")
    void blankProblem() {
        String code = main("List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5));",
                "list.removeIf(" + CodeProblem.BLANK + ");", "System.out.println(list);");

        assertThat(verifier.defectOf(item(Kind.CODE_BLANK, code, "[1, 2, 3]", 0, "",
                "n -> n > 3", "n -> n >= 3", "n -> n < 3", "n -> n == 3"))).isNull();
        assertThat(verifier.defectOf(item(Kind.CODE_BLANK, code, "[1, 2, 3]", 0, "",
                "n -> n > 3", "n -> n >= 4", "n -> n < 3", "n -> n == 3")))
                .as("n >= 4도 같은 결과다").contains("2번 오답을 넣어도 같은 출력");
        assertThat(verifier.defectOf(item(Kind.CODE_BLANK, code, "[1, 2, 3]", 0, "",
                "n -> n > 3", "n -> n >= 3", "n => n < 3", "n -> n == 3")))
                .as("컴파일되지 않는 오답은 괜찮다").isNull();
    }

    /* ── 한 줄 고치기 ── */

    @Test
    @DisplayName("한 줄 고치기: 그 줄을 정답으로 바꾸면 기대 출력이 나오고, 이미 맞는 코드면 버린다")
    void fixProblem() {
        String buggy = main("String a = new String(\"hi\");", "String b = new String(\"hi\");",
                "System.out.println(a == b);");   // 6행이 버그

        assertThat(verifier.defectOf(item(Kind.CODE_FIX, buggy, "true", 6, "",
                "System.out.println(a.equals(b));", "System.out.println(a.isEmpty());",
                "System.out.println(a == \"hi\");", "System.out.println(a.length() == 3);"))).isNull();
        assertThat(verifier.defectOf(item(Kind.CODE_FIX, buggy, "false", 6, "",
                "System.out.println(a.equals(b));", "System.out.println(a.isEmpty());",
                "System.out.println(a == \"hi\");", "System.out.println(a.length() == 3);")))
                .contains("고치기 전 코드가 이미 기대 출력을 낸다");
        assertThat(CodeVerifier.replaceLine(buggy, 6, "int x = 1;").lines().toList().get(5))
                .as("들여쓰기는 원래 줄의 것을 쓴다").isEqualTo("        int x = 1;");
    }

    /* ── 오류 지점 ── */

    @Test
    @DisplayName("오류 지점: 예외 이름과 줄이 실제와 같아야 한다. JDK 안에서 던져져도 호출한 줄로 본다")
    void errorProblem() {
        String code = main("List<Integer> list = new ArrayList<>(List.of(1, 2));", "int x = list.get(5);",
                "System.out.println(x);");   // 5행에서 IndexOutOfBoundsException

        assertThat(verifier.defectOf(item(Kind.CODE_ERROR, code, "", 5, "IndexOutOfBoundsException",
                "5행에서 IndexOutOfBoundsException이 난다", "4행에서 NullPointerException이 난다",
                "6행에서 ClassCastException이 난다", "예외 없이 끝난다"))).isNull();
        assertThat(verifier.defectOf(item(Kind.CODE_ERROR, code, "", 4, "NullPointerException",
                "4행에서 NullPointerException이 난다", "5행에서 IndexOutOfBoundsException이 난다",
                "6행에서 ClassCastException이 난다", "예외 없이 끝난다")))
                .contains("예외가 예상과 다르다", "실제 5행 IndexOutOfBoundsException");
        assertThat(verifier.defectOf(item(Kind.CODE_ERROR, main("System.out.println(1);"), "", 0, "",
                "예외 없이 끝난다", "4행에서 NullPointerException이 난다",
                "4행에서 ArithmeticException이 난다", "4행에서 ClassCastException이 난다"))).isNull();
    }

    /* ── 컴파일 여부 ── */

    @Test
    @DisplayName("컴파일 여부: 컴파일러가 말하는 첫 오류 줄과 같아야 한다")
    void compileProblem() {
        String broken = main("final int x = 1;", "x = 2;", "System.out.println(x);");   // 5행 재대입

        assertThat(verifier.defectOf(item(Kind.CODE_COMPILE, broken, "", 5, "",
                "5행에서 컴파일 오류가 난다", "4행에서 컴파일 오류가 난다", "6행에서 컴파일 오류가 난다", "컴파일된다")))
                .isNull();
        assertThat(verifier.defectOf(item(Kind.CODE_COMPILE, broken, "", 0, "",
                "컴파일된다", "4행에서 컴파일 오류가 난다", "5행에서 컴파일 오류가 난다", "6행에서 컴파일 오류가 난다")))
                .contains("예상 컴파일됨, 실제 5행 오류");
    }

    /* ── 실행기 ── */

    @Test
    @DisplayName("끝나지 않는 코드는 시간 제한에 걸리고, 자식 프로세스는 부모의 환경변수를 보지 못한다")
    void runnerLimitsAndIsolation() {
        CodeRunner runner = new CodeRunner();

        assertThat(runner.run(main("while (true) { }")).timedOut()).isTrue();
        // PATH는 어떤 환경에서도 부모에게 있다. 자식에게 넘어가지 않았는지로 환경을 비웠는지 본다
        assertThat(runner.run(main("System.out.println(System.getenv(\"PATH\") == null && System.getenv(\"Path\") == null);")).stdout())
                .isEqualTo("true");
    }
}
