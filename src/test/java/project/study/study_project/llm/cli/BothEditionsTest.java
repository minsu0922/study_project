package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 두 편 호출을 동시에 보내야 순차 단계가 넷으로 유지된다(설계 2026-11-18 "시간 제한"). */
class BothEditionsTest {

    @Test
    @DisplayName("두 작업이 동시에 돈다 — 차례로 돌면 서로를 기다리다 시간 초과로 실패한다")
    void runsConcurrently() {
        CountDownLatch both = new CountDownLatch(2);
        var pair = BothEditions.run(() -> meet(both, "입문"), () -> meet(both, "심화"));

        assertThat(pair.beginner()).isEqualTo("입문");
        assertThat(pair.advanced()).isEqualTo("심화");
    }

    private static String meet(CountDownLatch latch, String value) {
        latch.countDown();
        try {
            return latch.await(2, TimeUnit.SECONDS) ? value : "시간 초과";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "중단";
        }
    }

    @Test
    @DisplayName("심화편이 없으면 입문편만 돌고 심화편 결과는 null이다")
    void beginnerOnlyWhenAdvancedMissing() {
        AtomicInteger calls = new AtomicInteger();

        var pair = BothEditions.run(() -> calls.incrementAndGet(), null);

        assertThat(pair.beginner()).isEqualTo(1);
        assertThat(pair.advanced()).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("한쪽이 던진 예외는 그대로 다시 던진다")
    void rethrowsRuntimeException() {
        assertThatThrownBy(() -> BothEditions.run(() -> "ok", () -> {
            throw new IllegalStateException("심화편 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("심화편 실패");
    }
}
