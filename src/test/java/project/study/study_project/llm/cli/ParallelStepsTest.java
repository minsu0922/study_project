package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 성격이 다른 두 단계(사실 검수·독자 점검)를 동시에 돌린다. */
class ParallelStepsTest {

    @Test
    @DisplayName("두 결과를 함께 돌려준다")
    void runsBoth() {
        ParallelSteps.Pair<String, Integer> got = ParallelSteps.run(() -> "사실", () -> 3);
        assertThat(got.first()).isEqualTo("사실");
        assertThat(got.second()).isEqualTo(3);
    }

    @Test
    @DisplayName("한쪽이 던진 실행 예외를 감싸지 않고 그대로 던진다")
    void rethrowsRuntimeException() {
        assertThatThrownBy(() -> ParallelSteps.run(() -> "사실", () -> {
            throw new IllegalStateException("터짐");
        })).isInstanceOf(IllegalStateException.class).hasMessage("터짐");
    }
}
