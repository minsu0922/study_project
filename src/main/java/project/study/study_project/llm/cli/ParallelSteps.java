package project.study.study_project.llm.cli;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 성격이 다른 두 단계를 동시에 돌린다 — 사실 검수와 독자 점검. 차례로 돌리면 순차 단계가 하나 늘어
 * job 제한(300분)에 가까워진다. 단계마다 안에서 {@link BothEditions}로 두 편을 다시 나눠 돌린다.
 */
final class ParallelSteps {

    record Pair<A, B>(A first, B second) {
    }

    private ParallelSteps() {
    }

    /** @throws RuntimeException 한쪽이 던진 예외를 그대로 다시 던진다 */
    static <A, B> Pair<A, B> run(Supplier<A> first, Supplier<B> second) {
        // 공용 풀은 데몬 스레드라 배치 main이 먼저 끝나면 일이 잘린다. 직접 만들고 닫는다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<A> a = CompletableFuture.supplyAsync(first, pool);
            CompletableFuture<B> b = CompletableFuture.supplyAsync(second, pool);
            return new Pair<>(a.join(), b.join());
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
