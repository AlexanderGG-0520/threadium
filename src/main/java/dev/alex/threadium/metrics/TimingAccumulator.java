package dev.alex.threadium.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Lock-free interval accumulator. Snapshot fields are intentionally relaxed across a reset. */
public final class TimingAccumulator {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);

    public void record(long nanos) {
        if (nanos < 0L) return;
        saturatingAdd(count, 1L);
        saturatingAdd(totalNanos, nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
        minNanos.accumulateAndGet(nanos, Math::min);
    }

    public Sample snapshotAndReset() {
        // A sample racing this sequence can land in adjacent fields/intervals, but is never discarded.
        long sampleCount = count.getAndSet(0L);
        long total = totalNanos.getAndSet(0L);
        long maximum = maxNanos.getAndSet(0L);
        long minimum = minNanos.getAndSet(Long.MAX_VALUE);
        return new Sample(sampleCount, total, sampleCount == 0L ? 0L : minimum, maximum);
    }

    private static void saturatingAdd(AtomicLong value, long increment) {
        long current;
        do {
            current = value.get();
            if (current == Long.MAX_VALUE) return;
        } while (!value.compareAndSet(
                current, current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment));
    }

    public record Sample(long count, long totalNanos, long minNanos, long maxNanos) {
        public long averageNanos() {
            return count == 0L ? 0L : totalNanos / count;
        }

        public String describe() {
            return count == 0L
                    ? "count=0,totalNanos=0,minNanos=0,maxNanos=0"
                    : "count=" + count + ",totalNanos=" + totalNanos + ",averageNanos=" + averageNanos() + ",minNanos="
                            + minNanos + ",maxNanos=" + maxNanos;
        }
    }
}
