package dev.alex.threadium.benchmark;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.cow.Cow;

final class BenchmarkPopulationValidator {
    private static final double POSITION_TOLERANCE = .01, ROTATION_TOLERANCE = .1;

    private BenchmarkPopulationValidator() {}

    static boolean isActiveBenchmarkEntity(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        return entity != null
                && entity.level() == level
                && !entity.isRemoved()
                && entity.entityTags().contains("threadium_benchmark");
    }

    static BenchmarkPopulationSnapshot.Server server(ServerLevel level) {
        boolean[] matched = new boolean[BenchmarkSceneSpec.STATIC.entityCount()];
        int owned = 0, cows = 0, placed = 0, unexpected = 0, duplicates = 0;
        for (var entity : level.getAllEntities()) {
            if (!isActiveBenchmarkEntity(level, entity)) continue;
            owned++;
            if (!(entity instanceof Cow cow)) {
                unexpected++;
                continue;
            }
            cows++;
            int index = matchingPlacement(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
            if (index < 0
                    || !cow.isNoAi()
                    || !cow.isInvulnerable()
                    || !cow.isSilent()
                    || !cow.isPersistenceRequired()
                    || !cow.isNoGravity()
                    || cow.getDeltaMovement().lengthSqr() > 1e-8) {
                unexpected++;
                continue;
            }
            if (matched[index]) {
                duplicates++;
                continue;
            }
            matched[index] = true;
            placed++;
        }
        return new BenchmarkPopulationSnapshot.Server(
                owned, cows, placed, unexpected, BenchmarkSceneSpec.STATIC.entityCount() - placed, duplicates);
    }

    static BenchmarkPopulationSnapshot.Client client(Minecraft client) {
        boolean[] matched = new boolean[BenchmarkSceneSpec.STATIC.entityCount()];
        int tracked = 0, placed = 0, unexpected = 0;
        for (var entity : client.level.entitiesForRendering()) {
            if (!insideBounds(entity.getX(), entity.getY(), entity.getZ())) continue;
            if (!(entity instanceof Cow)) {
                if (entity != client.player) unexpected++;
                continue;
            }
            tracked++;
            int index = matchingPlacement(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
            if (index >= 0 && !matched[index]) {
                matched[index] = true;
                placed++;
            }
        }
        return new BenchmarkPopulationSnapshot.Client(tracked, placed, unexpected);
    }

    private static int matchingPlacement(double x, double y, double z, float yaw) {
        var placements = BenchmarkSceneSpec.STATIC.placements();
        for (int i = 0; i < placements.size(); i++) {
            var expected = placements.get(i);
            if (close(x, expected.x())
                    && close(y, expected.y())
                    && close(z, expected.z())
                    && Math.abs(Mth.wrapDegrees(yaw - expected.yaw())) <= ROTATION_TOLERANCE) return i;
        }
        return -1;
    }

    private static boolean insideBounds(double x, double y, double z) {
        var scene = BenchmarkSceneSpec.STATIC;
        double maxX = scene.baseX() + (scene.gridWidth() - 1) * scene.spacing(),
                maxZ = scene.baseZ() + (scene.gridHeight() - 1) * scene.spacing();
        return x >= scene.baseX() - 1
                && x <= maxX + 1
                && y >= scene.baseY() - 1
                && y <= scene.baseY() + 3
                && z >= scene.baseZ() - 1
                && z <= maxZ + 1;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= POSITION_TOLERANCE;
    }
}
