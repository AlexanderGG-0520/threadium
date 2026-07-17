package dev.alex.threadium.benchmark;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.animal.cow.Cow;

/** Deterministic client-side ModelPart animation used by the ANIMATED benchmark scene. */
final class BenchmarkAnimatedCowPose {
    static final String PROFILE = "cow_head_oscillation_v1";
    private static final float BASE_YAW = 180.0f;
    private static final float AMPLITUDE_DEGREES = 35.0f;
    private static final double TICK_PHASE = 0.35;
    private static final double ENTITY_PHASE = 0.173;

    private BenchmarkAnimatedCowPose() {}

    static int apply(Minecraft client, long tick) {
        if (client.level == null) return 0;
        int animated = 0;
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Cow cow)) continue;
            int index = placementIndex(cow);
            if (index < 0) continue;
            cow.yHeadRotO = yaw(tick - 1, index);
            cow.setYHeadRot(yaw(tick, index));
            animated++;
        }
        return animated;
    }

    static void restore(Minecraft client) {
        if (client.level == null) return;
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Cow cow) || placementIndex(cow) < 0) continue;
            cow.yHeadRotO = BASE_YAW;
            cow.setYHeadRot(BASE_YAW);
        }
    }

    static float yaw(long tick, int index) {
        return BASE_YAW + (float) Math.sin(tick * TICK_PHASE + index * ENTITY_PHASE) * AMPLITUDE_DEGREES;
    }

    private static int placementIndex(Cow cow) {
        var placements = BenchmarkSceneSpec.STATIC.placements();
        for (int i = 0; i < placements.size(); i++) {
            var placement = placements.get(i);
            if (Math.abs(cow.getX() - placement.x()) <= 0.01
                    && Math.abs(cow.getY() - placement.y()) <= 0.01
                    && Math.abs(cow.getZ() - placement.z()) <= 0.01) return i;
        }
        return -1;
    }
}
