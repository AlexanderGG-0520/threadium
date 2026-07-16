package dev.alex.threadium.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributionBucketsTest {
    @Test void exactBoundariesUseExpectedBucketsAndReset() {
        DistributionBuckets buckets = new DistributionBuckets(0, 1, 7, 31, 127, 511, 2047, 8191);
        long[] values = {0, 1, 2, 7, 8, 31, 32, 127, 128, 511, 512, 2047, 2048, 8191, 8192};
        for (long value : values) buckets.record(value);
        assertArrayEquals(new long[]{1, 1, 2, 2, 2, 2, 2, 2, 1}, buckets.snapshotAndReset());
        assertArrayEquals(new long[9], buckets.snapshotAndReset());
    }

    @Test void additionSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, DistributionBuckets.saturatingAdd(Long.MAX_VALUE - 2, 4));
        assertEquals(9, DistributionBuckets.saturatingAdd(5, 4));
        assertEquals(5, DistributionBuckets.saturatingAdd(5, -1));
    }
}
