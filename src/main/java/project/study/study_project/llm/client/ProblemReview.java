package project.study.study_project.llm.client;

/**
 * 문제 검수 지적의 모양 — 객관식 검수(llm.review)와 코드 문제 검수(ClaudeCodeProblemReviewer)가 함께 쓴다.
 * 옛 검수가 내던 종류(보기 단서 누설·난이도·의견 등)도 남겨 둔다. 저장된 지적을 읽을 때 필요하다.
 */
public final class ProblemReview {

    private ProblemReview() {
    }

    public enum FixTarget { QUESTION, CHOICE, EXPLANATION, RATIONALE }

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
        QUESTION_REVEALS("질문에 정답이 적힘", Grade.POLISH),
        NO_SUPPORT("문서에 근거 없음", Grade.DEFECT),
        /** AI가 낸 지적이 아니다. 문서 사실 검수 지적과 근거 인용을 코드가 대조한다({@code FlaggedSourceRule}). */
        SOURCE_FLAGGED("근거 문단에 사실 지적", Grade.DEFECT),
        EXPLANATION_MISMATCH("해설 어긋남", Grade.DEFECT),
        /** 아래 셋은 편집 호출이 낸다. 고쳐 넣은 둘은 들여올 때 처리된 것으로 저장한다(옛 글은 근거에 있다) */
        EXPLANATION_REWRITTEN("AI가 해설을 고침", Grade.POLISH),
        RATIONALE_REWRITTEN("AI가 오답 설명을 고침", Grade.POLISH),
        EXPLANATION_SUGGESTED("해설 고친 안", Grade.POLISH),
        /** 문제에 붙은 로그·표·그림이 사실과 어긋나거나 정답을 받치지 못한다. 틀린 그림은 학습자가 그대로 외운다. */
        EXHIBIT_MISMATCH("자료 어긋남", Grade.DEFECT),
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

        /** 제안을 이미 문제에 넣었을 때. 남겨 두면 검수함에서 같은 글을 또 넣는 버튼이 뜬다 */
        public Finding withoutSuggestion() {
            return new Finding(problemIndex, type, message, detail, null);
        }
    }
}
