package project.study.study_project.learningpath.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PathKeyTest {

    @Test
    @DisplayName("세 종류 모두 문자열로 갔다가 같은 값으로 돌아온다")
    void roundTrip() {
        for (PathKey key : new PathKey[]{PathKey.domain("NETWORK"), PathKey.code("java"), PathKey.custom(12)}) {
            assertThat(PathKey.parse(key.toString())).contains(key);
        }
        assertThat(PathKey.custom(12).toString()).isEqualTo("custom:12");
        assertThat(PathKey.parse("custom:12").orElseThrow().customId()).isEqualTo(12L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NETWORK", "domain:", "domain:network", "domain:A", "code:Java", "custom:0", "custom:-1",
            "custom:1a", "other:1", "domain:NET WORK", "domain:<b>"})
    @DisplayName("형식 밖의 키는 빈 값이다 — 주소창에서 오는 값이라 걸러야 한다")
    void rejectsMalformed(String raw) {
        assertThat(PathKey.parse(raw)).isEmpty();
    }
}
