package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행 결과와 글의 대조 — 코드를 실제로 돌린다. 2026-10-13 시험 생성에서 나온 문제 두 개를
 * 그대로 옮겨 "맞는 글에는 조용하다"를 보고, 값을 하나씩 틀리게 바꿔 "틀린 글은 잡는다"를 본다.
 */
class CodeFactCheckerTest {

    private final CodeFactChecker checker = new CodeFactChecker(new CodeRunner()::run);

    private static final String PAIRS_CODE = """
            import java.util.*;

            public class Main {
                public static void main(String[] args) {
                    List<Integer> list = new ArrayList<>();
                    list.add(200);
                    list.add(200);
                    list.add(5);
                    list.add(5);
                    int same = 0;
                    for (int i = 0; i + 1 < list.size(); i += 2) {
                        if (list.get(i) == list.get(i + 1)) {
                            same++;
                        }
                    }
                    System.out.println(same);
                }
            }""";

    private static Item fix(String question, String valueOfRationale) {
        return new Item(Kind.CODE_FIX, Difficulty.ADVANCED, "리스트 속 Integer 쌍 비교 고치기", question, PAIRS_CODE, "2", 12, "",
                List.of(new Choice("if (list.get(i).equals(list.get(i + 1))) {", true, ""),
                        new Choice("if (Integer.valueOf(list.get(i)) == Integer.valueOf(list.get(i + 1))) {", false,
                                valueOfRationale),
                        new Choice("if ((Object) list.get(i) == list.get(i + 1)) {", false,
                                "Object로 바꾸면 비교 방식이 달라진다고 오해했다. 여전히 참조 비교라 1이 출력된다."),
                        new Choice("if (list.get(i).equals(list.get(i + 1)) == false) {", false,
                                "equals 결과를 뒤집어 버린 것이다. 같은 쌍을 세지 않아 0이 출력된다.")),
                "6행~9행에서 값이 들어가고, 12행의 ==는 참조 비교라 16행에서 1이 출력된다.");
    }

    private static final String FIX_QUESTION =
            "이 코드는 이웃한 두 값이 같은 쌍의 개수로 2를 출력해야 하지만 1을 출력합니다. 12행을 어떻게 고쳐야 2가 출력되는가?";
    private static final String VALUE_OF_RATIONALE =
            "valueOf로 감싸면 값 비교가 된다고 오해했다. 200은 캐시 밖이라 여전히 참조가 달라 1이 출력된다.";

    @Test
    @DisplayName("시험 생성에서 나온 맞는 문제에는 아무 지적도 하지 않는다")
    void staysQuietOnCorrectProblems() {
        Item blank = new Item(Kind.CODE_BLANK, Difficulty.BEGINNER, "두 문자열의 내용 비교 조건",
                "실행했을 때 \"같다\"가 출력되도록 빈칸에 들어갈 코드로 알맞은 것은?", """
                public class Main {
                    public static void main(String[] args) {
                        String s1 = new String("hello");
                        String s2 = new String("hello");
                        if (____) {
                            System.out.println("같다");
                        } else {
                            System.out.println("다르다");
                        }
                    }
                }""", "같다", 0, "",
                List.of(new Choice("s1.equals(s2)", true, ""),
                        new Choice("s1 == s2", false, "==이 문자열 내용을 비교한다고 오해했다. 서로 다른 객체라 \"다르다\"가 출력된다."),
                        new Choice("s1 = s2", false, "대입식도 조건이 된다고 오해했다. String은 boolean이 아니어서 컴파일되지 않는다."),
                        new Choice("s1.compareTo(s2)", false, "compareTo가 boolean을 돌려준다고 오해했다. int를 돌려주므로 컴파일되지 않는다.")),
                "5행에서 s1 == s2는 false라 8행의 \"다르다\"가 출력된다.");

        assertThat(checker.findingsOf(0, blank)).isEmpty();
        assertThat(checker.findingsOf(0, fix(FIX_QUESTION, VALUE_OF_RATIONALE))).isEmpty();
    }

    @Test
    @DisplayName("질문이 말하는 지금 출력과 오답 이유가 말하는 결과를 실제 실행과 견준다")
    void catchesWrongValuesInQuestionAndRationale() {
        Item item = fix(FIX_QUESTION.replace("1을 출력합니다", "0을 출력합니다"),
                "valueOf로 감싸면 값 비교가 된다고 오해했다. 그래도 2가 출력된다.");

        List<Finding> findings = checker.findingsOf(3, item);

        assertThat(findings).extracting(Finding::problemIndex).containsOnly(3);
        assertThat(findings).filteredOn(f -> f.type() == FindingType.QUESTION_MISMATCH).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("「0」").contains("1"));
        assertThat(findings).filteredOn(f -> f.type() == FindingType.RATIONALE_MISMATCH).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith("2번").contains("「2」").contains("출력 「1」"));
    }

    @Test
    @DisplayName("컴파일되는 오답을 '컴파일되지 않는다'고 하면 잡는다")
    void catchesFalseCompileClaim() {
        Item item = fix(FIX_QUESTION, "valueOf로 감싸면 값 비교가 된다고 오해했다. valueOf는 Integer를 받지 않아 컴파일되지 않는다.");

        assertThat(checker.findingsOf(0, item)).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.RATIONALE_MISMATCH);
            assertThat(f.detail()).contains("컴파일되지 않는다");
        });
    }

    @Test
    @DisplayName("해설이 없는 줄을 가리키면 잡는다")
    void catchesLineOutsideCode() {
        Item ok = fix(FIX_QUESTION, VALUE_OF_RATIONALE);
        Item item = new Item(ok.kind(), ok.difficulty(), ok.title(), ok.question(), ok.code(), ok.expectedOutput(),
                ok.targetLine(), ok.errorType(), ok.choices(), "12행을 고치면 21행에서 2가 출력된다.");

        assertThat(checker.findingsOf(0, item)).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.EXPLANATION_MISMATCH);
            assertThat(f.detail()).isEqualTo("21행 — 코드는 18줄입니다");
        });
    }

    @Test
    @DisplayName("답을 알려 주는 주석은 잡고, 문자열 속 // 는 주석으로 보지 않는다")
    void catchesRevealingComments() {
        Item item = new Item(Kind.CODE_ERROR, Difficulty.INTERMEDIATE, "null 언박싱", "다음 코드를 실행하면 어떻게 되는가?", """
                public class Main {
                    public static void main(String[] args) {
                        String url = "http://example.com";
                        Integer n = null;
                        int m = n; // 여기서 예외
                        System.out.println(url + m);
                    }
                }""", "", 5, "NullPointerException",
                List.of(new Choice("5행에서 NullPointerException이 난다", true, ""),
                        new Choice("6행에서 NullPointerException이 난다", false, "a"),
                        new Choice("예외 없이 끝난다", false, "b"),
                        new Choice("컴파일되지 않는다", false, "c")),
                "5행에서 null을 언박싱한다.");

        assertThat(checker.findingsOf(0, item)).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.CODE_COMMENT_REVEALS);
            assertThat(f.detail()).isEqualTo("5행: int m = n; // 여기서 예외");
        });
        assertThat(CodeFactChecker.commentsOf("String url = \"http://example.com\";")).isEmpty();
    }

    @Test
    @DisplayName("오답 이유의 오해 문장은 틀린 말이 맞으니 대조에서 뺀다")
    void ignoresMisbeliefSentence() {
        assertThat(CodeFactChecker.factualPart("없는 키를 get하면 NullPointerException이 난다고 오해했다. get은 null을 돌려준다."))
                .isEqualTo("get은 null을 돌려준다.");
    }
}
