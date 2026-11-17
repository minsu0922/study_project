package project.study.study_project.llm.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.review.ProblemEditor.Edit;
import project.study.study_project.llm.review.ProblemEditor.EditBatch;
import project.study.study_project.llm.review.ProblemEditor.RationaleFix;
import project.study.study_project.llm.review.ProblemSolver.Solve;
import project.study.study_project.llm.support.ExplanationFormRule.FormIssue;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemEditorTest {

    private static final String SLUG = "port-number-program-demultiplexing-advanced";
    private static final String MD = "## 리슨 소켓 하나가 연결 수천 개를 받는 방식\n본문\n";
    private static final SourceDocument SOURCE = new SourceDocument(SLUG, "포트", MD);

    private static final String POINTER = "(심화편의 '리슨 소켓 하나가 연결 수천 개를 받는 방식' 절을 다시 읽어 보라)";

    private static final String OLD = """
            규칙: 연결은 네 값으로 갈린다.
            두 탭이 같은 서버에 붙는다.
            그래서 상대 포트가 가른다.
            """ + POINTER;

    private static final String NEW = """
            규칙: 연결 하나는 내 IP 주소·내 포트·상대 IP 주소·상대 포트 네 값으로 갈린다. 하나만 달라도 다른 연결이다.
            그림의 두 연결을 서버 쪽에서 적어 보자.
            첫 탭 연결: 서버 IP·443, 상대 IP는 PC 주소, 상대 포트 51000.
            둘째 탭 연결: 서버 IP·443, 상대 IP는 같은 PC 주소, 상대 포트 51001.
            네 값을 견준다: 서버 쪽 두 값과 상대 IP는 같고, 상대 포트만 다르다.
            그래서 두 연결을 가르는 값은 접속한 쪽의 포트 번호다.
            """ + POINTER;

    private static final String R = "서버 쪽 포트는 두 연결 모두 443으로 같아 이 값으로는 가르지 못한다.";

    private static GeneratedProblemItem item() {
        return item(OLD);
    }

    private static GeneratedProblemItem item(String explanation) {
        return new GeneratedProblemItem("두 탭의 연결을 가르는 값은?", "", explanation, List.of(
                new GeneratedChoice("접속한 쪽의 포트 번호", true, ""),
                new GeneratedChoice("서버 포트", false, R),
                new GeneratedChoice("IP 주소", false, "두 탭은 같은 PC에서 나와 IP 주소가 같아 가르는 값이 되지 못한다."),
                new GeneratedChoice("도착 순서", false, "운영체제는 순서가 아니라 패킷에 적힌 네 값으로 연결을 찾는다.")));
    }

    /** 해설 형식이 멀쩡한 문제 — 해설 말고 다른 칸을 재는 테스트가 쓴다 */
    private static final Shown FINE = new Shown(0, item(NEW), List.of(0, 1, 2, 3));
    /** 해설 형식이 걸리는 문제 — 고친 해설을 재는 테스트가 쓴다 */
    private static final Shown WEAK = new Shown(0, item(), List.of(0, 1, 2, 3));

    private static Edit edit(String reason, List<String> failed, String explanation, List<RationaleFix> rationales,
                             String questionFix, String exhibitIssue) {
        return new Edit(1, reason, failed, explanation, rationales, questionFix, exhibitIssue);
    }

    private static List<Finding> findings(Edit e) {
        return findings(WEAK, e, Map.of());
    }

    private static List<Finding> findings(Shown s, Edit e, Map<Integer, Solve> solves) {
        return ProblemEditor.findingsOf(List.of(s), new EditBatch(List.of(e)), SOURCE, solves);
    }

    @Test
    @DisplayName("재검사를 통과한 해설은 고쳐 넣음으로 내고 옛 해설을 근거에 남긴다")
    void rewrittenExplanationPasses() {
        List<Finding> f = findings(edit("B3: 「두 탭이 같은 서버에 붙는다.」 — 값이 없다", List.of("B3"), NEW, List.of(), "", ""));

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.EXPLANATION_REWRITTEN);
            assertThat(x.suggestion().target()).isEqualTo(FixTarget.EXPLANATION);
            assertThat(x.suggestion().text()).isEqualTo(NEW);
            assertThat(x.detail()).contains("값이 없다").contains("옛 해설:").contains("두 탭이 같은 서버에 붙는다.");
        });
    }

    @Test
    @DisplayName("형식을 어긴 고친 해설은 넣지 않고 제안으로 남긴다")
    void rewrittenExplanationFailingFormIsSuggested() {
        String noPointer = NEW.replace(POINTER, "").strip();
        List<Finding> f = findings(edit("B3: 「두 탭이 같은 서버에 붙는다.」 — 값이 없다", List.of("B3"), noPointer, List.of(), "", ""));

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.EXPLANATION_SUGGESTED);
            assertThat(x.suggestion().text()).isEqualTo(noPointer);
            assertThat(x.detail()).contains("넣지 않은 이유");
        });
    }

    @Test
    @DisplayName("그래서 줄이 정답 보기를 가리키지 않으면 넣지 않는다")
    void conclusionMustPointToAnswer() {
        String other = NEW.replace("그래서 두 연결을 가르는 값은 접속한 쪽의 포트 번호다.", "그래서 답은 도착 순서로 정해진다.");
        assertThat(findings(edit("B3: 「두 탭이 같은 서버에 붙는다.」 — 값이 없다", List.of("B3"), other, List.of(), "", "")))
                .extracting(Finding::type).containsExactly(FindingType.EXPLANATION_SUGGESTED);
    }

    @Test
    @DisplayName("A 기준이 걸렸는데 넣지 못하면 해설 어긋남 결함")
    void failedAWithoutInsertionIsDefect() {
        String noPointer = NEW.replace(POINTER, "").strip();
        assertThat(findings(edit("A2: 「연결은 네 값으로 갈린다.」 — 문서와 다르다", List.of("A2"), noPointer, List.of(), "", "")))
                .extracting(Finding::type).containsExactly(FindingType.EXPLANATION_MISMATCH);
    }

    @Test
    @DisplayName("오답 설명 고침은 실제 오답 보기에만, 30자 이상일 때만 받는다")
    void rationaleFixes() {
        String good = "도착 순서로 연결을 가른다고 본 오해다. 운영체제는 네 값으로 연결을 찾는다.";
        List<Finding> f = findings(FINE, edit("", List.of("C2"), "", List.of(
                new RationaleFix(1, good),         // 정답 보기 — 버림
                new RationaleFix(9, good),         // 없는 보기 — 버림
                new RationaleFix(3, "짧다"),        // 30자 미만 — 버림
                new RationaleFix(2, R),            // 원래와 같음 — 버림
                new RationaleFix(4, good)), "", ""), Map.of());

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.RATIONALE_REWRITTEN);
            assertThat(x.suggestion().target()).isEqualTo(FixTarget.RATIONALE);
            assertThat(x.suggestion().choiceIndex()).isEqualTo(3);
            assertThat(x.detail()).contains("옛 오답 설명:");
        });
    }

    @Test
    @DisplayName("원래와 같은 해설을 보내고 걸린 근거도 없으면 지적이 없다")
    void sameTextMakesNoFinding() {
        assertThat(findings(FINE, edit("", List.of(), NEW, List.of(), "", ""), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("풀이가 질문 구절을 내지 않은 문제의 고친 질문은 버린다")
    void questionFixNeedsSolverQuote() {
        assertThat(findings(FINE, edit("", List.of(), "", List.of(), "두 연결을 가르는 값은?", ""), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("풀이가 낸 질문 구절이 있으면 고친 질문을 제안으로 붙인다")
    void questionFixWithSolverQuote() {
        Solve solve = new Solve(1, "", 1, 0, "", "", "두 탭의");
        List<Finding> f = findings(FINE, edit("", List.of(), "", List.of(), "두 연결을 가르는 값은?", ""), Map.of(0, solve));

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.QUESTION_REVEALS);
            assertThat(x.suggestion().target()).isEqualTo(FixTarget.QUESTION);
        });
    }

    @Test
    @DisplayName("자료 어긋남은 결함으로 싣는다")
    void exhibitIssue() {
        assertThat(findings(FINE, edit("", List.of(), "", List.of(), "", "FIN과 ACK 순서가 뒤집혔다"), Map.of()))
                .extracting(Finding::type).containsExactly(FindingType.EXHIBIT_MISMATCH);
    }

    @Test
    @DisplayName("인용이 해설에 없는 근거 줄은 버린다")
    void dropsReasonsWithFabricatedQuotes() {
        List<Finding> f = findings(edit("B3: 「없는 문장」 — 지어냄\nB2: 「두 탭이 같은 서버에 붙는다.」 — 용어", List.of("B2", "B3"),
                NEW, List.of(), "", ""));

        assertThat(f).singleElement().satisfies(x -> assertThat(x.detail()).doesNotContain("지어냄").contains("용어"));
    }

    @Test
    @DisplayName("근거가 모두 지어낸 것이고 코드 검사에도 안 걸리면 고친 해설을 받지 않는다")
    void noTrustedReasonNoRewrite() {
        Edit e = edit("B2: 「없는 문장」 — 지어냄", List.of("B2"), NEW.replace("견준다", "비교한다"), List.of(), "", "");

        assertThat(findings(FINE, e, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("프롬프트는 원래 순서 보기와 정답 표시, 코드 검사 줄을 싣는다")
    void promptCarriesOriginalOrderAndFormIssues() {
        Shown shuffled = new Shown(0, item(), List.of(2, 0, 3, 1));
        String prompt = ProblemEditor.buildPrompt(List.of(shuffled), Map.of(0, new Solve(1, "풀이 이유", 2, 0, "", "", "")),
                SOURCE, Map.of(0, List.of(new FormIssue("B3", "걸음이 모자람 (1줄, 기준 3줄 이상)"))));

        assertThat(prompt).contains(MD)
                .contains("1) 접속한 쪽의 포트 번호 ← 정답")
                .contains("2) 서버 포트")
                .contains("코드 검사: [B3] 걸음이 모자람")
                .contains("풀이 이유");
    }

    @Test
    @DisplayName("편집 프롬프트에 생성 프롬프트의 해설·오답 설명 절이 붙는다")
    void promptCarriesGenerationGuide() {
        assertThat(ProblemEditor.PROMPT).contains("[해설]").contains("[오답 설명]").doesNotContain("[제목]");
        assertThat(ProblemSolver.PROMPT).contains("answerInQuestionQuote");
    }

    @Test
    @DisplayName("코드가 짧은 오답 설명을 찾았는데 편집이 고치지 않으면 고친 안 없이 지적한다")
    void unfixedShortRationaleIsReported() {
        List<GeneratedChoice> choices = new java.util.ArrayList<>(item(NEW).choices());
        choices.set(3, new GeneratedChoice("도착 순서", false, "틀렸다."));
        Shown s = new Shown(0, new GeneratedProblemItem("두 탭의 연결을 가르는 값은?", "", NEW, choices), List.of(0, 1, 2, 3));

        assertThat(findings(s, edit("", List.of(), "", List.of(), "", ""), Map.of())).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(FindingType.EXPLANATION_SUGGESTED);
            assertThat(x.message()).contains("C2");
        });
    }
}
