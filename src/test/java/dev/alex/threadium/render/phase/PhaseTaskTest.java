package dev.alex.threadium.render.phase;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PhaseTaskTest {
    @Test void asyncResultCanMergeOnlyOnce() {
        PhaseTask<Integer, Integer> task = task(7, value -> value * 2);
        task.run();
        assertEquals(14, task.takeAsyncResult(1));
        assertNull(task.takeAsyncResult(1));
        assertEquals(PhaseTaskState.MERGED, task.state());
    }

    @Test void wrongGenerationCannotMerge() {
        PhaseTask<Integer, Integer> task = task(7, value -> value);
        task.run();
        assertNull(task.takeAsyncResult(2));
        assertEquals(7, task.takeAsyncResult(1));
    }

    @Test void fallbackMakesLateWorkerCompletionStale() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PhaseTask<Integer, Integer> task = task(3, value -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return value;
        });
        Thread worker = new Thread(task);
        worker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(task.selectSynchronousFallback());
        task.markFallbackMerged();
        release.countDown();
        worker.join();
        assertTrue(task.staleCompletion());
        assertNull(task.takeAsyncResult(1));
    }

    @Test void failureRemainsRecoverableByFallback() {
        PhaseTask<Integer, Integer> task = task(1, value -> { throw new IllegalStateException("boom"); });
        task.run();
        assertEquals(PhaseTaskState.FAILED, task.state());
        assertNotNull(task.failure());
        assertTrue(task.selectSynchronousFallback());
        task.markFallbackMerged();
    }

    @Test void cancellationBeforeExecutionDoesNotRunProcessor() {
        boolean[] ran = {false};
        PhaseTask<Integer, Integer> task = task(1, value -> { ran[0] = true; return value; });
        task.cancel();
        task.run();
        assertFalse(ran[0]);
        assertEquals(PhaseTaskState.CANCELLED, task.state());
    }

    @Test void sharedAbsoluteDeadlineDoesNotRenewPerWait() throws Exception {
        PhaseTask<Integer, Integer> task = task(1, value -> value);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5);
        assertFalse(task.awaitUntil(deadline));
        long afterFirst = System.nanoTime();
        assertFalse(task.awaitUntil(deadline));
        assertTrue(System.nanoTime() - afterFirst < TimeUnit.MILLISECONDS.toNanos(5));
    }

    private static <T, R> PhaseTask<T, R> task(T value, java.util.function.Function<T, R> processor) {
        return new PhaseTask<>(1, 0, System.nanoTime(), Long.MAX_VALUE, value, processor, ignored -> { });
    }
}
