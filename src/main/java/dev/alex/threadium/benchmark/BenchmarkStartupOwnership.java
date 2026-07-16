package dev.alex.threadium.benchmark;

/** Explicit startup ownership contract shared by the real client bootstrap and tests. */
public record BenchmarkStartupOwnership(
        boolean benchmarkController,
        boolean benchmarkCommands,
        boolean modelPartRenderer,
        String rendererMetricsSource) {
    public static BenchmarkStartupOwnership forMode(ModelPartBenchmarkMode mode) {
        return new BenchmarkStartupOwnership(
                true,
                true,
                mode.replacementEnabled(),
                mode == ModelPartBenchmarkMode.VANILLA ? "NO_OP" : "MODEL_PART_SERVICE");
    }
}
