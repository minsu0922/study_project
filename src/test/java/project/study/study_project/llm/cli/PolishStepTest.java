package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.DocumentPolisher;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.support.TopicBrief;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 윤문본을 받아들일지 정한다 — 하나라도 걸리면 원본을 쓴다(설계 2026-11-18 "윤문본 채택 기준"). */
class PolishStepTest {

    private static final String BOX = "주소창에 이름을 치면 쇼핑몰이 뜬다.\n\n이 글은 그 과정을 따라간다.";

    private static final String BODY = """
            # 이름 풀이

            > **글의 주제**
            >
            > 주소창에 이름을 치면 쇼핑몰이 뜬다.
            >
            > 이 글은 그 과정을 따라간다.

            ## 무엇인가

            리졸버에 대해 설명하는 것이다. 리졸버는 300초 동안 답을 저장한다.
            """;

    private static final GeneratedDocumentItem ORIGINAL =
            new GeneratedDocumentItem("이름 풀이", "name-resolution", BODY, List.of("network"));

    private static DocumentPolisher returning(String body) {
        return (title, content, edition) -> body;
    }

    @Test
    @DisplayName("문장만 고친 윤문본은 채택하고, 제목·식별자·태그는 원본 것을 쓴다")
    void acceptsSentenceOnlyPolish() {
        String polished = BODY.replace("리졸버에 대해 설명하는 것이다.", "리졸버를 설명한다.");

        PolishOutcome outcome = PolishStep.decide(returning(polished), ORIGINAL, BOX);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.chosen()).isEqualTo(
                new GeneratedDocumentItem("이름 풀이", "name-resolution", polished, List.of("network")));
    }

    @Test
    @DisplayName("윤문 호출이 실패하면 원본을 쓴다")
    void keepsOriginalWhenCallFails() {
        DocumentPolisher failing = (t, c, e) -> {
            throw new BusinessException(ErrorCode.LLM_003, "윤문 응답이 MAX_TOKENS에서 잘렸습니다.");
        };

        PolishOutcome outcome = PolishStep.decide(failing, ORIGINAL, BOX);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.chosen()).isSameAs(ORIGINAL);
        assertThat(outcome.reason()).startsWith("윤문 호출 실패").contains("MAX_TOKENS");
    }

    @Test
    @DisplayName("소제목을 바꾸면 원본을 쓴다")
    void keepsOriginalWhenHeadingChanges() {
        PolishOutcome outcome = PolishStep.decide(returning(BODY.replace("## 무엇인가", "## 정의")), ORIGINAL, BOX);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).startsWith("보존 대조 실패").contains("소제목");
    }

    @Test
    @DisplayName("「글의 주제」 상자를 고치면 원본을 쓴다 — brief가 없는 날도 인용 상자 대조가 잡는다")
    void keepsOriginalWhenTopicBoxChanges() {
        String polished = BODY.replace("> 이 글은 그 과정을 따라간다.", "> 이 글은 그 과정을 차례로 따라간다.");

        assertThat(PolishStep.decide(returning(polished), ORIGINAL, BOX).applied()).isFalse();
        assertThat(PolishStep.decide(returning(polished), ORIGINAL, null).reason())
                .startsWith("보존 대조 실패").contains("인용 상자");
    }

    @Test
    @DisplayName("보존 대조를 통과해도 새 형식 경고가 생기면 원본을 쓴다")
    void keepsOriginalWhenNewValidatorWarningAppears() {
        String polished = BODY.replace("리졸버에 대해 설명하는 것이다.", "<mark>리졸버</mark>를 설명한다.");

        PolishOutcome outcome = PolishStep.decide(returning(polished), ORIGINAL, BOX);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).startsWith("새 형식 경고");
    }

    private static final String TWO_SECTIONS = """
            # 이름 풀이

            ## 무엇인가

            리졸버에 대해 설명하는 것이다. 리졸버는 300초 동안 답을 저장한다.

            ## 왜 필요한가

            대부분의 이름은 하루에 한 번도 바뀌지 않는 것이다.
            """;

    private static final GeneratedDocumentItem TWO =
            new GeneratedDocumentItem("이름 풀이", "name-resolution", TWO_SECTIONS, List.of("network"));

    @Test
    @DisplayName("조건어가 빠진 덩어리만 원고로 두고 나머지 윤문은 받는다 — 전에는 한 곳 때문에 편 전체를 버렸다")
    void keepsOnlyFailingProseBlock() {
        String polished = TWO_SECTIONS.replace("리졸버에 대해 설명하는 것이다.", "리졸버를 설명한다.")
                .replace("대부분의 이름은 하루에 한 번도 바뀌지 않는 것이다.", "이름은 하루에 한 번도 바뀌지 않는다.");

        PolishOutcome outcome = PolishStep.decide(returning(polished), TWO, null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.chosen().contentMd())
                .contains("리졸버를 설명한다.")
                .contains("대부분의 이름은 하루에 한 번도 바뀌지 않는 것이다.");
        assertThat(outcome.kept()).singleElement().asString()
                .startsWith("보존 대조 실패").contains("대부분").contains("「## 왜 필요한가」 아래");
    }

    @Test
    @DisplayName("새 형식 경고를 낸 덩어리만 원고로 둔다")
    void keepsOnlyBlockWithNewWarning() {
        String polished = TWO_SECTIONS.replace("리졸버에 대해 설명하는 것이다.", "<mark>리졸버</mark>를 설명한다.")
                .replace("바뀌지 않는 것이다.", "바뀌지 않는다.");

        PolishOutcome outcome = PolishStep.decide(returning(polished), TWO, null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.chosen().contentMd())
                .contains("리졸버에 대해 설명하는 것이다.").contains("바뀌지 않는다.").doesNotContain("<mark>");
        assertThat(outcome.kept()).singleElement().asString().startsWith("새 형식 경고").contains("「## 무엇인가」 아래");
    }

    @Test
    @DisplayName("앞에 안내문을 붙인 응답은 편 전체를 원본으로 둔다 — 첫 덩어리로 받아들이면 안내문이 본문에 남는다")
    void keepsOriginalWhenResponseIsPrefaced() {
        PolishOutcome outcome = PolishStep.decide(returning("다음은 윤문본입니다.\n\n" + TWO_SECTIONS), TWO, null);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).startsWith("보존 대조 실패").contains("첫 줄");
    }

    @Test
    @DisplayName("요약 줄은 원고로 둔 곳을 세 곳까지 적는다")
    void summaryLineListsKeptBlocks() {
        PolishOutcome partial = new PolishOutcome(ORIGINAL, true, null, List.of("a", "b", "c", "d"));

        assertThat(PolishStep.summaryLine("입문편", partial)).isEqualTo("✨ 입문편 윤문 적용 — 원고로 둔 곳 4곳: a; b; c 외 1곳");
    }

    @Test
    @DisplayName("원본에도 있던 경고는 숫자만 달라도 새 경고가 아니다")
    void sameWarningWithDifferentNumberIsNotNew() {
        List<DraftCheck> before = List.of(DraftCheck.warning("그림이 2개 겹칩니다."));
        List<DraftCheck> after = List.of(DraftCheck.warning("그림이 3개 겹칩니다."));

        assertThat(PolishStep.newWarnings(before, after)).isEmpty();
        assertThat(PolishStep.newWarnings(before, List.of(DraftCheck.blocking("HTML 태그가 있습니다."))))
                .containsExactly("HTML 태그가 있습니다.");
    }

    @Test
    @DisplayName("심화편이 없으면 입문편만 윤문한다")
    void polishesBeginnerOnlyWhenAdvancedMissing() {
        AtomicInteger calls = new AtomicInteger();
        DocumentPolisher counting = (t, c, e) -> {
            calls.incrementAndGet();
            return c;
        };
        TopicBrief brief = TopicBrief.withTopicBoxes(null, BOX, null);

        var pair = PolishStep.polishBoth(counting, ORIGINAL, null, brief);

        assertThat(pair.beginner().applied()).isTrue();
        assertThat(pair.advanced()).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("요약 줄은 적용과 원본 유지를 가려 쓴다")
    void summaryLines() {
        assertThat(PolishStep.summaryLine("입문편", new PolishOutcome(ORIGINAL, true, null)))
                .isEqualTo("✨ 입문편 윤문 적용");
        assertThat(PolishStep.summaryLine("심화편", new PolishOutcome(ORIGINAL, false, "수치가 다릅니다: [300]")))
                .isEqualTo("↩️ 심화편 원본 유지 — 수치가 다릅니다: [300]");
    }
}
