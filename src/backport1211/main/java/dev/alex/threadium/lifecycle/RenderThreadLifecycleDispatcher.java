package dev.alex.threadium.lifecycle;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Bounded dispatcher for low-frequency lifecycle work owned by one thread. Duplicate pending operations are coalesced
 * while their exact occurrence counts are retained.
 */
public final class RenderThreadLifecycleDispatcher {
    private final OwnerThreadScheduler scheduler;
    private final BooleanSupplier activeOwner;
    private final LifecycleAction action;
    private final ArrayDeque<Operation> order = new ArrayDeque<>(Operation.values().length);
    private final EnumMap<Operation, Long> pending = new EnumMap<>(Operation.class);
    private boolean taskScheduled;
    private boolean draining;
    private boolean shutdownPending;
    private boolean terminated;

    public RenderThreadLifecycleDispatcher(
            OwnerThreadScheduler scheduler, BooleanSupplier activeOwner, LifecycleAction action) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.activeOwner = Objects.requireNonNull(activeOwner, "activeOwner");
        this.action = Objects.requireNonNull(action, "action");
    }

    public RequestResult request(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        boolean ownerThread = scheduler.isOwnerThread();
        boolean schedule = false;
        synchronized (this) {
            if (terminated || shutdownPending) return RequestResult.IGNORED_AFTER_SHUTDOWN;
            pending.compute(operation, (ignored, count) -> count == null ? 1L : Math.addExact(count, 1L));
            if (!order.contains(operation)) order.addLast(operation);
            if (operation == Operation.SHUTDOWN) shutdownPending = true;
            if (!ownerThread && !taskScheduled) {
                taskScheduled = true;
                schedule = true;
            }
        }

        if (ownerThread) {
            drainOnOwnerThread();
            return RequestResult.EXECUTED;
        }
        if (!schedule || scheduler.execute(this::drainOnOwnerThread)) return RequestResult.QUEUED;
        synchronized (this) {
            taskScheduled = false;
        }
        return RequestResult.QUEUE_REJECTED;
    }

    /** Recovery hook for a scheduler rejection race; normally queued work runs before the next rendered frame. */
    public void drainPendingOnOwnerThread() {
        if (!scheduler.isOwnerThread()) throw new IllegalStateException("Lifecycle drain is not on its owner thread");
        drainOnOwnerThread();
    }

    public boolean isOwnerThread() {
        return scheduler.isOwnerThread();
    }

    public synchronized int pendingOperationKinds() {
        return pending.size();
    }

    private void drainOnOwnerThread() {
        if (!scheduler.isOwnerThread()) throw new IllegalStateException("Lifecycle action ran off its owner thread");
        synchronized (this) {
            if (draining) return;
            draining = true;
            taskScheduled = false;
        }
        try {
            while (true) {
                Operation operation;
                long occurrences;
                synchronized (this) {
                    operation = order.pollFirst();
                    if (operation == null) return;
                    occurrences = pending.remove(operation);
                }
                if (!activeOwner.getAsBoolean()) continue;
                action.run(operation, occurrences);
                if (operation == Operation.SHUTDOWN) {
                    synchronized (this) {
                        order.clear();
                        pending.clear();
                        terminated = true;
                    }
                    return;
                }
            }
        } finally {
            synchronized (this) {
                draining = false;
            }
        }
    }

    public enum Operation {
        WORLD,
        RESOURCES,
        SHUTDOWN
    }

    public enum RequestResult {
        EXECUTED,
        QUEUED,
        QUEUE_REJECTED,
        IGNORED_AFTER_SHUTDOWN
    }

    public interface OwnerThreadScheduler {
        boolean isOwnerThread();

        /** Returns false only when the owner executor can no longer accept useful work. */
        boolean execute(Runnable action);
    }

    @FunctionalInterface
    public interface LifecycleAction {
        void run(Operation operation, long occurrences);
    }
}
