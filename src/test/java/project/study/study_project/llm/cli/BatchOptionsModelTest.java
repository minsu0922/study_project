package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BatchOptionsModelTest {

    @Test
    @DisplayName("--model이 있으면 설정보다 앞선다")
    void optionWins() throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(new String[]{"--model=claude-x"});
        assertThat(BatchOptions.resolveModel(opts)).isEqualTo("claude-x");
    }

    @Test
    @DisplayName("빈 --model=은 지정 안 한 것으로 보고 application.yml 값을 쓴다")
    void blankFallsBackToConfig() throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(new String[]{"--model="});
        String configured = BatchOptions.configuredModel(BatchOptions.readGenerationConfig());
        assertThat(BatchOptions.resolveModel(opts)).isEqualTo(configured);
    }

    @Test
    @DisplayName("설정에 model이 없으면 기본 모델")
    void defaultWhenMissing() {
        assertThat(BatchOptions.configuredModel(Map.of())).isEqualTo(BatchOptions.DEFAULT_MODEL);
    }

    /** batch-enabled와 반대로, 키가 없으면 꺼진 것으로 본다. 평가 전에 윤문이 저절로 켜지면 안 된다. */
    @Test
    @DisplayName("윤문 설정이 없으면 꺼진 것으로 본다")
    void polishOffWhenMissing() {
        assertThat(BatchOptions.polishEnabled(Map.of())).isFalse();
    }

    @Test
    @DisplayName("윤문 설정을 true로 두면 켜진다")
    void polishOnWhenTrue() {
        assertThat(BatchOptions.polishEnabled(Map.of("polish-enabled", true))).isTrue();
    }

    @Test
    @DisplayName("application.yml에는 윤문이 꺼진 채로 들어 있다")
    void polishOffInApplicationYml() throws Exception {
        Map<String, Object> generation = BatchOptions.readGenerationConfig();
        assertThat(generation).containsEntry("polish-enabled", false);
        assertThat(BatchOptions.polishEnabled(generation)).isFalse();
    }
}
