package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 중단 스위치의 진리표 테스트 — docs/14.
 *
 * <p><b>두 줄짜리 판단을 왜 테스트하나.</b> 이 로직이 틀렸을 때의 증상이 조용하기 때문이다.
 * {@code ||}를 {@code &&}로 잘못 쓰면 "켜 뒀는데도 매일 아무것도 안 하는" 상태가 되는데,
 * 워크플로는 초록불로 끝나고 에러도 없다 — 몇 주 뒤 "왜 문제가 안 들어오지?" 할 때까지 모른다.
 * 이 프로젝트가 이미 똑같은 방식으로 한 번 당했다(배치를 켰는데 초안 0건, docs/14).
 *
 * <p>그때의 교훈은 "조용히 아무것도 안 하는 상태를 만들 수 있는 코드는 크기와 무관하게
 * 못 박아 둔다"였고, 이 테스트가 그 실행이다.
 */
class DraftGeneratorCliTest {

    @Test
    @DisplayName("배치가 켜져 있으면 생성한다 — 평소의 예약 실행 경로")
    void generatesWhenEnabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(true, false)).isTrue();
    }

    @Test
    @DisplayName("배치를 끄면 생성하지 않는다 — 스위치의 본래 목적")
    void doesNotGenerateWhenDisabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(false, false)).isFalse();
    }

    @Test
    @DisplayName("꺼져 있어도 force면 생성한다 — 설정을 되돌리는 것을 잊는 사고를 막는 예외 구멍")
    void forceOverridesDisabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(false, true)).isTrue();
    }

    @Test
    @DisplayName("켜져 있는데 force를 줘도 평소와 같다 — force는 켜는 스위치가 아니라 우회로일 뿐")
    void forceIsHarmlessWhenEnabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(true, true)).isTrue();
    }

    /* ══ 2단계: 주기 분야와 근거 문서 분야가 어긋날 때 ══════════ */

    /**
     * 실제로 겪을 상황이라 테스트로 못 박는다. 2026-08-11에 손으로 만든 문서(캐시 전략,
     * SYSTEM_DESIGN)가 OS 주기의 근거로 잡히는 배치가 예정돼 있었다.
     *
     * <p>맞추지 않으면 "운영체제 문제를 내라"와 "이 캐시 문서 안에서만 내라"가 한 프롬프트에
     * 함께 실린다. <b>모델은 오류를 내지 않는다</b> — 둘 중 하나를 무시하거나 섞은 문제를
     * 만들어 낸다. 조용히 결과만 나빠지는 종류라 사람이 알아채기까지 오래 걸린다.
     */
    @Test
    @DisplayName("주기 분야와 문서 분야가 다르면 문서 쪽으로 맞춘다 — 근거 문서가 곧 그 주기의 주제다")
    void alignsDomainWithDocumentWhenTheyDiffer() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.OS, Domain.SYSTEM_DESIGN))
                .isEqualTo(Domain.SYSTEM_DESIGN);
    }

    @Test
    @DisplayName("문서에 분야가 없으면(옛 형식) 주기 분야를 그대로 쓴다 — 알 수 없는 값 때문에 멀쩡한 분야를 버리지 않는다")
    void keepsPlanDomainWhenDocumentDomainMissing() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.OS, null))
                .isEqualTo(Domain.OS);
    }

    @Test
    @DisplayName("둘이 같으면 그대로 — 정상 주기에서는 아무 일도 일어나지 않는다")
    void keepsDomainWhenAlreadyMatching() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.SECURITY, Domain.SECURITY))
                .isEqualTo(Domain.SECURITY);
    }
}
