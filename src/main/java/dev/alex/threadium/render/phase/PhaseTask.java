package dev.alex.threadium.render.phase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** One phase slot. The worker and render thread compete through explicit CAS transitions. */
public final class PhaseTask<S, R> implements Runnable {
    private final long generation;
    private final int phaseSlot;
    private final long submissionNanos;
    private final long deadlineNanos;
    private final S snapshot;
    private final Function<S, R> processor;
    private final Consumer<PhaseTask<S, R>> completionObserver;
    private final AtomicReference<PhaseTaskState> state = new AtomicReference<>(PhaseTaskState.PENDING);
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile R result;
    private volatile Throwable failure;
    private volatile boolean staleCompletion;

    public PhaseTask(
            long generation,
            int phaseSlot,
            long submissionNanos,
            long deadlineNanos,
            S snapshot,
            Function<S, R> processor,
            Consumer<PhaseTask<S, R>> completionObserver) {
        this.generation = generation;
        this.phaseSlot = phaseSlot;
        this.submissionNanos = submissionNanos;
        this.deadlineNanos = deadlineNanos;
        this.snapshot = snapshot;
        this.processor = processor;
        this.completionObserver = completionObserver;
    }

    @Override
    public void run() {
        if (!state.compareAndSet(PhaseTaskState.PENDING, PhaseTaskState.RUNNING)) {
            completed.countDown();
            return;
        }
        try {
            R computed = processor.apply(snapshot);
            result = computed;
            if (!state.compareAndSet(PhaseTaskState.RUNNING, PhaseTaskState.ASYNC_COMPLETED)) {
                result = null;
                staleCompletion = true;
            }
        } catch (Throwable throwable) {
            failure = throwable;
            state.compareAndSet(PhaseTaskState.RUNNING, PhaseTaskState.FAILED);
        } finally {
            completed.countDown();
            completionObserver.accept(this);
        }
    }

    public boolean selectSynchronousFallback() {
        while (true) {
            PhaseTaskState current = state.get();
            if (current != PhaseTaskState.PENDING
                    && current != PhaseTaskState.RUNNING
                    && current != PhaseTaskState.FAILED
                    && current != PhaseTaskState.ASYNC_COMPLETED) return false;
            if (state.compareAndSet(current, PhaseTaskState.SYNC_FALLBACK)) {
                result = null;
                return true;
            }
        }
    }

    public boolean awaitUntil(long absoluteDeadlineNanos) throws InterruptedException {
        long remaining = absoluteDeadlineNanos - System.nanoTime();
        return remaining > 0L && completed.await(remaining, TimeUnit.NANOSECONDS);
    }

    public R takeAsyncResult(long expectedGeneration) {
        R value = result;
        if (generation != expectedGeneration
                || value == null
                || !state.compareAndSet(PhaseTaskState.ASYNC_COMPLETED, PhaseTaskState.MERGED)) return null;
        result = null;
        return value;
    }

    public R takeAsyncResult(long expectedGeneration, Predicate<? super R> acceptance) {
        R value = result;
        if (generation != expectedGeneration
                || value == null
                || !acceptance.test(value)
                || !state.compareAndSet(PhaseTaskState.ASYNC_COMPLETED, PhaseTaskState.MERGED)) return null;
        result = null;
        return value;
    }

    public void markFallbackMerged() {
        if (!state.compareAndSet(PhaseTaskState.SYNC_FALLBACK, PhaseTaskState.MERGED))
            throw new IllegalStateException("fallback slot was not owned");
    }

    public void cancel() {
        state.compareAndSet(PhaseTaskState.PENDING, PhaseTaskState.CANCELLED);
    }

    public PhaseTaskState state() {
        return state.get();
    }

    public long generation() {
        return generation;
    }

    public int phaseSlot() {
        return phaseSlot;
    }

    public long submissionNanos() {
        return submissionNanos;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public S snapshot() {
        return snapshot;
    }

    public Throwable failure() {
        return failure;
    }

    public boolean staleCompletion() {
        return staleCompletion;
    }
}
