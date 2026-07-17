package dev.alex.threadium.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimingAccumulatorTest {
    @Test
    void accumulatesCountTotalAndMaximum() {
        TimingAccumulator accumulator = new TimingAccumulator();
        accumulator.record(12);
        accumulator.record(4);
        accumulator.record(20);

        TimingAccumulator.Sample sample = accumulator.snapshotAndReset();
        assertEquals(3, sample.count());
        assertEquals(36, sample.totalNanos());
        assertEquals(4, sample.minNanos());
        assertEquals(20, sample.maxNanos());
        assertEquals(12, sample.averageNanos());
    }

    @Test
    void resetProducesZeroSample() {
        TimingAccumulator accumulator = new TimingAccumulator();
        accumulator.record(5);
        accumulator.snapshotAndReset();

        TimingAccumulator.Sample sample = accumulator.snapshotAndReset();
        assertEquals(0, sample.count());
        assertEquals(0, sample.totalNanos());
        assertEquals(0, sample.minNanos());
        assertEquals(0, sample.maxNanos());
        assertEquals(0, sample.averageNanos());
        assertEquals("count=0,totalNanos=0,minNanos=0,maxNanos=0", sample.describe());
    }

    @Test
    void ignoresNegativeDuration() {
        TimingAccumulator accumulator = new TimingAccumulator();
        accumulator.record(-1);
        assertEquals(0, accumulator.snapshotAndReset().count());
    }

    @Test
    void totalSaturatesAndConcurrentRecordsRemainAccountedFor() throws Exception {
        TimingAccumulator accumulator = new TimingAccumulator();
        accumulator.record(Long.MAX_VALUE - 1);
        accumulator.record(10);
        assertEquals(Long.MAX_VALUE, accumulator.snapshotAndReset().totalNanos());

        int threads = 4;
        int records = 10_000;
        Thread[] workers = new Thread[threads];
        for (int thread = 0; thread < threads; thread++) {
            workers[thread] = new Thread(() -> {
                for (int index = 0; index < records; index++) accumulator.record(3);
            });
            workers[thread].start();
        }
        for (Thread worker : workers) worker.join();
        TimingAccumulator.Sample sample = accumulator.snapshotAndReset();
        assertEquals((long) threads * records, sample.count());
        assertEquals((long) threads * records * 3, sample.totalNanos());
        assertEquals(3, sample.minNanos());
        assertEquals(3, sample.maxNanos());
    }
}
