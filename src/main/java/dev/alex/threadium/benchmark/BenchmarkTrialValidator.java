package dev.alex.threadium.benchmark;

import java.util.ArrayList;
import java.util.List;

public final class BenchmarkTrialValidator {
    private BenchmarkTrialValidator() {}

    public static List<String> validate(
            ModelPartBenchmarkMode mode,
            Counters c,
            boolean focused,
            boolean stableCamera,
            boolean stableWorld,
            boolean stableEntities,
            boolean stableResolution,
            boolean samplesOverflowed,
            long measuredNanos,
            long requiredNanos,
            int sampleCount) {
        ArrayList<String> reasons = new ArrayList<>();
        if (!focused) reasons.add("window lost focus");
        if (!stableCamera) reasons.add("camera moved");
        if (!stableWorld) reasons.add("world or resource generation changed");
        if (!stableEntities) reasons.add("entity count changed");
        if (!stableResolution) reasons.add("resolution changed");
        if (samplesOverflowed) reasons.add("frame sample capacity exceeded");
        if (measuredNanos < requiredNanos) reasons.add("measurement duration too short");
        if (sampleCount < 2) reasons.add("too few frame samples");
        if (c.backendFailures > 0) reasons.add("backend failures");
        if (c.submissionFailures > 0) reasons.add("Blaze3D submission failures");
        if (c.rawProductionDrawCalls > 0) reasons.add("raw production draws");
        if (c.queuedInstances != c.drawnInstances) reasons.add("queued/drawn mismatch");
        if (c.accepted != c.suppressions) reasons.add("accepted/suppression mismatch");
        if (c.fallbacks > 0) reasons.add("unexpected fallback");
        if (c.pipelineInvalid > 0) reasons.add("invalid pipeline");
        if (c.pipelineStale > 0) reasons.add("stale pipeline");
        if (c.pipelineUnknown > 0) reasons.add("unknown pipeline validity");
        if (mode == ModelPartBenchmarkMode.VANILLA) {
            if (c.accepted != 0
                    || c.suppressions != 0
                    || c.queuedInstances != 0
                    || c.drawnInstances != 0
                    || c.drawCalls != 0) reasons.add("VANILLA replacement activity");
        } else {
            if (c.accepted <= 0) reasons.add("no replacements accepted");
            if (mode == ModelPartBenchmarkMode.SINGLETON
                    && (c.multiInstanceBatches != 0
                            || c.maximumInstancesPerDraw != 1
                            || c.drawCalls != c.drawnInstances)) reasons.add("SINGLETON draw invariant");
            if (mode == ModelPartBenchmarkMode.BATCHING
                    && (c.drawCalls >= c.drawnInstances
                            || c.multiInstanceBatches <= 0
                            || c.maximumInstancesPerDraw <= 1)) reasons.add("BATCHING draw invariant");
        }
        return List.copyOf(reasons);
    }

    public record Counters(
            long accepted,
            long suppressions,
            long queuedInstances,
            long drawnInstances,
            long drawCalls,
            long multiInstanceBatches,
            long maximumInstancesPerDraw,
            long backendFailures,
            long submissionFailures,
            long rawProductionDrawCalls,
            long fallbacks,
            long pipelineInvalid,
            long pipelineStale,
            long pipelineUnknown) {}
}
