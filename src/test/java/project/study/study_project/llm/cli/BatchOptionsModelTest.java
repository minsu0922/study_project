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
}
