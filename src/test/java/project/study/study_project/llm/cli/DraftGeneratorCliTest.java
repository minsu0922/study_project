package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
