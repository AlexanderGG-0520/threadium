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
    public static final BenchmarkSceneSpec STATIC = new BenchmarkSceneSpec(
            "Threadium Benchmark", 1, "minecraft:cow", 16, 16, 2.5, -18.75, 65.0, 0.0, "minecraft:overworld", 6000L);

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
