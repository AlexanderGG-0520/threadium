package dev.alex.threadium.render.phase;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated bounded platform-thread executor; rejection is returned to the render-thread fallback path. */
public final class ThreadiumPhaseScheduler {
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public ThreadiumPhaseScheduler(int workers, int queueCapacity) {
        int boundedWorkers = Math.max(1, Math.min(workers, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Threadium Phase Worker #" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(boundedWorkers, boundedWorkers, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    public boolean submit(Runnable task) {
        if (shutdown.get()) return false;
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public int queueDepth() { return executor.getQueue().size(); }
    public int activeWorkers() { return executor.getActiveCount(); }
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof PhaseTask<?, ?> task) task.cancel();
        }
    }
    public boolean isShutdown() { return shutdown.get(); }
}
