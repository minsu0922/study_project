package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SourceQuoteRule} — 근거 인용이 진짜 문서에서 왔는지, 그리고 <b>오늘 캘 자리에서</b>
 * 왔는지를 재는 규칙.
 *
 * <p>여기서 못 박는 것은 두 가지다. 하나는 <b>정규화</b> — 조사나 줄바꿈이 달라졌다고
 * "문서 밖"이라 부르면 경고가 매번 헛울리고, 헛울리는 경고는 다음부터 아무도 안 본다.
 * 다른 하나는 <b>침범만 본다</b>는 판정 방향이다. "오늘 지목한 절 안인가"로 재면 중급이
 * 매번 걸린다(설계 근거는 본론에 흩어져 있어도 된다고 프롬프트가 허용한다).
 */
class SourceQuoteRuleTest {

    /**
     * 실제 생성 문서(2026-08-19, hash-table-collision-and-rehashing)의 절 구조를 그대로 줄인 것.
     * 본론 절({@code ## 충돌을 처리하는 두 방식})이 {@code ### 왜 이렇게 설계됐는가}를 품는 배치가
     * 이 검사의 핵심이라, 그 모양을 실물에서 베껴 왔다.
     */
    private static final String DOCUMENT = """
            # 해시 테이블

            ## 무엇인가
            해시 테이블은 키를 계산해 자리를 정하는 표다.

            ## 왜 필요한가
            목록을 처음부터 훑지 않아도 되기 때문이다.

            ## 충돌을 처리하는 두 방식
            체이닝은 같은 자리에 목록을 매단다.

            ### 왜 이렇게 설계됐는가
            적재율을 예측할 수 없어 1을 넘어도 동작이 이어져야 하기 때문이다.

            ## 실무에서는 이렇게 쓴다
            기동 시점에 크기가 정해지면 개방 주소법이 유리하다.

            ## 언제 깨지는가
            적재율이 임계값을 넘는 순간의 삽입 하나가 버킷 배열을 2배로 키운다.

            ## 면접에서 이렇게 물어본다
            재해싱이 왜 지연 스파이크를 만드는지 물어본다.
            """;

    private static final SourceDocument GENERATED =
            new SourceDocument("hash-table", "해시 테이블", DOCUMENT);

    private static final SourceDocument UPLOADED = new SourceDocument(
            "wiki-page", "사내 위키", DOCUMENT, SourceDocument.Kind.UPLOADED);

    /** 고급 전용 절의 문장 — 8/14 사고에서 중급이 미리 먹어 치운 바로 그 재료. */
    private static final String ADVANCED_SENTENCE =
            "적재율이 임계값을 넘는 순간의 삽입 하나가 버킷 배열을 2배로 키운다.";

    @Nested
    @DisplayName("인용이 문서에서 왔는가")
    class QuotePresence {

        @Test
        @DisplayName("근거 문서가 없는 날(폴백)은 검사하지 않는다 — 대조할 원본이 없다")
        void skipsWhenNoSourceDocument() {
            assertThat(SourceQuoteRule.warningOf(itemQuoting(ADVANCED_SENTENCE), null,
                    Difficulty.INTERMEDIATE)).isNull();
        }

        @Test
        @DisplayName("인용이 비어 있으면 알린다 — 근거를 안 밝힌 것도 결함이다")
        void warnsOnBlankQuote() {
            assertThat(SourceQuoteRule.warningOf(itemQuoting("  "), GENERATED, Difficulty.BEGINNER))
                    .contains("근거 인용이 비어 있음");
        }

