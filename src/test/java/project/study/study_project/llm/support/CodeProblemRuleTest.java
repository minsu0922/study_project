package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeCodeProblemGenerator;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 코드 문제를 돌려 보기 전에 거르는 규칙. 금지 소재는 실행 전에 막아야 한다. */
class CodeProblemRuleTest {

    private static final String CODE = """
            public class Main {
                public static void main(String[] args) {
                    Integer a = 127, b = 127;
                    System.out.println(a == b);
                }
            }""";

    private static final List<Choice> CHOICES = List.of(
            new Choice("true", true, ""), new Choice("false", false, "참조만 본 오해"),
            new Choice("127", false, "값을 찍는다는 오해"), new Choice("컴파일 오류", false, "오토박싱을 모르는 오해"));

    private static Item item(Kind kind, String code, String expectedOutput, int targetLine, String errorType) {
        return new Item(kind, Difficulty.BEGINNER, "Integer 비교", "다음 코드의 출력은?", code, expectedOutput,
                targetLine, errorType, CHOICES, "3행의 127은 캐시 범위 안이라 같은 객체다.");
    }

    private static Item output(String code) {
        return item(Kind.CODE_OUTPUT, code, "true", 0, "");
    }

    @Test
    @DisplayName("모양이 맞고 금지 소재가 없으면 통과한다")
    void acceptsWellFormedProblem() {
        assertThat(CodeProblemRule.defectOf(output(CODE))).isNull();
    }

    @Test
    @DisplayName("실행마다 결과가 달라지거나 실행 환경을 건드리는 코드는 돌리기 전에 버린다")
    void rejectsBannedMaterial() {
        assertThat(CodeProblemRule.defectOf(output(CODE.replace("Integer a = 127, b = 127;", "new Thread(() -> {}).start();"))))
                .contains("스레드");
        assertThat(CodeProblemRule.defectOf(output(CODE.replace("a == b", "new java.util.HashMap<String, Integer>()"))))
                .contains("순회 순서");
        assertThat(CodeProblemRule.defectOf(output(CODE.replace("a == b", "System.getenv(\"ANTHROPIC_API_KEY\")"))))
                .contains("실행 환경");
        assertThat(CodeProblemRule.defectOf(output(CODE.replace("a == b", "new java.util.LinkedHashMap<String, Integer>()"))))
                .as("순서가 정해진 맵은 쓸 수 있다").isNull();
    }

    @Test
    @DisplayName("오답 보기에 숨긴 금지 소재도 본다 — 빈칸·고치기 유형은 보기를 코드에 끼워 돌린다")
    void rejectsBannedMaterialInChoices() {
        Item blank = new Item(Kind.CODE_BLANK, Difficulty.BEGINNER, "제목", "출력이 true가 되려면?",
                CODE.replace("a == b", CodeProblem.BLANK), "true", 0, "",
                List.of(new Choice("a == b", true, ""), new Choice("System.exit(0)", false, "오해"),
                        new Choice("a != b", false, "오해"), new Choice("a.equals(1)", false, "오해")), "해설");

        assertThat(CodeProblemRule.defectOf(blank)).contains("실행 환경");
    }

    @Test
    @DisplayName("빈칸 유형은 빈칸이 정확히 하나여야 하고, 다른 유형에는 빈칸이 없어야 한다")
    void checksBlankMarker() {
        String withBlank = CODE.replace("a == b", CodeProblem.BLANK);

        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_BLANK, withBlank, "true", 0, ""))).isNull();
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_BLANK, CODE, "true", 0, ""))).contains("빈칸 표시");
        assertThat(CodeProblemRule.defectOf(output(withBlank))).contains("빈칸 표시");
    }

    @Test
    @DisplayName("대조할 값이 반쪽이면 버린다 — 예외 줄과 이름, 바꿀 줄, 예상 출력")
    void checksValuesTheRunnerCompares() {
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_ERROR, CODE, "", 3, ""))).contains("짝이 맞지");
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_ERROR, CODE, "", 3, "NullPointerException"))).isNull();
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_ERROR, CODE, "", 0, ""))).as("예외 없이 끝나는 문제").isNull();
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_FIX, CODE, "true", 0, ""))).contains("바꿀 줄");
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_FIX, CODE, "true", 99, ""))).contains("범위");
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_OUTPUT, CODE, " ", 0, ""))).contains("예상 출력");
        assertThat(CodeProblemRule.defectOf(item(Kind.CODE_COMPILE, CODE, "", 0, ""))).as("컴파일되는 문제").isNull();
    }

    @Test
    @DisplayName("보기는 서로 다른 4개이고 정답은 하나다")
    void checksChoices() {
        Item twoCorrect = new Item(Kind.CODE_OUTPUT, Difficulty.BEGINNER, "제목", "질문", CODE, "true", 0, "",
                List.of(new Choice("true", true, ""), new Choice("false", true, ""),
                        new Choice("127", false, "오해"), new Choice("오류", false, "오해")), "해설");
        Item duplicated = new Item(Kind.CODE_OUTPUT, Difficulty.BEGINNER, "제목", "질문", CODE, "true", 0, "",
                List.of(new Choice("true", true, ""), new Choice("false", false, "오해"),
                        new Choice("false ", false, "오해"), new Choice("오류", false, "오해")), "해설");

        assertThat(CodeProblemRule.defectOf(twoCorrect)).contains("정답이 1개");
        assertThat(CodeProblemRule.defectOf(duplicated)).contains("같은 보기");
    }

    @Test
    @DisplayName("파일 본체로 옮길 때 코드는 싣지 않고, 정답 보기의 오답 설명은 비운다")
    void convertsToProblemItemWithoutCode() {
        var problem = CodeProblem.toProblemItem(output(CODE));

        assertThat(problem.question()).isEqualTo("다음 코드의 출력은?");
        assertThat(problem.choices()).hasSize(4);
        assertThat(problem.choices().get(0).rationale()).isEmpty();
        assertThat(problem.choices().get(1).rationale()).isEqualTo("참조만 본 오해");
        assertThat(CodeProblem.Part.of(2, output(CODE)).code()).isEqualTo(CODE);
    }

    @Test
    @DisplayName("요청 글에 주제, 난이도 배분, 전에 낸 코드를 싣는다")
    void promptCarriesTopicPlanAndPreviousCodes() {
        Map<Difficulty, Integer> plan = new LinkedHashMap<>();
        plan.put(Difficulty.BEGINNER, 2);
        plan.put(Difficulty.INTERMEDIATE, 2);
        plan.put(Difficulty.ADVANCED, 1);

        String prompt = ClaudeCodeProblemGenerator.buildPrompt("==과 equals", plan, List.of(CODE));

        assertThat(prompt).contains("주제: ==과 equals", "문제 수: 5개 (초급 2개, 중급 2개, 고급 1개)",
                "[전에 낸 코드 1]", "Integer a = 127, b = 127;");
        assertThat(ClaudeCodeProblemGenerator.buildPrompt("주제", plan, List.of())).doesNotContain("전에 낸 코드");
    }
}
