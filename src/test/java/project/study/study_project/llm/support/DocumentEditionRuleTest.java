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
