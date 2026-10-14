package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 객관식 문제 검수의 응답 스키마와 결과 — docs/22 §3.2~3.4.
 *
 * <p>호출은 셋이다. A는 질문을 가리고 보기만 보여 준다(보기 단서 누설).
 * B는 문서와 함께 정식으로 풀게 한다(정답·근거·질문 누설·난이도·의견).
 * C는 다 푼 뒤 정답과 해설을 열어 해설을 견주고, 지적이 달린 문제에 고친 문장을 낸다.
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

            @JsonPropertyDescription("질문이 용어가 무엇인지(뜻, 설명, 뜻에 맞는 용어)만 묻는가. 이유·비교·순서·적용을 물으면 false")
            boolean asksDefinition,

            @JsonPropertyDescription("지문에 적힌, 답을 가르는 조건이 무엇인지. 없으면 빈 문자열")
            String conditionReason,

            @JsonPropertyDescription("그 조건이 적힌 지문 문장. 지문에서 글자 그대로 복사한다. 없으면 빈 문자열")
            String conditionQuote,

            @JsonPropertyDescription("오답마다, 그 오답이 정답이 되는 다른 조건. 댈 수 없는 오답은 '없음'")
            String elsewhereReason,

            @JsonPropertyDescription("다른 조건이었다면 정답이 되는 오답의 개수 0~3")
            int elsewhereCount,

            @JsonPropertyDescription("지문이나 보기에서 어색하거나 뜻이 흐린 문장 하나. 글자 그대로 복사한다. 없으면 빈 문자열")
            String awkwardQuote,

            @JsonPropertyDescription("awkwardQuote를 뜻은 그대로 두고 고쳐 쓴 문장. 없으면 빈 문자열")
            String awkwardFix,

            @JsonPropertyDescription("풀면서 되돌아가 다시 읽은 곳과 그 이유 한두 문장. 없으면 빈 문자열")
            String readabilityNote,

            @JsonPropertyDescription("학습자 입장에서 이 문제를 풀어 본 총평 한두 문장")
            String overallNote
    ) {

        /** 의견 칸(2026-10-12)이 생기기 전 형태 — 테스트가 쓴다. */
        public Judgement(int problemNo, String answerReason, int chosenNo, int otherCorrectNo,
                         String otherCorrectReason, String supportQuote, String revealReason,
                         boolean asksDefinition, String conditionReason, String conditionQuote,
                         String elsewhereReason, int elsewhereCount) {
            this(problemNo, answerReason, chosenNo, otherCorrectNo, otherCorrectReason, supportQuote, revealReason,
                    asksDefinition, conditionReason, conditionQuote, elsewhereReason, elsewhereCount, "", "", "", "");
        }
    }

    public record JudgementBatch(
            @JsonPropertyDescription("문제별 검수 결과")
            List<Judgement> judgements
    ) {
    }

    /* ── C. 정답과 해설을 열어 본 뒤 ── */

    /**
     * 개념 문제 검수가 모델에게 고르게 하는 고칠 대상. 응답 스키마에 실리므로 {@link FixTarget}과 따로 둔다.
     * 저쪽에 코드 문제용 값을 더해도 개념 문제 검수의 요청은 바뀌지 않는다.
     */
    public enum AfterSolveFix { NONE, QUESTION, CHOICE }

    /**
     * 제안이 고치는 곳. 해설과 오답 이유는 코드 문제 검수만 고친다.
     * 코드와 정답은 고치지 않는다. 바꾸면 실행 검증을 다시 해야 한다.
     */
    public enum FixTarget { QUESTION, CHOICE, EXPLANATION, RATIONALE }

    @JsonClassDescription("정답과 해설을 열어 본 뒤의 검토")
    public record AfterSolve(
            @JsonPropertyDescription("문제 번호. 입력으로 준 번호를 그대로 돌려준다")
            int problemNo,

            @JsonPropertyDescription("해설이나 오답 풀이가 표시된 정답·검수자 풀이와 어긋나는 점. 없으면 빈 문자열")
            String explanationIssue,

            @JsonPropertyDescription("고칠 대상. NONE=고칠 것 없음, QUESTION=질문 문장, CHOICE=보기 하나")
            AfterSolveFix fixTarget,

            @JsonPropertyDescription("fixTarget이 CHOICE일 때 고칠 보기 번호 1~4. 아니면 0")
            int fixChoiceNo,

            @JsonPropertyDescription("고쳐 쓴 문장 전체. 고칠 것이 없으면 빈 문자열")
            String fixText
    ) {
    }

    public record AfterSolveBatch(
            @JsonPropertyDescription("문제별 검토")
            List<AfterSolve> reviews
    ) {
    }

    /* ── 결과 ── */

    /**
     * 지적의 무게. 결함은 문제를 틀리게 만들고, 다듬기는 문제를 쉽게 만들고, 의견은 참고용이다.
     * 검수함은 의견을 지적 수에 세지 않는다. 들여오기를 막는다면 결함만 대상이다(docs/22 §6).
     */
    public enum Grade { DEFECT, POLISH, OPINION }

    public enum FindingType {
        ANSWER_MISMATCH("정답 의심", Grade.DEFECT),
        OTHER_CORRECT("정답이 둘일 수 있음", Grade.DEFECT),
        CHOICE_CUE_LEAK("보기 단서 누설", Grade.POLISH),
        QUESTION_REVEALS("질문이 답을 드러냄", Grade.POLISH),
        NO_SUPPORT("문서에 근거 없음", Grade.DEFECT),
        /** AI가 낸 지적이 아니다. 문서 사실 검수 지적과 근거 인용을 코드가 대조한다({@code FlaggedSourceRule}). */
        SOURCE_FLAGGED("근거 문단에 사실 지적", Grade.DEFECT),
        EXPLANATION_MISMATCH("해설 어긋남", Grade.DEFECT),
        /** 아래 셋은 AI가 아니라 코드 문제의 실행 결과를 코드가 대조한다({@code CodeFactChecker}). */
        QUESTION_MISMATCH("질문 어긋남", Grade.DEFECT),
        RATIONALE_MISMATCH("오답 이유 어긋남", Grade.DEFECT),
        CODE_COMMENT_REVEALS("주석이 답을 드러냄", Grade.POLISH),
        /** 아래 넷은 코드 문제 AI 검수({@code ClaudeCodeProblemReviewer})만 낸다. */
        ENVIRONMENT_DEPENDENT("실행 환경 의존", Grade.DEFECT),
        OFF_TOPIC("주제 이탈", Grade.POLISH),
        /** 정답은 실행으로 확인했으니 결함이 아니다. AI도 걸린 함정이거나 질문이 흐리다는 신호다. */
        MISSOLVED("AI가 다른 답을 고름", Grade.POLISH),
        AMBIGUOUS_QUESTION("질문이 모호함", Grade.POLISH),
        DIFFICULTY_MISMATCH("난이도 어긋남", Grade.POLISH),
        SOLUTION("AI 풀이", Grade.OPINION),
        AWKWARD_SENTENCE("어색한 문장", Grade.OPINION),
        READABILITY("가독성", Grade.OPINION),
        OVERALL("총평", Grade.OPINION);

        private final String label;
        private final Grade grade;

        FindingType(String label, Grade grade) {
            this.label = label;
            this.grade = grade;
        }

        public String label() {
            return label;
        }

        public Grade grade() {
            return grade;
        }
    }

    /**
     * 지적에 붙는 고친 문장 제안.
     *
     * @param target      고치는 곳
     * @param choiceIndex 보기나 오답 이유를 고칠 때 그 보기의 원래 위치(0부터). 질문·해설이면 {@code null}
     * @param text        고쳐 쓴 글 전체. 그 칸을 통째로 바꾼다
     */
    public record Suggestion(FixTarget target, Integer choiceIndex, String text) {
    }

    /**
     * @param problemIndex 검수에 넘긴 목록 안의 위치(0부터)
     * @param message      결론 한 문장. 종류마다 코드가 정한 문장이라 목록에서 훑어 읽힌다
     * @param detail       그렇게 본 근거. 줄바꿈으로 나눈 여러 줄이고 AI가 쓴 글이 섞인다.
     *                     댈 근거가 없는 종류와 이 칸이 생기기 전(2026-10-07)의 지적은 {@code null}
     * @param suggestion   고친 문장 제안. 다듬기 지적에만 붙고, 없으면 {@code null}
     */
    public record Finding(int problemIndex, FindingType type, String message, String detail, Suggestion suggestion) {

        public Finding(int problemIndex, FindingType type, String message) {
            this(problemIndex, type, message, null, null);
        }

        public Finding(int problemIndex, FindingType type, String message, String detail) {
            this(problemIndex, type, message, detail, null);
        }

        public Finding withSuggestion(Suggestion s) {
            return new Finding(problemIndex, type, message, detail, s);
        }
    }
}
