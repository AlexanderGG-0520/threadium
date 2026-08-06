package dev.alex.threadium.benchmark;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.render.entity.ModelPartReplacementService;
import dev.alex.threadium.render.modelpart.DifferentialExecutionScope;
import dev.alex.threadium.validation.DifferentialImageComparator;
import dev.alex.threadium.validation.DifferentialReadbackTracker;
import dev.alex.threadium.validation.DifferentialResultPolicy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/** Developer-only same-frame Vanilla/Threadium image and depth differential harness for Minecraft 1.21.1. */
public final class PipelineDifferentialRunner1211 {
    public enum Phase {
        IDLE,
        RUNNING,
        COMPLETE,
        FAILED,
        ABORTED
    }

    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int PIXELS = WIDTH * HEIGHT;
    private static final int LIGHT = 0x00F000F0;
    private static final int COLOR = 0xFFFFFFFF;
    private static final ArrayDeque<Sample> PENDING = new ArrayDeque<>();
    private static final LinkedHashMap<String, String> RESULTS = new LinkedHashMap<>();
    private static final ArrayList<SampleResult> SAMPLE_RESULTS = new ArrayList<>();
    private static Phase phase = Phase.IDLE;
    private static String suiteLabel = "none";
    private static String failure;
    private static Path suiteResultPath;
    private static SimpleFramebuffer privateTarget;
    private static boolean executing;

    private PipelineDifferentialRunner1211() {}

    public static String run(MinecraftClient client, String canonical) {
        if (!DifferentialFixtureRegistry1211.contains(canonical)) {
            return "[Threadium Differential 1.21.1] Unknown canonical pipeline: " + canonical;
        }
        return start(client, canonical, samples(DifferentialFixtureRegistry1211.require(canonical)));
    }

    public static String runPhase(MinecraftClient client, String phaseName) {
        DifferentialFixtureRegistry1211.Phase selected;
        try {
            selected = DifferentialFixtureRegistry1211.Phase.valueOf(phaseName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return "[Threadium Differential 1.21.1] Unknown phase: " + phaseName;
        }
        if (selected == DifferentialFixtureRegistry1211.Phase.BASELINE) {
            return "[Threadium Differential 1.21.1] Phase must be A, B, or C.";
        }
        List<Sample> selectedSamples = DifferentialFixtureRegistry1211.phase(selected).stream()
                .flatMap(fixture -> samples(fixture).stream())
                .toList();
        return start(client, "phase-" + selected.name().toLowerCase(java.util.Locale.ROOT), selectedSamples);
    }

    public static String runAll(MinecraftClient client) {
        List<Sample> all = DifferentialFixtureRegistry1211.fixtures().stream()
                .flatMap(fixture -> samples(fixture).stream())
                .toList();
        return start(client, "all", all);
    }

    public static String rerunFailed(MinecraftClient client) {
        List<Sample> failed = RESULTS.entrySet().stream()
                .filter(entry -> !"PASS".equals(entry.getValue()))
                .flatMap(entry -> samples(DifferentialFixtureRegistry1211.require(entry.getKey())).stream())
                .toList();
        if (failed.isEmpty()) return "[Threadium Differential 1.21.1] No failed pipelines to rerun.";
        return start(client, "rerun-failed", failed);
    }

    private static String start(MinecraftClient client, String label, List<Sample> samples) {
        if (active()) return "[Threadium Differential 1.21.1] Run rejected: another suite is active.";
        if (client.world == null || client.player == null) {
            return "[Threadium Differential 1.21.1] Run rejected: enter a world first.";
        }
        if (!ModelPartReplacementService.gpuConfigured()) {
            return "[Threadium Differential 1.21.1] Run rejected: GPU replacement is not configured.";
        }
        cleanupTransient();
        PENDING.addAll(samples);
        RESULTS.clear();
        SAMPLE_RESULTS.clear();
        suiteLabel = label;
        phase = Phase.RUNNING;
        return "[Threadium Differential 1.21.1] Started " + label + " with " + samples.size() + " samples.";
    }

    public static void renderTail() {
        if (phase != Phase.RUNNING || executing) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            failSuite("world or player became unavailable");
            return;
        }
        Sample sample = PENDING.pollFirst();
        if (sample == null) {
            finishSuite();
            return;
        }
        executing = true;
        try {
            SampleResult result = execute(client, sample);
            SAMPLE_RESULTS.add(result);
            RESULTS.merge(sample.pipeline(), result.state(), PipelineDifferentialRunner1211::mergeState);
        } catch (Throwable throwable) {
            String message = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            SAMPLE_RESULTS.add(new SampleResult(sample, "HARNESS_ERROR", null, message));
            RESULTS.merge(sample.pipeline(), "HARNESS_ERROR", PipelineDifferentialRunner1211::mergeState);
            ThreadiumClient.LOGGER.error("Minecraft 1.21.1 differential sample failed", throwable);
        } finally {
            executing = false;
            DifferentialExecutionScope.reset();
        }
        if (PENDING.isEmpty()) finishSuite();
    }

