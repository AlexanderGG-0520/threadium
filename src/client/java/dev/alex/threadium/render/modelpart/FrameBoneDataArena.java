package dev.alex.threadium.render.modelpart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Frame-scoped pool for pose arrays.
 *
 * <p>Entries may be reused only after {@link #beginFrame()}, when the previous frame's ModelPart queue has already been
 * submitted. Bucketing by exact bone count keeps the array lengths authoritative for backend capacity and upload
 * calculations while eliminating steady-state per-entity pose-array allocation.
 */
final class FrameBoneDataArena {
    private final Map<Integer, Bucket> buckets = new HashMap<>();

    ModelPartBoneData acquire(int boneCount) {
        if (boneCount <= 0) throw new IllegalArgumentException("boneCount must be positive");
        return buckets.computeIfAbsent(boneCount, Bucket::new).acquire();
    }

    void beginFrame() {
        for (Bucket bucket : buckets.values()) bucket.beginFrame();
    }

    void clear() {
        buckets.clear();
    }

    int pooledEntries() {
        int total = 0;
        for (Bucket bucket : buckets.values()) total += bucket.entries.size();
        return total;
    }

    private static final class Bucket {
        private final int boneCount;
        private final ArrayList<ModelPartBoneData> entries = new ArrayList<>();
        private int used;

        private Bucket(int boneCount) {
            this.boneCount = boneCount;
        }

        private ModelPartBoneData acquire() {
            ModelPartBoneData result;
            if (used == entries.size()) {
                result = new ModelPartBoneData(
                        new float[Math.multiplyExact(boneCount, 28)], new long[(boneCount + 63) >>> 6]);
                entries.add(result);
            } else {
                result = entries.get(used);
                Arrays.fill(result.visibility(), 0L);
            }
            used++;
            return result;
        }

        private void beginFrame() {
            used = 0;
        }
    }
}
