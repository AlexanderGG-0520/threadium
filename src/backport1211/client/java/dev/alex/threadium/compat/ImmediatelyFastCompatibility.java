package dev.alex.threadium.compat;

import dev.alex.threadium.ThreadiumClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Version-bounded observation policy for ImmediatelyFast's Minecraft 1.21.1 batching implementation.
 *
 * <p>Threadium's compatibility mixin observes the exact consumer returned by ImmediatelyFast without wrapping,
 * replacing, or mutating it. Observation is enabled only for the audited 1.6.11 Minecraft 1.21.1 line. Unknown
 * versions remain untouched and use Threadium's normal fail-closed material resolution and vanilla fallback paths.
 */
public final class ImmediatelyFastCompatibility {
    private static final String MOD_ID = "immediatelyfast";
    private static final String VERIFIED_VERSION_PREFIX = "1.6.11+1.21.1";
    private static final RuntimeState RUNTIME = detectRuntime();
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean();

    private ImmediatelyFastCompatibility() {}

    public static boolean observationSupported() {
        return RUNTIME.loaded() && RUNTIME.verified();
    }

    public static void logStatus() {
        if (!RUNTIME.loaded() || !STATUS_LOGGED.compareAndSet(false, true)) return;
        if (RUNTIME.verified()) {
            ThreadiumClient.LOGGER.info(
                    "ImmediatelyFast {} compatibility: verified Minecraft 1.21.1 batching observation enabled; returned consumers remain unmodified",
                    RUNTIME.version());
        } else {
            ThreadiumClient.LOGGER.warn(
                    "ImmediatelyFast {} is outside Threadium's verified Minecraft 1.21.1 range; compatibility observation is disabled and uncertain paths use vanilla fallback",
                    RUNTIME.version());
        }
    }

    static boolean isVerifiedVersion(String version) {
        return version != null && version.startsWith(VERIFIED_VERSION_PREFIX);
    }

    static RuntimeState runtimeStateForTest(boolean loaded, String version) {
        return new RuntimeState(loaded, version, loaded && isVerifiedVersion(version));
    }

    private static RuntimeState detectRuntime() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded(MOD_ID)) return new RuntimeState(false, "absent", false);
        Optional<String> version = loader.getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
        String friendly = version.orElse("unknown");
        return new RuntimeState(true, friendly, isVerifiedVersion(friendly));
    }

    record RuntimeState(boolean loaded, String version, boolean verified) {}
}
