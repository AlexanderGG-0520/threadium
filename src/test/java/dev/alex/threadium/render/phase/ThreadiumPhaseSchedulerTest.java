package dev.alex.threadium.render.phase;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadiumPhaseSchedulerTest {
    @Test
    void boundedQueueRejectsAndCallerCanFallback() throws Exception {
        ThreadiumPhaseScheduler scheduler = new ThreadiumPhaseScheduler(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(scheduler.submit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(scheduler.submit(() -> {}));

        PhaseTask<Integer, Integer> rejected =
                new PhaseTask<>(1, 0, System.nanoTime(), Long.MAX_VALUE, 4, value -> value, ignored -> {});
        assertFalse(scheduler.submit(rejected));
        assertTrue(rejected.selectSynchronousFallback());
        assertEquals(4, rejected.snapshot());
        rejected.markFallbackMerged();
        release.countDown();
        scheduler.shutdown();
    }

    @Test
    void shutdownIsRepeatedlySafeAndRejectsNewWork() {
        ThreadiumPhaseScheduler scheduler = new ThreadiumPhaseScheduler(1, 1);
        scheduler.shutdown();
        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());
        assertFalse(scheduler.submit(() -> fail("must not execute")));
    }
}
