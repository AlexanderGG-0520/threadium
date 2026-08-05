package dev.alex.threadium.config;

import dev.alex.threadium.ThreadiumClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Dependency-free runtime configuration using the same entity-GPU property names as the 26.2 line. */
public record ThreadiumConfig(
        boolean enabled,
        boolean metricsEnabled,
        boolean debugLogging,
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
        int metricsOutputIntervalSeconds) {
    private static final String FILE_NAME = "threadium.properties";

    public ThreadiumConfig {
        gpuBackend = normalizeBackend(gpuBackend);
        validateValues(
                gpuMinimumGroupSubmits,
                gpuMaxInstances,
                gpuMaxBonesPerModel,
                gpuMaxBonesPerFrame,
                gpuMaxVerticesPerMesh,
                gpuMaxIndicesPerMesh,
                gpuMaxCachedMeshes,
                gpuMaxMeshBytes,
                metricsOutputIntervalSeconds);
    }

    /** The backport remains opt-in until parity implementation and real-machine validation are complete. */
    public static ThreadiumConfig defaults() {
        return new ThreadiumConfig(
                false,
                true,
                false,
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
                30);
    }

    public static ThreadiumConfig load() {
        return load(configPath());
    }

    static ThreadiumConfig load(Path path) {
        ThreadiumConfig defaults = defaults();
        Properties properties = read(path);
        try {
            return new ThreadiumConfig(
                    bool(properties, "enabled", defaults.enabled),
                    bool(properties, "metrics.enabled", defaults.metricsEnabled),
                    bool(properties, "debug.logging", defaults.debugLogging),
                    bool(properties, "entity.gpu.enabled", defaults.gpuEntityEnabled),
                    integer(properties, "entity.gpu.minimumGroupSubmits", defaults.gpuMinimumGroupSubmits, 1, 65536),
                    choice(properties, "entity.gpu.backend", defaults.gpuBackend, "auto", "opengl33", "disabled"),
                    integer(properties, "entity.gpu.maxInstances", defaults.gpuMaxInstances, 1, 65536),
                    integer(properties, "entity.gpu.maxBonesPerModel", defaults.gpuMaxBonesPerModel, 1, 1024),
                    integer(properties, "entity.gpu.maxBonesPerFrame", defaults.gpuMaxBonesPerFrame, 1, 1048576),
                    integer(properties, "entity.gpu.maxVerticesPerMesh", defaults.gpuMaxVerticesPerMesh, 4, 4194304),
                    integer(properties, "entity.gpu.maxIndicesPerMesh", defaults.gpuMaxIndicesPerMesh, 6, 6291456),
                    integer(properties, "entity.gpu.maxCachedMeshes", defaults.gpuMaxCachedMeshes, 1, 8192),
                    longValue(properties, "entity.gpu.maxMeshBytes", defaults.gpuMaxMeshBytes, 1048576L, 1L << 34),
                    bool(properties, "entity.gpu.allowVanillaFallback", defaults.gpuAllowVanillaFallback),
                    bool(properties, "entity.gpu.batchConsolidation", defaults.gpuBatchConsolidation),
                    integer(
                            properties,
                            "metrics.output.interval.seconds",
                            defaults.metricsOutputIntervalSeconds,
                            5,
                            3600));
        } catch (IllegalArgumentException exception) {
            ThreadiumClient.LOGGER.error("Invalid Threadium configuration at {}; using safe defaults", path, exception);
            return defaults;
        }
    }

    public static boolean save(ThreadiumConfig config) {
        return save(configPath(), config);
    }

    static boolean save(Path path, ThreadiumConfig config) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(config, "config");
        validate(config);
        Properties properties = read(path);
        set(properties, "enabled", config.enabled);
        set(properties, "metrics.enabled", config.metricsEnabled);
        set(properties, "debug.logging", config.debugLogging);
        set(properties, "entity.gpu.enabled", config.gpuEntityEnabled);
        set(properties, "entity.gpu.minimumGroupSubmits", config.gpuMinimumGroupSubmits);
        set(properties, "entity.gpu.backend", config.gpuBackend);
        set(properties, "entity.gpu.maxInstances", config.gpuMaxInstances);
        set(properties, "entity.gpu.maxBonesPerModel", config.gpuMaxBonesPerModel);
        set(properties, "entity.gpu.maxBonesPerFrame", config.gpuMaxBonesPerFrame);
        set(properties, "entity.gpu.maxVerticesPerMesh", config.gpuMaxVerticesPerMesh);
        set(properties, "entity.gpu.maxIndicesPerMesh", config.gpuMaxIndicesPerMesh);
        set(properties, "entity.gpu.maxCachedMeshes", config.gpuMaxCachedMeshes);
        set(properties, "entity.gpu.maxMeshBytes", config.gpuMaxMeshBytes);
        set(properties, "entity.gpu.allowVanillaFallback", config.gpuAllowVanillaFallback);
        set(properties, "entity.gpu.batchConsolidation", config.gpuBatchConsolidation);
        set(properties, "metrics.output.interval.seconds", config.metricsOutputIntervalSeconds);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Threadium runtime configuration");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            ThreadiumClient.LOGGER.error("Could not save Threadium configuration to {}", path, exception);
            return false;
        }
    }

    public boolean replacementEnabled() {
        return enabled || Boolean.getBoolean("threadium.backport1211.replacement");
    }

    public boolean gpuReplacementEnabled() {
        if (!replacementEnabled() || !gpuEntityEnabled || "disabled".equals(gpuBackend)) return false;
        return Boolean.getBoolean("threadium.backport1211.gpu") || "auto".equals(gpuBackend) || "opengl33".equals(gpuBackend);
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static Properties read(Path path) {
        Properties properties = new Properties();
        if (!Files.exists(path)) return properties;
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException exception) {
            ThreadiumClient.LOGGER.warn("Could not read existing Threadium configuration at {}", path, exception);
        }
        return properties;
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int integer(Properties properties, String key, int fallback, int minimum, int maximum) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value.trim());
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(key + " is out of range");
        return parsed;
    }

    private static long longValue(Properties properties, String key, long fallback, long minimum, long maximum) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        long parsed = Long.parseLong(value.trim());
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(key + " is out of range");
        return parsed;
    }

    private static String choice(Properties properties, String key, String fallback, String... allowed) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return normalized;
        throw new IllegalArgumentException(key + " has an unsupported value");
    }

    private static String normalizeBackend(String backend) {
        if (backend == null) throw new IllegalArgumentException("entity.gpu.backend cannot be null");
        String normalized = backend.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("auto") && !normalized.equals("opengl33") && !normalized.equals("disabled")) {
            throw new IllegalArgumentException("entity.gpu.backend has an unsupported value");
        }
        return normalized;
    }

    private static void validate(ThreadiumConfig config) {
        validateValues(
                config.gpuMinimumGroupSubmits,
                config.gpuMaxInstances,
                config.gpuMaxBonesPerModel,
                config.gpuMaxBonesPerFrame,
                config.gpuMaxVerticesPerMesh,
                config.gpuMaxIndicesPerMesh,
                config.gpuMaxCachedMeshes,
                config.gpuMaxMeshBytes,
                config.metricsOutputIntervalSeconds);
    }

    private static void validateValues(
            int minimumGroupSubmits,
            int maxInstances,
            int maxBonesPerModel,
            int maxBonesPerFrame,
            int maxVerticesPerMesh,
            int maxIndicesPerMesh,
            int maxCachedMeshes,
            long maxMeshBytes,
            int metricsIntervalSeconds) {
        if (minimumGroupSubmits < 1 || minimumGroupSubmits > 65536
                || maxInstances < 1 || maxInstances > 65536
                || maxBonesPerModel < 1 || maxBonesPerModel > 1024
                || maxBonesPerFrame < 1 || maxBonesPerFrame > 1048576
                || maxVerticesPerMesh < 4 || maxVerticesPerMesh > 4194304
                || maxIndicesPerMesh < 6 || maxIndicesPerMesh > 6291456
                || maxCachedMeshes < 1 || maxCachedMeshes > 8192
                || maxMeshBytes < 1048576L || maxMeshBytes > (1L << 34)
                || metricsIntervalSeconds < 5 || metricsIntervalSeconds > 3600) {
            throw new IllegalArgumentException("Threadium configuration values are outside their supported ranges");
        }
    }

    private static void set(Properties properties, String key, Object value) {
        properties.setProperty(key, String.valueOf(value));
    }
}
