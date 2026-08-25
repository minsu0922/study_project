package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.QuestionKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-25에 들어온 검사들을 못 박는 테스트.
 *
 * <p><b>왜 이제야 생겼나.</b> 이 규칙들은 지금까지 배치 CLI에서만 돌았고, 검증도
 * {@code DraftGeneratorCliTest}가 <b>배치 관점으로</b> 해 왔다("5개 중 몇 개가 쓸 만한가").
 * 그런데 같은 날 검사가 검수 화면으로도 흘러가면서 규칙 자체를 직접 겨누는 자리가 필요해졌다 —
 * 배치를 통해서만 재면 "경고 문구가 바뀌었는데 아무도 몰랐다"가 생긴다.
 *
 * <p>여기서 재는 것은 <b>새 규칙과 그 경계</b>다. 기존 규칙(제목 길이·마크다운·정답 길이 편향)은
 * 이미 배치 테스트가 덮고 있어 옮겨 오지 않았다 — 같은 것을 두 곳에서 재면 한쪽만 고쳐진다.
 */
class ProblemItemRuleTest {

    private static GeneratedProblemItem item(String question, String explanation, QuestionKind kind) {
        return new GeneratedProblemItem(question, "", explanation, List.of(
                new GeneratedProblemItem.GeneratedChoice("정답 보기 내용입니다", true),
                new GeneratedProblemItem.GeneratedChoice("오답 보기 하나입니다", false),
                new GeneratedProblemItem.GeneratedChoice("오답 보기 둘입니다", false),
                new GeneratedProblemItem.GeneratedChoice("오답 보기 셋입니다", false)),
                "", "제목", kind);
    }

    /** 오답 셋을 내용으로 인용하고 문서 절까지 가리키는, 규칙을 다 지킨 해설. */
    private static String goodExplanation() {
        return "정답인 이유는 갱신 시 캐시에 값을 넣으면 늦게 도착한 조회가 옛 값을 덮기 때문이다. "
                + "\"오답 보기 하나입니다\"는 커밋 순서를 뒤집어 이해한 것이고, "
                + "\"오답 보기 둘입니다\"는 TTL과 명시적 무효화를 뒤섞은 오해다. "
                + "\"오답 보기 셋입니다\"는 키가 여럿인 상황과 혼동한 것이다. "
                + "(문서의 '왜 이렇게 설계됐는가' 절을 다시 읽어 보라)";
    }

    @Nested
    @DisplayName("해설이 보기를 번호로 가리키면 차단한다")
    class ChoiceNumberReferenceIsBlocking {

        /**
         * 2026-08-25에 경고에서 차단으로 올렸다. 보기를 섞어 내보내기로 한 이상 번호로 가리킨
         * 해설은 <b>반드시</b> 틀리고, 검수자는 섞이기 전 화면을 보므로 번호가 맞아 보인다 —
         * 사람 눈으로는 영영 안 걸린다. 경고로 두면 그대로 승인된다.
         */
        @Test
        @DisplayName("\"두 번째 보기는\"이 든 해설은 저장 전에 버린다")
        void rejectsOrdinalReference() {
            GeneratedProblemItem bad = item("지문", "두 번째 보기는 MVCC를 락 기반과 혼동한 것이다.", null);

            assertThat(ProblemItemRule.defectOf(bad, ProblemType.MULTIPLE_CHOICE))
                    .contains("번호로 가리킴");
            assertThat(ProblemItemRule.isUsable(bad, ProblemType.MULTIPLE_CHOICE)).isFalse();
        }

        @Test
        @DisplayName("내용으로 인용한 해설은 통과한다 — 이것이 프롬프트가 요구하는 형태다")
        void allowsQuotedReference() {
            GeneratedProblemItem good = item("지문",
                    "\"MVCC도 읽기에 공유 락을 건다\"는 보기는 혼동한 것이다.", null);

            assertThat(ProblemItemRule.defectOf(good, ProblemType.MULTIPLE_CHOICE)).isNull();
        }

