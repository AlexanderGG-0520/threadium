package dev.alex.threadium.render.entity;

import dev.alex.threadium.ThreadiumClient;
import java.util.concurrent.atomic.AtomicLong;

/** Phase B observer. It never acquires ownership and therefore can never suppress vanilla rendering. */
public final class PassThroughEntityRenderService {
    private static volatile PassThroughEntityRenderService instance;

    private final AtomicLong observedModelPartRenders = new AtomicLong();
    private final AtomicLong worldGeneration = new AtomicLong();
    private final AtomicLong resourceGeneration = new AtomicLong();
    private volatile boolean enabled = true;

    private PassThroughEntityRenderService() {}

    public static synchronized void initialize() {
        if (instance == null) instance = new PassThroughEntityRenderService();
    }

    public static void beginFrame() {
        // Reserved for coherent frame-boundary publication. Phase B has no pending renderer state.
    }

    public static void observeModelPartRender() {
        PassThroughEntityRenderService current = instance;
        if (current == null || !current.enabled) return;
        if (current.observedModelPartRenders.getAndIncrement() == 0) {
            ThreadiumClient.LOGGER.info(
                    "Threadium detected Minecraft 1.21.1 ModelPart rendering; Vanilla pass-through remains active");
        }
    }

    public static void invalidateWorld() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.worldGeneration.incrementAndGet();
        current.observedModelPartRenders.set(0);
    }

    public static void invalidateResources() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.resourceGeneration.incrementAndGet();
        current.observedModelPartRenders.set(0);
    }

    public static void shutdown() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.enabled = false;
        current.observedModelPartRenders.set(0);
        instance = null;
    }

    public static Diagnostics diagnostics() {
        PassThroughEntityRenderService current = instance;
        return current == null
                ? new Diagnostics(false, 0, 0, 0)
                : new Diagnostics(
                        current.enabled,
                        current.observedModelPartRenders.get(),
                        current.worldGeneration.get(),
                        current.resourceGeneration.get());
    }

    public record Diagnostics(
            boolean enabled, long observedModelPartRenders, long worldGeneration, long resourceGeneration) {}
}
