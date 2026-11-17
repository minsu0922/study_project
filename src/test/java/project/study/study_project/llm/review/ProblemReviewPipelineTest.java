package project.study.study_project.llm.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReviewer.Reviewed;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.review.ProblemEditor.Edit;
import project.study.study_project.llm.review.ProblemEditor.EditBatch;
import project.study.study_project.llm.review.ProblemEditor.RationaleFix;
import project.study.study_project.llm.review.ProblemSolver.Solve;
import project.study.study_project.llm.review.ProblemSolver.SolveBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemReviewPipelineTest {

    private static final String SUPPORT = "연결은 네 값으로 갈린다.";
    private static final String MD = "## 리슨 소켓 하나가 연결 수천 개를 받는 방식\n" + SUPPORT + "\n";
    private static final SourceDocument SOURCE = new SourceDocument("port-advanced", "포트", MD);
    private static final String POINTER = "(심화편의 '리슨 소켓 하나가 연결 수천 개를 받는 방식' 절을 다시 읽어 보라)";

    private static final String OLD = "규칙: 연결은 네 값으로 갈린다.\n두 탭이 같은 서버에 붙는다.\n그래서 상대 포트가 가른다.\n" + POINTER;
    private static final String NEW = """
            규칙: 연결 하나는 내 IP 주소·내 포트·상대 IP 주소·상대 포트 네 값으로 갈린다. 하나만 달라도 다른 연결이다.
            그림의 두 연결을 서버 쪽에서 적어 보자.
            첫 탭 연결: 서버 IP·443, 상대 IP는 PC 주소, 상대 포트 51000.
            둘째 탭 연결: 서버 IP·443, 상대 IP는 같은 PC 주소, 상대 포트 51001.
            네 값을 견준다: 서버 쪽 두 값과 상대 IP는 같고, 상대 포트만 다르다.
            그래서 두 연결을 가르는 값은 접속한 쪽의 포트 번호다.
            """ + POINTER;
    private static final String NEW_RATIONALE = "도착 순서로 연결을 가른다고 본 오해다. 운영체제는 네 값으로 연결을 찾는다.";

    private static GeneratedProblemItem mc(String question) {
        return new GeneratedProblemItem(question, "", OLD, List.of(
                new GeneratedChoice("접속한 쪽의 포트 번호", true, ""),
                new GeneratedChoice("서버 포트", false, "서버 쪽 포트는 두 연결 모두 443으로 같아 이 값으로는 가르지 못한다."),
                new GeneratedChoice("IP 주소", false, "두 탭은 같은 PC에서 나와 IP 주소가 같아 가르는 값이 되지 못한다."),
                new GeneratedChoice("도착 순서", false, "짧다")));
    }

    private final List<String> editorPrompts = new ArrayList<>();

    /** 정답을 맞힌 풀이. first가 거짓이면 첫 문제는 틀리게 푼다 */
    private Function<String, SolveBatch> solver(List<GeneratedProblemItem> problems, boolean firstCorrect) {
        return prompt -> {
            List<Shown> shown = Shown.showable(problems);
            List<Solve> solves = new ArrayList<>();
            for (int k = 0; k < shown.size(); k++) {
                int correct = shown.get(k).correctNo();
                int chosen = k == 0 && !firstCorrect ? correct % 4 + 1 : correct;
                solves.add(new Solve(k + 1, "이유", chosen, 0, "", SUPPORT, ""));
            }
            return new SolveBatch(solves);
        };
    }

    private Function<String, EditBatch> editor() {
        return prompt -> {
            editorPrompts.add(prompt);
            return new EditBatch(List.of(new Edit(1, "B3: 「두 탭이 같은 서버에 붙는다.」 — 값이 없다", List.of("B3", "C2"), NEW,
                    List.of(new RationaleFix(4, NEW_RATIONALE)), "", "")));
        };
    }

    @Test
    @DisplayName("표시대로 푼 문제만 편집에 넘긴다")
    void editsOnlyProblemsSolvedAsMarked() {
        List<GeneratedProblemItem> problems = List.of(mc("틀리게 풀 문제는?"), mc("맞게 풀 문제는?"));
        new ProblemReviewPipeline(solver(problems, false), editor()).review(problems, SOURCE, null);

        assertThat(editorPrompts).singleElement().asString().contains("맞게 풀 문제는?").doesNotContain("틀리게 풀 문제는?");
    }

    @Test
    @DisplayName("고친 해설과 오답 설명이 문제에 들어가고, 넣은 지적에서는 제안이 떨어진다")
    void appliesRewrites() {
        List<GeneratedProblemItem> problems = List.of(mc("두 탭의 연결을 가르는 값은?"));
        Reviewed r = new ProblemReviewPipeline(solver(problems, true), editor()).review(problems, SOURCE, null);

        assertThat(r.problems().get(0).explanation()).isEqualTo(NEW);
        assertThat(r.problems().get(0).choices().get(3).rationale()).isEqualTo(NEW_RATIONALE);
        assertThat(r.problems().get(0).question()).isEqualTo("두 탭의 연결을 가르는 값은?");
        assertThat(r.findings()).extracting(Finding::type)
                .containsExactlyInAnyOrder(FindingType.EXPLANATION_REWRITTEN, FindingType.RATIONALE_REWRITTEN);
        assertThat(r.findings()).allSatisfy(f -> assertThat(f.suggestion()).isNull());
    }

    @Test
    @DisplayName("편집이 실패하면 풀이 지적만 돌려주고 문제는 그대로다")
    void editorFailureKeepsSolverFindings() {
        List<GeneratedProblemItem> problems = List.of(mc("틀리게 풀 문제는?"), mc("맞게 풀 문제는?"));
        Reviewed r = new ProblemReviewPipeline(solver(problems, false), prompt -> {
            throw new BusinessException(ErrorCode.LLM_003, "API 오류");
        }).review(problems, SOURCE, null);

        assertThat(r.findings()).extracting(Finding::type).containsExactly(FindingType.ANSWER_MISMATCH);
        assertThat(r.problems()).isEqualTo(problems);
    }

    @Test
    @DisplayName("풀이가 실패하면 예외를 그대로 던진다")
    void solverFailureThrows() {
        List<GeneratedProblemItem> problems = List.of(mc("문제는?"));
        ProblemReviewPipeline pipeline = new ProblemReviewPipeline(prompt -> {
            throw new BusinessException(ErrorCode.LLM_003, "API 오류");
        }, editor());

        assertThatThrownBy(() -> pipeline.review(problems, SOURCE, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("문서 사실 지적을 근거 인용에 잇는다")
    void linksDocumentFactFindings() {
        GeneratedProblemItem base = mc("두 탭의 연결을 가르는 값은?");
        GeneratedProblemItem quoted = new GeneratedProblemItem(base.question(), "", base.answer(), base.explanation(),
                base.choices(), SUPPORT, "", null, "");
        List<GeneratedProblemItem> problems = List.of(quoted);
        FactCheckFinding fact = new FactCheckFinding(SUPPORT, FactCheckFinding.Kind.FACT_ERROR, "다섯 값이다", "연결은 다섯 값으로 갈린다.",
                FactCheckFinding.Confidence.HIGH);

        Reviewed r = new ProblemReviewPipeline(solver(problems, true), editor()).review(problems, SOURCE, List.of(fact));

        assertThat(r.findings()).extracting(Finding::type).contains(FindingType.SOURCE_FLAGGED);
    }
}
