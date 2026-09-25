package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.QuestionKind;

import java.util.ArrayList;
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

        /** 상황형은 2026-09-17부터 고급 전용이라, 하한도 고급에서 잰다. */
        private List<String> advancedWarnings(String question, QuestionKind kind) {
            return ProblemItemRule.qualityWarningsOf(
                    item(question, goodExplanation(), kind), Difficulty.ADVANCED, true);
        }

        /**
         * <b>이 기능이 생긴 이유</b>다. 상한(250)만 있던 동안 실측 다섯이 118~173자로 상한
         * 근처에도 안 갔다 — 상한은 아무 일도 하지 않고 있었고, 정작 문제는 짧아서 흐린 것이었다.
         *
         * <p>2026-09-17에 <b>재는 자리가 중급에서 고급으로 옮겨 갔다.</b> 중급에서 상황형을
         * 뺐으니 중급에 이 하한을 대면 형태를 잘못 적은 문제에만 울리는데, 그건 다른 경고가
         * 이미 잡는다 — 한 결함에 메시지가 둘 뜨면 검수자가 둘 다 흘려 읽는다.
         */
        @Test
        @DisplayName("고급 상황형 지문이 하한에 못 미치면 경고한다")
        void warnsWhenSituationQuestionIsTooShort() {
            assertThat(advancedWarnings(repeat(ProblemItemRule.SITUATION_QUESTION_MIN - 1), QuestionKind.SITUATION))
                    .anySatisfy(w -> assertThat(w).contains("상황형 지문이 짧음"));

            assertThat(advancedWarnings(repeat(ProblemItemRule.SITUATION_QUESTION_MIN), QuestionKind.SITUATION))
                    .noneSatisfy(w -> assertThat(w).contains("상황형 지문이 짧음"));
        }

        /**
         * <b>중급에 상황형이 오면 길이가 아니라 형태를 지적한다</b>(2026-09-17).
         * 지문을 늘리라는 말은 이제 틀린 처방이다 — 장면 자체를 지워야 한다.
         */
        @Test
        @DisplayName("중급 상황형은 길이가 아니라 형태로 지적한다 — 늘리라는 말은 틀린 처방이다")
        void warnsAboutKindNotLengthForIntermediateSituation() {
            assertThat(warnings(repeat(80), QuestionKind.SITUATION))
                    .anySatisfy(w -> assertThat(w).contains("중급에 열지 않은 형태"))
                    .noneSatisfy(w -> assertThat(w).contains("지문이 짧음"));
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

        /*
         * "오답 셋 중 하나만 짚은 해설은 경고한다"와 그 짝이 여기 있었다 — 2026-08-27에 지웠다.
         *
         * 해설 안의 따옴표 인용 개수를 세는 대리 지표를 재던 테스트였는데, 오답 설명이
         * 보기 행(Choice.rationale)으로 옮겨 가면서 그 지표 자체가 없어졌다.
         * 같은 판단을 실물로 재는 테스트는 아래 RationaleContent에 있다.
         */

        @Test
        @DisplayName("정답 근거만 담은 해설은 경고하지 않는다 — 오답 설명은 이제 보기 쪽 몫이다")
        void acceptsExplanationThatOnlyJustifiesTheAnswer() {
            assertThat(warnings(goodExplanation(), true))
                    .noneSatisfy(w -> assertThat(w).contains("오답 설명"));
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
         * <b>이 검사가 두 번 옮겨 다닌 이유</b>다. 처음에는 하한("최소 2개")이었는데 실물은
         * 정반대였다 — 5문제 중 5개가 상황형이었고, 이어 1건씩 세 번 더 뽑아도 세 번 다
         * 상황형이었다. 그래서 상한으로 뒤집었다(2026-08-25).
         *
         * <p><b>2026-09-17에 중급에서 고급으로 옮겼다.</b> 중급에서 상황형을 아예 뺐으니
         * 중급의 상한은 0인데, 0은 개수 상한이 아니라 <b>형태 목록</b>이 말하는 것이다
         * ({@code INTERMEDIATE_KINDS}). 한편 고급 프롬프트의 "셋을 낸다면 SITUATION 1개"는
         * 그동안 <b>아무도 재지 않고</b> 있었다.
         */
        @Test
        @DisplayName("고급 상황형이 상한을 넘으면 경고한다 — 프롬프트에만 있고 검사가 없던 규칙이다")
        void warnsWhenSituationsExceedTheCap() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.SITUATION,
                            QuestionKind.COMPARISON, QuestionKind.CAUSE),
                    Difficulty.ADVANCED))
                    .anySatisfy(w -> assertThat(w).contains("상황 적용형이 3개")
                            .contains("고급 상한 %d개".formatted(ProblemItemRule.ADVANCED_SITUATION_MAX)));
        }

        /** 상한이지 목표가 아니다 — 상황형이 하나뿐이어도 조용해야 한다. */
        @Test
        @DisplayName("상한 안이면 아무 말도 하지 않는다 — 상한이지 목표가 아니다")
        void staysQuietWithinTheCap() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.COMPARISON, QuestionKind.CAUSE),
                    Difficulty.ADVANCED))
                    .isEmpty();
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.COMPARISON, QuestionKind.CAUSE), Difficulty.ADVANCED))
                    .as("상황형이 0개여도 이제는 경고하지 않는다")
                    .isEmpty();
        }

        /**
         * 중급 배치는 이 검사에서 <b>빠져 있어야</b> 한다. 중급에 상황형이 섞여 오면
         * 항목별 경고(중급에 열지 않은 형태)가 각각 잡는다 — 배치 경고까지 함께 뜨면
         * 한 결함에 메시지가 여럿이 되고, 그러면 목록 전체를 흘려 읽게 된다.
         */
        @Test
        @DisplayName("중급 배치에는 상황형 개수 경고를 달지 않는다 — 항목별 형태 경고가 이미 잡는다")
        void leavesIntermediateBatchesToThePerItemCheck() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.SITUATION,
                            QuestionKind.COMPARISON, QuestionKind.CAUSE),
                    Difficulty.INTERMEDIATE))
                    .noneSatisfy(w -> assertThat(w).contains("상황 적용형이"));
        }

        /**
         * 초급은 정의를 묻는 자리라 형태를 나눌 것이 없다. 2026-09-17까지 이 자리에는 "고급은
         * 정의상 전부 상황형이라 매번 울린다"는 이유로 <b>고급을 빼는</b> 테스트가 있었는데,
         * 같은 날 고급에도 형태 셋이 열려 있다는 사실에 맞춰 뒤집혔다(위 두 테스트).
         * 남은 것은 초급이다 — 초급 배치에는 이 경고가 붙을 이유가 없다.
         */
        @Test
        @DisplayName("초급 배치에는 유형 쏠림을 재지 않는다 — 정의를 묻는 자리라 나눌 형태가 없다")
        void kindBalanceSkipsBeginner() {
            assertThat(ProblemItemRule.batchWarningsOf(
                    batch(QuestionKind.SITUATION, QuestionKind.SITUATION, QuestionKind.SITUATION),
                    Difficulty.BEGINNER))
                    .noneSatisfy(w -> assertThat(w).contains("상황 적용형"));
        }

        @Test
        @DisplayName("아무도 유형을 선언하지 않은 옛 배치는 유형 쏠림을 재지 않는다")
        void skipsKindBalanceWithoutDeclaredKinds() {
            assertThat(ProblemItemRule.batchWarningsOf(batch(null, null, null), Difficulty.ADVANCED))
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

    /**
     * 오답 설명({@code Choice.rationale}) — 2026-08-27 신설.
     *
     * <p>해설이 오답을 "②번"처럼 번호로 가리킬 수 없다는 제약에서 나온 구조다. 보기를 섞어
     * 내보내므로(위치 편향) 번호는 반드시 어긋나는데, 설명을 보기 행에 붙여 두면 섞은 순서
     * 그대로 번호를 매겨도 짝이 안 어긋난다. 여기서 재는 것은 그 자리가 <b>실제로 채워지는가</b>다.
     */
    @Nested
    @DisplayName("오답마다 설명이 붙었는지 잰다")
    class RationaleContent {

        /** 오답 셋 중 앞에서부터 {@code explained}개만 설명을 채운 문제. */
        private GeneratedProblemItem withRationales(int explained, String text) {
            List<GeneratedProblemItem.GeneratedChoice> choices = new ArrayList<>();
            choices.add(new GeneratedProblemItem.GeneratedChoice("정답 보기 내용입니다", true, ""));
            for (int i = 1; i <= 3; i++) {
                choices.add(new GeneratedProblemItem.GeneratedChoice(
                        "오답 보기 " + i + "입니다", false, i <= explained ? text : ""));
            }
            return new GeneratedProblemItem("가".repeat(200), "", goodExplanation(),
                    choices, "", "제목", QuestionKind.SITUATION);
        }

        private List<String> warningsFor(GeneratedProblemItem item) {
            return ProblemItemRule.qualityWarningsOf(item, Difficulty.INTERMEDIATE, true);
        }

        private static final String GOOD = "커밋 순서를 뒤집어 이해한 설명이다. 캐시는 커밋 뒤에 지운다.";

        @Test
        @DisplayName("셋 다 채우면 조용하다 — 이것이 새 형식의 정상")
        void staysQuietWhenEveryWrongChoiceIsExplained() {
            assertThat(warningsFor(withRationales(3, GOOD)))
                    .noneSatisfy(w -> assertThat(w).contains("오답 설명"));
        }

        /**
         * <b>일부만 채워진 것이 진짜 결함이다.</b> 화면이 새 형식으로 판정해 오답 분석 칸을
         * 그리는데 한 자리가 비어 나온다. 메우는 일은 사람만 할 수 있다.
         */
        @Test
        @DisplayName("셋 중 하나만 채우면 알린다 — 화면에 빈 칸이 생긴다")
        void warnsWhenSomeWrongChoicesHaveNoRationale() {
            assertThat(warningsFor(withRationales(1, GOOD)))
                    .anySatisfy(w -> assertThat(w).contains("오답 설명이 빠진 보기가 있음"));
        }

        /**
         * <b>하나도 없는 것은 알리지 않는다.</b> 이 필드가 생기기 전에 만들어진 초안이 전부
         * 여기 걸리는데 검수자가 할 수 있는 일이 없다 — 승인하면 화면이 통짜 해설로 그린다.
         * 고칠 것이 없는데 뜨는 경고는 그 목록 전체를 안 보게 만든다.
         */
        @Test
        @DisplayName("하나도 없으면 조용하다 — 옛 초안 전부에 붙는 경고는 목록을 무력화한다")
        void staysQuietOnOldFormatDrafts() {
            assertThat(warningsFor(withRationales(0, GOOD)))
                    .noneSatisfy(w -> assertThat(w).contains("오답 설명"));
        }

        /** 있는 것과 쓸모 있는 것은 다르다 — 한 마디로 때운 설명은 없는 것과 같다. */
        @Test
        @DisplayName("한 마디로 때운 설명은 알린다 — 어떤 오해인지가 안 들어간다")
        void warnsWhenRationaleIsTooShort() {
            assertThat(warningsFor(withRationales(3, "틀렸다")))
                    .anySatisfy(w -> assertThat(w).contains("오답 설명이 너무 짧음"));
        }

        /**
         * 정답 쪽 설명은 저장할 때 버려진다({@code AdminProblemService}). 그래도 알리는 이유는
         * 그 값이 <b>조용히 사라지기</b> 때문이다 — 모델이 근거를 정답 보기에 몰아 쓰고
         * 해설을 얇게 남기면, 정리된 뒤에는 근거가 어디에도 없는 문제가 된다.
         */
        @Test
        @DisplayName("정답 보기에 설명이 붙으면 알린다 — 저장할 때 조용히 버려지는 값이다")
        void warnsWhenTheCorrectChoiceCarriesRationale() {
            GeneratedProblemItem item = new GeneratedProblemItem("가".repeat(200), "", goodExplanation(),
                    List.of(new GeneratedProblemItem.GeneratedChoice("정답 보기 내용입니다", true, GOOD),
                            new GeneratedProblemItem.GeneratedChoice("오답 하나", false, GOOD),
                            new GeneratedProblemItem.GeneratedChoice("오답 둘", false, GOOD),
                            new GeneratedProblemItem.GeneratedChoice("오답 셋", false, GOOD)),
                    "", "제목", QuestionKind.SITUATION);

            assertThat(warningsFor(item))
                    .anySatisfy(w -> assertThat(w).contains("정답 보기에 오답 설명이 붙음"));
        }

        /**
         * <b>번호 지목은 설명이 보기로 옮겨 온 뒤에도 막아야 한다.</b> "③번과 달리 이쪽은…"
         * 같은 문장은 그대로 나올 수 있고, 오히려 더 눈에 안 띈다 — 설명이 이미 보기에
         * 붙어 있어 짝이 맞는 것처럼 보인다.
         */
        @Test
        @DisplayName("오답 설명이 보기를 번호로 가리키면 차단한다 — 해설과 같은 이유다")
        void blocksOrdinalReferenceInsideRationale() {
            GeneratedProblemItem item = withRationales(3, "③번과 달리 이쪽은 읽기 차단을 말한다.");

            assertThat(ProblemItemRule.defectOf(item, ProblemType.MULTIPLE_CHOICE))
                    .contains("오답 설명이 보기를 번호로 가리킴");
        }
    }

    /**
     * <b>정답 보기가 질문을 되풀이하면 경고한다</b>(2026-09-04).
     *
     * <p>짝짓기에는 같은 검사가 처음부터 있었다("오른쪽에 왼쪽 용어가 그대로 있음"). 객관식에만
     * 없어서, 질문을 <b>27자 통째로 복사한</b> 정답 보기가 검수를 그냥 통과해 사이트에 올라갔다.
     * 아래 두 사례는 그때 실제로 걸러졌어야 할 실물이다 — 지어낸 예가 아니라 승인된 문제다.
     *
     * <p>여기서 지키는 것은 <b>양쪽 경계</b>다. 문턱만 재면 헛울리는 쪽을 놓친다 — 네 보기가
     * 다 같은 주제어를 담는 것은 정상이고, 그걸 걸러 내면 정의를 묻는 초급 문제가 매번 경고를 단다.
     */
    @Nested
    @DisplayName("정답 보기가 질문을 되풀이하면 경고한다")
    class AnswerGiveaway {

        private List<String> warningsFor(String question,
                                         String correct, String w1, String w2, String w3) {
            GeneratedProblemItem item = new GeneratedProblemItem(question, "", goodExplanation(),
                    List.of(new GeneratedProblemItem.GeneratedChoice(correct, true),
                            new GeneratedProblemItem.GeneratedChoice(w1, false),
                            new GeneratedProblemItem.GeneratedChoice(w2, false),
                            new GeneratedProblemItem.GeneratedChoice(w3, false)),
                    // 유형은 비워 둔다 — 이 규칙은 형태와 무관하고, 값을 넣으면 형태 규칙의
                    // 경고가 함께 섞여 "무엇 때문에 걸렸는지"가 흐려진다.
                    "", "제목", null);
            return ProblemItemRule.qualityWarningsOf(item, Difficulty.BEGINNER, true);
        }

        /** 실물 #11392 — 겹침 27자. 승인된 객관식 81개 중 가장 심했다. */
        @Test
        @DisplayName("질문 문장을 통째로 옮긴 정답은 걸린다 — 27자짜리가 실제로 통과했다")
        void catchesVerbatimCopy() {
            assertThat(warningsFor(
                    "두 스레드가 같은 자리를 동시에 읽고 쓰는 순서에 따라 결과가 달라지는 상황을 가리키는 용어는?",
                    "경쟁 상태 — 두 스레드가 같은 자리를 동시에 읽고 쓰는 순서에 따라 결과가 달라진다",
                    "스택 오버플로 — 호출이 너무 깊어져 스택 영역을 넘어선 상태다",
                    "가시성 — 한 스레드가 쓴 값이 다른 스레드에게 보이는지의 문제다",
                    "뮤텍스 — 여럿 중 하나만 통과시키고 나머지를 기다리게 하는 자물쇠다"))
                    .anySatisfy(w -> assertThat(w).contains("질문을").contains("되풀이"));
        }

        /**
         * 실물 #11390 — 겹침 14자, 오답도 9자 겹친다("전역·정적 변수가"를 나눠 갖는다).
         * 차이만 보면 5자라 놓친다. 그래서 <b>절대 길이도 함께</b> 본다.
         */
        @Test
        @DisplayName("오답도 어느 정도 겹치는 경우까지 잡는다 — 차이만 보면 놓친다")
        void catchesCopyEvenWhenDistractorsShareWords() {
            assertThat(warningsFor(
                    "프로세스 주소 공간에서 초기값이 없는 전역·정적 변수가 놓이며 0으로 채워져 시작하는 영역의 이름은?",
                    "BSS — 초기값이 없는 전역·정적 변수가 0으로 채워져 시작하는 영역",
                    "데이터 — 초기값이 있는 전역·정적 변수가 들어가는 영역",
                    "힙 — 실행 중에 요청해서 받는 메모리로 객체가 사는 영역",
                    "코드 — 실행 파일에서 읽어 온 기계어 명령이 놓이는 영역"))
                    .anySatisfy(w -> assertThat(w).contains("질문을").contains("되풀이"));
        }

        /**
         * <b>헛울리면 안 되는 쪽.</b> 질문이 주제어를 말하면 네 보기가 모두 그 말을 담는 것이
         * 정상이다. 실측에서 "Write-Through"가 정확히 이 모양이었다(정답 13자 / 오답 13자).
         */
        @Test
        @DisplayName("네 보기가 다 같은 주제어를 담으면 조용하다 — 그건 결함이 아니다")
        void staysQuietWhenAllChoicesShareTheSubject() {
            assertThat(warningsFor(
                    "Write-Through 캐시 쓰기 전략을 골랐을 때 가장 먼저 나타나는 특징은?",
                    "Write-Through 전략은 쓰기 지연이 늘어나는 대신 값이 어긋날 틈이 없다",
                    "Write-Through 전략은 쓰기가 즉시 끝나고 나중에 원본에 반영된다",
                    "Write-Through 전략은 원본만 고치고 캐시는 지워 다음 조회에 채운다",
                    "Write-Through 전략은 읽기를 원본으로 우회시켜 캐시를 비워 둔다"))
                    .noneSatisfy(w -> assertThat(w).contains("되풀이"));
        }

        /** 낱말 몇 개가 겹치는 것은 정상이다 — 어절이 아니라 <b>이어 붙은 구절</b>만 본다. */
        @Test
        @DisplayName("낱말만 겹치는 것은 조용하다 — 구절이 옮겨진 것만 본다")
        void staysQuietOnScatteredWordOverlap() {
            assertThat(warningsFor(
                    "스레드를 하나 더 만들 때 새로 생기는 메모리 영역은 무엇인가?",
                    "스택 한 벌과 레지스터 저장 자리가 생긴다",
                    "힙 전체가 한 벌 더 복사된다",
                    "코드 영역이 스레드 수만큼 늘어난다",
                    "파일 디스크립터 표가 새로 만들어진다"))
                    .noneSatisfy(w -> assertThat(w).contains("되풀이"));
        }

        /**
         * 실물 2026-09-24 — 겹침 9자, 오답 최대 2자. 12자 문턱 아래라 조용히 지나갔고, 같은 배치의
         * 다섯 문제가 모두 "정답이 보기에 들어 있다"로 거절됐다. 질문이 뜻을 풀어 쓰고 "용어와 그 뜻"을
         * 물으면, 정답의 뜻 부분이 질문을 말만 바꿔 되받는다.
         */
        @Test
        @DisplayName("용어 — 뜻 꼴은 짧은 되받기도 잡는다 — 말을 바꿔 쓰면 겹침이 6~10자로 끊긴다")
        void catchesParaphrasedDefinitionInTermPairs() {
            assertThat(warningsFor(
                    "데이터베이스 표의 한 줄에 대응하도록 만들어 둔 자바 클래스를 부르는 용어와 그 뜻으로 옳은 것은?",
                    "엔티티 — 표 한 줄에 대응하도록 만든 자바 클래스",
                    "ORM — 객체를 다루면 표가 바뀌게 해 주는 매핑 기술",
                    "JPQL — 엔티티 이름과 필드 이름으로 쓰는 질의어",
                    "JPAQueryFactory — 질의문 조립을 시작하는 입구 객체"))
                    .anySatisfy(w -> assertThat(w).contains("질문을").contains("되풀이"));
        }

        /**
         * 실물 2026-09-24 — 정답 7자 / 오답 4자로 차이가 3뿐이다. 오답이 "실행할 때"를 우연히
         * 나눠 가졌을 뿐 결함은 같다. 용어 — 뜻 꼴의 차이 문턱을 3으로 둔 이유다.
         */
        @Test
        @DisplayName("오답이 몇 글자 나눠 가져도 용어 — 뜻 꼴이면 잡는다")
        void catchesTermPairEvenWhenDistractorSharesAFewChars() {
            assertThat(warningsFor(
                    "검색 화면처럼 사용자가 어떤 입력만 채울지 미리 알 수 없어, 실행할 때가 되어야 조건이 정해지는 질의문을 부르는 용어와 그 뜻은?",
                    "동적 쿼리 — 실행할 때가 되어서야 어떤 조건이 들어갈지 정해지는 질의문",
                    "바인딩 파라미터 — 값을 비워 두었다가 실행할 때 따로 넘기는 자리",
                    "Q 타입 — 엔티티마다 자동으로 만들어지는 짝 클래스",
                    "JDBC — 자바가 데이터베이스에 연결해 결과를 받아 오는 표준 방식"))
                    .anySatisfy(w -> assertThat(w).contains("질문을").contains("되풀이"));
        }

        /**
         * <b>헛울리면 안 되는 쪽</b>, 실물 2026-09-21(승인됨) — 정답 7자 / 오답 5자, 차이 2.
         * 오답들도 "글자 흐름을 … 잘라"를 고르게 나눠 가져 문장만으로는 답이 좁혀지지 않는다.
         * 용어 — 뜻 꼴에서 걸리면 안 되는 것 중 가장 가까운 실물이라, 차이 문턱의 경계를 지킨다.
         */
        @Test
        @DisplayName("용어 — 뜻 꼴이라도 오답이 고르게 겹치면 조용하다")
        void staysQuietWhenTermPairDistractorsShareEvenly() {
            assertThat(warningsFor(
                    "브라우저 파싱 과정에서 글자를 의미 단위로 잘라 내는 부품의 이름과 그 역할로 옳은 것은?",
                    "토크나이저(tokenizer) — 글자 흐름을 태그 시작, 속성 이름, 텍스트 같은 의미 단위로 잘라 준다",
                    "DOM(document object model) — 글자를 훑어 태그와 텍스트로 잘라 내는 브라우저 부품이다",
                    "DOCTYPE — 문서의 글자 흐름을 태그·속성·텍스트 단위로 나누는 선언이다",
                    "엔티티 참조 — 글자 흐름을 읽어 태그와 텍스트의 경계를 판단하는 표기다"))
                    .noneSatisfy(w -> assertThat(w).contains("되풀이"));
        }

        /**
         * <b>낮춘 문턱이 일반 보기로 새지 않는가</b>, 실물 2026-09-22(승인됨) — 정답 9자 겹침은
         * "HTML 이스케이프"라는 고유명사가 길어서 생긴 정상 겹침이다. 보기가 용어 — 뜻 꼴이 아니므로
         * 12자 문턱이 그대로 적용돼야 한다.
         */
        @Test
        @DisplayName("일반 문장 보기는 12자 문턱 그대로다 — 긴 고유명사 겹침은 결함이 아니다")
        void keepsGeneralThresholdForPlainChoices() {
            assertThat(warningsFor(
                    "프로필 화면에 회원이 직접 입력한 개인 홈페이지 주소를 링크로 걸어 주는 기능을 넣었다. 템플릿에서 href=\"{{url}}\" 로 출력하고 값에는 HTML 이스케이프를 적용했다. 이 상황을 가장 정확히 설명한 것은?",
                    "HTML 이스케이프는 문법상 특별한 글자를 바꾸는 방어인데, 이 값은 특수문자가 하나도 없어 그대로 통과하고 스킴 자체가 코드 실행 지시가 된다",
                    "이스케이프 라이브러리가 콜론을 변환 대상에 넣지 않아서 생긴 구멍이며, 콜론을 엔티티로 치환하면 막힌다",
                    "템플릿이 이미 이스케이프한 값에 코드가 한 번 더 이스케이프를 걸어, 브라우저 디코딩 과정에서 원래 문자열로 되살아난 것이다",
                    "서버가 만든 HTML은 안전했지만 브라우저 스크립트가 링크를 다시 조립하면서 서버의 인코딩이 무효가 된 것이다"))
                    .noneSatisfy(w -> assertThat(w).contains("되풀이"));
        }
    }

    /**
     * <b>보기 전부가 같은 문장을 되풀이하면 경고한다</b>(2026-09-08).
     *
     * <p>아래 걸려야 하는 둘과 걸리면 안 되는 둘은 <b>전부 실물</b>이다 — 생성된 객관식 초안
     * 128개를 재서, 공유 구절이 가장 길었던 넷을 그대로 옮겼다. 지어낸 예로 경계를 재면
     * 문턱을 내 마음대로 정하게 된다.
     *
     * <p>실측 분포가 두 덩어리로 갈렸다는 것이 이 검사의 근거다 — 27자·22자 둘, 그리고 나머지
     * 126개가 전부 7자 이하. 그 빈 구간에 문턱을 두었으므로, 여기서 재는 것은 문턱 자체가 아니라
     * <b>양쪽 덩어리가 각각 어느 편에 남는가</b>다.
     */
    @Nested
    @DisplayName("보기 전부가 같은 문장을 되풀이하면 경고한다")
    class SharedChoiceText {

        private List<String> warningsFor(String question, String correct, String w1, String w2, String w3) {
            GeneratedProblemItem item = new GeneratedProblemItem(question, "", goodExplanation(),
                    List.of(new GeneratedProblemItem.GeneratedChoice(correct, true),
                            new GeneratedProblemItem.GeneratedChoice(w1, false),
                            new GeneratedProblemItem.GeneratedChoice(w2, false),
                            new GeneratedProblemItem.GeneratedChoice(w3, false)),
                    "", "제목", null);
            return ProblemItemRule.qualityWarningsOf(item, Difficulty.BEGINNER, true);
        }

        /** 실물 2026-09-08 — 공유 22자(가장 긴 보기의 85%). 사용자가 읽다 짚은 바로 그 문제다. */
        @Test
        @DisplayName("뜻이 넷 다 같고 용어만 갈리면 걸린다 — 짝짓기를 물어 놓고 한쪽을 고정한 꼴이다")
        void catchesIdenticalPredicate() {
            assertThat(warningsFor(
                    "나중에 넣은 것을 먼저 꺼내는 순서 규칙으로 값을 담는 자료구조의 이름과 뜻이 바르게 짝지어진 것은?",
                    "스택 — 나중에 넣은 값을 먼저 꺼내는 규칙으로 값을 담는다",
                    "큐 — 나중에 넣은 값을 먼저 꺼내는 규칙으로 값을 담는다",
                    "힙 영역 — 나중에 넣은 값을 먼저 꺼내는 규칙으로 값을 담는다",
                    "반환 주소 — 나중에 넣은 값을 먼저 꺼내는 규칙으로 값을 담는다"))
                    .anySatisfy(w -> assertThat(w).contains("같은 문장을 되풀이"));
        }

        /** 같은 배치의 다른 한 건 — 공유 27자. 한 배치에 둘이 나왔다는 것이 우연이 아니라는 증거다. */
        @Test
        @DisplayName("보기가 길어도 대부분이 같은 문장이면 걸린다")
        void catchesIdenticalPredicateEvenWhenLong() {
            assertThat(warningsFor(
                    "재귀 함수 안에서 되감기를 시작하는 지점이 되는 조건의 이름과 뜻이 바르게 짝지어진 것은?",
                    "기저 조건 — 더 이상 자기 자신을 부르지 않고 값을 그대로 돌려주는 조건이다",
                    "재귀 깊이 — 더 이상 자기 자신을 부르지 않고 값을 그대로 돌려주는 조건이다",
                    "스택 오버플로 — 더 이상 자기 자신을 부르지 않고 값을 그대로 돌려주는 조건이다",
                    "반환 주소 — 더 이상 자기 자신을 부르지 않고 값을 그대로 돌려주는 조건이다"))
                    .anySatisfy(w -> assertThat(w).contains("같은 문장을 되풀이"));
        }

        /**
         * <b>헛울리면 안 되는 쪽 (1) — 실물, 공유 7자.</b> 네 보기가 {@code "…"를 알리는 표시}로
         * 끝난다. 결이 맞는 보기는 오히려 좋은 문제의 표시다 — 끝이 같으니 <b>다른 곳</b>을 봐야 갈린다.
         */
        @Test
        @DisplayName("보기의 끝맺음이 같은 것은 조용하다 — 결을 맞춘 것은 결함이 아니다")
        void staysQuietOnSharedEnding() {
            assertThat(warningsFor(
                    "TCP 세그먼트에 붙는 플래그 중 FIN이 뜻하는 것은?",
                    "\"내 쪽 보낼 것 끝났다\"를 알리는 표시",
                    "\"연결 시작하자\"를 알리는 표시",
                    "\"거기까지 잘 받았다\"를 알리는 표시",
                    "\"이 연결 무효, 즉시 끊어\"를 알리는 표시"))
                    .noneSatisfy(w -> assertThat(w).contains("같은 문장을 되풀이"));
        }

        /**
         * <b>헛울리면 안 되는 쪽 (2) — 실물, 공유 7자.</b> 이쪽은 <b>용어를 고정하고 뜻을 갈랐다</b>.
         * 걸린 실물과 정확히 반대 모양이고, 이것이 {@code 용어 — 뜻} 꼴의 올바른 형태다.
         * 이 테스트가 없으면 다음 사람이 "용어가 겹치면 걸자"로 검사를 뒤집을 수 있다.
         */
        @Test
        @DisplayName("용어를 고정하고 뜻을 가른 것은 조용하다 — 그게 이 꼴의 올바른 형태다")
        void staysQuietWhenTermIsFixedAndMeaningsDiffer() {
            assertThat(warningsFor(
                    "XSS 방어에서 말하는 '출력 컨텍스트(output context)'의 뜻으로 옳은 것은?",
                    "출력 컨텍스트 — 문자열이 최종적으로 삽입되는 문법 자리를 뜻한다",
                    "출력 컨텍스트 — 특별한 뜻을 가진 글자를 안전한 표기로 바꾸는 처리를 뜻한다",
                    "출력 컨텍스트 — 삽입된 값이 자기 자리의 울타리를 닫고 밖으로 빠져나가는 것을 뜻한다",
                    "출력 컨텍스트 — 문자열이 코드나 마크업으로 해석되는 지점을 뜻한다"))
                    .noneSatisfy(w -> assertThat(w).contains("같은 문장을 되풀이"));
        }

        /**
         * 순서 배열·짝짓기는 재지 않는다. 항목이 "~한다"로 끝나며 구절을 나눠 가지는 것이 정상이라,
         * 객관식 문턱을 그대로 들이대면 멀쩡한 문제가 걸린다. 유형 값이 없어도 <b>모양</b>으로
         * 갈리는지를 확인한다 — 정답 표시가 없으면 객관식이 아니다.
         */
        @Test
        @DisplayName("정답 표시가 없는 항목 묶음은 재지 않는다 — 순서·짝짓기는 겹치는 것이 정상이다")
        void ignoresNonMultipleChoiceShapes() {
            GeneratedProblemItem ordering = new GeneratedProblemItem(
                    "커밋이 끝나기까지의 순서를 바르게 놓으시오.", "1|2|3|4", goodExplanation(),
                    // 일부러 문턱을 넘게 지었다(공유 12자, 가장 긴 항목의 57%) — 모양으로 거르지
                    // 않으면 걸리는 값이라야 이 테스트가 무언가를 지킨다.
                    List.of(new GeneratedProblemItem.GeneratedChoice("트랜잭션을 시작하고 잠금을 잡은 뒤 로그를 남긴다", false),
                            new GeneratedProblemItem.GeneratedChoice("변경을 버퍼에 쌓고 잠금을 잡은 뒤 로그를 남긴다", false),
                            new GeneratedProblemItem.GeneratedChoice("페이지를 고치고 잠금을 잡은 뒤 로그를 남긴다", false),
                            new GeneratedProblemItem.GeneratedChoice("커밋 표시를 찍고 잠금을 잡은 뒤 로그를 남긴다", false)),
                    "", "제목", null);
            assertThat(ProblemItemRule.qualityWarningsOf(ordering, Difficulty.BEGINNER, true))
                    .noneSatisfy(w -> assertThat(w).contains("같은 문장을 되풀이"));
        }
    }

    /**
     * 제목 형식 — 2026-09-17에 물음표만 보던 검사를 <b>문서 제목과 같은 잣대</b>로 넓혔다.
     *
     * <p>문서 제목 규칙을 명사구로 뒤집으면서, 문제 제목에도 같은 둘(의문형 어미·줄표 부연)을
     * 대기로 했다. 목록 화면에서 문서와 문제가 같은 흐름으로 읽히는데 제목 형식만 다르면
     * <b>두 세대처럼 보인다</b>.
     *
     * <p>판정 자체는 {@code TitleStyleRuleTest}가 잰다. 여기서 보는 것은 그 판정이
     * <b>문제 경고 목록까지 실제로 흘러오는가</b>다 — 규칙만 만들고 부르는 곳을 빠뜨리면
     * 아무 오류 없이 조용하다.
     */
    /**
     * 해설이 <b>어느 편의 절인지</b> 밝히는지 — 2026-09-17 신설.
     *
     * <p>중급이 두 편에서 캐게 되면서({@code DocumentEditionRule.bodyFor}) 절 이름만으로는
     * 어느 글을 열지 정할 수 없게 됐다. {@code ## 용어 한눈에}는 두 편에 다 있고, 근거 문서
     * 링크는 한 편으로만 간다. 09-17 실물에서 입문편 절을 가리킨 문제의 링크가 심화편으로
     * 가 있었다 — 눌러도 그 절이 없다.
     */
    @Nested
    @DisplayName("중급 해설은 어느 편의 절인지 밝힌다")
    class ExplanationEdition {

        private List<String> warningsFor(String explanation, Difficulty difficulty) {
            GeneratedProblemItem withExplanation = new GeneratedProblemItem(
                    "가".repeat(120), "", explanation,
                    List.of(new GeneratedProblemItem.GeneratedChoice("정답 보기 내용입니다", true),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 하나입니다", false),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 둘입니다", false),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 셋입니다", false)),
                    "", "제목", QuestionKind.COMPARISON);
            return ProblemItemRule.qualityWarningsOf(withExplanation, difficulty, true);
        }

        /** 편 없이 절만 가리킨 해설 — 09-17 실물이 다섯 건 모두 이 꼴이었다. */
        private static final String NO_EDITION = "정답인 이유는 인스턴스 수와 실행 시간이 갈림길이기 때문이다. "
                + "둘 중 하나라도 커지면 기동 시 실행은 재시작 루프를 만든다. "
                + "(문서의 '실무에서 어디에 나타나는가' 절을 다시 읽어 보라)";

        @Test
        @DisplayName("편을 안 밝히면 알린다 — 같은 이름의 절이 두 편에 다 있다")
        void warnsWhenEditionIsMissing() {
            assertThat(warningsFor(NO_EDITION, Difficulty.INTERMEDIATE))
                    .anySatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
        }

        @Test
        @DisplayName("편을 밝히면 조용하다 — 입문편이든 심화편이든")
        void staysQuietWhenEditionIsNamed() {
            assertThat(warningsFor(NO_EDITION.replace("문서의", "심화편의"), Difficulty.INTERMEDIATE))
                    .noneSatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
            assertThat(warningsFor(NO_EDITION.replace("문서의", "입문편의"), Difficulty.INTERMEDIATE))
                    .noneSatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
        }

        /**
         * 초급·고급은 한 편만 읽는다. 거기에 이 경고를 달면 <b>고칠 것이 없는데 뜨는 경고</b>가
         * 되고, 그런 경고는 목록 전체를 안 보게 만든다.
         */
        @Test
        @DisplayName("초급·고급에는 달지 않는다 — 한 편만 읽으므로 밝힐 것이 없다")
        void appliesOnlyToIntermediate() {
            assertThat(warningsFor(NO_EDITION, Difficulty.BEGINNER))
                    .noneSatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
            assertThat(warningsFor(NO_EDITION, Difficulty.ADVANCED))
                    .noneSatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
        }

        /** 절을 아예 안 가리킨 해설에는 <b>기존 경고 하나만</b> 떠야 한다 — 한 결함에 메시지 둘은 나쁘다. */
        @Test
        @DisplayName("절을 안 가리킨 해설에는 경고가 하나만 뜬다")
        void doesNotStackWithTheMissingPointerWarning() {
            List<String> warnings = warningsFor(
                    "정답인 이유는 인스턴스 수와 실행 시간이 갈림길이기 때문이다. "
                            + "둘 중 하나라도 커지면 기동 시 실행은 재시작 루프를 만든다.",
                    Difficulty.INTERMEDIATE);

            assertThat(warnings).anySatisfy(w -> assertThat(w).contains("다시 읽을 문서 절이 없음"))
                    .noneSatisfy(w -> assertThat(w).contains("편을 밝히지 않음"));
        }
    }

    @Nested
    @DisplayName("제목이 물음 꼴이거나 줄표 부연이면 알린다")
    class TitleStyle {

        private List<String> warningsForTitle(String title) {
            GeneratedProblemItem withTitle = new GeneratedProblemItem(
                    "가".repeat(200), "", goodExplanation(),
                    List.of(new GeneratedProblemItem.GeneratedChoice("정답 보기 내용입니다", true),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 하나입니다", false),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 둘입니다", false),
                            new GeneratedProblemItem.GeneratedChoice("오답 보기 셋입니다", false)),
                    "", title, QuestionKind.SITUATION);
            return ProblemItemRule.qualityWarningsOf(withTitle, Difficulty.INTERMEDIATE, true);
        }

        @Test
        @DisplayName("물음표 없는 의문문도 잡는다 — 이게 그동안 새어 나가던 꼴이다")
        void warnsOnQuestionEndingWithoutQuestionMark() {
            assertThat(warningsForTitle("스레드를 하나 더 만들면 무엇이 생기는가"))
                    .anySatisfy(w -> assertThat(w).contains("물음 꼴"));
        }

        @Test
        @DisplayName("물음표로 끝나는 제목은 그대로 잡는다 — 옛 검사를 넓혔지 지우지 않았다")
        void stillWarnsOnQuestionMark() {
            assertThat(warningsForTitle("이 상황의 원인으로 가장 적절한 것은?"))
                    .anySatisfy(w -> assertThat(w).contains("물음 꼴"));
        }

        @Test
        @DisplayName("줄표 부연이 붙으면 알린다 — 40자 안에서 부제까지 붙이면 목록에서 잘린다")
        void warnsOnDashSubtitle() {
            assertThat(warningsForTitle("커넥션 풀 고갈 — 대기하다 타임아웃으로 끝나는 자리"))
                    .anySatisfy(w -> assertThat(w).contains("줄표 부연"));
        }

        @Test
        @DisplayName("명사구 제목은 조용하다 — 프롬프트가 모범으로 보여 준 형태다")
        void staysQuietOnNounPhrase() {
            assertThat(warningsForTitle("커넥션 풀이 고갈될 때의 대기 동작 (HikariCP)"))
                    .noneSatisfy(w -> assertThat(w).contains("제목"));
        }
    }
}
