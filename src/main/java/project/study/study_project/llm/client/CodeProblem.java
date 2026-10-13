package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import project.study.study_project.global.common.Difficulty;

import java.util.List;

/**
 * 자바 코드 문제의 응답 스키마와 파일에 싣는 곁칸.
 *
 * <p>개념 문제의 {@link GeneratedProblemItem}에 칸을 더하지 않고 따로 둔 이유: 그 record가
 * 개념 문제 생성의 응답 형식이라, 칸을 더하면 측정을 거친 생성 요청까지 바뀐다.
 *
 * <p>근거가 문서가 아니라 실행 결과다. 그래서 정답을 글이 아니라 <b>기계가 대조할 값</b>으로도 받는다
 * ({@code expectedOutput}, {@code targetLine}, {@code errorType}). 보기 문장만 받으면 실행 결과와
 * 견줄 방법이 문장 해석뿐이다.
 */
public final class CodeProblem {

    private CodeProblem() {
    }

    /** 빈칸 코드 유형에서 빈칸을 나타내는 표시. 코드에 정확히 한 번 나와야 한다. */
    public static final String BLANK = "____";

    public static final String LANGUAGE_JAVA = "java";

    /**
     * 다섯 유형. 유형마다 코드·보기·대조 값의 쓰임이 다르다.
     *
     * <ul>
     *   <li>{@code CODE_OUTPUT} — 완성된 코드. 보기는 출력. {@code expectedOutput}과 대조한다
     *   <li>{@code CODE_BLANK} — {@link #BLANK} 한 곳이 빈 코드. 보기는 넣을 코드.
     *       정답을 넣으면 {@code expectedOutput}이 나오고 오답은 그렇지 않아야 한다
     *   <li>{@code CODE_ERROR} — 완성된 코드. 보기는 "몇 행에서 무슨 예외". {@code targetLine}·{@code errorType}과 대조한다
     *   <li>{@code CODE_COMPILE} — 완성된 코드. 보기는 컴파일 여부. {@code targetLine}(0이면 컴파일됨)과 대조한다
     *   <li>{@code CODE_FIX} — 버그가 든 코드. 보기는 {@code targetLine}을 바꿀 코드.
     *       정답으로 바꾸면 {@code expectedOutput}이 나오고 오답은 그렇지 않아야 한다
     * </ul>
     */
    public enum Kind {
        CODE_OUTPUT("실행 결과"),
        CODE_BLANK("빈칸 코드"),
        CODE_ERROR("오류 지점"),
        CODE_COMPILE("컴파일 여부"),
        CODE_FIX("한 줄 고치기");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 보기를 코드에 끼워 넣어 돌려 보는 유형인가. 이 유형은 오답 보기도 하나씩 실행해 본다. */
        public boolean substitutesChoice() {
            return this == CODE_BLANK || this == CODE_FIX;
        }
    }

    @JsonClassDescription("자바 코드 문제 하나")
    public record Item(
            @JsonPropertyDescription("문제 유형")
            Kind kind,

            @JsonPropertyDescription("난이도. 요청에 적힌 배분을 따른다")
            Difficulty difficulty,

            @JsonPropertyDescription("문제 목록에 뜰 한 줄 제목. 무엇에 관한 문제인지 명사구로 적는다. 정답을 드러내지 않는다")
            String title,

            @JsonPropertyDescription("코드 위에 놓일 질문 한두 문장. 코드를 여기에 넣지 않는다")
            String question,

            @JsonPropertyDescription("Main.java 전체. public class Main과 main 메서드를 갖춘다. "
                    + "CODE_BLANK는 빈칸 자리에 ____를 정확히 한 번 쓴다")
            String code,

            @JsonPropertyDescription("표준 출력 전체를 글자 그대로. CODE_OUTPUT은 이 코드의 출력, "
                    + "CODE_BLANK·CODE_FIX는 정답을 넣었을 때의 출력. CODE_ERROR·CODE_COMPILE은 빈 문자열")
            String expectedOutput,

            @JsonPropertyDescription("줄 번호(1부터). CODE_ERROR는 예외가 나는 줄(예외가 없으면 0), "
                    + "CODE_COMPILE은 컴파일 오류가 나는 줄(컴파일되면 0), CODE_FIX는 바꿀 줄. 그 밖에는 0")
            int targetLine,

            @JsonPropertyDescription("CODE_ERROR에서 나는 예외의 클래스 이름(예: NullPointerException). "
                    + "예외가 없거나 다른 유형이면 빈 문자열")
            String errorType,

            @JsonPropertyDescription("보기 4개. 정답은 정확히 1개")
            List<Choice> choices,

            @JsonPropertyDescription("왜 이 답인지. 코드의 줄을 'N행'으로 가리키며 실행 흐름을 따라 설명한다")
            String explanation
    ) {
    }

    public record Choice(
            @JsonPropertyDescription("보기 내용. CODE_BLANK는 빈칸에 넣을 코드, CODE_FIX는 그 줄을 대신할 코드 한 줄")
            String text,

            @JsonPropertyDescription("정답이면 true")
            boolean correct,

            @JsonPropertyDescription("오답일 때 왜 틀렸는지 한 줄(어떤 오해인지). 정답이면 빈 문자열")
            String rationale
    ) {
    }

    /**
     * 문제 파일의 본체로 옮긴다. 코드와 대조 값은 여기 담지 않고 {@link Part}로 따로 싣는다.
     * 객관식이라 answer는 비우고, 근거 문서가 없어 sourceQuote도 비운다.
     */
    public static GeneratedProblemItem toProblemItem(Item item) {
        List<GeneratedProblemItem.GeneratedChoice> choices = item.choices().stream()
                .map(c -> new GeneratedProblemItem.GeneratedChoice(
                        c.text().strip(), c.correct(), c.correct() || c.rationale() == null ? "" : c.rationale().strip()))
                .toList();
        return new GeneratedProblemItem(item.question().strip(), "", item.explanation().strip(), choices, "",
                item.title() == null ? "" : item.title().strip());
    }

    /** 구조화 출력의 최상위 봉투 — 최상위는 객체여야 한다. */
    public record Batch(
            @JsonPropertyDescription("문제 목록")
            List<Item> problems
    ) {
    }

    /**
     * 문제 파일에 싣는 곁칸. 문제 본체({@link GeneratedProblemItem})와 번호로 짝짓는다.
     *
     * @param problemIndex 파일의 {@code problems} 안 위치(0부터)
     * @param language     지금은 {@code java}뿐이다
     */
    public record Part(int problemIndex, Kind kind, String language, String code, String expectedOutput,
                       int targetLine, String errorType) {

        public static Part of(int problemIndex, Item item) {
            return new Part(problemIndex, item.kind(), LANGUAGE_JAVA, item.code(), item.expectedOutput(),
                    item.targetLine(), item.errorType());
        }
    }
}
