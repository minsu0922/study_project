package project.study.study_project.llm.cli;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 입문편·심화편 호출을 동시에 보낸다. 배치 호출 하나가 최대 45분을 기다리므로, 차례로 보내면
 * 순차 단계가 늘어 job 제한(300분)에 가까워진다(설계 2026-11-18 "시간 제한").
 */
final class BothEditions {

    record Pair<T>(T beginner, T advanced) {
    }

    private BothEditions() {
    }

    /**
     * @param advanced 심화편이 없는 날은 {@code null}. 그때는 입문편만 돌린다
     * @throws RuntimeException 한쪽이 던진 예외를 그대로 다시 던진다
     */
    static <T> Pair<T> run(Supplier<T> beginner, Supplier<T> advanced) {
        if (advanced == null) {
            return new Pair<>(beginner.get(), null);
        }
        // 공용 풀은 데몬 스레드라 배치 main이 먼저 끝나면 일이 잘린다. 직접 만들고 닫는다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<T> b = CompletableFuture.supplyAsync(beginner, pool);
            CompletableFuture<T> a = CompletableFuture.supplyAsync(advanced, pool);
            return new Pair<>(b.join(), a.join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        } finally {
            pool.shutdown();
        }
    }
}
