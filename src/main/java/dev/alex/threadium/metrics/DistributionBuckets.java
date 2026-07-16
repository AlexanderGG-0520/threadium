package dev.alex.threadium.metrics;

import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed, allocation-free-at-record-time distributions used by staged-buffer metrics. */
public final class DistributionBuckets {
    private final long[] upperBounds;
    private final AtomicLongArray counts;

    public DistributionBuckets(long... upperBounds) {
        this.upperBounds = upperBounds.clone();
        for (int index = 1; index < this.upperBounds.length; index++) {
            if (this.upperBounds[index] <= this.upperBounds[index - 1]) throw new IllegalArgumentException("bounds must increase");
        }
        this.counts = new AtomicLongArray(upperBounds.length + 1);
    }

    public void record(long value) {
        int bucket = 0;
        while (bucket < upperBounds.length && value > upperBounds[bucket]) bucket++;
        saturatingIncrement(counts, bucket);
    }

    public long[] snapshotAndReset() {
        long[] snapshot = new long[counts.length()];
        for (int index = 0; index < snapshot.length; index++) snapshot[index] = counts.getAndSet(index, 0L);
        return snapshot;
    }

    public static long saturatingAdd(long current, long increment) {
        if (increment <= 0L) return current;
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    private static void saturatingIncrement(AtomicLongArray values, int index) {
        long current;
        do {
            current = values.get(index);
            if (current == Long.MAX_VALUE) return;
        } while (!values.compareAndSet(index, current, current + 1L));
    }
}
