package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMappersTest {

    record Sample(String name) {
    }

    private static final String WITH_UNKNOWN = "{\"name\":\"a\",\"extra\":1}";

    @Test
    @DisplayName("STRICT는 모르는 필드에서 실패한다")
    void strictRejectsUnknown() {
        assertThatThrownBy(() -> JsonMappers.STRICT.readValue(WITH_UNKNOWN, Sample.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("LENIENT는 모르는 필드를 건너뛴다")
    void lenientIgnoresUnknown() throws Exception {
        assertThat(JsonMappers.LENIENT.readValue(WITH_UNKNOWN, Sample.class).name()).isEqualTo("a");
    }
}
