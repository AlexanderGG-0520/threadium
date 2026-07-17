package dev.alex.threadium.scheduler;

/** Pure policy so bounds remain testable without Minecraft. */
public final class SchedulerPolicy {
    public static final int QUEUE_CAPACITY = 64;
    public static final int MAX_WORKERS = 4;

    private SchedulerPolicy() {}

    public static int workerCount(int override, int processors) {
        if (override < 0 || processors < 1) throw new IllegalArgumentException("invalid worker inputs");
        if (override > 0) return Math.min(override, MAX_WORKERS);
        return Math.max(1, Math.min(MAX_WORKERS, (processors - 1) / 2));
    }
}
