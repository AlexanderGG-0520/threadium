package dev.alex.threadium.render.entity;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Phase B observer. It never acquires ownership and therefore can never suppress vanilla rendering. */
public final class PassThroughEntityRenderService {
    private static volatile PassThroughEntityRenderService instance;

    private final LongAdder observedLivingEntityRenders = new LongAdder();
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

    public static void observeLivingEntityRender() {
        PassThroughEntityRenderService current = instance;
        if (current == null || !current.enabled) return;
        current.observedLivingEntityRenders.increment();
    }

    public static void invalidateWorld() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.worldGeneration.incrementAndGet();
        current.observedLivingEntityRenders.reset();
    }

    public static void invalidateResources() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.resourceGeneration.incrementAndGet();
        current.observedLivingEntityRenders.reset();
    }
}
