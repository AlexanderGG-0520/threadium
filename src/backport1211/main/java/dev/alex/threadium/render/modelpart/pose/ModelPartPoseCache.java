package dev.alex.threadium.render.modelpart.pose;

import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Render-thread-owned exact frame-local pose interning with adaptive direct-packing bypass.
 *
 * <p>Static or repeated animated poses retain collision-safe exact interning. A mesh that produces too many unique
 * poses without reuse stops paying map-retention costs for the rest of the frame; its candidates are returned directly
 * to the backend and packed once. The policy resets every frame.
 */
public final class ModelPartPoseCache {
    public static final int MISS_PROBE_LIMIT = 4;

    private final int maximumUniquePoses;
    private final long maximumRetainedBytes;
    private final Map<ModelPartPoseKey, ImmutableModelPartBonePose> poses = new HashMap<>();
    private final IdentityHashMap<ImmutableModelPartMesh, ReuseProbe> probes = new IdentityHashMap<>();
    private long retainedBytes;
    private boolean lastLookupPerformed;
    private boolean lastStored;
    private boolean lastBypassed;

    public ModelPartPoseCache(int maximumUniquePoses, long maximumRetainedBytes) {
        if (maximumUniquePoses <= 0 || maximumRetainedBytes < 0) {
            throw new IllegalArgumentException("Pose cache limits are invalid");
        }
        this.maximumUniquePoses = maximumUniquePoses;
        this.maximumRetainedBytes = maximumRetainedBytes;
    }

    public InternResult intern(ImmutableModelPartBonePose candidate) {
        return intern(null, candidate);
    }

    public InternResult intern(ImmutableModelPartMesh mesh, ImmutableModelPartBonePose candidate) {
        Objects.requireNonNull(candidate, "candidate");
        ReuseProbe probe = mesh == null ? null : probes.computeIfAbsent(mesh, ignored -> new ReuseProbe());
        lastLookupPerformed = false;
        lastStored = false;
        lastBypassed = probe != null && probe.bypass;
        if (lastBypassed) return new InternResult(candidate, false, false, false, true);

        lastLookupPerformed = true;
        ImmutableModelPartBonePose existing = poses.get(candidate.key());
        if (existing != null) {
            if (probe != null) {
                probe.hits++;
                probe.reuseConfirmed = true;
            }
            return new InternResult(existing, true, true, false, false);
        }

        boolean shouldStore = true;
        if (probe != null) {
            probe.misses++;
            boolean withinInitialBudget = probe.misses < MISS_PROBE_LIMIT;
            boolean reuseStillDominant = probe.reuseConfirmed && (long) probe.hits * 2L >= probe.misses;
            shouldStore = withinInitialBudget || reuseStillDominant;
            if (!shouldStore && probe.misses >= MISS_PROBE_LIMIT) probe.bypass = true;
        }
        if (!shouldStore) {
            lastBypassed = true;
            return new InternResult(candidate, false, true, false, true);
        }

        long nextRetainedBytes = Math.addExact(retainedBytes, candidate.retainedBytes());
        if (poses.size() >= maximumUniquePoses || nextRetainedBytes > maximumRetainedBytes) {
            throw new ModelPartPoseCapacityException("Frame-local exact pose cache capacity exceeded");
        }
        poses.put(candidate.key(), candidate);
        retainedBytes = nextRetainedBytes;
        lastStored = true;
        return new InternResult(candidate, false, true, true, false);
    }

    public int uniquePoseCount() {
        return poses.size();
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    public boolean lastLookupPerformed() {
        return lastLookupPerformed;
    }

    public boolean lastStored() {
        return lastStored;
    }

    public boolean lastBypassed() {
        return lastBypassed;
    }

    public void clear() {
        poses.clear();
        probes.clear();
        retainedBytes = 0;
        lastLookupPerformed = false;
        lastStored = false;
        lastBypassed = false;
    }

    public record InternResult(
            ImmutableModelPartBonePose pose, boolean hit, boolean lookupPerformed, boolean stored, boolean bypassed) {
        public InternResult(ImmutableModelPartBonePose pose, boolean hit) {
            this(pose, hit, true, !hit, false);
        }
    }

    private static final class ReuseProbe {
        private int hits;
        private int misses;
        private boolean reuseConfirmed;
        private boolean bypass;
    }
}
