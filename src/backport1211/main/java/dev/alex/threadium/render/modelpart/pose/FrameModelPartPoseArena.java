package dev.alex.threadium.render.modelpart.pose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Frame-scoped pool for exact pose capture arrays.
 *
 * <p>Entries are reused only after the previous frame has been fully drained. The immutable pose returned by a capture
 * therefore remains authoritative for the lifetime of every queued instance that references it.
 */
public final class FrameModelPartPoseArena {
    private final ModelPartPoseCapture.Limits limits;
    private final Map<Integer, Bucket> buckets = new HashMap<>();

    public FrameModelPartPoseArena(ModelPartPoseCapture.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public ModelPartPoseCapture acquire(int boneCount) {
        if (boneCount <= 0) throw new IllegalArgumentException("boneCount must be positive");
        return buckets.computeIfAbsent(boneCount, count -> new Bucket(count, limits))
                .acquire();
    }

    public void beginFrame() {
        for (Bucket bucket : buckets.values()) bucket.beginFrame();
    }

    public void clear() {
        buckets.clear();
    }

    public int pooledEntries() {
        int total = 0;
        for (Bucket bucket : buckets.values()) total = Math.addExact(total, bucket.entries.size());
        return total;
    }

    private static final class Bucket {
        private final int boneCount;
        private final ModelPartPoseCapture.Limits limits;
        private final ArrayList<ModelPartPoseCapture> entries = new ArrayList<>();
        private int used;

        private Bucket(int boneCount, ModelPartPoseCapture.Limits limits) {
            this.boneCount = boneCount;
            this.limits = limits;
        }

        private ModelPartPoseCapture acquire() {
            ModelPartPoseCapture capture;
            if (used == entries.size()) {
                capture = new ModelPartPoseCapture(boneCount, limits);
                entries.add(capture);
            } else {
                capture = entries.get(used);
                capture.resetForFrameReuse();
            }
            used++;
            return capture;
        }

        private void beginFrame() {
            used = 0;
        }
    }
}
