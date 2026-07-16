package dev.alex.threadium.scheduler;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.metrics.ThreadiumMetrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Centrally owned, bounded platform-thread executor. Phase 0 deliberately submits no work. */
public final class ThreadiumScheduler {
    private final ThreadPoolExecutor executor;
    private final ShutdownGuard shutdownGuard = new ShutdownGuard();

    public ThreadiumScheduler(int requestedWorkers, ThreadiumMetrics metrics) {
        int workers = SchedulerPolicy.workerCount(requestedWorkers, Runtime.getRuntime().availableProcessors());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Threadium-Worker-" + sequence.incrementAndGet());
            thread.setDaemon(true); // Client shutdown never waits for a worker; lifecycle still shuts down explicitly.
            thread.setUncaughtExceptionHandler((ignored, error) -> {
                metrics.recordWorkerFailure();
                ThreadiumClient.LOGGER.error("Uncaught Threadium worker failure", error);
            });
            return thread;
        };
        executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(SchedulerPolicy.QUEUE_CAPACITY), factory,
                (task, ignored) -> {
                    metrics.recordDeadlineMiss();
                    ThreadiumClient.LOGGER.debug("Threadium rejected a bounded work item");
                });
        executor.allowCoreThreadTimeOut(true);
    }

    public int queueDepth() { return executor.getQueue().size(); }
    public int activeWorkers() { return executor.getActiveCount(); }
    public void shutdown() {
        if (shutdownGuard.beginShutdown()) executor.shutdownNow();
    }
}
