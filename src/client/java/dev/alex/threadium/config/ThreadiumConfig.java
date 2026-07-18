package dev.alex.threadium.config;

import dev.alex.threadium.ThreadiumClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Small, dependency-free configuration. Unknown keys are preserved on disk. */
public record ThreadiumConfig(
        boolean enabled,
        boolean metricsEnabled,
        boolean debugLogging,
        boolean parallelVisibilityEnabled,
        boolean phasePipelineEnabled,
        int workerCountOverride,
        int visibilityEntityThreshold,
        int phaseQueueCapacity,
        int phaseDeadlineMicros,
        int minTranslucentSubmits,
        int maxConsecutiveFailures,
        boolean retainedTextEnabled,
        int retainedTextMaxCacheEntries,
        long retainedTextMaxGpuBytes,
        int retainedTextEntryIdleSeconds,
        boolean gpuEntityEnabled,
        int gpuMinimumGroupSubmits,
        String gpuBackend,
        int gpuMaxInstances,
        int gpuMaxBonesPerModel,
        int gpuMaxBonesPerFrame,
        int gpuMaxVerticesPerMesh,
        int gpuMaxIndicesPerMesh,
        int gpuMaxCachedMeshes,
        long gpuMaxMeshBytes,
        boolean gpuAllowVanillaFallback,
        boolean gpuBatchConsolidation,
        String gpuDebugVisualMode,
        boolean gpuDebugSuppressVanilla,
        int metricsOutputIntervalSeconds) {
    private static final String FILE_NAME = "threadium.properties";

    public static ThreadiumConfig defaults() {
        return new ThreadiumConfig(
                true,
                true,
                false,
                false,
                true,
                0,
                256,
                32,
                500,
                128,
                3,
                true,
                512,
                64L * 1024L * 1024L,
                60,
                true,
                16,
                "auto",
                8192,
                128,
                131072,
                262144,
                393216,
                512,
                268435456L,
                true,
                true,
                "off",
                false,
                30);
    }

    public ThreadiumConfig forBenchmark(dev.alex.threadium.benchmark.ModelPartBenchmarkMode mode) {
        return new ThreadiumConfig(
                enabled,
                true,
                debugLogging,
                parallelVisibilityEnabled,
                phasePipelineEnabled,
                workerCountOverride,
                visibilityEntityThreshold,
                phaseQueueCapacity,
                phaseDeadlineMicros,
                minTranslucentSubmits,
                maxConsecutiveFailures,
                retainedTextEnabled,
                retainedTextMaxCacheEntries,
                retainedTextMaxGpuBytes,
                retainedTextEntryIdleSeconds,
                mode.replacementEnabled(),
                1,
                gpuBackend,
                gpuMaxInstances,
                gpuMaxBonesPerModel,
                gpuMaxBonesPerFrame,
                gpuMaxVerticesPerMesh,
                gpuMaxIndicesPerMesh,
                gpuMaxCachedMeshes,
                gpuMaxMeshBytes,
                gpuAllowVanillaFallback,
                mode.consolidationEnabled(),
                "normal",
                true,
                metricsOutputIntervalSeconds);
    }

    public static boolean save(ThreadiumConfig config) {
        try {
            validateEditableValues(config);
        } catch (IllegalArgumentException exception) {
            ThreadiumClient.LOGGER.warn("Refusing to save invalid Threadium configuration", exception);
            return false;
        }
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        return writeConfig(path, config);
    }

    private static void validateEditableValues(ThreadiumConfig config) {
        if (config.workerCountOverride < 0 || config.workerCountOverride > 4)
            throw new IllegalArgumentException("workers.override must be between 0 and 4");
        if (config.gpuMinimumGroupSubmits < 1 || config.gpuMinimumGroupSubmits > 65_536)
            throw new IllegalArgumentException("entity.gpu.minimumGroupSubmits must be between 1 and 65536");
        if (config.metricsOutputIntervalSeconds < 5 || config.metricsOutputIntervalSeconds > 3_600)
            throw new IllegalArgumentException("metrics.output.interval.seconds must be between 5 and 3600");
    }

    public static ThreadiumConfig load() {
        ThreadiumConfig defaults = defaults();
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException | IllegalArgumentException exception) {
                ThreadiumClient.LOGGER.error("Could not read {}; using safe defaults", path, exception);
                return defaults;
            }
        }

        try {
            ThreadiumConfig config = new ThreadiumConfig(
                    booleanValue(properties, "enabled", defaults.enabled),
                    booleanValue(properties, "metrics.enabled", defaults.metricsEnabled),
                    booleanValue(properties, "debug.logging", defaults.debugLogging),
                    booleanValue(properties, "parallel.visibility.enabled", defaults.parallelVisibilityEnabled),
                    booleanValue(properties, "phase.pipeline.enabled", defaults.phasePipelineEnabled),
                    boundedInt(properties, "workers.override", defaults.workerCountOverride, 0, 4),
                    boundedInt(
                            properties, "visibility.entity.threshold", defaults.visibilityEntityThreshold, 1, 100_000),
                    boundedInt(properties, "phase.queue.capacity", defaults.phaseQueueCapacity, 1, 1_024),
                    boundedInt(properties, "phase.deadline.micros", defaults.phaseDeadlineMicros, 0, 10_000),
                    boundedInt(
                            properties,
                            "phase.translucent.minimum.submits",
                            defaults.minTranslucentSubmits,
                            2,
                            1_000_000),
                    boundedInt(properties, "phase.failures.maximum", defaults.maxConsecutiveFailures, 1, 100),
                    booleanValue(properties, "display.text.retained.enabled", defaults.retainedTextEnabled),
                    boundedInt(
                            properties,
                            "display.text.retained.maxCacheEntries",
                            defaults.retainedTextMaxCacheEntries,
                            1,
                            16_384),
                    boundedLong(
                            properties,
                            "display.text.retained.maxGpuBytes",
                            defaults.retainedTextMaxGpuBytes,
                            0L,
                            1L << 32),
                    boundedInt(
                            properties,
                            "display.text.retained.entryIdleSeconds",
                            defaults.retainedTextEntryIdleSeconds,
                            1,
                            86_400),
                    booleanValue(properties, "entity.gpu.enabled", defaults.gpuEntityEnabled),
                    boundedInt(properties, "entity.gpu.minimumGroupSubmits", defaults.gpuMinimumGroupSubmits, 1, 65536),
                    stringValue(
                            properties,
                            "entity.gpu.backend",
                            defaults.gpuBackend,
                            "auto",
                            "opengl45",
                            "opengl33",
                            "disabled"),
                    boundedInt(properties, "entity.gpu.maxInstances", defaults.gpuMaxInstances, 1, 65536),
                    boundedInt(properties, "entity.gpu.maxBonesPerModel", defaults.gpuMaxBonesPerModel, 1, 1024),
                    boundedInt(properties, "entity.gpu.maxBonesPerFrame", defaults.gpuMaxBonesPerFrame, 1, 1048576),
                    boundedInt(properties, "entity.gpu.maxVerticesPerMesh", defaults.gpuMaxVerticesPerMesh, 4, 4194304),
                    boundedInt(properties, "entity.gpu.maxIndicesPerMesh", defaults.gpuMaxIndicesPerMesh, 6, 6291456),
                    boundedInt(properties, "entity.gpu.maxCachedMeshes", defaults.gpuMaxCachedMeshes, 1, 8192),
                    boundedLong(properties, "entity.gpu.maxMeshBytes", defaults.gpuMaxMeshBytes, 1048576L, 1L << 34),
                    booleanValue(properties, "entity.gpu.allowVanillaFallback", defaults.gpuAllowVanillaFallback),
                    booleanValue(properties, "entity.gpu.batchConsolidation", defaults.gpuBatchConsolidation),
                    stringValue(
                            properties,
                            "entity.gpu.debugVisualMode",
                            defaults.gpuDebugVisualMode,
                            "off",
                            "screen_triangle",
                            "screen_triangle_main_target",
                            "mesh_clip_space",
                            "mesh_magenta",
                            "mesh_no_depth",
                            "mesh_no_cull",
                            "mesh_identity_bone",
                            "mesh_identity_root",
                            "mesh_projection_only",
                            "normal"),
                    booleanValue(properties, "entity.gpu.debugSuppressVanilla", defaults.gpuDebugSuppressVanilla),
                    boundedInt(
                            properties,
                            "metrics.output.interval.seconds",
                            defaults.metricsOutputIntervalSeconds,
                            5,
                            3_600));
            if (!Files.exists(path)) {
                writeConfig(path, config);
            }
            return config;
        } catch (IllegalArgumentException exception) {
            ThreadiumClient.LOGGER.error("Invalid {}; using safe defaults", path, exception);
            return defaults;
        }
    }

    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null
                ? fallback
                : switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new IllegalArgumentException("Expected true or false for " + key);
                };
    }

    private static int boundedInt(Properties properties, String key, int fallback, int minimum, int maximum) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value.trim());
        if (parsed < minimum || parsed > maximum)
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        return parsed;
    }

    private static long boundedLong(Properties properties, String key, long fallback, long minimum, long maximum) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        long parsed = Long.parseLong(value.trim());
        if (parsed < minimum || parsed > maximum)
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        return parsed;
    }

    private static String stringValue(Properties properties, String key, String fallback, String... allowed) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw new IllegalArgumentException("Unsupported value for " + key);
    }

    private static boolean writeConfig(Path path, ThreadiumConfig config) {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException | IllegalArgumentException exception) {
                ThreadiumClient.LOGGER.warn(
                        "Could not preserve unknown configuration keys from {}; rewriting known values",
                        path,
                        exception);
                properties.clear();
            }
        }
        properties.setProperty("enabled", Boolean.toString(config.enabled));
        properties.setProperty("metrics.enabled", Boolean.toString(config.metricsEnabled));
        properties.setProperty("debug.logging", Boolean.toString(config.debugLogging));
        properties.setProperty("parallel.visibility.enabled", Boolean.toString(config.parallelVisibilityEnabled));
        properties.setProperty("phase.pipeline.enabled", Boolean.toString(config.phasePipelineEnabled));
        properties.setProperty("workers.override", Integer.toString(config.workerCountOverride));
        properties.setProperty("visibility.entity.threshold", Integer.toString(config.visibilityEntityThreshold));
        properties.setProperty("phase.queue.capacity", Integer.toString(config.phaseQueueCapacity));
        properties.setProperty("phase.deadline.micros", Integer.toString(config.phaseDeadlineMicros));
        properties.setProperty("phase.translucent.minimum.submits", Integer.toString(config.minTranslucentSubmits));
        properties.setProperty("phase.failures.maximum", Integer.toString(config.maxConsecutiveFailures));
        properties.setProperty("display.text.retained.enabled", Boolean.toString(config.retainedTextEnabled));
        properties.setProperty(
                "display.text.retained.maxCacheEntries", Integer.toString(config.retainedTextMaxCacheEntries));
        properties.setProperty("display.text.retained.maxGpuBytes", Long.toString(config.retainedTextMaxGpuBytes));
        properties.setProperty(
                "display.text.retained.entryIdleSeconds", Integer.toString(config.retainedTextEntryIdleSeconds));
        properties.setProperty("entity.gpu.enabled", Boolean.toString(config.gpuEntityEnabled));
        properties.setProperty("entity.gpu.minimumGroupSubmits", Integer.toString(config.gpuMinimumGroupSubmits));
        properties.setProperty("entity.gpu.backend", config.gpuBackend);
        properties.setProperty("entity.gpu.maxInstances", Integer.toString(config.gpuMaxInstances));
        properties.setProperty("entity.gpu.maxBonesPerModel", Integer.toString(config.gpuMaxBonesPerModel));
        properties.setProperty("entity.gpu.maxBonesPerFrame", Integer.toString(config.gpuMaxBonesPerFrame));
        properties.setProperty("entity.gpu.maxVerticesPerMesh", Integer.toString(config.gpuMaxVerticesPerMesh));
        properties.setProperty("entity.gpu.maxIndicesPerMesh", Integer.toString(config.gpuMaxIndicesPerMesh));
        properties.setProperty("entity.gpu.maxCachedMeshes", Integer.toString(config.gpuMaxCachedMeshes));
        properties.setProperty("entity.gpu.maxMeshBytes", Long.toString(config.gpuMaxMeshBytes));
        properties.setProperty("entity.gpu.allowVanillaFallback", Boolean.toString(config.gpuAllowVanillaFallback));
        properties.setProperty("entity.gpu.batchConsolidation", Boolean.toString(config.gpuBatchConsolidation));
        properties.setProperty("entity.gpu.debugVisualMode", config.gpuDebugVisualMode);
        properties.setProperty("entity.gpu.debugSuppressVanilla", Boolean.toString(config.gpuDebugSuppressVanilla));
        properties.setProperty(
                "metrics.output.interval.seconds", Integer.toString(config.metricsOutputIntervalSeconds));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), FILE_NAME, ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, " Threadium Phase 0 configuration");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            ThreadiumClient.LOGGER.warn("Could not write configuration {}", path, exception);
            return false;
        }
    }
}