    public static String abort() {
        if (!active()) return "[Threadium Differential 1.21.1] No active suite.";
        PENDING.clear();
        phase = Phase.ABORTED;
        failure = "aborted by user";
        writeSuiteResult();
        return "[Threadium Differential 1.21.1] Aborted. Result: " + suiteResultPath;
    }

    public static List<String> status() {
        return List.of(
                "[Threadium Differential 1.21.1] phase=" + phase + ", suite=" + suiteLabel + ", remaining="
                        + PENDING.size() + ", completed=" + SAMPLE_RESULTS.size(),
                "[Threadium Differential 1.21.1] results=" + RESULTS + ", resultPath="
                        + (suiteResultPath == null ? "pending" : suiteResultPath)
                        + (failure == null ? "" : ", failure=" + failure));
    }

    public static boolean active() {
        return phase == Phase.RUNNING;
    }

    public static void invalidate(String reason) {
        if (!active()) return;
        PENDING.clear();
        phase = Phase.FAILED;
        failure = reason;
        writeSuiteResult();
    }

    private static SampleResult execute(MinecraftClient client, Sample sample) throws IOException {
        DifferentialFixtureRegistry1211.Fixture fixture = DifferentialFixtureRegistry1211.require(sample.pipeline());
        boolean resourcesPresent = resourcesPresent(client, fixture);
        if (!resourcesPresent) {
            return recordWithoutRendering(sample, fixture, "HARNESS_ERROR", "required texture resource is missing");
        }

        Framebuffer target = target(client, fixture.outputTarget());
        if (target == null || target.textureWidth < WIDTH || target.textureHeight < HEIGHT) {
            return recordWithoutRendering(sample, fixture, "HARNESS_ERROR", "required framebuffer is unavailable");
        }

        int[] referenceColor = new int[PIXELS];
        int[] candidateColor = new int[PIXELS];
        float[] referenceDepth = new float[PIXELS];
        float[] candidateDepth = new float[PIXELS];
        DifferentialReadbackTracker readbacks = new DifferentialReadbackTracker();
        PassResult reference =
                renderPass(client, fixture, sample, target, true, referenceColor, referenceDepth, readbacks);
        PassResult candidate =
                renderPass(client, fixture, sample, target, false, candidateColor, candidateDepth, readbacks);

        boolean compareColor = fixture.targetContract() != DifferentialFixtureRegistry1211.TargetContract.DEPTH_ONLY;
        boolean compareDepth = fixture.targetContract() != DifferentialFixtureRegistry1211.TargetContract.OUTLINE;
        DifferentialImageComparator.Metrics colorMetrics = compareColor
                ? DifferentialImageComparator.compare(
                        referenceColor,
                        candidateColor,
                        null,
                        null,
                        new DifferentialImageComparator.Tolerance(fixture.colorChannelTolerance(), 0.0f, false))
                : null;
        DifferentialImageComparator.Metrics depthMetrics = compareDepth
                ? DifferentialImageComparator.compare(
                        referenceColor,
                        candidateColor,
                        referenceDepth,
                        candidateDepth,
                        new DifferentialImageComparator.Tolerance(255, fixture.depthTolerance(), true))
                : null;

        DifferentialExecutionScope.Snapshot candidateScope = candidate.scope();
        long failures = Math.max(0L, candidate.failuresAfter() - candidate.failuresBefore());
        DifferentialResultPolicy.State state = DifferentialResultPolicy.classify(new DifferentialResultPolicy.Evidence(
                true,
                resourcesPresent,
                reference.success(),
                candidate.success(),
                readbacks.ready() && !DifferentialExecutionScope.active(),
                Arrays.equals(reference.poseBits(), candidate.poseBits()),
                candidateScope == null ? 0 : candidateScope.observed(),
                candidateScope == null ? 0 : candidateScope.accepted(),
                candidateScope == null ? 0 : candidateScope.fallback(),
                failures,
                0,
                colorMetrics,
                depthMetrics));

        String detail = policyDetail(reference.scope(), candidateScope, colorMetrics, depthMetrics, failures);
        Path resultPath = writeSampleResult(
                sample,
                fixture,
                state.name(),
                detail,
                referenceColor,
                candidateColor,
                referenceDepth,
                candidateDepth,
                reference.scope(),
                candidateScope,
                colorMetrics,
                depthMetrics);
        return new SampleResult(sample, state.name(), resultPath, detail);
    }