        @Test
        @DisplayName("문서에 없는 문장을 인용하면 알린다 — 문서 밖에서 끌어온 문제다")
        void warnsWhenQuoteIsNotInDocument() {
            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting("레디스는 단일 스레드로 명령을 처리한다."), GENERATED, Difficulty.BEGINNER))
                    .contains("문서에서 찾지 못함");
        }

        /**
         * 정규화가 없으면 이 검사는 쓸 수 없다. 모델은 문서를 그대로 옮기라는 지시를 받아도
         * 줄바꿈을 눌러 붙이거나 공백 개수를 바꾼다 — 그때마다 "문서 밖"이라 부르면
         * 경고가 일상이 되고, 일상이 된 경고는 없는 것과 같다({@code DocumentDraftValidator} 주석).
         */
        @Test
        @DisplayName("줄바꿈·공백이 달라도 같은 문장으로 본다 — 그러지 않으면 매번 헛울린다")
        void normalizesWhitespaceBeforeComparing() {
            String reflowed = "적재율이   임계값을 넘는 순간의\n삽입 하나가\t버킷 배열을 2배로 키운다.";

            assertThat(SourceQuoteRule.warningOf(itemQuoting(reflowed), GENERATED, Difficulty.ADVANCED))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("남의 난이도가 쓸 절을 캤는가")
    class SectionTrespass {

        /**
         * 2026-08-14 사고의 재현. 중급이 지목하는 절이 문서에 없자 모델이 조용히 다른 절을
         * 캤는데, 그게 하필 고급 전용({@code ## 언제 깨지는가})이라 <b>다음 날 고급이 쓸 재료가
         * 하루 먼저 소진됐다</b>. 지금까지 이걸 보는 장치가 없었다 —
         * {@code PromptEvalCli.missingSections}는 절이 문서에 <b>있는지</b>만 본다.
         */
        @Test
        @DisplayName("중급이 고급 절에서 캐면 알린다 — 다음 날 재료를 미리 먹는다")
        void warnsWhenIntermediateMinesAdvancedSection() {
            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting(ADVANCED_SENTENCE), GENERATED, Difficulty.INTERMEDIATE))
                    .contains("## 언제 깨지는가");
        }

        @Test
        @DisplayName("고급이 자기 절에서 캐면 정상이다")
        void allowsAdvancedMiningItsOwnSection() {
            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting(ADVANCED_SENTENCE), GENERATED, Difficulty.ADVANCED)).isNull();
        }

        /**
         * 중급이 지목하는 {@code ### 왜 이렇게 설계됐는가}는 본론 절 <b>안</b>에 있는 소제목이라,
         * "오늘 지목한 ## 절 안인가"로 재면 정상 동작이 매번 걸린다. 그래서 침범만 본다.
         */
        @Test
        @DisplayName("본론 절에서 캔 것은 봐준다 — 어느 난이도의 전용 절도 아니다")
        void allowsMiningNeutralSections() {
            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting("적재율을 예측할 수 없어 1을 넘어도 동작이 이어져야 하기 때문이다."),
                    GENERATED, Difficulty.INTERMEDIATE)).isNull();

            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting("목록을 처음부터 훑지 않아도 되기 때문이다."),
                    GENERATED, Difficulty.INTERMEDIATE)).isNull();
        }

        /**
         * 업로드 문서에는 {@code ## 무엇인가} 같은 약속된 절이 없다. 절 이름을 모르는 문서에
         * 침범 잣대를 대면 나오는 것은 결함이 아니라 잡음이다 —
         * {@code ClaudeProblemGenerator.sourceFocus}가 업로드 경로를 가른 것과 같은 판단.
         */
        @Test
        @DisplayName("업로드 문서는 절 검사를 건너뛴다 — 어떤 절이 있을지 알 수 없다")
        void skipsSectionCheckForUploadedDocument() {
            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting(ADVANCED_SENTENCE), UPLOADED, Difficulty.INTERMEDIATE))
                    .as("같은 인용이라도 우리 양식이 아니면 '남의 절'을 정의할 수 없다")
                    .isNull();

            assertThat(SourceQuoteRule.warningOf(
                    itemQuoting("이 문장은 문서에 없다."), UPLOADED, Difficulty.INTERMEDIATE))
                    .as("인용이 문서에서 왔는지는 양식과 무관하므로 그대로 본다")
                    .contains("문서에서 찾지 못함");
        }
    }

    /** 인용 말고는 다 멀쩡한 문항 — 이 검사는 인용만 보므로 나머지는 최소한으로 채운다. */
    private static GeneratedProblemItem itemQuoting(String quote) {
        return new GeneratedProblemItem("무엇이 원인인가?", "", "해설", List.of(
                new GeneratedProblemItem.GeneratedChoice("정답", true),
                new GeneratedProblemItem.GeneratedChoice("오답", false)), quote);
    }
}
