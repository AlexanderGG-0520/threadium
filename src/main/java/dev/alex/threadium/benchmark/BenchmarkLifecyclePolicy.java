package dev.alex.threadium.benchmark;

public final class BenchmarkLifecyclePolicy {
    private BenchmarkLifecyclePolicy() {}

    public enum Phase {
        IDLE,
        SETUP,
        WARMUP,
        MEASUREMENT,
        COMPLETE,
        ABORTED
    }

    public static boolean cameraLocked(Phase phase) {
        return phase == Phase.SETUP || phase == Phase.WARMUP || phase == Phase.MEASUREMENT;
    }

    public static boolean mayStart(Phase phase, boolean worldLoaded, boolean worldValid, boolean sceneValid) {
        return phase == Phase.IDLE && worldLoaded && worldValid && sceneValid;
    }

    public static boolean mayAbort(Phase phase) {
        return phase == Phase.SETUP || phase == Phase.WARMUP || phase == Phase.MEASUREMENT;
    }
}
