package dev.alex.threadium.config;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Small dependency-free 1.21.1 configuration screen. */
public final class ThreadiumConfigScreen extends Screen {
    private static final int[] GROUP_MINIMUMS = {1, 4, 8, 16, 32, 64};
    private static final int[] METRICS_INTERVALS = {5, 10, 30, 60, 300};

    private final Screen parent;
    private final ThreadiumConfig original;
    private boolean enabled;
    private boolean metricsEnabled;
    private boolean debugLogging;
    private boolean gpuEntityEnabled;
    private boolean gpuAllowVanillaFallback;
    private boolean gpuBatchConsolidation;
    private int gpuMinimumGroupSubmits;
    private int metricsOutputIntervalSeconds;
    private String gpuBackend;
    private Text saveError;

    public ThreadiumConfigScreen(Screen parent) {
        super(Text.literal("Threadium 1.21.1"));
        this.parent = parent;
        this.original = ThreadiumRuntimeConfig.current();
        enabled = original.enabled();
        metricsEnabled = original.metricsEnabled();
        debugLogging = original.debugLogging();
        gpuEntityEnabled = original.gpuEntityEnabled();
        gpuAllowVanillaFallback = original.gpuAllowVanillaFallback();
        gpuBatchConsolidation = original.gpuBatchConsolidation();
        gpuMinimumGroupSubmits = original.gpuMinimumGroupSubmits();
        metricsOutputIntervalSeconds = original.metricsOutputIntervalSeconds();
        gpuBackend = original.gpuBackend();
    }

    @Override
    protected void init() {
        int width = Math.min(170, (this.width - 30) / 2);
        int left = this.width / 2 - width - 3;
        int right = this.width / 2 + 3;
        int y = 44;

        addDrawableChild(ButtonWidget.builder(toggle("Threadium", enabled), button -> {
                    enabled = !enabled;
                    button.setMessage(toggle("Threadium", enabled));
                })
                .dimensions(left, y, width, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(toggle("GPU entities", gpuEntityEnabled), button -> {
                    gpuEntityEnabled = !gpuEntityEnabled;
                    button.setMessage(toggle("GPU entities", gpuEntityEnabled));
                })
                .dimensions(right, y, width, 20)
                .build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(backendLabel(), button -> {
                    gpuBackend = switch (gpuBackend) {
                        case "auto" -> "opengl33";
                        case "opengl33" -> "disabled";
                        default -> "auto";
                    };
                    button.setMessage(backendLabel());
                })
                .dimensions(left, y, width, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(groupLabel(), button -> {
                    gpuMinimumGroupSubmits = next(GROUP_MINIMUMS, gpuMinimumGroupSubmits, 16);
                    button.setMessage(groupLabel());
                })
                .dimensions(right, y, width, 20)
                .build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(toggle("Batch consolidation", gpuBatchConsolidation), button -> {
                    gpuBatchConsolidation = !gpuBatchConsolidation;
                    button.setMessage(toggle("Batch consolidation", gpuBatchConsolidation));
                })
                .dimensions(left, y, width, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(toggle("Vanilla fallback", gpuAllowVanillaFallback), button -> {
                    gpuAllowVanillaFallback = !gpuAllowVanillaFallback;
                    button.setMessage(toggle("Vanilla fallback", gpuAllowVanillaFallback));
                })
                .dimensions(right, y, width, 20)
                .build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(toggle("Metrics", metricsEnabled), button -> {
                    metricsEnabled = !metricsEnabled;
                    button.setMessage(toggle("Metrics", metricsEnabled));
                })
                .dimensions(left, y, width, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(intervalLabel(), button -> {
                    metricsOutputIntervalSeconds = next(METRICS_INTERVALS, metricsOutputIntervalSeconds, 30);
                    button.setMessage(intervalLabel());
                })
                .dimensions(right, y, width, 20)
                .build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(toggle("Debug logging", debugLogging), button -> {
                    debugLogging = !debugLogging;
                    button.setMessage(toggle("Debug logging", debugLogging));
                })
                .dimensions(left, y, width, 20)
                .build());

        int actions = Math.min(this.height - 30, y + 34);
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
                .dimensions(left, actions, width, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose())
                .dimensions(right, actions, width, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("Saved settings apply at the next render-frame boundary."),
                width / 2,
                27,
                0xAAAAAA);
        if (saveError != null) {
            context.drawCenteredTextWithShadow(textRenderer, saveError, width / 2, height - 12, 0xFF5555);
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private void saveAndClose() {
        ThreadiumConfig updated = new ThreadiumConfig(
                enabled,
                metricsEnabled,
                debugLogging,
                gpuEntityEnabled,
                gpuMinimumGroupSubmits,
                gpuBackend,
                original.gpuMaxInstances(),
                original.gpuMaxBonesPerModel(),
                original.gpuMaxBonesPerFrame(),
                original.gpuMaxVerticesPerMesh(),
                original.gpuMaxIndicesPerMesh(),
                original.gpuMaxCachedMeshes(),
                original.gpuMaxMeshBytes(),
                gpuAllowVanillaFallback,
                gpuBatchConsolidation,
                metricsOutputIntervalSeconds);
        if (!ThreadiumConfig.save(updated)) {
            saveError = Text.literal("Could not save threadium.properties");
            return;
        }
        ThreadiumRuntimeConfig.publishSaved(updated);
        ModelPartReplacementService.requestConfigurationReload();
        close();
    }

    private Text backendLabel() {
        return Text.literal("GPU backend: " + gpuBackend);
    }

    private Text groupLabel() {
        return Text.literal("GPU group minimum: " + gpuMinimumGroupSubmits);
    }

    private Text intervalLabel() {
        return Text.literal("Metrics interval: " + metricsOutputIntervalSeconds + "s");
    }

    private static Text toggle(String label, boolean value) {
        return Text.literal(label + ": " + (value ? "ON" : "OFF"));
    }

    private static int next(int[] values, int current, int fallback) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return values[(index + 1) % values.length];
        }
        return fallback;
    }
}
