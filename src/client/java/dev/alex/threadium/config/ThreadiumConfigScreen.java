package dev.alex.threadium.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small dependency-free configuration screen exposed through Mod Menu. */
public final class ThreadiumConfigScreen extends Screen {
    private static final int[] METRICS_INTERVALS = {5, 15, 30, 60, 120, 300};
    private static final int[] GPU_GROUP_MINIMUMS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

    private final Screen parent;
    private final ThreadiumConfig original;

    private boolean enabled;
    private boolean metricsEnabled;
    private boolean debugLogging;
    private boolean parallelVisibilityEnabled;
    private boolean phasePipelineEnabled;
    private boolean retainedTextEnabled;
    private boolean gpuEntityEnabled;
    private boolean gpuAllowVanillaFallback;
    private boolean gpuBatchConsolidation;
    private int gpuMinimumGroupSubmits;
    private int workerCountOverride;
    private int metricsOutputIntervalSeconds;
    private Component saveError;

    public ThreadiumConfigScreen(Screen parent) {
        super(Component.literal("Threadium Configuration"));
        this.parent = parent;
        this.original = ThreadiumConfig.load();
        this.enabled = original.enabled();
        this.metricsEnabled = original.metricsEnabled();
        this.debugLogging = original.debugLogging();
        this.parallelVisibilityEnabled = original.parallelVisibilityEnabled();
        this.phasePipelineEnabled = original.phasePipelineEnabled();
        this.retainedTextEnabled = original.retainedTextEnabled();
        this.gpuEntityEnabled = original.gpuEntityEnabled();
        this.gpuAllowVanillaFallback = original.gpuAllowVanillaFallback();
        this.gpuBatchConsolidation = original.gpuBatchConsolidation();
        this.gpuMinimumGroupSubmits = original.gpuMinimumGroupSubmits();
        this.workerCountOverride = original.workerCountOverride();
        this.metricsOutputIntervalSeconds = original.metricsOutputIntervalSeconds();
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(150, (this.width - 24) / 2);
        int left = this.width / 2 - buttonWidth - 2;
        int right = this.width / 2 + 2;
        int y = 42;

        this.addRenderableWidget(Button.builder(toggle("Threadium", enabled), button -> {
                    enabled = !enabled;
                    button.setMessage(toggle("Threadium", enabled));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(toggle("Metrics", metricsEnabled), button -> {
                    metricsEnabled = !metricsEnabled;
                    button.setMessage(toggle("Metrics", metricsEnabled));
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        y += 24;
        this.addRenderableWidget(Button.builder(toggle("Debug logging", debugLogging), button -> {
                    debugLogging = !debugLogging;
                    button.setMessage(toggle("Debug logging", debugLogging));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(intervalLabel(), button -> {
                    metricsOutputIntervalSeconds = nextInterval(metricsOutputIntervalSeconds);
                    button.setMessage(intervalLabel());
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        y += 24;
        this.addRenderableWidget(Button.builder(toggle("Parallel visibility", parallelVisibilityEnabled), button -> {
                    parallelVisibilityEnabled = !parallelVisibilityEnabled;
                    button.setMessage(toggle("Parallel visibility", parallelVisibilityEnabled));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(workerLabel(), button -> {
                    workerCountOverride = (workerCountOverride + 1) % 5;
                    button.setMessage(workerLabel());
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        y += 24;
        this.addRenderableWidget(Button.builder(toggle("Phase pipeline", phasePipelineEnabled), button -> {
                    phasePipelineEnabled = !phasePipelineEnabled;
                    button.setMessage(toggle("Phase pipeline", phasePipelineEnabled));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(toggle("Retained text", retainedTextEnabled), button -> {
                    retainedTextEnabled = !retainedTextEnabled;
                    button.setMessage(toggle("Retained text", retainedTextEnabled));
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        y += 24;
        this.addRenderableWidget(Button.builder(toggle("GPU entities", gpuEntityEnabled), button -> {
                    gpuEntityEnabled = !gpuEntityEnabled;
                    button.setMessage(toggle("GPU entities", gpuEntityEnabled));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(toggle("Batch consolidation", gpuBatchConsolidation), button -> {
                    gpuBatchConsolidation = !gpuBatchConsolidation;
                    button.setMessage(toggle("Batch consolidation", gpuBatchConsolidation));
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        y += 24;
        this.addRenderableWidget(Button.builder(toggle("Vanilla fallback", gpuAllowVanillaFallback), button -> {
                    gpuAllowVanillaFallback = !gpuAllowVanillaFallback;
                    button.setMessage(toggle("Vanilla fallback", gpuAllowVanillaFallback));
                })
                .pos(left, y)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(gpuGroupMinimumLabel(), button -> {
                    gpuMinimumGroupSubmits = nextGpuGroupMinimum(gpuMinimumGroupSubmits);
                    button.setMessage(gpuGroupMinimumLabel());
                })
                .pos(right, y)
                .size(buttonWidth, 20)
                .build());

        int actionY = Math.min(this.height - 28, y + 30);
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> closeToParent())
                .pos(left, actionY)
                .size(buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveAndClose())
                .pos(right, actionY)
                .size(buttonWidth, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        graphics.centeredText(
                this.font,
                Component.literal("Save applies live; renderer: next frame; workers: next world."),
                this.width / 2,
                24,
                0xFFAAAAAA);
        if (saveError != null)
            graphics.centeredText(this.font, saveError, this.width / 2, this.height - 10, 0xFFFF5555);
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    private void saveAndClose() {
        ThreadiumConfig config = buildConfig();
        if (!ThreadiumConfig.save(config)) {
            saveError = Component.literal("Could not save threadium.properties; previous settings remain active.");
            return;
        }
        ThreadiumRuntimeConfig.publishSaved(config);
        dev.alex.threadium.lifecycle.ThreadiumLifecycle.applyPublishedConfiguration();
        closeToParent();
    }

    private void closeToParent() {
        this.minecraft.gui.setScreen(parent);
    }

    private ThreadiumConfig buildConfig() {
        return new ThreadiumConfig(
                enabled,
                metricsEnabled,
                debugLogging,
                parallelVisibilityEnabled,
                phasePipelineEnabled,
                workerCountOverride,
                original.visibilityEntityThreshold(),
                original.phaseQueueCapacity(),
                original.phaseDeadlineMicros(),
                original.minTranslucentSubmits(),
                original.maxConsecutiveFailures(),
                retainedTextEnabled,
                original.retainedTextMaxCacheEntries(),
                original.retainedTextMaxGpuBytes(),
                original.retainedTextEntryIdleSeconds(),
                gpuEntityEnabled,
                gpuMinimumGroupSubmits,
                original.gpuBackend(),
                original.gpuMaxInstances(),
                original.gpuMaxBonesPerModel(),
                original.gpuMaxBonesPerFrame(),
                original.gpuMaxVerticesPerMesh(),
                original.gpuMaxIndicesPerMesh(),
                original.gpuMaxCachedMeshes(),
                original.gpuMaxMeshBytes(),
                gpuAllowVanillaFallback,
                gpuBatchConsolidation,
                original.gpuDebugVisualMode(),
                original.gpuDebugSuppressVanilla(),
                metricsOutputIntervalSeconds);
    }

    private Component workerLabel() {
        return Component.literal("Workers: " + (workerCountOverride == 0 ? "Auto" : workerCountOverride));
    }

    private Component intervalLabel() {
        return Component.literal("Metrics interval: " + metricsOutputIntervalSeconds + "s");
    }

    private Component gpuGroupMinimumLabel() {
        return Component.literal("GPU group minimum: " + gpuMinimumGroupSubmits);
    }

    private static Component toggle(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "ON" : "OFF"));
    }

    private static int nextGpuGroupMinimum(int current) {
        for (int i = 0; i < GPU_GROUP_MINIMUMS.length; i++) {
            if (GPU_GROUP_MINIMUMS[i] == current) {
                return GPU_GROUP_MINIMUMS[(i + 1) % GPU_GROUP_MINIMUMS.length];
            }
        }
        return 16;
    }

    private static int nextInterval(int current) {
        for (int i = 0; i < METRICS_INTERVALS.length; i++) {
            if (METRICS_INTERVALS[i] == current) {
                return METRICS_INTERVALS[(i + 1) % METRICS_INTERVALS.length];
            }
        }
        return 30;
    }
}
