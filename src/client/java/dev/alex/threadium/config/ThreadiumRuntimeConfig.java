package dev.alex.threadium.config;

import java.util.Objects;

/** Atomically publishes persisted settings and applies deferred settings at explicit lifecycle boundaries. */
public final class ThreadiumRuntimeConfig {
    private static volatile Controller controller;
    private static volatile ThreadiumConfig persisted;

    private ThreadiumRuntimeConfig() {}

    public static void initialize(ThreadiumConfig config) {
        persisted = config;
        controller = new Controller(Snapshot.from(config));
    }

    public static ThreadiumConfig persisted() {
        ThreadiumConfig current = persisted;
        return current == null ? ThreadiumConfig.defaults() : current;
    }

    public static Snapshot desired() {
        Controller current = controller;
        return current == null ? Snapshot.from(ThreadiumConfig.defaults()) : current.desired();
    }

    public static Snapshot effective() {
        Controller current = controller;
        return current == null ? Snapshot.from(ThreadiumConfig.defaults()) : current.effective();
    }

    public static boolean publishSaved(ThreadiumConfig config) {
        Controller current = controller;
        if (current == null) return false;
        if (!current.publishAfterSave(Snapshot.from(config), true)) return false;
        persisted = config;
        return true;
    }

    public static Transition applyFrameBoundary(boolean safe) {
        Controller current = controller;
        return current == null ? Transition.unchanged(effective()) : current.applyFrameBoundary(safe);
    }

    public static Transition applyWorldBoundary() {
        Controller current = controller;
        return current == null ? Transition.unchanged(effective()) : current.applyWorldBoundary();
    }

    public record Snapshot(
            boolean enabled,
            boolean metricsEnabled,
            boolean debugLogging,
            boolean parallelVisibilityEnabled,
            boolean phasePipelineEnabled,
            int workerCountOverride,
            boolean retainedTextEnabled,
            boolean gpuEntityEnabled,
            int gpuMinimumGroupSubmits,
            boolean gpuAllowVanillaFallback,
            boolean gpuBatchConsolidation,
            int metricsOutputIntervalSeconds) {
        static Snapshot from(ThreadiumConfig config) {
            return new Snapshot(
                    config.enabled(),
                    config.metricsEnabled(),
                    config.debugLogging(),
                    config.parallelVisibilityEnabled(),
                    config.phasePipelineEnabled(),
                    config.workerCountOverride(),
                    config.retainedTextEnabled(),
                    config.gpuEntityEnabled(),
                    config.gpuMinimumGroupSubmits(),
                    config.gpuAllowVanillaFallback(),
                    config.gpuBatchConsolidation(),
                    config.metricsOutputIntervalSeconds());
        }

        Snapshot withImmediateFrom(Snapshot desired) {
            return new Snapshot(
                    enabled,
                    desired.metricsEnabled,
                    desired.debugLogging,
                    parallelVisibilityEnabled,
                    phasePipelineEnabled,
                    workerCountOverride,
                    retainedTextEnabled,
                    gpuEntityEnabled,
                    desired.gpuMinimumGroupSubmits,
                    desired.gpuAllowVanillaFallback,
                    desired.gpuBatchConsolidation,
                    desired.metricsOutputIntervalSeconds);
        }

        Snapshot withFrameFrom(Snapshot desired) {
            return new Snapshot(
                    desired.enabled,
                    metricsEnabled,
                    debugLogging,
                    parallelVisibilityEnabled,
                    desired.phasePipelineEnabled,
                    workerCountOverride,
                    desired.retainedTextEnabled,
                    desired.gpuEntityEnabled,
                    gpuMinimumGroupSubmits,
                    gpuAllowVanillaFallback,
                    gpuBatchConsolidation,
                    metricsOutputIntervalSeconds);
        }

        Snapshot withWorldFrom(Snapshot desired) {
            return new Snapshot(
                    enabled,
                    metricsEnabled,
                    debugLogging,
                    desired.parallelVisibilityEnabled,
                    phasePipelineEnabled,
                    desired.workerCountOverride,
                    retainedTextEnabled,
                    gpuEntityEnabled,
                    gpuMinimumGroupSubmits,
                    gpuAllowVanillaFallback,
                    gpuBatchConsolidation,
                    metricsOutputIntervalSeconds);
        }
    }

    public record Transition(Snapshot before, Snapshot after, boolean applied) {
        static Transition unchanged(Snapshot snapshot) {
            return new Transition(snapshot, snapshot, false);
        }

        public boolean changed() {
            return !before.equals(after);
        }
    }

    /** Update methods are rare lifecycle operations; hot paths only perform one volatile snapshot read. */
    static final class Controller {
        private volatile Snapshot desired;
        private volatile Snapshot effective;

        Controller(Snapshot initial) {
            desired = Objects.requireNonNull(initial, "initial");
            effective = initial;
        }

        Snapshot desired() {
            return desired;
        }

        Snapshot effective() {
            return effective;
        }

        synchronized boolean publishAfterSave(Snapshot saved, boolean persistenceSucceeded) {
            if (!persistenceSucceeded) return false;
            desired = Objects.requireNonNull(saved, "saved");
            effective = effective.withImmediateFrom(saved);
            return true;
        }

        synchronized Transition applyFrameBoundary(boolean safe) {
            Snapshot before = effective;
            if (!safe) return Transition.unchanged(before);
            Snapshot after = before.withImmediateFrom(desired).withFrameFrom(desired);
            effective = after;
            return new Transition(before, after, !before.equals(after));
        }

        synchronized Transition applyWorldBoundary() {
            Snapshot before = effective;
            Snapshot after =
                    before.withImmediateFrom(desired).withFrameFrom(desired).withWorldFrom(desired);
            effective = after;
            return new Transition(before, after, !before.equals(after));
        }

        boolean framePending() {
            Snapshot current = effective;
            Snapshot target = current.withFrameFrom(desired);
            return !current.equals(target);
        }

        boolean worldPending() {
            Snapshot current = effective;
            Snapshot target = current.withWorldFrom(desired);
            return !current.equals(target);
        }
    }
}