        /**
         * 해설이 비어도 차단은 아니다 — 해설 없음은 경고다. 차단 검사가 defectOf로 옮겨 오면서
         * 빈 해설이 이 정규식을 타는 경로가 생겼는데, 거기서 NPE가 나면 <b>생성 전체가 죽는다</b>.
         */
        @Test
        @DisplayName("해설이 비어 있어도 여기서 터지지 않는다")
        void toleratesBlankExplanation() {
            assertThat(ProblemItemRule.defectOf(item("지문", null, null), ProblemType.MULTIPLE_CHOICE))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("중급 지문 길이는 유형에 따라 다르게 잰다")
    class QuestionLengthByKind {

        private static String repeat(int n) {
            return "가".repeat(n);
        }

        private List<String> warnings(String question, QuestionKind kind) {
            return ProblemItemRule.qualityWarningsOf(
                    item(question, goodExplanation(), kind), Difficulty.INTERMEDIATE, true);
        }

        /**
         * <b>이 기능이 생긴 이유</b>다. 상한(250)만 있던 동안 실측 다섯이 118~173자로 상한
         * 근처에도 안 갔다 — 상한은 아무 일도 하지 않고 있었고, 정작 문제는 짧아서 흐린 것이었다.
         */
        @Test
        @DisplayName("상황형 지문이 하한에 못 미치면 경고한다")
        void warnsWhenSituationQuestionIsTooShort() {
            assertThat(warnings(repeat(ProblemItemRule.SITUATION_QUESTION_MIN - 1), QuestionKind.SITUATION))
                    .anySatisfy(w -> assertThat(w).contains("상황형 지문이 짧음"));

            assertThat(warnings(repeat(ProblemItemRule.SITUATION_QUESTION_MIN), QuestionKind.SITUATION))
                    .noneSatisfy(w -> assertThat(w).contains("상황형 지문이 짧음"));
        }

        /**
         * 나머지 네 형태는 <b>짧은 것이 정상</b>이다. 진술 판정형은 지문이 한 줄이고 보기가 본문이다.
         * 구분 없이 하한을 걸면 짧아도 되는 문제에 군더더기를 붙이게 만든다 —
         * 지금 고치려는 것과 정확히 반대 방향의 사고다.
         */
        @Test
        @DisplayName("비교·판정형은 짧아도 경고하지 않는다")
        void doesNotWarnForNonSituationKinds() {
            assertThat(warnings(repeat(60), QuestionKind.COMPARISON))
                    .noneSatisfy(w -> assertThat(w).contains("지문이 짧음"));
            assertThat(warnings(repeat(60), QuestionKind.JUDGMENT))
                    .noneSatisfy(w -> assertThat(w).contains("지문이 짧음"));
        }

        /**
         * 유형을 도입하기 전에 만들어진 초안이 갑자기 경고를 달고 나오면 검수자가 경고 전체를
         * 안 보게 된다 — 이 저장소가 이미 겪은 실패 방식이다.
         */
        @Test
        @DisplayName("유형을 선언하지 않은 옛 초안은 길이 하한을 적용하지 않는다")
        void skipsLengthRuleWhenKindIsUnknown() {
            assertThat(warnings(repeat(60), null))
                    .noneSatisfy(w -> assertThat(w).contains("지문이 짧음"));
        }

        @Test
        @DisplayName("상한을 넘기면 유형과 무관하게 경고한다 — 길면 무엇을 묻는지가 흐려진다")
        void warnsWhenQuestionExceedsMax() {
            assertThat(warnings(repeat(ProblemItemRule.INTERMEDIATE_QUESTION_MAX + 1), QuestionKind.JUDGMENT))
                    .anySatisfy(w -> assertThat(w).contains("중급 지문이 김"));
        }
    }

    @Nested
    @DisplayName("해설이 요구받은 것을 실제로 담았는지 잰다")
    class ExplanationContent {

        private List<String> warnings(String explanation, boolean hasSourceDocument) {
            return ProblemItemRule.qualityWarningsOf(
                    item("가".repeat(200), explanation, QuestionKind.SITUATION),
                    Difficulty.INTERMEDIATE, hasSourceDocument);
        }

        /**
         * 프롬프트는 "오답마다 어떤 오해인지 밝혀라"를 요구하는데 재는 사람이 없었다.
         * 따옴표 인용 개수는 대리 지표라 <b>모자랄 때만</b> 말한다 — 한쪽으로만 트는 검사가
         * 오탐이 훨씬 적다.
         */
        @Test
        @DisplayName("오답 셋 중 하나만 짚은 해설은 경고한다")
        void warnsWhenFewerChoicesAreAddressed() {
            String lazy = "정답인 이유는 캐시에 값을 넣으면 늦게 온 조회가 덮기 때문이다. "
                    + "\"오답 보기 하나입니다\"는 순서를 뒤집어 이해한 것이다. "
                    + "나머지도 비슷한 오해다. (문서의 '왜 이렇게 설계됐는가' 절을 다시 읽어 보라)";

            assertThat(warnings(lazy, true))
                    .anySatisfy(w -> assertThat(w).contains("짚은 오답이 적음"));
        }

        @Test
        @DisplayName("오답 셋을 다 인용한 해설은 경고하지 않는다")
        void acceptsFullyAddressedExplanation() {
            assertThat(warnings(goodExplanation(), true))
                    .noneSatisfy(w -> assertThat(w).contains("짚은 오답이 적음"));
        }

        /** 학습자가 틀린 뒤 돌아갈 유일한 입구다. 빠지면 해설이 그 자리에서 끝나 버린다. */
        @Test
        @DisplayName("근거 문서가 있는데 다시 읽을 절을 안 가리키면 경고한다")
        void warnsWhenDocumentSectionHintIsMissing() {
            String noHint = goodExplanation().replaceAll("\\(문서의.*", "");

            assertThat(warnings(noHint, true))
                    .anySatisfy(w -> assertThat(w).contains("다시 읽을 문서 절이 없음"));
        }

        /**
         * 근거 없이 만든 문제나 올린 파일로 만든 문제는 가리킬 문서가 없다. 거기까지 경고하면
         * 헛울리는 경고가 되고, 그러면 다음부터 아무도 안 본다.
         */
        @Test
        @DisplayName("근거 문서가 없으면 그 경고를 내지 않는다")
        void skipsDocumentHintWhenThereIsNoSource() {
            String noHint = goodExplanation().replaceAll("\\(문서의.*", "");

            assertThat(warnings(noHint, false))
                    .noneSatisfy(w -> assertThat(w).contains("다시 읽을 문서 절이 없음"));
        }
    }

    @Nested
    @DisplayName("배치 전체를 봐야 아는 것 — 유형 쏠림")
    class BatchBalance {

        private List<GeneratedProblemItem> batch(QuestionKind... kinds) {
            return java.util.Arrays.stream(kinds)
                    .map(k -> item("가".repeat(200), goodExplanation(), k))
                    .toList();
        }

        @Test
        @DisplayName("상황형이 기준에 못 미치면 경고한다 — 면접에서 가장 많이 나오는 형태다")
        void warnsWhenSituationsAreScarce() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.COMPARISON, QuestionKind.CAUSE,
                            QuestionKind.JUDGMENT, QuestionKind.SEQUENCE),
                    Difficulty.INTERMEDIATE))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("상황 적용형이 1개뿐");
        }

        @Test
        @DisplayName("기준을 채우면 아무 말도 하지 않는다")
        void staysQuietWhenBalanced() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.COMPARISON),
                    Difficulty.INTERMEDIATE))
                    .isEmpty();
        }

        /** 초급은 정의를 묻는 자리라 형태를 나눌 것이 없고, 고급은 정의상 언제나 상황형이다. */
        @Test
        @DisplayName("중급이 아니면 재지 않는다 — 매번 울리는 경고는 아무도 안 본다")
        void appliesOnlyToIntermediate() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.COMPARISON, QuestionKind.COMPARISON), Difficulty.BEGINNER))
                    .isEmpty();
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.COMPARISON, QuestionKind.COMPARISON), Difficulty.ADVANCED))
                    .isEmpty();
        }

        @Test
        @DisplayName("아무도 유형을 선언하지 않은 옛 배치는 통째로 건너뛴다")
        void skipsBatchesWithoutDeclaredKinds() {
            assertThat(ProblemItemRule.batchWarningsOf(batch(null, null, null), Difficulty.INTERMEDIATE))
                    .isEmpty();
        }
    }
}