    private static PassResult renderPass(
            MinecraftClient client,
            DifferentialFixtureRegistry1211.Fixture fixture,
            Sample sample,
            Framebuffer target,
            boolean reference,
            int[] colors,
            float[] depths,
            DifferentialReadbackTracker readbacks) {
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var previousSorting = RenderSystem.getVertexSorting();
        Matrix4f previousTexture = new Matrix4f(RenderSystem.getTextureMatrix());
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        long failuresBefore = ModelPartReplacementService.diagnostics().replacementFailures();
        boolean modelViewPushed = false;
        DifferentialExecutionScope.Snapshot scopeSnapshot = null;
        boolean success = false;
        int[] poseBits = new int[0];
        Object groupOwner = fixture;

        try {
            target.beginWrite(true);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glDepthMask(true);
            GL11C.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11C.glClearDepth(1.0);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);

            RenderSystem.setProjectionMatrix(
                    new Matrix4f().perspective((float) Math.toRadians(55.0), 1.0f, 0.05f, 100.0f), previousSorting);
            modelView.pushMatrix();
            modelViewPushed = true;
            modelView.identity();
            RenderSystem.applyModelViewMatrix();

            ModelPart root = client.getEntityModelLoader().getModelPart(fixture.modelLayer());
            applyFixturePose(root, sample.animation());
            poseBits = poseBits(root);
            MatrixStack matrices = fixtureMatrices(sample.cameraYaw());
            DifferentialExecutionScope.Mode mode = reference
                    ? DifferentialExecutionScope.Mode.REFERENCE_BYPASS
                    : DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT;
            try (var scope = DifferentialExecutionScope.enter(mode, fixture.canonical())) {
                ModelPartReplacementService.beginRenderGroup(groupOwner);
                try {
                    renderFixture(root, matrices, fixture, sample.animation());
                } finally {
                    ModelPartReplacementService.endRenderGroup(groupOwner);
                }
                scopeSnapshot = scope.result();
            }

            readback(target, colors, depths);
            readbacks.complete(
                    reference
                            ? DifferentialReadbackTracker.Slot.REFERENCE_COLOR
                            : DifferentialReadbackTracker.Slot.CANDIDATE_COLOR);
            readbacks.complete(
                    reference
                            ? DifferentialReadbackTracker.Slot.REFERENCE_DEPTH
                            : DifferentialReadbackTracker.Slot.CANDIDATE_DEPTH);
            success = true;
        } finally {
            if (modelViewPushed) {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setTextureMatrix(previousTexture);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
        long failuresAfter = ModelPartReplacementService.diagnostics().replacementFailures();
        return new PassResult(success, scopeSnapshot, poseBits, failuresBefore, failuresAfter);
    }

    private static MatrixStack fixtureMatrices(float cameraYaw) {
        MatrixStack matrices = new MatrixStack();
        matrices.translate(0.0, 0.2, -4.0);
        matrices.multiply(new Quaternionf().rotationY((float) Math.toRadians(cameraYaw)));
        matrices.scale(-1.0f, -1.0f, 1.0f);
        matrices.translate(0.0, -1.501, 0.0);
        return matrices;
    }

    private static void renderFixture(
            ModelPart root, MatrixStack matrices, DifferentialFixtureRegistry1211.Fixture fixture, float animation) {
        BufferAllocator allocator = new BufferAllocator(1 << 20);
        try {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            switch (fixture.kind()) {
                case CRUMBLING -> renderCrumbling(root, matrices, fixture, immediate);
                case OUTLINE_CULL, OUTLINE_NO_CULL -> renderOutline(root, matrices, fixture, immediate);
                case ARMOR_DECAL, ENTITY_DECAL, GLINT ->
                    renderDepthEqual(root, matrices, fixture, animation, immediate);
                default -> renderLayerInstances(root, matrices, layer(fixture, animation), immediate, fixture.sorted());
            }
            immediate.draw();
        } finally {
            allocator.close();
        }
    }

    private static void renderLayerInstances(
            ModelPart root,
            MatrixStack matrices,
            RenderLayer layer,
            VertexConsumerProvider.Immediate immediate,
            boolean sorted) {
        VertexConsumer consumer = immediate.getBuffer(layer);
        if (!sorted) {
            root.render(matrices, consumer, LIGHT, OverlayTexture.DEFAULT_UV, COLOR);
            return;
        }
        matrices.push();
        matrices.translate(-0.18, 0.0, -0.10);
        root.render(matrices, consumer, LIGHT, OverlayTexture.DEFAULT_UV, 0xD8FFFFFF);
        matrices.pop();
        matrices.push();
        matrices.translate(0.18, 0.0, 0.10);
        root.render(matrices, consumer, LIGHT, OverlayTexture.DEFAULT_UV, 0xB8FFFFFF);
        matrices.pop();
    }

    private static void renderDepthEqual(
            ModelPart root,
            MatrixStack matrices,
            DifferentialFixtureRegistry1211.Fixture fixture,
            float animation,
            VertexConsumerProvider.Immediate immediate) {
        Identifier texture = fixture.texture() == null
                ? Identifier.ofVanilla("textures/models/armor/iron_layer_1.png")
                : fixture.texture();
        RenderLayer base = fixture.kind()
                                == dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor.Kind
                                        .ARMOR_DECAL
                        || fixture.kind()
                                == dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor.Kind.GLINT
                ? RenderLayer.getArmorCutoutNoCull(texture)
                : RenderLayer.getEntitySolid(texture);
        root.render(matrices, immediate.getBuffer(base), LIGHT, OverlayTexture.DEFAULT_UV, COLOR);
        RenderLayer overlay = layer(fixture, animation);
        root.render(matrices, immediate.getBuffer(overlay), LIGHT, OverlayTexture.DEFAULT_UV, COLOR);
    }

    private static void renderCrumbling(
            ModelPart root,
            MatrixStack matrices,
            DifferentialFixtureRegistry1211.Fixture fixture,
            VertexConsumerProvider.Immediate immediate) {
        VertexConsumer base = immediate.getBuffer(
                RenderLayer.getEntitySolid(Identifier.ofVanilla("textures/entity/chest/normal.png")));
        VertexConsumer breaking = immediate.getBuffer(RenderLayer.getBlockBreaking(fixture.texture()));
        VertexConsumer decal = new OverlayVertexConsumer(breaking, matrices.peek(), 1.0f);
        root.render(matrices, VertexConsumers.union(decal, base), LIGHT, OverlayTexture.DEFAULT_UV, COLOR);
    }

    private static void renderOutline(
            ModelPart root,
            MatrixStack matrices,
            DifferentialFixtureRegistry1211.Fixture fixture,
            VertexConsumerProvider.Immediate immediate) {
        OutlineVertexConsumerProvider outline = new OutlineVertexConsumerProvider(immediate);
        outline.setColor(68, 204, 255, 255);
        RenderLayer requested = fixture.kind()
                        == dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor.Kind.OUTLINE_CULL
                ? RenderLayer.getEntityCutout(fixture.texture())
                : RenderLayer.getOutline(fixture.texture());
        root.render(matrices, outline.getBuffer(requested), LIGHT, OverlayTexture.DEFAULT_UV, COLOR);
        outline.draw();
    }

    private static RenderLayer layer(DifferentialFixtureRegistry1211.Fixture fixture, float animation) {
        Identifier texture = fixture.texture();
        return switch (fixture.kind()) {
            case ARMOR_CUTOUT -> RenderLayer.getArmorCutoutNoCull(texture);
            case ARMOR_DECAL -> RenderLayer.createArmorDecalCutoutNoCull(texture);
            case ENTITY_SOLID -> RenderLayer.getEntitySolid(texture);
            case ENTITY_CUTOUT_CULL -> RenderLayer.getEntityCutout(texture);
            case ENTITY_CUTOUT -> RenderLayer.getEntityCutoutNoCull(texture);
            case ENTITY_CUTOUT_Z_OFFSET -> RenderLayer.getEntityCutoutNoCullZOffset(texture);
            case ENTITY_DECAL -> RenderLayer.getEntityDecal(texture);
            case ENTITY_TRANSLUCENT -> RenderLayer.getEntityTranslucent(texture);
            case ENTITY_TRANSLUCENT_EMISSIVE -> RenderLayer.getEntityTranslucentEmissive(texture);
            case ENTITY_TRANSLUCENT_CULL -> RenderLayer.getEntityTranslucentCull(texture);
            case ITEM_ENTITY_TRANSLUCENT_CULL -> RenderLayer.getItemEntityTranslucentCull(texture);
            case BANNER_PATTERN -> RenderLayer.getEntityNoOutline(texture);
            case EYES -> RenderLayer.getEyes(texture);
            case BREEZE_WIND -> RenderLayer.getBreezeWind(texture, animation, animation * 0.5f);
            case ENERGY_SWIRL -> RenderLayer.getEnergySwirl(texture, animation, animation * 0.5f);
            case GLINT -> RenderLayer.getArmorEntityGlint();
            case WATER_MASK -> RenderLayer.getWaterMask();
            case CRUMBLING -> RenderLayer.getBlockBreaking(texture);
            case OUTLINE_CULL ->
                RenderLayer.getEntityCutout(texture).getAffectedOutline().orElseThrow();
            case OUTLINE_NO_CULL -> RenderLayer.getOutline(texture);
            case UNSUPPORTED -> throw new IllegalArgumentException("unsupported fixture");
        };
    }

    private static Framebuffer target(
            MinecraftClient client, DifferentialFixtureRegistry1211.OutputTarget outputTarget) {
        return switch (outputTarget) {
            case MAIN -> privateTarget();
            case OUTLINE -> client.worldRenderer.getEntityOutlinesFramebuffer();
            case ITEM_ENTITY ->
                MinecraftClient.isFabulousGraphicsOrBetter()
                        ? client.worldRenderer.getEntityFramebuffer()
                        : privateTarget();
        };
    }

    private static SimpleFramebuffer privateTarget() {
        if (privateTarget == null || privateTarget.textureWidth != WIDTH || privateTarget.textureHeight != HEIGHT) {
            if (privateTarget != null) privateTarget.delete();
            privateTarget = new SimpleFramebuffer(WIDTH, HEIGHT, true, true);
        }
        return privateTarget;
    }

    private static void readback(Framebuffer target, int[] colors, float[] depths) {
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.fbo);
        GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        ByteBuffer rgba = MemoryUtil.memAlloc(PIXELS * 4);
        FloatBuffer depth = MemoryUtil.memAllocFloat(PIXELS);
        try {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glReadPixels(0, 0, WIDTH, HEIGHT, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, rgba);
            GL11C.glReadPixels(0, 0, WIDTH, HEIGHT, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
            for (int gpuY = 0; gpuY < HEIGHT; gpuY++) {
                for (int x = 0; x < WIDTH; x++) {
                    int source = (x + gpuY * WIDTH) * 4;
                    int targetIndex = DifferentialReadbackTracker.normalizedIndex(x, gpuY, WIDTH, HEIGHT);
                    int red = rgba.get(source) & 255;
                    int green = rgba.get(source + 1) & 255;
                    int blue = rgba.get(source + 2) & 255;
                    int alpha = rgba.get(source + 3) & 255;
                    colors[targetIndex] = alpha << 24 | red << 16 | green << 8 | blue;
                    depths[targetIndex] = depth.get(x + gpuY * WIDTH);
                }
            }
        } finally {
            MemoryUtil.memFree(rgba);
            MemoryUtil.memFree(depth);
        }
    }

    private static void applyFixturePose(ModelPart root, float animation) {
        int index = 0;
        for (ModelPart part : root.traverse().toList()) {
            float phase = animation * (float) (Math.PI * 2.0) + index * 0.37f;
            part.pitch += (float) Math.sin(phase) * 0.08f;
            part.yaw += (float) Math.cos(phase * 0.7f) * 0.11f;
            part.roll += (float) Math.sin(phase * 0.5f) * 0.04f;
            index++;
        }
    }

    private static int[] poseBits(ModelPart root) {
        List<ModelPart> parts = root.traverse().toList();
        int[] bits = new int[parts.size() * 11];
        int index = 0;
        for (ModelPart part : parts) {
            bits[index++] = Float.floatToRawIntBits(part.pivotX);
            bits[index++] = Float.floatToRawIntBits(part.pivotY);
            bits[index++] = Float.floatToRawIntBits(part.pivotZ);
            bits[index++] = Float.floatToRawIntBits(part.pitch);
            bits[index++] = Float.floatToRawIntBits(part.yaw);
            bits[index++] = Float.floatToRawIntBits(part.roll);
            bits[index++] = Float.floatToRawIntBits(part.xScale);
            bits[index++] = Float.floatToRawIntBits(part.yScale);
            bits[index++] = Float.floatToRawIntBits(part.zScale);
            bits[index++] = part.visible ? 1 : 0;
            bits[index++] = part.hidden ? 1 : 0;
        }
        return bits;
    }

    private static boolean resourcesPresent(MinecraftClient client, DifferentialFixtureRegistry1211.Fixture fixture) {
        return fixture.texture() == null
                || client.getResourceManager().getResource(fixture.texture()).isPresent();
    }

    private static List<Sample> samples(DifferentialFixtureRegistry1211.Fixture fixture) {
        ArrayList<Sample> result = new ArrayList<>();
        for (float animation : fixture.animationSamples()) {
            for (float yaw : fixture.cameraYawSamples()) {
                result.add(new Sample(fixture.canonical(), animation, yaw, 0, 0));
            }
        }
        for (int index = 0; index < result.size(); index++) {
            Sample sample = result.get(index);
            result.set(
                    index, new Sample(sample.pipeline(), sample.animation(), sample.cameraYaw(), index, result.size()));
        }
        return List.copyOf(result);
    }

    private static SampleResult recordWithoutRendering(
            Sample sample, DifferentialFixtureRegistry1211.Fixture fixture, String state, String detail)
            throws IOException {
        Path path = writeSampleResult(
                sample,
                fixture,
                state,
                detail,
                new int[PIXELS],
                new int[PIXELS],
                new float[PIXELS],
                new float[PIXELS],
                null,
                null,
                null,
                null);
        return new SampleResult(sample, state, path, detail);
    }

    private static Path writeSampleResult(
            Sample sample,
            DifferentialFixtureRegistry1211.Fixture fixture,
            String state,
            String detail,
            int[] referenceColor,
            int[] candidateColor,
            float[] referenceDepth,
            float[] candidateDepth,
            DifferentialExecutionScope.Snapshot referenceScope,
            DifferentialExecutionScope.Snapshot candidateScope,
            DifferentialImageComparator.Metrics colorMetrics,
            DifferentialImageComparator.Metrics depthMetrics)
            throws IOException {
        Path directory = outputDirectory();
        String stem = timestamp() + "_" + sample.pipeline() + "_" + sample.index();
        Path result = directory.resolve(stem + ".json");
        Files.writeString(
                result,
                "{\n"
                        + "  \"pipeline\": \"" + escape(sample.pipeline()) + "\",\n"
                        + "  \"fixture\": \"" + escape(fixture.fixtureName()) + "\",\n"
                        + "  \"sample\": " + sample.index() + ",\n"
                        + "  \"sampleCount\": " + sample.total() + ",\n"
                        + "  \"animation\": " + sample.animation() + ",\n"
                        + "  \"cameraYaw\": " + sample.cameraYaw() + ",\n"
                        + "  \"referenceScope\": " + scopeJson(referenceScope) + ",\n"
                        + "  \"candidateScope\": " + scopeJson(candidateScope) + ",\n"
                        + "  \"color\": " + metricJson(colorMetrics) + ",\n"
                        + "  \"depth\": " + metricJson(depthMetrics) + ",\n"
                        + "  \"detail\": \"" + escape(detail) + "\",\n"
                        + "  \"result\": \"" + state + "\"\n"
                        + "}\n");
        writePng(directory.resolve(stem + "_reference.png"), referenceColor);
        writePng(directory.resolve(stem + "_candidate.png"), candidateColor);
        int[] difference = new int[PIXELS];
        for (int index = 0; index < PIXELS; index++) {
            int left = referenceColor[index];
            int right = candidateColor[index];
            int maximum = Math.max(
                    Math.max(
                            Math.abs((left >>> 24 & 255) - (right >>> 24 & 255)),
                            Math.abs((left >>> 16 & 255) - (right >>> 16 & 255))),
                    Math.max(
                            Math.abs((left >>> 8 & 255) - (right >>> 8 & 255)),
                            Math.abs((left & 255) - (right & 255))));
            difference[index] = 0xFF000000 | maximum << 16 | maximum << 8 | maximum;
        }
        writePng(directory.resolve(stem + "_difference.png"), difference);
        writeDepthPng(directory.resolve(stem + "_reference_depth.png"), referenceDepth);
        writeDepthPng(directory.resolve(stem + "_candidate_depth.png"), candidateDepth);
        return result;
    }

    private static void writePng(Path path, int[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", path.toFile());
    }

    private static void writeDepthPng(Path path, float[] depths) throws IOException {
        int[] pixels = new int[PIXELS];
        for (int index = 0; index < PIXELS; index++) {
            int value = Math.max(0, Math.min(255, Math.round((1.0f - depths[index]) * 255.0f)));
            pixels[index] = 0xFF000000 | value << 16 | value << 8 | value;
        }
        writePng(path, pixels);
    }

    private static String policyDetail(
            DifferentialExecutionScope.Snapshot reference,
            DifferentialExecutionScope.Snapshot candidate,
            DifferentialImageComparator.Metrics color,
            DifferentialImageComparator.Metrics depth,
            long failures) {
        return "referenceObserved=" + (reference == null ? 0 : reference.observed()) + ", candidateObserved="
                + (candidate == null ? 0 : candidate.observed()) + ", accepted="
                + (candidate == null ? 0 : candidate.accepted()) + ", fallback="
                + (candidate == null ? 0 : candidate.fallback()) + ", suppressions="
                + (candidate == null ? 0 : candidate.suppressions()) + ", backendFailures=" + failures
                + ", colorDifferences=" + (color == null ? 0 : color.differingPixels()) + ", depthDifferences="
                + (depth == null ? 0 : depth.depthDifferences());
    }

    private static String scopeJson(DifferentialExecutionScope.Snapshot scope) {
        if (scope == null) return "null";
        return "{\"mode\":\"" + scope.mode() + "\",\"observed\":" + scope.observed() + ",\"accepted\":"
                + scope.accepted() + ",\"fallback\":" + scope.fallback() + ",\"suppressions\":"
                + scope.suppressions() + ",\"fallbackReason\":\"" + escape(scope.fallbackReason()) + "\"}";
    }

    private static String metricJson(DifferentialImageComparator.Metrics metrics) {
        if (metrics == null) return "null";
        return "{\"pixelsCompared\":" + metrics.pixelsCompared() + ",\"differingPixels\":"
                + metrics.differingPixels() + ",\"coverageMaskDifferences\":"
                + metrics.coverageMaskDifferences() + ",\"maximumRedDifference\":"
                + metrics.maximumRedDifference() + ",\"maximumGreenDifference\":"
                + metrics.maximumGreenDifference() + ",\"maximumBlueDifference\":"
                + metrics.maximumBlueDifference() + ",\"maximumAlphaDifference\":"
                + metrics.maximumAlphaDifference() + ",\"meanAbsoluteColorDifference\":"
                + metrics.meanAbsoluteColorDifference() + ",\"depthDifferences\":"
                + metrics.depthDifferences() + ",\"maximumDepthDifference\":"
                + metrics.maximumDepthDifference() + "}";
    }

    private static void finishSuite() {
        if (phase != Phase.RUNNING) return;
        phase = RESULTS.values().stream().allMatch("PASS"::equals) ? Phase.COMPLETE : Phase.FAILED;
        writeSuiteResult();
    }

    private static void failSuite(String reason) {
        PENDING.clear();
        phase = Phase.FAILED;
        failure = reason;
        writeSuiteResult();
    }

    private static void writeSuiteResult() {
        try {
            Path directory = outputDirectory();
            suiteResultPath = directory.resolve(timestamp() + "_suite_" + safeName(suiteLabel) + ".json");
            StringBuilder samples = new StringBuilder();
            for (int index = 0; index < SAMPLE_RESULTS.size(); index++) {
                SampleResult result = SAMPLE_RESULTS.get(index);
                if (index != 0) samples.append(",\n");
                samples.append("    {\"pipeline\":\"")
                        .append(escape(result.sample().pipeline()))
                        .append("\",\"sample\":")
                        .append(result.sample().index())
                        .append(",\"result\":\"")
                        .append(result.state())
                        .append("\",\"path\":\"")
                        .append(escape(
                                result.resultPath() == null
                                        ? null
                                        : result.resultPath().toString()))
                        .append("\",\"detail\":\"")
                        .append(escape(result.detail()))
                        .append("\"}");
            }
            Files.writeString(
                    suiteResultPath,
                    "{\n  \"suite\": \"" + escape(suiteLabel) + "\",\n  \"phase\": \"" + phase
                            + "\",\n  \"failure\": \"" + escape(failure) + "\",\n  \"results\": \""
                            + escape(RESULTS.toString()) + "\",\n  \"samples\": [\n" + samples + "\n  ]\n}\n");
        } catch (IOException exception) {
            ThreadiumClient.LOGGER.error("Could not write Minecraft 1.21.1 differential suite result", exception);
        }
    }

    private static Path outputDirectory() throws IOException {
        Path directory = FabricLoader.getInstance()
                .getGameDir()
                .resolve("pipeline-differential")
                .resolve("mc1.21.1");
        Files.createDirectories(directory);
        return directory;
    }

    private static String mergeState(String left, String right) {
        if ("HARNESS_ERROR".equals(left) || "HARNESS_ERROR".equals(right)) return "HARNESS_ERROR";
        if ("FAIL".equals(left) || "FAIL".equals(right)) return "FAIL";
        return "PASS";
    }

    private static String timestamp() {
        return Instant.now().toString().replace(':', '-');
    }

    private static String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void cleanupTransient() {
        PENDING.clear();
        suiteResultPath = null;
        failure = null;
        executing = false;
        DifferentialExecutionScope.reset();
    }

    private record Sample(String pipeline, float animation, float cameraYaw, int index, int total) {}

    private record SampleResult(Sample sample, String state, Path resultPath, String detail) {}

    private record PassResult(
            boolean success,
            DifferentialExecutionScope.Snapshot scope,
            int[] poseBits,
            long failuresBefore,
            long failuresAfter) {}
}
