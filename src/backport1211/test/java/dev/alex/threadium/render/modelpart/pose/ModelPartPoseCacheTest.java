package dev.alex.threadium.render.modelpart.pose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ModelPartPoseCacheTest {
    @Test
    void exactEqualPosesFromDistinctRootsCanBeInterned() {
        ModelPartPoseCache cache = new ModelPartPoseCache(2, 64 * 1024);
        ModelPartPoseCache.InternResult first = cache.intern(ModelPartPoseTestFixtures.pose(2, 0, true, true));
        ModelPartPoseCache.InternResult second = cache.intern(ModelPartPoseTestFixtures.pose(2, 0, true, true));

        assertFalse(first.hit());
        assertTrue(second.hit());
        assertSame(first.pose(), second.pose());
        assertEquals(1, cache.uniquePoseCount());
        assertEquals(first.pose().retainedBytes(), cache.retainedBytes());
    }

    @Test
    void changedAnimatedStateDoesNotHitByObjectIdentity() {
        ModelPartPoseCache cache = new ModelPartPoseCache(2, 64 * 1024);

        assertFalse(
                cache.intern(ModelPartPoseTestFixtures.pose(1, 0, true, true)).hit());
        assertFalse(
                cache.intern(ModelPartPoseTestFixtures.pose(1, 1, true, true)).hit());
        assertEquals(2, cache.uniquePoseCount());
    }

    @Test
    void frameAndLifecycleClearReleaseAllPoseState() {
        ModelPartPoseCache cache = new ModelPartPoseCache(1, 64 * 1024);
        cache.intern(ModelPartPoseTestFixtures.pose(1, 0, true, true));

        cache.clear();

        assertEquals(0, cache.uniquePoseCount());
        assertEquals(0, cache.retainedBytes());
        assertFalse(
                cache.intern(ModelPartPoseTestFixtures.pose(1, 0, true, true)).hit());
    }

    @Test
    void uniqueAndRetainedByteLimitsRejectWithoutPartialInsertion() {
        ImmutableModelPartBonePose pose = ModelPartPoseTestFixtures.pose(1, 0, true, true);
        ModelPartPoseCache bytesLimited = new ModelPartPoseCache(1, pose.retainedBytes() - 1);
        assertThrows(ModelPartPoseCapacityException.class, () -> bytesLimited.intern(pose));
        assertEquals(0, bytesLimited.uniquePoseCount());

        ModelPartPoseCache countLimited = new ModelPartPoseCache(1, 64 * 1024);
        countLimited.intern(pose);
        assertThrows(
                ModelPartPoseCapacityException.class,
                () -> countLimited.intern(ModelPartPoseTestFixtures.pose(1, 1, true, true)));
        assertEquals(1, countLimited.uniquePoseCount());
    }

    @Test
    void captureLimitsRejectBeforeArrayAllocation() {
        ModelPartPoseCapture.Limits limits = new ModelPartPoseCapture.Limits(2, 49, 1, 1_024);
        assertThrows(ModelPartPoseCapacityException.class, () -> new ModelPartPoseCapture(2, limits));
        assertThrows(ModelPartPoseCapacityException.class, () -> new ModelPartPoseCapture(3, limits));
    }
}
