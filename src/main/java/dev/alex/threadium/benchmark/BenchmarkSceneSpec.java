package dev.alex.threadium.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Stable, process-independent description of the dedicated STATIC scene. */
public record BenchmarkSceneSpec(
        String worldIdentifier,
        int version,
        String entityType,
        int gridWidth,
        int gridHeight,
        double spacing,
        double baseX,
        double baseY,
        double baseZ,
        String dimension,
        long fixedTime) {
    public static final int MIN_ENTITY_COUNT = 16;
    public static final int MAX_ENTITY_COUNT = 1024;
    public static final int DEFAULT_ENTITY_COUNT = 256;
    private static final double FOOTPRINT_SIZE = 37.5;
    private static final double FOOTPRINT_MIN_X = -18.75;
    private static final double FOOTPRINT_MIN_Z = 0.0;

    public static final BenchmarkSceneSpec STATIC = forEntityCount(configuredEntityCount());

    public static BenchmarkSceneSpec forEntityCount(int entityCount) {
        validateEntityCount(entityCount);
        int log2 = Integer.numberOfTrailingZeros(entityCount);
        int gridWidth = 1 << ((log2 + 1) / 2);
        int gridHeight = entityCount / gridWidth;
        double spacing = FOOTPRINT_SIZE / (gridWidth - 1);
        double depth = (gridHeight - 1) * spacing;
        double baseZ = FOOTPRINT_MIN_Z + (FOOTPRINT_SIZE - depth) * 0.5;
        return new BenchmarkSceneSpec(
                "Threadium Benchmark",
                2,
                "minecraft:cow",
                gridWidth,
                gridHeight,
                spacing,
                FOOTPRINT_MIN_X,
                65.0,
                baseZ,
                "minecraft:overworld",
                6000L);
    }

    private static int configuredEntityCount() {
        String raw = System.getProperty("threadium.benchmark.entities", Integer.toString(DEFAULT_ENTITY_COUNT));
        try {
            int entityCount = Integer.parseInt(raw);
            validateEntityCount(entityCount);
            return entityCount;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("threadium.benchmark.entities must be an integer: " + raw, invalid);
        }
    }

    private static void validateEntityCount(int entityCount) {
        if (entityCount < MIN_ENTITY_COUNT || entityCount > MAX_ENTITY_COUNT || (entityCount & (entityCount - 1)) != 0)
            throw new IllegalArgumentException(
                    "entityCount must be a power of two from " + MIN_ENTITY_COUNT + " through " + MAX_ENTITY_COUNT);
    }

    public int entityCount() {
        return Math.multiplyExact(gridWidth, gridHeight);
    }

    public List<EntityPlacement> placements() {
        ArrayList<EntityPlacement> result = new ArrayList<>(entityCount());
        for (int row = 0; row < gridHeight; row++)
            for (int column = 0; column < gridWidth; column++)
                result.add(new EntityPlacement(baseX + column * spacing, baseY, baseZ + row * spacing, 180.0f));
        return List.copyOf(result);
    }

    public String descriptor() {
        StringBuilder value = new StringBuilder();
        value.append("threadium-scene\n")
                .append(worldIdentifier)
                .append('\n')
                .append(version)
                .append('\n')
                .append(dimension)
                .append('\n')
                .append(entityType)
                .append('\n')
                .append(gridWidth)
                .append('x')
                .append(gridHeight)
                .append('\n')
                .append(format(spacing))
                .append('\n')
                .append(fixedTime)
                .append('\n');
        for (EntityPlacement placement : placements())
            value.append(format(placement.x))
                    .append(',')
                    .append(format(placement.y))
                    .append(',')
                    .append(format(placement.z))
                    .append(',')
                    .append(format(placement.yaw))
                    .append('\n');
        return value.toString();
    }

    public String hash() {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(descriptor().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public String markerContents() {
        return "world=" + worldIdentifier + "\nversion=" + version + "\nhash=" + hash() + "\nexpectedEntities="
                + entityCount() + "\nauthoritativelyValidated=true\n";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public record EntityPlacement(double x, double y, double z, float yaw) {}
}
