package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 난이도 → 편 규칙. <b>이 규칙이 두 곳에 있어서 실제로 어긋났던 자리다.</b>
 *
 * <p>배치({@code DraftGeneratorCli.editionFor})와 관리 화면({@code AdminBatchService.sourceOf})이
 * 각자 판정하다가, 화면 쪽은 난이도를 아예 안 보고 늘 입문편 slug를 찍고 있었다.
 * 2026-09-14 실행 로그에 그 어긋남이 남아 있다 — 배치는 {@code ...-advanced}로 돌았는데
 * 화면은 꼬리 없는 slug를 적었다. 그 화면의 일이 바로 "어긋남을 한눈에 보는 것"이었다.
 */
class DocumentEditionRuleTest {

    @Test
    @DisplayName("초급은 입문편, 중급·고급은 심화편 — SOURCE_SECTIONS가 지목하는 절과 짝이다")
    void mapsDifficultyToEdition() {
        assertThat(DocumentEditionRule.pick(both(), Difficulty.BEGINNER).slug()).isEqualTo("probe");
        assertThat(DocumentEditionRule.pick(both(), Difficulty.INTERMEDIATE).slug())
                .as("2026-09-14에 중급이 심화편으로 옮겨 왔다 — 난이도에 바닥을 만들기 위해서다")
                .isEqualTo("probe-advanced");
        assertThat(DocumentEditionRule.pick(both(), Difficulty.ADVANCED).slug()).isEqualTo("probe-advanced");
    }

    /**
     * 2026-09-03 이전 파일 15편에는 심화편 칸이 없고, 심화편 생성만 실패한 날도 있다.
     * 그때 {@code null}을 돌려주면 쓸 수 있는 문서를 두고 그날이 폴백으로 떨어진다 —
     * 옛 문서는 한 편에 모든 재료가 들어 있던 시절의 것이라 {@code ## 언제 깨지는가}가 그 안에 있다.
     */
    @ParameterizedTest
    @EnumSource(Difficulty.class)
    @DisplayName("심화편이 없으면 어느 난이도든 입문편으로 돌아간다 — 옛 문서 15편이 그렇다")
    void fallsBackToBeginnerEdition(Difficulty difficulty) {
        assertThat(DocumentEditionRule.pick(beginnerOnly(), difficulty).slug()).isEqualTo("probe");
    }

    @Test
    @DisplayName("심화편 칸은 있는데 본문이 비면 없는 것으로 본다 — 심화편 생성만 실패한 날")
    void treatsBlankAdvancedAsMissing() {
        GeneratedDocumentFile file = file(item("probe", "# 본문"), item("probe-advanced", "   "));

        assertThat(DocumentEditionRule.pick(file, Difficulty.ADVANCED).slug()).isEqualTo("probe");
    }

    @Test
    @DisplayName("문서일에는 난이도가 없다 — 그날은 읽는 날이 아니라 만드는 날이라 입문편을 돌려준다")
    void documentDayHasNoDifficulty() {
        assertThat(DocumentEditionRule.pick(both(), null).slug()).isEqualTo("probe");
    }

