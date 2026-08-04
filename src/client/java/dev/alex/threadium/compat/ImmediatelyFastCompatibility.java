package dev.alex.threadium.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.alex.threadium.ThreadiumClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Version-bounded compatibility policy for ImmediatelyFast's Minecraft 26.2 enhanced batching implementation.
 *
 * <p>ImmediatelyFast 1.16.2 modifies feature-group reorderability and scissor equality, but keeps the ModelPart
 * consumer boundary on the vanilla {@link BufferBuilder}. Threadium therefore never reflectively unwraps consumers: the
 * verified vanilla builder is accepted directly and every unknown wrapper falls back to vanilla rendering.
 */
public final class ImmediatelyFastCompatibility {
    private static final String MOD_ID = "immediatelyfast";
    private static final String VERIFIED_VERSION_PREFIX = "1.16.2+26.2";
    private static final RuntimeState RUNTIME = detectRuntime();
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean();

    private ImmediatelyFastCompatibility() {}

    public static boolean accepts(VertexConsumer consumer) {
        return consumer instanceof BufferBuilder;
    }

    public static String fallbackReason(VertexConsumer consumer) {
        String consumerName = consumer == null ? "null" : consumer.getClass().getName();
        if (!RUNTIME.loaded()) return "unsupported wrapped vertex consumer: " + consumerName;
        if (!RUNTIME.verified())
            return "unsupported wrapped vertex consumer with unverified ImmediatelyFast " + RUNTIME.version() + ": "
                    + consumerName;
        return "unsupported wrapped vertex consumer with ImmediatelyFast enhanced batching: " + consumerName;
    }

    public static void logStatus() {
        if (!RUNTIME.loaded() || !STATUS_LOGGED.compareAndSet(false, true)) return;
        if (RUNTIME.verified())
            ThreadiumClient.LOGGER.info(
                    "ImmediatelyFast {} compatibility: verified enhanced batching; BufferBuilder boundary retained, unknown wrappers use vanilla fallback",
                    RUNTIME.version());
        else
            ThreadiumClient.LOGGER.warn(
                    "ImmediatelyFast {} is outside Threadium's verified 26.2 range; only vanilla BufferBuilder consumers are accepted",
                    RUNTIME.version());
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
