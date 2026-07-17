package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchmarkAnimatedCowPoseTest {
    @Test
    void deterministicPoseVariesAcrossTicksAndEntitiesWithinBounds() {
        float first = BenchmarkAnimatedCowPose.yaw(10, 0);
        float nextTick = BenchmarkAnimatedCowPose.yaw(11, 0);
        float nextEntity = BenchmarkAnimatedCowPose.yaw(10, 1);
        assertNotEquals(first, nextTick);
        assertNotEquals(first, nextEntity);
        for (int tick = 0; tick < 100; tick++)
            for (int entity = 0; entity < BenchmarkSceneSpec.STATIC.entityCount(); entity++)
                assertTrue(BenchmarkAnimatedCowPose.yaw(tick, entity) >= 145.0f
                        && BenchmarkAnimatedCowPose.yaw(tick, entity) <= 215.0f);
    }
}
