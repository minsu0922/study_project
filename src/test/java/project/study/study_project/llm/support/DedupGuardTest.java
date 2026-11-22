package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.DedupProposal;
import project.study.study_project.llm.client.DocumentEdition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DedupGuardTest {

    private static final String KEPT = "서비스는 할 일을 정한다.";
    private static final String DUP = "할 일을 정하는 쪽은 서비스다.";

    private static final String BODY = """
            # 제목

            ## 본론

            첫 문장은 절을 연다. %s 가운데 문장이 있다. %s 끝 문장이 남는다.

            다른 문단이다.
            """.formatted(KEPT, DUP);

    private static DedupGuard.Decision check(String body, DocumentEdition edition, String target, String kept) {
        return DedupGuard.check(body, edition, List.of(new DedupProposal(target, kept, "새 정보 없음")));
    }

    private static String whyRejected(String body, DocumentEdition edition, String target, String kept) {
        DedupGuard.Decision d = check(body, edition, target, kept);
        assertThat(d.accepted()).isEmpty();
        return d.rejected().get(0).why();
    }

    @Test
    @DisplayName("문단 가운데 중복 문장만 지우고 앞뒤 문장을 한 칸으로 잇는다")
    void removesSentenceInsideParagraph() {
        DedupGuard.Decision d = check(BODY, DocumentEdition.BEGINNER, DUP, KEPT);

        assertThat(d.accepted()).hasSize(1);
        assertThat(d.accepted().get(0).section()).isEqualTo("본론");
        assertThat(DedupGuard.apply(BODY, d.accepted()))
                .doesNotContain(DUP)
                .contains("가운데 문장이 있다. 끝 문장이 남는다.");
    }

    @Test
    @DisplayName("문단이 통째로 비면 빈 줄을 겹치지 않게 지운다")
    void removesWholeParagraph() {
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n중간 문단이다.\n\n" + DUP + "\n\n끝 문단이다.\n";

        DedupGuard.Decision d = check(body, DocumentEdition.BEGINNER, DUP, KEPT);

        assertThat(DedupGuard.apply(body, d.accepted()))
                .isEqualTo("# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n중간 문단이다.\n\n끝 문단이다.\n");
    }

    @Test
    @DisplayName("본문에 없거나 두 번 나오는 문장은 버린다")
    void rejectsMissingOrAmbiguousText() {
        assertThat(whyRejected(BODY, DocumentEdition.BEGINNER, "없는 문장이다.", KEPT)).contains("찾지 못함");
        String twice = BODY + "\n" + DUP + "\n";
        assertThat(whyRejected(twice, DocumentEdition.BEGINNER, DUP, KEPT)).contains("찾지 못함");
    }

    @Test
    @DisplayName("남길 문장이 뒤에 있으면 버린다")
    void rejectsKeptAfterTarget() {
        assertThat(whyRejected(BODY, DocumentEdition.BEGINNER, KEPT, DUP)).contains("앞에 없음");
    }

    @Test
    @DisplayName("문장 중간을 자르면 버린다")
    void rejectsPartialSentence() {
        assertThat(whyRejected(BODY, DocumentEdition.BEGINNER, "정하는 쪽은 서비스다.", KEPT)).contains("문장 처음");
        assertThat(whyRejected(BODY, DocumentEdition.BEGINNER, "할 일을 정하는 쪽은", KEPT)).contains("문장 끝");
    }

    @Test
    @DisplayName("글의 주제·정리·오해·면접 절의 문장은 지우지 않는다")
    void rejectsProtectedSections() {
        String summary = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n## 정리\n\n답이다. " + DUP + " 끝이다.\n";
        assertThat(whyRejected(summary, DocumentEdition.BEGINNER, DUP, KEPT)).contains("지우면 안 되는 절");

        String myth = "# 제목\n\n## 실무에서는 이렇게 쓴다\n\n첫 문장이다. " + KEPT
                + "\n\n### 헷갈리기 쉬운 점\n\n읽는 법이다. " + DUP + " 끝이다.\n";
        assertThat(whyRejected(myth, DocumentEdition.BEGINNER, DUP, KEPT)).contains("지우면 안 되는 절");

        String interview = "# 제목\n\n## 어떤 때 통하지 않는가\n\n첫 문장이다. " + KEPT
                + "\n\n## 면접에서 이렇게 물어본다\n\n여는 문장이다. " + DUP + " 끝이다.\n";
        assertThat(whyRejected(interview, DocumentEdition.ADVANCED, DUP, KEPT)).contains("지우면 안 되는 절");
    }

    @Test
    @DisplayName("코드·표·목록 안의 문장과 소제목 바로 아래 첫 문장은 지우지 않는다")
    void rejectsStructuralPositions() {
        String list = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n- " + DUP + "\n";
        assertThat(whyRejected(list, DocumentEdition.BEGINNER, DUP, KEPT)).contains("일반 문단이 아님");

        String first = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n### 소제목\n\n" + DUP + " 끝이다.\n";
        assertThat(whyRejected(first, DocumentEdition.BEGINNER, DUP, KEPT)).contains("첫 문장");
    }

    @Test
    @DisplayName("그림·코드 바로 앞뒤 문장은 지우지 않는다")
    void rejectsSentenceNextToFigure() {
        String before = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n둘째 문장이다. " + DUP
                + "\n\n```mermaid\nflowchart TD\n```\n";
        assertThat(whyRejected(before, DocumentEdition.BEGINNER, DUP, KEPT)).contains("바로 앞뒤");

        String after = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + "\n\n```java\nint a;\n```\n\n" + DUP + " 끝이다.\n";
        assertThat(whyRejected(after, DocumentEdition.BEGINNER, DUP, KEPT)).contains("바로 앞뒤");
    }

    @Test
    @DisplayName("굵은 글씨로 용어를 푸는 문장은 지우지 않는다")
    void rejectsBoldDefinition() {
        String bold = "**서비스**는 할 일을 정하는 층이다.";
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + " " + bold + " 끝이다.\n";
        assertThat(whyRejected(body, DocumentEdition.BEGINNER, bold, KEPT)).contains("굵은 글씨");
    }

    @Test
    @DisplayName("지울 문장의 수치·코드·정도 부사·가능 표현이 남길 문장에 없으면 버린다")
    void rejectsLostFacts() {
        assertThat(lost("칸이 5개다.", "칸이 여러 개다.")).contains("5");
        assertThat(lost("`memo` 칸이 생긴다.", "새 칸이 생긴다.")).contains("memo");
        assertThat(lost("보통 서비스에 둔다.", "규칙은 서비스로 모은다.")).contains("보통");
        assertThat(lost("서비스에 둘 수 있다.", "서비스에 둔다.")).contains("가능 표현");
    }

    private static String lost(String target, String kept) {
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + kept + " 둘째 문장이다. " + target + " 끝이다.\n";
        return whyRejected(body, DocumentEdition.BEGINNER, target, kept);
    }

    @Test
    @DisplayName("다음 문장이 지울 문장을 받으면 버린다")
    void rejectsWhenNextSentenceRefersBack() {
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + " 둘째 문장이다. " + DUP + " 그래서 코드가 짧다.\n";
        assertThat(whyRejected(body, DocumentEdition.BEGINNER, DUP, KEPT)).contains("뒷문장");
    }

    @Test
    @DisplayName("입문편 중급 재료 구역의 문장은 같은 구역에 남는 문장이 있어야 지운다")
    void keepsBeginnerIntermediateMaterial() {
        String outside = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT
                + "\n\n## 실무에서는 이렇게 쓴다\n\n첫 문장이다2. 둘째 문장이다. " + DUP + " 끝이다.\n";
        assertThat(whyRejected(outside, DocumentEdition.BEGINNER, DUP, KEPT)).contains("문제 재료");

        String inside = "# 제목\n\n## 실무에서는 이렇게 쓴다\n\n첫 문장이다. " + KEPT + " 둘째 문장이다. " + DUP + " 끝이다.\n";
        assertThat(check(inside, DocumentEdition.BEGINNER, DUP, KEPT).accepted()).hasSize(1);
    }

    @Test
    @DisplayName("심화편 고급 재료 구역의 문장은 본론 문장으로 대신할 수 없다")
    void keepsAdvancedMaterial() {
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT
                + "\n\n## 어떤 때 통하지 않는가\n\n첫 문장이다2. 둘째 문장이다. " + DUP + " 끝이다.\n";
        assertThat(whyRejected(body, DocumentEdition.ADVANCED, DUP, KEPT)).contains("문제 재료");
    }

    @Test
    @DisplayName("남길 문장을 다른 후보가 지우면 뒤 후보를 버린다")
    void rejectsChainedRemoval() {
        String third = "서비스가 할 일을 정한다.";
        String body = "# 제목\n\n## 본론\n\n첫 문장이다. " + KEPT + " 둘째 문장이다. " + DUP + " 셋째 문장이다. " + third + " 끝이다.\n";

        DedupGuard.Decision d = DedupGuard.check(body, DocumentEdition.BEGINNER, List.of(
                new DedupProposal(third, DUP, "연쇄"),
                new DedupProposal(DUP, KEPT, "중복")));

        assertThat(d.accepted()).extracting(DedupGuard.Accepted::removed).containsExactly(DUP);
        assertThat(d.rejected()).extracting(DedupGuard.Rejected::why).containsExactly("남길 문장이 이미 지워짐");
    }
}
