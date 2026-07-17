package dev.alex.threadium.benchmark;

import java.util.Arrays;

public record FrameStatistics(
        int sampleCount,
        long totalNanos,
        double averageNanos,
        double medianNanos,
        double p95Nanos,
        double p99Nanos,
        double p999Nanos,
        double averageFps,
        double onePercentLowFps,
        double pointOnePercentLowFps,
        long maximumNanos,
        double standardDeviationNanos) {
    public static FrameStatistics calculate(long[] samples) {
        if (samples.length == 0) return new FrameStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long total = 0;
        for (long value : samples) total = Math.addExact(total, value);
        double average = (double) total / samples.length;
        return new FrameStatistics(
                samples.length,
                total,
                average,
                percentile(sorted, .5),
                percentile(sorted, .95),
                percentile(sorted, .99),
                samples.length >= 1000 ? percentile(sorted, .999) : 0,
                1_000_000_000d / average,
                slowLow(sorted, .01),
                samples.length >= 1000 ? slowLow(sorted, .001) : 0,
                sorted[sorted.length - 1],
                standardDeviation(samples, average));
    }

    private static double percentile(long[] sorted, double p) {
        double index = p * (sorted.length - 1);
        int low = (int) Math.floor(index), high = (int) Math.ceil(index);
        return sorted[low] + (sorted[high] - sorted[low]) * (index - low);
    }

    private static double slowLow(long[] sorted, double fraction) {
        int count = Math.max(1, (int) Math.ceil(sorted.length * fraction));
        double total = 0;
        for (int i = sorted.length - count; i < sorted.length; i++) total += sorted[i];
        return 1_000_000_000d / (total / count);
    }

    private static double standardDeviation(long[] values, double average) {
        double total = 0;
        for (long value : values) {
            double delta = value - average;
            total += delta * delta;
        }
        return Math.sqrt(total / values.length);
    }
}
