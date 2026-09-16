package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 난이도 재료 규칙 — 2026-09-05에 {@code DraftGeneratorCli}에서 뽑아내며 함께 만들었다.
 *
 * <p>이 규칙이 막는 사고는 <b>입문편에 고급을 거는 것</b>이다. 두 편은 제목이 완전히 같고
 * slug만 {@code -advanced}로 갈려서, 관리자 드롭다운에서 잘못 고르기가 아주 쉽다. 잘못 고르면
 * 프롬프트가 없는 절을 지목하고 모델은 멈추지 않고 다른 절을 판다 — 실패가 아니라 조용한
 * 품질 저하라, 요금을 다 낸 뒤 사람이 읽어야 알아차린다.
 */
class DifficultyMaterialRuleTest {

    /** 입문편의 필수 절 가운데 초급·중급이 캐는 것들만 담은 최소 본문. */
    private static final String BEGINNER_DOC = """
            # 스레드를 하나 더 만들면 메모리에 무엇이 새로 생기는가
            ## 핵심 요약
            ## 바탕이 되는 개념
            ## 무엇인가
            ## 왜 필요한가
            ### 왜 이렇게 설계됐는가
            ## 실무에서는 이렇게 쓴다
            ## 자주 하는 오해
            """;

    /** 심화편 — 입문편과 이름이 겹치는 절이 없다({@code ClaudeDocumentGenerator} 주석). */
    private static final String ADVANCED_DOC = """
            # 스레드를 하나 더 만들면 메모리에 무엇이 새로 생기는가
            ## 핵심 요약
            ## 이 글을 읽기 전에
            ## 언제 깨지는가
            ## 면접에서 이렇게 물어본다
            ## 면접 한 줄 요약
            """;

    @Nested
    @DisplayName("편에 맞는 난이도")
    class Matching {

        @Test
        @DisplayName("입문편에서 초급·중급을 캘 수 있다")
        void beginnerEditionFeedsLowerDifficulties() {
            assertThat(DifficultyMaterialRule.hasMaterialFor(BEGINNER_DOC, Difficulty.BEGINNER)).isTrue();
            assertThat(DifficultyMaterialRule.hasMaterialFor(BEGINNER_DOC, Difficulty.INTERMEDIATE)).isTrue();
        }

        @Test
        @DisplayName("심화편에서 고급을 캘 수 있다")
        void advancedEditionFeedsAdvanced() {
            assertThat(DifficultyMaterialRule.hasMaterialFor(ADVANCED_DOC, Difficulty.ADVANCED)).isTrue();
        }
    }

    @Nested
    @DisplayName("편이 어긋난 조합 — 이 규칙이 존재하는 이유")
    class Mismatched {

        @Test
        @DisplayName("입문편에 고급을 걸면 걸린다 — 관리자 화면으로 새던 조합이다")
        void beginnerEditionCannotFeedAdvanced() {
            assertThat(DifficultyMaterialRule.hasMaterialFor(BEGINNER_DOC, Difficulty.ADVANCED)).isFalse();
        }

        @Test
        @DisplayName("심화편에 초급·중급을 걸어도 걸린다")
        void advancedEditionCannotFeedLowerDifficulties() {
            assertThat(DifficultyMaterialRule.hasMaterialFor(ADVANCED_DOC, Difficulty.BEGINNER)).isFalse();
            assertThat(DifficultyMaterialRule.hasMaterialFor(ADVANCED_DOC, Difficulty.INTERMEDIATE)).isFalse();
        }

        /**
         * 사유 문구가 <b>무엇을 고쳐야 하는지</b>까지 말해야 한다. "재료가 없습니다"만 뜨면
         * 관리자는 문서가 잘못된 줄 알고 문서를 고치러 간다 — 정작 바꿀 것은 드롭다운의
         * 한 줄 아래에 있는 같은 제목의 다른 편이다.
         */
        @Test
        @DisplayName("사유에 어느 편을 골라야 하는지 적는다 — 제목이 같아 고를 것을 못 찾는다")
        void reasonNamesTheEditionToPick() {
            assertThat(DifficultyMaterialRule.missingMaterialOf(BEGINNER_DOC, Difficulty.ADVANCED))
                    .contains("고급")
                    .contains("## 언제 깨지는가")
                    .contains("심화편");

            assertThat(DifficultyMaterialRule.missingMaterialOf(ADVANCED_DOC, Difficulty.BEGINNER))
                    .contains("입문편");
        }

        @Test
        @DisplayName("재료가 있으면 사유가 없다")
        void noReasonWhenMaterialExists() {
            assertThat(DifficultyMaterialRule.missingMaterialOf(BEGINNER_DOC, Difficulty.BEGINNER)).isNull();
        }
    }

    /**
     * <b>편 이름이 아니라 절을 본다.</b> 2026-09-03 이전 문서 15편은 두 편으로 갈리기 전의
     * 한 편짜리라 slug에 꼬리가 없는데 {@code ## 언제 깨지는가}를 실제로 갖고 있다.
     * slug로 판정했다면 그 문서들로는 고급을 못 뽑았을 것이다 — 재료는 멀쩡히 있는데도.
     */
    @Test
    @DisplayName("한 편짜리 옛 문서는 세 난이도를 모두 통과한다 — 이름이 아니라 있는 것을 본다")
    void singleEditionLegacyDocumentFeedsEveryDifficulty() {
        String legacy = BEGINNER_DOC + ADVANCED_DOC;

        for (Difficulty difficulty : Difficulty.values()) {
            assertThat(DifficultyMaterialRule.hasMaterialFor(legacy, difficulty))
                    .as("%s가 캘 절이 한 편짜리 문서 안에 있다", difficulty)
                    .isTrue();
        }
    }

