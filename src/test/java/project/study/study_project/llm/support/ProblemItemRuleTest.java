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

    /**
     * 고급은 중급의 다섯 중 셋만 쓴다 — 2026-08-25.
     *
     * <p>전에는 고급을 "지문에 상황과 이미 시도한 것이 있다"로 못 박아 사실상 상황형 하나였는데,
     * 재료를 열어 보니 더 넓었다({@code ### 흔한 오해}, {@code ## 면접에서 이렇게 물어본다}).
     * 다만 판정·순서 둘은 닫아 뒀고, 닫아 둔 것이 <b>지켜지는지</b>를 여기서 잰다.
     */
    @Nested
    @DisplayName("고급에 열어 둔 형태는 셋뿐")
    class AdvancedKinds {

        private List<String> warnings(QuestionKind kind) {
            return ProblemItemRule.qualityWarningsOf(
                    item("가".repeat(200) + ". 이때 옳은 판단은?", goodExplanation(), kind),
                    Difficulty.ADVANCED, true);
        }

        @Test
        @DisplayName("상황·비교·인과는 통과한다")
        void allowsTheThreeOpenedKinds() {
            for (QuestionKind k : List.of(QuestionKind.SITUATION, QuestionKind.COMPARISON, QuestionKind.CAUSE)) {
                assertThat(warnings(k))
                        .as("고급에 연 형태다: %s", k)
                        .noneSatisfy(w -> assertThat(w).contains("고급에 열지 않은 형태"));
            }
        }

        /**
         * 판정형은 중급 판정형과 겉모습이 같아 오답 설계로만 갈리는데, 그 구분이 이 프롬프트에서
         * 가장 자주 무너진다. 순서형은 정답이 하나로 떨어져 "넷 다 그럴듯"이 성립하지 않는다.
         */
        @Test
        @DisplayName("판정·순서는 경고한다 — 하나는 중급과 겹치고 하나는 고급의 정의와 부딪힌다")
        void warnsOnClosedKinds() {
            assertThat(warnings(QuestionKind.JUDGMENT))
                    .anySatisfy(w -> assertThat(w).contains("고급에 열지 않은 형태").contains("진술 판정"));
            assertThat(warnings(QuestionKind.SEQUENCE))
                    .anySatisfy(w -> assertThat(w).contains("고급에 열지 않은 형태").contains("순서·절차"));
        }

        /** 중급에서는 다섯이 전부 열려 있다 — 고급 규칙이 중급까지 번지면 안 된다. */
        @Test
        @DisplayName("같은 형태라도 중급에서는 경고하지 않는다")
        void doesNotLeakIntoIntermediate() {
            assertThat(ProblemItemRule.qualityWarningsOf(
                    item("가".repeat(200) + ". 이때 옳은 판단은?", goodExplanation(), QuestionKind.SEQUENCE),
                    Difficulty.INTERMEDIATE, true))
                    .noneSatisfy(w -> assertThat(w).contains("고급에 열지 않은 형태"));
        }

        @Test
        @DisplayName("유형을 선언하지 않은 옛 초안은 건너뛴다")
        void skipsWhenKindIsUnknown() {
            assertThat(warnings(null))
                    .noneSatisfy(w -> assertThat(w).contains("고급에 열지 않은 형태"));
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

        /**
         * <b>2026-08-25 실물 오탐 — 다섯 중 넷이 헛걸렸다.</b> 첫 패턴은 {@code 문서의 '○○' 절}
         * 이라는 한 가지 모양만 봤는데, 실제로는 절 이름 뒤에 하위 항목이 붙어 "절"이 문장
         * 중간에 오고 "대목"·"문답"으로 끝나는 경우가 더 많았다. 프롬프트가 요구한 것보다
         * <b>더 정확하게</b> 가리킨 해설들이 벌을 받은 셈이다.
         *
         * <p>아래는 그날 실제로 나온 다섯 줄이다. 패턴을 다시 좁히면 여기서 걸린다.
         */
        @Test
        @DisplayName("절 뒤에 하위 항목이 붙어도 가리킨 것으로 본다 — 실물 다섯 줄을 그대로 박아 둔다")
        void acceptsSectionHintsWithSubItems() {
            List<String> real = List.of(
                    "(문서의 '왜 이렇게 설계됐는가' 절을 다시 읽어 보라)",
                    "(문서의 '언제 깨지는가' 중 '서버가 능동 종료자가 되는 배치' 대목을 다시 읽어 보라)",
                    "(문서의 '실무에서는 이렇게 쓴다' 절을 다시 읽어 보라)",
                    "(문서의 '언제 깨지는가' 중 'SO_REUSEADDR에 대한 과신' 대목을 다시 읽어 보라)",
                    "(문서의 '면접에서 이렇게 물어본다' 중 TIME_WAIT 10만 개 접근 문답을 다시 읽어 보라)");

            for (String line : real) {
                String explanation = "정답 근거를 길게 설명한다. ".repeat(20) + line;
                assertThat(warnings(explanation, true))
                        .as("이 줄이 헛걸리면 경고가 통째로 죽는다: %s", line)
                        .noneSatisfy(w -> assertThat(w).contains("다시 읽을 문서 절이 없음"));
            }
        }
    }

    @Nested
    @DisplayName("배치 전체를 봐야 아는 것 — 유형 쏠림과 문형 반복")
    class BatchBalance {

        /** 지문의 마지막 물음을 하나씩 다르게 준다 — 문형 반복 검사에 걸리지 않게. */
        private List<GeneratedProblemItem> batch(QuestionKind... kinds) {
            List<GeneratedProblemItem> out = new java.util.ArrayList<>();
            for (int i = 0; i < kinds.length; i++) {
                out.add(item("가".repeat(200) + ". 물음 " + i + "은?", goodExplanation(), kinds[i]));
            }
            return out;
        }

        /**
         * <b>이 검사가 뒤집힌 이유</b>다. 처음에는 하한("최소 2개")이었는데 실물은 정반대였다 —
         * 5문제 중 5개가 상황형이었고, 이어서 1건씩 세 번 더 뽑았는데 세 번 다 상황형이었다.
         * 상황형은 재료가 가장 풍부해서 내버려 두면 전부를 차지한다.
         */
        @Test
        @DisplayName("상황형이 상한을 넘으면 경고한다 — 내버려 두면 전부를 차지한다")
        void warnsWhenSituationsExceedTheCap() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.SITUATION,
                            QuestionKind.COMPARISON, QuestionKind.CAUSE),
                    Difficulty.INTERMEDIATE))
                    .anySatisfy(w -> assertThat(w).contains("상황 적용형이 3개").contains("상한 2개"));
        }

        /** 상한이지 목표가 아니다 — 상황형이 하나뿐이어도 조용해야 한다. */
        @Test
        @DisplayName("상한 안이면 아무 말도 하지 않는다 — 상한이지 목표가 아니다")
        void staysQuietWithinTheCap() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.COMPARISON, QuestionKind.JUDGMENT),
                    Difficulty.INTERMEDIATE))
                    .isEmpty();
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.COMPARISON, QuestionKind.CAUSE), Difficulty.INTERMEDIATE))
                    .as("상황형이 0개여도 이제는 경고하지 않는다")
                    .isEmpty();
        }

        /** 초급은 정의를 묻는 자리라 형태를 나눌 것이 없고, 고급은 정의상 언제나 상황형이다. */
        @Test
        @DisplayName("유형 쏠림은 중급에만 잰다 — 고급은 정의상 전부 상황형이라 매번 울린다")
        void kindBalanceAppliesOnlyToIntermediate() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.SITUATION),
                    Difficulty.ADVANCED))
                    .noneSatisfy(w -> assertThat(w).contains("상황 적용형"));
        }

        @Test
        @DisplayName("아무도 유형을 선언하지 않은 옛 배치는 유형 쏠림을 재지 않는다")
        void skipsKindBalanceWithoutDeclaredKinds() {
            assertThat(ProblemItemRule.batchWarningsOf(batch(null, null, null), Difficulty.INTERMEDIATE))
                    .noneSatisfy(w -> assertThat(w).contains("상황 적용형"));
        }

        /**
         * <b>형태를 갈라도 문형이 같으면 소용없다.</b> 2026-08-25 실측에서 SYSTEM_DESIGN 중급
         * 6문제 중 다섯이 "~가장 적절한 것은?"으로 끝났다. 반면 NETWORK 5문제는 다섯이 전부 달랐다 —
         * 같은 상황형인데 한쪽만 쏠렸다는 것이 "형태가 아니라 문형이 문제"라는 증거다.
         */
        @Test
        @DisplayName("마지막 물음이 겹치면 경고한다 — 문형이 같으면 개념이 아니라 형식이 외워진다")
        void warnsWhenTheClosingQuestionRepeats() {
            List<GeneratedProblemItem> same = List.of(
                    item("배달 앱에서 값이 안 바뀐다. 원인으로 가장 적절한 것은?",
                            goodExplanation(), QuestionKind.SITUATION),
                    item("러닝 앱에서 질의가 튄다. 원인으로 가장 적절한 것은?",
                            goodExplanation(), QuestionKind.COMPARISON));

            assertThat(ProblemItemRule.batchWarningsOf(same, Difficulty.INTERMEDIATE))
                    .anySatisfy(w -> assertThat(w)
                            .contains("마지막 물음이 겹침")
                            .as("무엇이 겹쳤는지 보여야 고칠 자리가 정해진다")
                            .contains("원인으로 가장 적절한 것은?"));
        }

        /** 유형 쏠림과 달리 문형 반복은 난이도를 가리지 않는다 — 고급 지문도 물음으로 끝난다. */
        @Test
        @DisplayName("문형 반복은 고급에서도 잰다 — 유형 쏠림만 중급 전용이다")
        void tailRepetitionAlsoAppliesToAdvanced() {
            List<GeneratedProblemItem> same = List.of(
                    item("정산 배치가 밀린다. 가장 적절한 조치는?", goodExplanation(), null),
                    item("이미지 서버가 느리다. 가장 적절한 조치는?", goodExplanation(), null));

            assertThat(ProblemItemRule.batchWarningsOf(same, Difficulty.ADVANCED))
                    .anySatisfy(w -> assertThat(w).contains("마지막 물음이 겹침"));
        }

        /**
         * <b>알려진 한계를 못 박는다.</b> 지문이 한 문장뿐이면(초급이 대개 그렇다) 잘라 낼
         * 문장 경계가 없어 <b>지문 전체</b>가 비교 대상이 된다. 그래서 "○○의 정의로 옳은 것은?"처럼
         * <b>접미사만</b> 같은 것은 걸리지 않는다.
         *
         * <p>일부러 이렇게 뒀다. 접미사로 비교하면 초급이 매번 걸리는데, 초급에서 "정의로 옳은
         * 것은?"이 반복되는 것은 <b>정상</b>이다 — 같은 것을 묻는 자리라 형식이 같은 편이 낫다.
         * 잡으려는 것은 중급·고급에서 상황 한 문단을 써 놓고 물음만 복사하는 경우다.
         */
        @Test
        @DisplayName("한 문장짜리 지문은 접미사가 같아도 안 걸린다 — 초급의 정형은 정상이다")
        void doesNotFlagSharedSuffixInSingleSentenceQuestions() {
            List<GeneratedProblemItem> beginner = List.of(
                    item("핸드셰이크의 정의로 옳은 것은?", goodExplanation(), null),
                    item("TIME_WAIT의 정의로 옳은 것은?", goodExplanation(), null));

            assertThat(ProblemItemRule.batchWarningsOf(beginner, Difficulty.BEGINNER)).isEmpty();
        }

        /**
         * <b>2026-08-25 실물 — 완전 일치로는 못 잡았다.</b> 고급에서 이 짝이 나왔다:
         * "가장 적절한 조치는?"과 "이 상황에서 가장 적절한 조치는?". 문자열로는 다르지만
         * 학습자에게는 같은 물음이다. 앞에 말 몇 개만 붙이면 빠져나가는 검사는 있으나 마나다.
         */
        @Test
        @DisplayName("앞에 말만 덧붙인 물음도 겹친 것으로 본다 — 문자열이 달라도 학습자에겐 같다")
        void catchesTailsThatOnlyDifferByAPrefix() {
            List<GeneratedProblemItem> real = List.of(
                    item("로그인 API에 TIME_WAIT가 많다. 가장 적절한 조치는?",
                            goodExplanation(), QuestionKind.SITUATION),
                    item("쿠폰 API에서 소켓이 는다. 이 상황에서 가장 적절한 조치는?",
                            goodExplanation(), QuestionKind.SITUATION));

            assertThat(ProblemItemRule.batchWarningsOf(real, Difficulty.ADVANCED))
                    .anySatisfy(w -> assertThat(w)
                            .contains("마지막 물음이 겹침")
                            .as("겹친 부분만 보여야 어디를 바꿀지 정해진다")
                            .contains("가장 적절한 조치는?"));
        }

        /**
         * 접미사 판정으로 바꾸면서 <b>초급을 잡지 않는다</b>는 성질이 유지되는지 다시 확인한다.
         * "끝 N글자 비교"로 했다면 여기서 걸렸을 것이다 — 두 물음의 끝 10글자가 같다.
         * 서로 접미사가 아니므로 통과해야 한다.
         */
        @Test
        @DisplayName("접미사가 아니면 끝이 비슷해도 통과한다 — 초급의 정형을 잡으면 안 된다")
        void stillIgnoresBeginnerBoilerplate() {
            List<GeneratedProblemItem> beginner = List.of(
                    item("핸드셰이크의 정의로 옳은 것은?", goodExplanation(), null),
                    item("TIME_WAIT의 정의로 옳은 것은?", goodExplanation(), null));

            assertThat(ProblemItemRule.batchWarningsOf(beginner, Difficulty.BEGINNER)).isEmpty();
        }

        @Test
        @DisplayName("물음이 서로 다르면 조용하다 — 오탐이 경고를 무력화한다")
        void staysQuietWhenClosingQuestionsDiffer() {
            List<GeneratedProblemItem> varied = List.of(
                    item("배달 앱에서 값이 안 바뀐다. 원인으로 가장 적절한 것은?",
                            goodExplanation(), QuestionKind.SITUATION),
                    item("러닝 앱에서 질의가 튄다. 가장 먼저 할 확인은?",
                            goodExplanation(), QuestionKind.COMPARISON));

            assertThat(ProblemItemRule.batchWarningsOf(varied, Difficulty.INTERMEDIATE)).isEmpty();
        }
    }
}
