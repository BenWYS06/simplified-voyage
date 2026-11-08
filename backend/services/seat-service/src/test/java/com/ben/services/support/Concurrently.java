package com.ben.services.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Runs tasks at the same moment to provoke races. Every thread first waits at a start gate; the gate opens only
 * when all of them are parked there, so they are released together (thread start-up time doesn't count).
 * Returns each task's outcome in order: its result, or the exception it threw.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public static List<Object> run(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();      // the start gate
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();          // open the gate: every task runs now

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
