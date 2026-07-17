package dev.alex.threadium.metrics;

/** Pure calculations kept separate from Minecraft integration. */
public final class StagedMetricMath {
    private StagedMetricMath() {}

    public static long quadCount(boolean quadTopology, long vertexCount) {
        return quadTopology && vertexCount >= 0L && vertexCount % 4L == 0L ? vertexCount / 4L : 0L;
    }

    public static long availableOverlap(long readyNanos, long requiredNanos) {
        if (readyNanos <= 0L || requiredNanos <= readyNanos) return 0L;
        return requiredNanos - readyNanos;
    }
}
