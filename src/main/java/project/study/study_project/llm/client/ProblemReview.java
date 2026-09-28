package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 객관식 문제 검수의 응답 스키마와 결과 — docs/22 §3.2~3.4.
 *
 * <p>호출은 둘이다. A는 질문을 가리고 보기만 보여 준다(보기 단서 누설).
 * B는 문서와 함께 정식으로 풀게 한다(정답·근거·질문 누설·난이도).
 * 필드 순서가 곧 생성 순서라, 이유를 먼저 쓰고 번호를 나중에 고르게 뒀다.
 */
public final class ProblemReview {

    private ProblemReview() {
    }

    /* ── A. 보기만 보고 고르기 ── */

    @JsonClassDescription("질문 없이 보기만 보고 고른 답")
    public record ChoiceOnlyAnswer(
            @JsonPropertyDescription("문제 번호. 입력으로 준 번호를 그대로 돌려준다")
            int problemNo,

            @JsonPropertyDescription("글의 모양만으로 정답이 드러나는 단서. 없으면 빈 문자열")
            String cue,

            @JsonPropertyDescription("단서가 가리키는 보기 번호 1~4. 단서가 없으면 0")
            int choiceNo
    ) {
    }

    public record ChoiceOnlyBatch(
            @JsonPropertyDescription("문제별 답")
            List<ChoiceOnlyAnswer> answers
    ) {
    }

    /* ── B. 문서와 함께 풀기 ── */

    @JsonClassDescription("문서를 보고 문제 하나를 검수한 결과")
    public record Judgement(
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

            @JsonPropertyDescription("질문의 표현을 한 보기만 되받아 문장 비교만으로 답이 좁혀지면 그 이유. 아니면 빈 문자열")
            String revealReason,

            @JsonPropertyDescription("질문이 용어의 뜻, 또는 뜻에 맞는 용어만 묻는가")
            boolean asksDefinition,

            @JsonPropertyDescription("지문에 적힌, 답을 가르는 조건이 무엇인지. 없으면 빈 문자열")
            String conditionReason,

            @JsonPropertyDescription("그 조건이 적힌 지문 문장. 지문에서 글자 그대로 복사한다. 없으면 빈 문자열")
            String conditionQuote,

            @JsonPropertyDescription("오답마다, 그 오답이 정답이 되는 다른 조건. 댈 수 없는 오답은 '없음'")
            String elsewhereReason,

            @JsonPropertyDescription("다른 조건이었다면 정답이 되는 오답의 개수 0~3")
            int elsewhereCount
    ) {
    }

    public record JudgementBatch(
            @JsonPropertyDescription("문제별 검수 결과")
            List<Judgement> judgements
    ) {
    }

    /* ── 결과 ── */

    public enum FindingType {
        ANSWER_MISMATCH("정답 의심"),
        OTHER_CORRECT("정답이 둘일 수 있음"),
        CHOICE_CUE_LEAK("보기 단서 누설"),
        QUESTION_REVEALS("질문이 답을 드러냄"),
        NO_SUPPORT("문서에 근거 없음"),
        DIFFICULTY_MISMATCH("난이도 어긋남");

        private final String label;

        FindingType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** @param problemIndex 검수에 넘긴 목록 안의 위치(0부터) */
    public record Finding(int problemIndex, FindingType type, String message) {
    }
}