    /**
     * <b>중급이 읽는 본문에 입문편 두 절이 붙는지</b>(2026-09-17).
     *
     * <p>09-17 실물에서 중급이 캘 수 있는 분량은 908자였다(초급 3,185자, 고급 4,623자).
     * 5문제를 요청하니 문제당 182자다. 그 결과 뒤쪽 세 문제가 <b>빈 지문</b>으로 돌아오고,
     * 남은 둘은 모자란 재료를 채우러 고급 절까지 넘어가 지문이 어려워졌다.
     *
     * <p>여기서 지키는 것은 <b>두 절이 실제로 실린다는 사실</b>이다. 붙이는 코드가 빠져도
     * 예외는 나지 않는다 — 문제는 그대로 생성되고, 몇 주 뒤 수확량이 다시 2개가 될 뿐이다.
     */
    @Test
    @DisplayName("중급 본문에 입문편의 중급 절 둘이 붙는다 — 심화편 한 절(908자)로는 5문제를 못 낸다")
    void intermediateBodyCarriesBeginnerSections() {
        GeneratedDocumentFile file = file(
                item("probe", """
                        # 입문편
                        ## 무엇인가
                        정의다.
                        ### 왜 이렇게 설계됐는가
                        다른 선택지를 두고 이것을 고른 이유다.
                        ## 실무에서는 이렇게 쓴다
                        장면 하나를 처음부터 끝까지.
                        ## 자주 하는 오해
                        오해다."""),
                item("probe-advanced", "# 심화편\n## 실무에서 어디에 나타나는가\n항목들."));

        String body = DocumentEditionRule.bodyFor(file, Difficulty.INTERMEDIATE);

        assertThat(body)
                .as("주 재료인 심화편 절이 먼저 온다")
                .contains("## 실무에서 어디에 나타나는가")
                .as("붙여 온 부분임을 모델이 알아야 한다")
                .contains("같은 주제 입문편에서 가져온 부분")
                .contains("### 왜 이렇게 설계됐는가")
                .contains("다른 선택지를 두고 이것을 고른 이유다.")
                .contains("## 실무에서는 이렇게 쓴다")
                .contains("장면 하나를 처음부터 끝까지.")
                .as("중급 재료가 아닌 절까지 끌고 오면 다시 어려워진다 — 자르는 끝이 다음 제목이다")
                .doesNotContain("## 자주 하는 오해")
                .doesNotContain("오해다.");
    }

    @Test
    @DisplayName("초급·고급 본문은 그대로다 — 붙이는 것은 재료가 마른 중급뿐이다")
    void otherDifficultiesKeepTheirEditionBody() {
        assertThat(DocumentEditionRule.bodyFor(both(), Difficulty.BEGINNER)).isEqualTo("# 입문편");
        assertThat(DocumentEditionRule.bodyFor(both(), Difficulty.ADVANCED)).isEqualTo("# 심화편");
    }

    /**
     * 심화편이 없는 날에는 중급도 입문편을 읽는다. 그 본문에는 두 절이 <b>이미 들어 있으므로</b>
     * 또 붙이면 같은 글이 두 번 실린다 — 중복 지문은 모델에게 "이게 중요하다"는 잘못된 신호다.
     */
    @Test
    @DisplayName("심화편이 없어 입문편을 읽는 날에는 붙이지 않는다 — 같은 글이 두 번 실린다")
    void doesNotDuplicateWhenReadingBeginnerEdition() {
        String body = DocumentEditionRule.bodyFor(beginnerOnly(), Difficulty.INTERMEDIATE);

        assertThat(body).isEqualTo("# 옛 단일 문서")
                .doesNotContain("같은 주제 입문편에서 가져온 부분");
    }

    /**
     * 오려 붙일 절 이름과 프롬프트가 지목하는 이름이 <b>같은 값</b>인지.
     * 갈라지면 프롬프트는 있지도 않은 절을 캐라고 말하게 되고, 그때 모델은 오류를 내지 않고
     * 조용히 아무 데나 캔다 — 2026-09-17에 실제로 그 상태였다(09-14의 편 변경 뒤
     * {@code 재료:} 줄이 입문편 절 이름 그대로 남아 있었다).
     */
    @Test
    @DisplayName("오려 붙일 절과 프롬프트가 지목하는 절이 같은 이름이다 — 갈라지면 없는 절을 캐라고 시킨다")
    void excerptSectionsMatchTheSourceSections() {
        assertThat(project.study.study_project.llm.client.ClaudeProblemGenerator.SOURCE_SECTIONS
                .get(Difficulty.INTERMEDIATE))
                .containsAll(DocumentEditionRule.BEGINNER_SECTIONS_FOR_INTERMEDIATE);
    }

    private GeneratedDocumentFile both() {
        return file(item("probe", "# 입문편"), item("probe-advanced", "# 심화편"));
    }

    private GeneratedDocumentFile beginnerOnly() {
        return file(item("probe", "# 옛 단일 문서"), null);
    }

    private GeneratedDocumentFile file(GeneratedDocumentItem beginner, GeneratedDocumentItem advanced) {
        return new GeneratedDocumentFile("테스트", "2026-09-14", "2026-09-14T00:00:00Z",
                Domain.NETWORK, "test-model", beginner, advanced);
    }

    private GeneratedDocumentItem item(String slug, String body) {
        return new GeneratedDocumentItem("제목", slug, body, List.of("net"));
    }
}
