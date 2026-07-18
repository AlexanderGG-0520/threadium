package dev.alex.threadium.lifecycle;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import dev.alex.threadium.metrics.ThreadiumMetrics;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import dev.alex.threadium.render.phase.ThreadiumPhasePipeline;
import dev.alex.threadium.render.text.RetainedTextManager;
import dev.alex.threadium.scheduler.ThreadiumScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

/** Owns lifecycle transitions; no world, renderer, entity, or resource object is retained. */
public final class ThreadiumLifecycle {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final GenerationCounter WORLD_GENERATION = new GenerationCounter();
    private static final GenerationCounter RESOURCE_GENERATION = new GenerationCounter();
    private static ThreadiumScheduler scheduler;
    private static ThreadiumMetrics metrics;

    private ThreadiumLifecycle() {}

    public static void initialize(ThreadiumConfig config, ThreadiumMetrics newMetrics) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            ThreadiumClient.LOGGER.warn("Ignoring repeated Threadium initialization");
            return;
        }
        metrics = newMetrics;
        scheduler = new ThreadiumScheduler(config.workerCountOverride(), metrics);
        ThreadiumPhasePipeline.initialize(config);
        RetainedTextManager.initialize(config);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> applyWorldBoundary("world join"));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> applyWorldBoundary("world disconnect"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown(client));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            dev.alex.threadium.benchmark.ThreadiumBenchmark.tick(client);
            dev.alex.threadium.benchmark.PipelineDifferentialRunner.tick();
            if (metrics != null && scheduler != null)
                metrics.reportIfDue(scheduler, worldGeneration(), resourceGeneration());
        });
        ThreadiumClient.LOGGER.info("Benchmark tick callback registered: true");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ReloadGenerationListener());
    }

    public static long worldGeneration() {
        return WORLD_GENERATION.current();
    }

    public static long resourceGeneration() {
        return RESOURCE_GENERATION.current();
    }

    /** Called by the config screen only after the atomic file replacement succeeded. */
    public static void applyPublishedConfiguration() {
        ThreadiumRuntimeConfig.Snapshot snapshot = ThreadiumRuntimeConfig.effective();
        if (metrics != null) metrics.applyRuntimeConfig(snapshot);
    }

    /** GameRenderer HEAD is after all commands from the preceding frame have been submitted. */
    public static void beginRenderFrame() {
        ThreadiumRuntimeConfig.Transition transition = ThreadiumRuntimeConfig.applyFrameBoundary(true);
        if (transition.applied()) {
            ThreadiumRuntimeConfig.Snapshot snapshot = transition.after();
            if (metrics != null) metrics.applyRuntimeConfig(snapshot);
            ThreadiumPhasePipeline.applyRuntimeConfig(snapshot);
            RetainedTextManager.applyRuntimeConfig(snapshot);
        }
        // Also consumes immediate GPU threshold/consolidation changes published since the last frame.
        if (!dev.alex.threadium.benchmark.ThreadiumBenchmark.active()) ModelPartRenderService.applyRuntimeConfig();
    }

    private static void applyWorldBoundary(String reason) {
        ThreadiumRuntimeConfig.Transition transition = ThreadiumRuntimeConfig.applyWorldBoundary();
        ThreadiumConfig persisted = ThreadiumRuntimeConfig.persisted();
        if (transition.changed()) {
            ThreadiumRuntimeConfig.Snapshot snapshot = transition.after();
            if (metrics != null) metrics.applyRuntimeConfig(snapshot);
            ThreadiumPhasePipeline.applyRuntimeConfig(snapshot);
            RetainedTextManager.applyRuntimeConfig(snapshot);
            if (!dev.alex.threadium.benchmark.ThreadiumBenchmark.active()) ModelPartRenderService.applyRuntimeConfig();
        }
        if (scheduler != null) scheduler.shutdown();
        scheduler = new ThreadiumScheduler(persisted.workerCountOverride(), metrics);
        ThreadiumPhasePipeline.applyWorldConfiguration(persisted);
        advanceWorldGeneration(reason);
    }

    private static void advanceWorldGeneration(String reason) {
        long generation = WORLD_GENERATION.advance();
        metrics.resetWorldScoped();
        ThreadiumPhasePipeline.invalidate(reason);
        RetainedTextManager.invalidate(reason);
        if (ModelPartRenderService.get() != null) ModelPartRenderService.get().invalidate();
        dev.alex.threadium.benchmark.PipelineDifferentialRunner.invalidate(reason);
        ThreadiumClient.LOGGER.info("Threadium {}: worldGeneration={}", reason, generation);
    }

    private static synchronized void shutdown(net.minecraft.client.Minecraft client) {
        if (!INITIALIZED.compareAndSet(true, false)) return;
        dev.alex.threadium.benchmark.ThreadiumBenchmark.shutdown(client);
        long worldGeneration = WORLD_GENERATION.advance();
        long resourceGeneration = RESOURCE_GENERATION.advance();
        ThreadiumClient.LOGGER.info(
                "Threadium client shutdown: worldGeneration={}, resourceGeneration={}",
                worldGeneration,
                resourceGeneration);
        ThreadiumPhasePipeline.shutdown();
        RetainedTextManager.invalidate("client shutdown");
        if (ModelPartRenderService.get() != null) ModelPartRenderService.get().close();
        if (scheduler != null) scheduler.shutdown();
        scheduler = null;
        ThreadiumMetrics.deactivate(metrics);
        dev.alex.threadium.metrics.StagedVertexMetrics.configure(false);
        metrics = null;
        ThreadiumClient.LOGGER.info("Threadium scheduler shutdown completed; metrics reporter deactivated");
    }

    private static final class ReloadGenerationListener implements SimpleSynchronousResourceReloadListener {
        @Override
        public Identifier getFabricId() {
            return Identifier.fromNamespaceAndPath(ThreadiumClient.MOD_ID, "resource-generation");
        }

        @Override
        public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
            long generation = RESOURCE_GENERATION.advance();
            ThreadiumPhasePipeline.invalidate("resource reload");
            RetainedTextManager.invalidate("resource reload");
            if (ModelPartRenderService.get() != null)
                ModelPartRenderService.get().invalidate();
            dev.alex.threadium.benchmark.ThreadiumBenchmark.resourceReloaded();
            dev.alex.threadium.benchmark.PipelineDifferentialRunner.invalidate("resource reload");
            ThreadiumClient.LOGGER.info("Threadium resource reload completed: resourceGeneration={}", generation);
        }
    }
}
