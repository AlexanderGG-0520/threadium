package dev.alex.threadium.render.phase;

import dev.alex.threadium.metrics.TimingAccumulator;

import java.util.concurrent.atomic.AtomicLong;

final class PhasePipelineMetrics {
    final TimingAccumulator snapshot = new TimingAccumulator();
    final TimingAccumulator submission = new TimingAccumulator();
    final TimingAccumulator queueWait = new TimingAccumulator();
    final TimingAccumulator worker = new TimingAccumulator();
    final TimingAccumulator renderWait = new TimingAccumulator();
    final TimingAccumulator merge = new TimingAccumulator();
    final TimingAccumulator fallback = new TimingAccumulator();
    final AtomicLong rejections = new AtomicLong();
    final AtomicLong asyncCompletions = new AtomicLong();
    final AtomicLong fallbacks = new AtomicLong();
    final AtomicLong stale = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicLong submits = new AtomicLong();
    final AtomicLong resultSize = new AtomicLong();

    String snapshotAndReset(int queue, int active) {
        return "phasePipeline={queue=" + queue + ",active=" + active
                + ",snapshot=" + snapshot.snapshotAndReset().describe()
                + ",submission=" + submission.snapshotAndReset().describe()
                + ",queueWait=" + queueWait.snapshotAndReset().describe()
                + ",worker=" + worker.snapshotAndReset().describe()
                + ",renderWait=" + renderWait.snapshotAndReset().describe()
                + ",merge=" + merge.snapshotAndReset().describe()
                + ",fallback=" + fallback.snapshotAndReset().describe()
                + ",rejections=" + rejections.getAndSet(0) + ",async=" + asyncCompletions.getAndSet(0)
                + ",fallbacks=" + fallbacks.getAndSet(0) + ",stale=" + stale.getAndSet(0)
                + ",failures=" + failures.getAndSet(0) + ",submits=" + submits.getAndSet(0)
                + ",resultSize=" + resultSize.getAndSet(0) + '}';
    }
}
