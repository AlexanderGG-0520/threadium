package dev.alex.threadium.render.modelpart.pose;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned frame-local exact pose interning. */
public final class ModelPartPoseCache {
    private final int maximumUniquePoses;
    private final long maximumRetainedBytes;
    private final Map<ModelPartPoseKey, ImmutableModelPartBonePose> poses = new HashMap<>();
    private long retainedBytes;

    public ModelPartPoseCache(int maximumUniquePoses, long maximumRetainedBytes) {
        if (maximumUniquePoses <= 0 || maximumRetainedBytes < 0) {
            throw new IllegalArgumentException("Pose cache limits are invalid");
        }
        this.maximumUniquePoses = maximumUniquePoses;
        this.maximumRetainedBytes = maximumRetainedBytes;
    }

    public InternResult intern(ImmutableModelPartBonePose candidate) {
        Objects.requireNonNull(candidate, "candidate");
        ImmutableModelPartBonePose existing = poses.get(candidate.key());
        if (existing != null) return new InternResult(existing, true);

        long nextRetainedBytes = Math.addExact(retainedBytes, candidate.retainedBytes());
        if (poses.size() >= maximumUniquePoses || nextRetainedBytes > maximumRetainedBytes) {
            throw new ModelPartPoseCapacityException("Frame-local exact pose cache capacity exceeded");
        }
        poses.put(candidate.key(), candidate);
        retainedBytes = nextRetainedBytes;
        return new InternResult(candidate, false);
    }

    public int uniquePoseCount() {
        return poses.size();
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    public void clear() {
        poses.clear();
        retainedBytes = 0;
    }

    public record InternResult(ImmutableModelPartBonePose pose, boolean hit) {}
}