    /**
     * <b>절 이름을 바꿔도 이미 나간 문서가 죽지 않는다</b>(2026-09-16).
     *
     * <p>{@code ## 언제 깨지는가}를 {@code ## 어떤 때 통하지 않는가}로, {@code ## 실제로는
     * 어디에서 만나는가}를 {@code ## 실무에서 어디에 나타나는가}로 바꿨다. 옛 이름은 제목만
     * 보고는 안에 무엇이 있는지 짐작할 수 없었다 — "무엇이 깨지나"(코드? 개념? 서버?)가
     * 제목에 없고, 정작 내용은 시점이 아니라 <b>조건</b>이었다.
     *
     * <p>문제는 그 이름이 <b>이미 나간 문서 14편과 문제 초안 15개</b>에 박혀 있다는 것이다.
     * 새 이름만 인정하면 그 문서들이 하루아침에 "고급 재료 없음"이 되고 고급 날이 통째로
     * 근거 없는 폴백으로 떨어진다. 실패가 아니라 조용한 품질 저하라, 요금을 다 낸 뒤
     * 사람이 읽어야 알아차린다 — 이 클래스가 막으려는 사고와 정확히 같은 종류다.
     *
     * <p>그래서 <b>판정하는 자리는 두 이름을 다 받는다</b>. 새 이름만 쓰는 곳은 새로 나가는
     * 프롬프트뿐이다. 옛 이름을 언제 지울지는 정하지 않았다 — 문서 14편이 살아 있는 한
     * 지워도 되는 날이 오지 않는다.
     */
    @Nested
    @DisplayName("절 이름을 바꾼 뒤 — 두 이름을 다 받는다")
    class RenamedSections {

        @Test
        @DisplayName("새 이름 '어떤 때 통하지 않는가'로 고급을 캔다")
        void newAdvancedNameFeedsAdvanced() {
            String doc = "# 제목\n## 어떤 때 통하지 않는가\n";

            assertThat(DifficultyMaterialRule.hasMaterialFor(doc, Difficulty.ADVANCED)).isTrue();
        }

        @Test
        @DisplayName("옛 이름 '언제 깨지는가'도 그대로 통한다 — 이미 나간 문서 14편이 이 이름이다")
        void legacyAdvancedNameStillFeedsAdvanced() {
            String doc = "# 제목\n## 언제 깨지는가\n";

            assertThat(DifficultyMaterialRule.hasMaterialFor(doc, Difficulty.ADVANCED)).isTrue();
        }

        @Test
        @DisplayName("새 이름 '실무에서 어디에 나타나는가'로 중급을 캔다")
        void newIntermediateNameFeedsIntermediate() {
            String doc = "# 제목\n## 실무에서 어디에 나타나는가\n";

            assertThat(DifficultyMaterialRule.hasMaterialFor(doc, Difficulty.INTERMEDIATE)).isTrue();
        }

        @Test
        @DisplayName("옛 이름 '실제로는 어디에서 만나는가'도 그대로 통한다")
        void legacyIntermediateNameStillFeedsIntermediate() {
            String doc = "# 제목\n## 실제로는 어디에서 만나는가\n";

            assertThat(DifficultyMaterialRule.hasMaterialFor(doc, Difficulty.INTERMEDIATE)).isTrue();
        }

        /**
         * 이름을 바꾼 것이 <b>편 경계를 흐리지 않는지</b>. 새 이름이 초급 재료로도 인정되면
         * 입문편에 고급을 거는 사고({@link Mismatched})를 막던 문턱이 같이 사라진다.
         */
        @Test
        @DisplayName("새 이름도 초급 재료는 아니다 — 편 경계는 그대로다")
        void newNamesDoNotFeedBeginner() {
            String doc = "# 제목\n## 실무에서 어디에 나타나는가\n## 어떤 때 통하지 않는가\n";

            assertThat(DifficultyMaterialRule.hasMaterialFor(doc, Difficulty.BEGINNER)).isFalse();
        }
    }

    /**
     * 판단 근거가 없으면 막지 않는다 — 확신 없이 버리는 쪽이 더 나쁘다. 여기서 {@code false}를
     * 돌려주면 배치는 멀쩡한 문서를 폴백으로 보내고, 관리자 화면은 이유 없이 거절한다.
     */
    @Test
    @DisplayName("null이 섞이면 통과시킨다 — 확신 없는 차단이 더 해롭다")
    void nullsPass() {
        assertThat(DifficultyMaterialRule.hasMaterialFor(null, Difficulty.ADVANCED)).isTrue();
        assertThat(DifficultyMaterialRule.hasMaterialFor(BEGINNER_DOC, null)).isTrue();
        assertThat(DifficultyMaterialRule.missingMaterialOf(null, Difficulty.ADVANCED)).isNull();
    }
}
