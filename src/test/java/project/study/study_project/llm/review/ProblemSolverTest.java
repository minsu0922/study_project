package project.study.study_project.llm.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.review.ProblemSolver.Solve;
import project.study.study_project.llm.review.ProblemSolver.SolveBatch;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemSolverTest {

    private static final String DOC = "스키마는 표의 모양과 배치를 가리킨다.";

    private static GeneratedProblemItem mc(String question) {
        return new GeneratedProblemItem(question, "", "해설 비밀 문장", List.of(
                new GeneratedChoice("스키마", true, ""),
                new GeneratedChoice("테이블", false, "오답 설명 비밀"),
                new GeneratedChoice("컬럼", false, ""),
                new GeneratedChoice("행", false, "")));
    }

    /** 섞인 화면에서 정답을 맞힌 풀이 */
    private static Solve fine(Shown s) {
        return new Solve(1, "이유", s.correctNo(), 0, "", DOC, "");
    }

    private static List<Finding> findings(Shown s, Solve solve) {
        return ProblemSolver.findingsOf(List.of(s), new SolveBatch(List.of(solve)), DOC);
    }

    @Test
    @DisplayName("정답을 맞히고 근거가 문서에 있으면 지적이 없다")
    void noFindingsWhenSolvedWithSupport() {
        Shown s = Shown.showable(List.of(mc("표의 모양을 뭐라 하나?"))).get(0);
        assertThat(findings(s, fine(s))).isEmpty();
    }

    @Test
    @DisplayName("다른 보기를 고르면 정답 의심, 근거 속 번호는 원래 순서로 바꾼다")
    void answerMismatchUsesOriginalNumbers() {
        Shown s = new Shown(0, mc("표의 모양을 뭐라 하나?"), List.of(2, 0, 3, 1));
        Solve wrong = new Solve(1, "1번 컬럼이 맞다", 1, 0, "", DOC, "");

        List<Finding> f = findings(s, wrong);

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.ANSWER_MISMATCH);
            assertThat(x.detail()).contains("「컬럼」").contains("3번 컬럼이 맞다");
        });
    }

    @Test
    @DisplayName("다른 정답이 있으면 정답이 둘일 수 있음")
    void otherCorrect() {
        Shown s = new Shown(0, mc("표의 모양을 뭐라 하나?"), List.of(0, 1, 2, 3));
        Solve two = new Solve(1, "이유", 1, 2, "테이블도 맞다", DOC, "");

        assertThat(findings(s, two)).extracting(Finding::type).containsExactly(FindingType.OTHER_CORRECT);
    }

    @Test
    @DisplayName("근거 인용이 없거나 문서에 없으면 근거 없음")
    void noSupport() {
        Shown s = Shown.showable(List.of(mc("표의 모양을 뭐라 하나?"))).get(0);

        assertThat(findings(s, new Solve(1, "이유", s.correctNo(), 0, "", "", "")))
                .extracting(Finding::type).containsExactly(FindingType.NO_SUPPORT);
        assertThat(findings(s, new Solve(1, "이유", s.correctNo(), 0, "", "지어낸 문장", "")))
                .singleElement().satisfies(x -> assertThat(x.detail()).contains("지어낸 문장"));
    }

    @Test
    @DisplayName("질문에 그대로 적힌 정답 구절이면 질문에 정답이 적힘")
    void answerInQuestion() {
        Shown s = Shown.showable(List.of(mc("표의 모양과 배치, 곧 스키마를 뭐라 하나?"))).get(0);
        Solve revealed = new Solve(1, "이유", s.correctNo(), 0, "", DOC, "곧 스키마를");

        assertThat(findings(s, revealed)).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.QUESTION_REVEALS);
            assertThat(x.detail()).contains("곧 스키마를");
        });
    }

    @Test
    @DisplayName("질문에 없는 구절은 질문에 정답이 적힘을 내지 않는다")
    void answerInQuestionMustBeInQuestion() {
        Shown s = Shown.showable(List.of(mc("표의 모양을 뭐라 하나?"))).get(0);
        Solve made = new Solve(1, "이유", s.correctNo(), 0, "", DOC, "스키마라고 부른다");

        assertThat(findings(s, made)).isEmpty();
    }

    @Test
    @DisplayName("응답에서 빠진 문제는 건너뛴다")
    void skipsMissingSolve() {
        List<Shown> shown = Shown.showable(List.of(mc("표의 모양을 뭐라 하나?"), mc("둘째 문제는?")));
        Solve first = new Solve(1, "이유", shown.get(0).correctNo(), 0, "", DOC, "");

        assertThat(ProblemSolver.findingsOf(shown, new SolveBatch(List.of(first)), DOC)).isEmpty();
    }

    @Test
    @DisplayName("프롬프트에는 문서와 자료가 들어가고 해설·오답 설명은 들어가지 않는다")
    void promptHidesAnswerMaterial() {
        GeneratedProblemItem base = mc("아래 집계에서 먼저 의심할 것은?");
        GeneratedProblemItem withExhibit = new GeneratedProblemItem(base.question(), "State  Count\nTIME-WAIT 28211",
                base.answer(), base.explanation(), base.choices(), "", "", null, "");

        String prompt = ProblemSolver.buildPrompt(Shown.showable(List.of(withExhibit)), new SourceDocument("s", "t", DOC));

        assertThat(prompt).contains(DOC).contains("[자료]\nState  Count")
                .doesNotContain("해설 비밀 문장").doesNotContain("오답 설명 비밀");
    }

    @Test
    @DisplayName("표시대로 풀었는지 가린다")
    void solvedAsMarked() {
        Shown s = Shown.showable(List.of(mc("표의 모양을 뭐라 하나?"))).get(0);
        assertThat(ProblemSolver.solvedAsMarked(s, fine(s))).isTrue();
        assertThat(ProblemSolver.solvedAsMarked(s, new Solve(1, "", s.correctNo() % 4 + 1, 0, "", DOC, ""))).isFalse();
        assertThat(ProblemSolver.solvedAsMarked(s, null)).isFalse();
    }
}
