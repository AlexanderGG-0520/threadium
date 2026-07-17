package dev.alex.threadium.benchmark;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.render.modelpart.DifferentialExecutionScope;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import dev.alex.threadium.validation.DifferentialImageComparator;
import dev.alex.threadium.validation.DifferentialReadbackTracker;
import dev.alex.threadium.validation.DifferentialResultPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ambient.BatModel;
import net.minecraft.client.model.animal.cow.CowModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.shulker.ShulkerModel;
import net.minecraft.client.model.monster.spider.SpiderModel;
import net.minecraft.client.model.monster.warden.WardenModel;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.BatRenderState;
import net.minecraft.client.renderer.entity.state.CowRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.ShulkerRenderState;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Developer-only, same-frame Vanilla/Threadium ModelPart differential runner. */
public final class PipelineDifferentialRunner {
    public enum Phase {
        IDLE,
        PREPARING,
        REFERENCE_RENDERED,
        CANDIDATE_RENDERED,
        READBACK_PENDING,
        COMPARING,
        COMPLETE,
        FAILED,
        ABORTED
    }

    private static final int WIDTH = 256, HEIGHT = 256, PIXELS = WIDTH * HEIGHT, TIMEOUT_FRAMES = 600;
    private static final Identifier STEVE_TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final RenderPipeline DEPTH_COPY_PIPELINE =
            net.minecraft.client.renderer.RenderPipelines.register(RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("threadium", "pipeline/differential_depth_copy"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("threadium", "core/differential_depth_copy"))
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(
                            new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_RED))
                    .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                    .build());
    private static Phase phase = Phase.IDLE;
    private static boolean replaying;
    private static int pendingFrames;
    private static String failure, resultState = "NOT_EXECUTED";
    private static Path resultPath;
    private static ModelFeatureRenderer.Submit<?> source;
    private static TextureTarget referenceTarget, candidateTarget, referenceDepthReadTarget, candidateDepthReadTarget;
    private static StagedVertexBuffer referenceStaging, candidateStaging;
    private static final int[] colorsReference = new int[PIXELS], colorsCandidate = new int[PIXELS];
    private static final float[] depthReference = new float[PIXELS], depthCandidate = new float[PIXELS];
    private static DifferentialReadbackTracker readbacks = new DifferentialReadbackTracker();
    private static boolean referenceSuccess, candidateSuccess, poseBitsIdentical;
    private static DifferentialExecutionScope.Snapshot referenceScope, candidateScope;
    private static ModelPartRenderService.DifferentialMetrics metricsBefore, metricsAfter;
    private static DifferentialImageComparator.Metrics colorMetrics, depthMetrics;
    private static Identifier fixtureTexture;
    private static String fixtureModel = "unresolved";
    private static DifferentialFixtureRegistry.Fixture currentFixture;
    private static int[] viewMatrixBits;
    private static GpuBufferSlice projectionBinding;
    private static ProjectionMatrixBuffer fixtureProjectionBuffer;
    private static GpuBufferSlice previousProjectionBinding;
    private static ProjectionType previousProjectionType;
    private static String deviceInfo = "unknown";
    private static final ArrayList<GpuBuffer> readbackBuffers = new ArrayList<>(4);
    private static final ArrayDeque<Sample> suitePending = new ArrayDeque<>();
    private static final LinkedHashMap<String, String> suiteResults = new LinkedHashMap<>();
    private static final ArrayList<SampleResult> suiteSampleResults = new ArrayList<>();
    private static boolean suiteActive, startingSuiteMember, suiteTerminalRecorded;
    private static String suiteLabel;
    private static Path suiteResultPath;
    private static Sample currentSample;

    private PipelineDifferentialRunner() {}

    public static String run(Minecraft client, String pipeline) {
        if (!DifferentialFixtureRegistry.contains(pipeline))
            return "[Threadium Differential] Unknown canonical pipeline: " + pipeline;
        return startSuite(client, pipeline, samples(DifferentialFixtureRegistry.require(pipeline)));
    }

    private static String runSample(Minecraft client, Sample sample) {
        String pipeline = sample.pipeline();
        if (active()) return "[Threadium Differential] Run rejected: another run is active.";
        if (client.level == null || client.player == null)
            return "[Threadium Differential] Run rejected: invalid world/render state.";
        if (ModelPartRenderService.get() == null)
            return "[Threadium Differential] Run rejected: backend not available.";
        cleanup();
        phase = Phase.PREPARING;
        resultState = "NOT_EXECUTED";
        failure = null;
        resultPath = null;
        source = null;
        pendingFrames = 0;
        readbacks = new DifferentialReadbackTracker();
        currentSample = sample;
        currentFixture = DifferentialFixtureRegistry.require(pipeline);
        fixtureTexture = currentFixture.texture();
        deviceInfo = RenderSystem.getDevice().getDeviceInfo().toString();
        if (fixtureTexture != null
                && !isAtlasTexture(fixtureTexture)
                && client.getResourceManager().getResource(fixtureTexture).isEmpty())
            return failCommand("required Vanilla texture missing: " + fixtureTexture);
        for (Identifier auxiliary : currentFixture.auxiliaryResources())
            if (client.getResourceManager().getResource(auxiliary).isEmpty())
                return failCommand("required auxiliary resource missing: " + auxiliary);
        source = createSource(client, currentFixture, sample.animation());
        fixtureModel = source.model().getClass().getName() + "[" + currentFixture.modelLayer() + "]";
        notify(
                client,
                "[Threadium Differential] " + pipeline + " sample " + (sample.index() + 1) + "/" + sample.total()
                        + ": preparing");
        return "[Threadium Differential] " + pipeline + " run accepted.";
    }

    public static String runPhase(Minecraft client, String phaseName) {
        DifferentialFixtureRegistry.Phase selected;
        try {
            selected = DifferentialFixtureRegistry.Phase.valueOf(phaseName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return "[Threadium Differential] Unknown phase: " + phaseName;
        }
        if (selected == DifferentialFixtureRegistry.Phase.BASELINE)
            return "[Threadium Differential] Phase must be A, B, or C.";
        return startSuite(
                client,
                "phase-" + selected.name().toLowerCase(java.util.Locale.ROOT),
                DifferentialFixtureRegistry.phase(selected).stream()
                        .flatMap(fixture -> samples(fixture).stream())
                        .toList());
    }

    public static String runAll(Minecraft client) {
        return startSuite(
                client,
                "all",
                DifferentialFixtureRegistry.fixtures().stream()
                        .flatMap(fixture -> samples(fixture).stream())
                        .toList());
    }

    public static String rerunFailed(Minecraft client) {
        List<Sample> failed = suiteResults.entrySet().stream()
                .filter(entry -> !"PASS".equals(entry.getValue()))
                .flatMap(entry -> samples(DifferentialFixtureRegistry.require(entry.getKey())).stream())
                .toList();
        if (failed.isEmpty()) return "[Threadium Differential] No failed pipelines to rerun.";
        return startSuite(client, "rerun-failed", failed);
    }

    private static String startSuite(Minecraft client, String label, List<Sample> samples) {
        if (active() || suiteActive) return "[Threadium Differential] Suite rejected: another run is active.";
        suiteActive = true;
        suiteLabel = label;
        suiteResultPath = null;
        suiteResults.clear();
        suiteSampleResults.clear();
        suitePending.clear();
        suitePending.addAll(samples);
        suiteTerminalRecorded = false;
        startNextSuiteMember(client);
        return "[Threadium Differential] Started " + label + " with " + samples.size() + " samples.";
    }

    private static void startNextSuiteMember(Minecraft client) {
        Sample next = suitePending.pollFirst();
        if (next == null) {
            finishSuite();
            return;
        }
        suiteTerminalRecorded = false;
        startingSuiteMember = true;
        try {
            String response = runSample(client, next);
            if (!response.endsWith("run accepted.")) {
                suiteResults.put(next.pipeline(), "HARNESS_ERROR");
                phase = Phase.FAILED;
                failure = response;
            }
        } finally {
            startingSuiteMember = false;
        }
    }

    private static List<Sample> samples(DifferentialFixtureRegistry.Fixture fixture) {
        ArrayList<Sample> result = new ArrayList<>();
        for (float animation : fixture.animationSamples())
            for (float yaw : fixture.cameraYawSamples())
                result.add(new Sample(fixture.canonical(), animation, yaw, 0, 0));
        for (int i = 0; i < result.size(); i++)
            result.set(
                    i,
                    new Sample(
                            result.get(i).pipeline(),
                            result.get(i).animation(),
                            result.get(i).cameraYaw(),
                            i,
                            result.size()));
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelFeatureRenderer.Submit<?> createSource(
            Minecraft client, DifferentialFixtureRegistry.Fixture fixture, float animation) {
        Model model;
        Object state;
        if (fixture.canonical().equals("entity_solid")) {
            PlayerModel player = new PlayerModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            AvatarRenderState avatar = new AvatarRenderState();
            avatar.showHat = avatar.showJacket = avatar.showLeftPants =
                    avatar.showRightPants = avatar.showLeftSleeve = avatar.showRightSleeve = true;
            model = player;
            state = avatar;
        } else if (fixture.canonical().equals("entity_cutout_cull")
                || fixture.canonical().equals("outline_cull")) {
            model = new BatModel(client.getEntityModels().bakeLayer(ModelLayers.BAT));
            state = livingState(new BatRenderState());
        } else if (fixture.canonical().equals("entity_cutout")
                || fixture.canonical().equals("outline_no_cull")) {
            model = new CowModel(client.getEntityModels().bakeLayer(ModelLayers.COW));
            state = livingState(new CowRenderState());
        } else if (fixture.canonical().equals("entity_cutout_z_offset")) {
            model = new ShulkerModel(client.getEntityModels().bakeLayer(ModelLayers.SHULKER));
            state = new ShulkerRenderState();
        } else if (fixture.canonical().equals("armor_cutout")
                || fixture.canonical().equals("armor_decal")
                || fixture.canonical().equals("glint")) {
            model = new HumanoidModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER_ARMOR.chest()));
            state = livingState(new HumanoidRenderState());
        } else if (fixture.canonical().equals("entity_translucent_emissive")) {
            model = new WardenModel(client.getEntityModels().bakeLayer(ModelLayers.WARDEN_BIOLUMINESCENT));
            state = livingState(new WardenRenderState());
        } else if (fixture.canonical().equals("banner_pattern")) {
            model = new BannerFlagModel(client.getEntityModels().bakeLayer(ModelLayers.STANDING_BANNER_FLAG));
            state = 0.0f;
        } else if (fixture.canonical().equals("eyes")) {
            model = new SpiderModel(client.getEntityModels().bakeLayer(ModelLayers.SPIDER));
            state = livingState(new LivingEntityRenderState());
        } else if (fixture.canonical().equals("crumbling")) {
            model = new ChestModel(client.getEntityModels().bakeLayer(ModelLayers.CHEST));
            state = 0.0f;
        } else {
            model = new Model.Simple(
                    client.getEntityModels().bakeLayer(fixture.modelLayer()), id -> renderType(fixture, animation));
            state = Unit.INSTANCE;
        }
        // Feature-only baked layers inherit visibility from their parent model in
        // Vanilla.  Differential fixtures have no parent render, so reproduce the
        // resulting visible feature snapshot explicitly before capturing it.
        model.root().getAllParts().forEach(part -> part.visible = true);
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = null;
        if (fixture.material() == DifferentialFixtureRegistry.Material.ARMOR_DECAL)
            sprite = client.getAtlasManager()
                    .get(new SpriteId(
                            Sheets.ARMOR_TRIMS_SHEET,
                            Identifier.withDefaultNamespace("trims/entity/humanoid/sentry_iron")));
        else if (fixture.material() == DifferentialFixtureRegistry.Material.BANNER_PATTERN)
            sprite = client.getAtlasManager().get(Sheets.BANNER_PATTERN_BASE);
        PoseStack.Pose pose = new PoseStack().last().copy();
        PoseStack.Pose decal = fixture.material() == DifferentialFixtureRegistry.Material.CRUMBLING ? pose : null;
        return new ModelFeatureRenderer.Submit(
                renderType(fixture, animation),
                pose,
                model,
                state,
                0x00F000F0,
                OverlayTexture.NO_OVERLAY,
                -1,
                sprite,
                decal);
    }

    private static <T extends LivingEntityRenderState> T livingState(T state) {
        state.scale = 1.0f;
        state.ageScale = 1.0f;
        return state;
    }

    private static boolean isAtlasTexture(Identifier texture) {
        return texture.equals(Sheets.ARMOR_TRIMS_SHEET) || texture.equals(Sheets.BANNER_SHEET);
    }

    private static RenderType renderType(DifferentialFixtureRegistry.Fixture fixture, float animation) {
        Identifier texture = fixture.texture();
        return switch (fixture.material()) {
            case ENTITY_SOLID -> RenderTypes.entitySolid(texture);
            case ENTITY_CUTOUT_CULL -> RenderTypes.entityCutoutCull(texture);
            case ENTITY_CUTOUT -> RenderTypes.entityCutout(texture);
            case ENTITY_CUTOUT_Z_OFFSET -> RenderTypes.entityCutoutZOffset(texture);
            case ENTITY_CUTOUT_DISSOLVE ->
                RenderTypes.entityCutoutDissolve(
                        texture, fixture.auxiliaryResources().getFirst());
            case ENTITY_TRANSLUCENT -> RenderTypes.entityTranslucent(texture);
            case ENTITY_TRANSLUCENT_CULL -> RenderTypes.entityTranslucentCullItemTarget(texture);
            case ENTITY_TRANSLUCENT_EMISSIVE -> RenderTypes.entityTranslucentEmissive(texture);
            case ARMOR_CUTOUT -> RenderTypes.armorCutoutNoCull(texture);
            case ARMOR_DECAL -> Sheets.armorTrimsSheet(true);
            case ARMOR_TRANSLUCENT -> RenderTypes.armorTranslucent(texture);
            case BANNER_PATTERN -> RenderTypes.bannerPattern(texture);
            case BREEZE_WIND -> RenderTypes.breezeWind(texture, animation, animation * .5f);
            case ENERGY_SWIRL -> RenderTypes.energySwirl(texture, animation, animation * .5f);
            case EYES -> RenderTypes.eyes(texture);
            case GLINT -> RenderTypes.entityGlint();
            case OUTLINE_CULL -> RenderTypes.entityCutoutCull(texture).outline().orElseThrow();
            case OUTLINE_NO_CULL -> RenderTypes.outline(texture);
            case CRUMBLING -> RenderTypes.crumbling(texture);
            case WATER_MASK -> RenderTypes.waterMask();
        };
    }

    public static String abort() {
        if (!active()) return "[Threadium Differential] Abort rejected: no active run.";
        phase = Phase.ABORTED;
        resultState = "ABORTED";
        failure = "aborted by user";
        writeResult();
        cleanupGpu();
        DifferentialExecutionScope.reset();
        notify(Minecraft.getInstance(), "[Threadium Differential] " + pipelineName() + ": ABORTED");
        return "[Threadium Differential] Aborted. Result: " + resultPath;
    }

    public static List<String> status() {
        return List.of(
                "[Threadium Differential] pipeline=" + pipelineName() + ", phase=" + phase + ", result=" + resultState,
                "referenceSuccess=" + referenceSuccess + ", candidateSuccess=" + candidateSuccess
                        + ", referenceInterceptionBypassed="
                        + (referenceScope != null && referenceScope.observed() > 0),
                "candidateExpectedDescriptorEncountered=" + (candidateScope != null && candidateScope.observed() > 0)
                        + ", candidateAccepted=" + (candidateScope != null && candidateScope.accepted() > 0)
                        + ", fallback=" + (candidateScope == null ? 0 : candidateScope.fallback()) + ", resultPath="
                        + (resultPath == null ? "pending" : resultPath)
                        + (failure == null ? "" : ", reason=" + failure));
    }

    private static String pipelineName() {
        return currentFixture == null ? "none" : currentFixture.canonical();
    }

    public static boolean active() {
        return switch (phase) {
            case PREPARING, REFERENCE_RENDERED, CANDIDATE_RENDERED, READBACK_PENDING, COMPARING -> true;
            default -> false;
        };
    }

    public static boolean replaying() {
        return replaying;
    }

    public static void renderTail() {
        if (!active() || replaying) return;
        if (++pendingFrames > TIMEOUT_FRAMES) {
            fail("fixture/readback timeout");
            return;
        }
        if (phase == Phase.PREPARING && source != null) execute();
    }

    public static void tick() {
        if (active() && ++pendingFrames > TIMEOUT_FRAMES) fail("readback timeout");
        if (suiteActive && !active() && (phase == Phase.COMPLETE || phase == Phase.FAILED || phase == Phase.ABORTED)) {
            if (!suiteTerminalRecorded) {
                suiteResults.merge(
                        pipelineName(),
                        resultState,
                        (oldValue, newValue) -> "PASS".equals(oldValue) && "PASS".equals(newValue)
                                ? "PASS"
                                : !"PASS".equals(oldValue) ? oldValue : newValue);
                suiteSampleResults.add(new SampleResult(currentSample, resultState, resultPath, failure));
                suiteTerminalRecorded = true;
            }
            if (phase == Phase.ABORTED) {
                suiteActive = false;
                suitePending.clear();
            } else startNextSuiteMember(Minecraft.getInstance());
        }
    }

    private static void finishSuite() {
        suiteActive = false;
        try {
            Path dir = Path.of("pipeline-differential");
            Files.createDirectories(dir);
            suiteResultPath = dir.resolve(Instant.now().toString().replace(':', '-') + "_" + suiteLabel + ".json");
            StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"suite\": \"")
                    .append(suiteLabel)
                    .append("\",\n  \"samples\": [\n");
            for (int i = 0; i < suiteSampleResults.size(); i++) {
                SampleResult sample = suiteSampleResults.get(i);
                json.append("    {\"pipeline\":\"")
                        .append(sample.sample().pipeline())
                        .append("\",\"animationTime\":")
                        .append(sample.sample().animation())
                        .append(",\"cameraYaw\":")
                        .append(sample.sample().cameraYaw())
                        .append(",\"result\":\"")
                        .append(sample.result())
                        .append("\",\"report\":")
                        .append(
                                sample.path() == null
                                        ? "null"
                                        : "\"" + escape(sample.path().toString()) + "\"")
                        .append(",\"failureReason\":")
                        .append(sample.failure() == null ? "null" : "\"" + escape(sample.failure()) + "\"")
                        .append('}')
                        .append(i + 1 < suiteSampleResults.size() ? ",\n" : "\n");
            }
            json.append("  ],\n  \"results\": {\n");
            int index = 0;
            for (var entry : suiteResults.entrySet())
                json.append("    \"")
                        .append(entry.getKey())
                        .append("\": \"")
                        .append(entry.getValue())
                        .append("\"")
                        .append(++index < suiteResults.size() ? "," : "")
                        .append('\n');
            json.append("  },\n  \"result\": \"")
                    .append(
                            suiteResults.size()
                                                            == DifferentialFixtureRegistry.fixtures()
                                                                    .size()
                                                    && suiteResults.values().stream()
                                                            .allMatch("PASS"::equals)
                                            || !"all".equals(suiteLabel)
                                                    && suiteResults.values().stream()
                                                            .allMatch("PASS"::equals)
                                    ? "PASS"
                                    : "FAIL")
                    .append("\"\n}\n");
            Files.writeString(suiteResultPath, json);
        } catch (IOException e) {
            ThreadiumClient.LOGGER.error("Failed to write differential suite result", e);
        }
        notify(
                Minecraft.getInstance(),
                "[Threadium Differential] " + suiteLabel + " complete. Result: " + suiteResultPath);
    }

    public static void invalidate(String reason) {
        if (active()) fail(reason);
        DifferentialExecutionScope.reset();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void execute() {
        RenderSystem.assertOnRenderThread();
        ModelPartRenderService service = ModelPartRenderService.get();
        if (service == null) {
            fail("backend unavailable during execution");
            return;
        }
        replaying = true;
        PoseBits original = null;
        try {
            referenceTarget =
                    new TextureTarget("Threadium differential reference", WIDTH, HEIGHT, true, GpuFormat.RGBA8_UNORM);
            candidateTarget =
                    new TextureTarget("Threadium differential candidate", WIDTH, HEIGHT, true, GpuFormat.RGBA8_UNORM);
            referenceStaging = new StagedVertexBuffer(() -> "Threadium differential reference", 262144);
            candidateStaging = new StagedVertexBuffer(() -> "Threadium differential candidate", 262144);
            var model = source.model();
            original = PoseBits.capture(model.root());
            viewMatrixBits = matrixBits(RenderSystem.getModelViewMatrixCopy());
            previousProjectionBinding = RenderSystem.getProjectionMatrixBuffer();
            previousProjectionType = RenderSystem.getProjectionType();
            if (previousProjectionBinding == null) throw new IllegalStateException("projection binding unavailable");
            fixtureProjectionBuffer = new ProjectionMatrixBuffer("Threadium differential fixture");
            Projection fixtureProjection = new Projection();
            fixtureProjection.setupPerspective(0.05f, 100.0f, 60.0f, WIDTH, HEIGHT);
            projectionBinding = fixtureProjectionBuffer.getBuffer(fixtureProjection);
            RenderSystem.setProjectionMatrix(projectionBinding, ProjectionType.PERSPECTIVE);
            Matrix4f rootMatrix = new Matrix4f(RenderSystem.getModelViewMatrixCopy())
                    .invert()
                    .translate(0.0f, -0.75f, -4.0f)
                    .rotateY((float) Math.toRadians(currentSample.cameraYaw()));
            com.mojang.blaze3d.vertex.PoseStack rootStack = new com.mojang.blaze3d.vertex.PoseStack();
            rootStack.last().mulPose(rootMatrix);
            var pose = rootStack.last().copy();
            var fixture = new ModelFeatureRenderer.Submit(
                    source.renderType(),
                    pose,
                    model,
                    source.state(),
                    source.lightCoords(),
                    source.overlayCoords(),
                    source.tintedColor(),
                    source.sprite(),
                    source.sheetedDecalPose() == null ? null : pose);
            List<ModelFeatureRenderer.Submit<?>> submits =
                    switch (currentFixture.material()) {
                        case ARMOR_DECAL ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.armorCutoutNoCull(Identifier.withDefaultNamespace(
                                                    "textures/entity/equipment/humanoid/iron.png")),
                                            pose,
                                            source,
                                            null),
                                    fixture);
                        case ENTITY_TRANSLUCENT_EMISSIVE ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.entityCutout(Identifier.withDefaultNamespace(
                                                    "textures/entity/warden/warden.png")),
                                            pose,
                                            source,
                                            null),
                                    fixture);
                        case EYES ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.entityCutout(Identifier.withDefaultNamespace(
                                                    "textures/entity/spider/spider.png")),
                                            pose,
                                            source,
                                            null),
                                    fixture);
                        case BANNER_PATTERN ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.entityCutout(Sheets.BANNER_SHEET),
                                            pose,
                                            source,
                                            Minecraft.getInstance()
                                                    .getAtlasManager()
                                                    .get(Sheets.BANNER_BASE)),
                                    fixture);
                        case CRUMBLING ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.entitySolid(currentFixture.texture()), pose, source, null),
                                    fixture);
                        case GLINT ->
                            List.of(
                                    layeredSubmit(
                                            RenderTypes.armorCutoutNoCull(Identifier.withDefaultNamespace(
                                                    "textures/entity/equipment/humanoid/iron.png")),
                                            pose,
                                            source,
                                            null),
                                    fixture);
                        default -> List.of(fixture);
                    };
            if (currentFixture.sorted()) {
                ArrayList<ModelFeatureRenderer.Submit<?>> overlapping = new ArrayList<>(submits.size() * 2);
                PoseStack secondStack = new PoseStack();
                secondStack.last().mulPose(new Matrix4f(rootMatrix).translate(0.35f, 0.0f, 0.45f));
                PoseStack.Pose secondPose = secondStack.last().copy();
                // Keep equal RenderTypes adjacent so both Vanilla's staged buffer
                // and Threadium's sorted group see one two-instance sort scope.
                for (ModelFeatureRenderer.Submit<?> submit : submits) {
                    overlapping.add(submit);
                    overlapping.add(duplicateSubmit(submit, secondPose));
                }
                submits = List.copyOf(overlapping);
            }
            metricsBefore = service.differentialMetrics();
            PoseBits referenceBits = prepareAndRender(submits, true);
            referenceSuccess = true;
            phase = Phase.REFERENCE_RENDERED;
            original.restore(model.root());
            PoseBits candidateBits = prepareAndRender(submits, false);
            candidateSuccess = true;
            phase = Phase.CANDIDATE_RENDERED;
            poseBitsIdentical = referenceBits.equals(candidateBits);
            if (!poseBitsIdentical)
                throw new IllegalStateException("reference/candidate raw ModelPart pose bits differ");
            original.restore(model.root());
            metricsAfter = service.differentialMetrics();
            scheduleReadbacks();
            phase = Phase.READBACK_PENDING;
            pendingFrames = 0;
            notify(Minecraft.getInstance(), "[Threadium Differential] " + pipelineName() + ": readback pending");
        } catch (Throwable t) {
            if (original != null)
                try {
                    original.restore(source.model().root());
                } catch (Throwable ignored) {
                }
            fail("execution failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (previousProjectionBinding != null) {
                RenderSystem.setProjectionMatrix(previousProjectionBinding, previousProjectionType);
                previousProjectionBinding = null;
                previousProjectionType = null;
            }
            replaying = false;
            DifferentialExecutionScope.reset();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelFeatureRenderer.Submit<?> layeredSubmit(
            RenderType type,
            PoseStack.Pose pose,
            ModelFeatureRenderer.Submit<?> source,
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return new ModelFeatureRenderer.Submit(
                type,
                pose,
                source.model(),
                source.state(),
                source.lightCoords(),
                source.overlayCoords(),
                source.tintedColor(),
                sprite,
                null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelFeatureRenderer.Submit<?> duplicateSubmit(
            ModelFeatureRenderer.Submit<?> source, PoseStack.Pose pose) {
        return new ModelFeatureRenderer.Submit(
                source.renderType(),
                pose,
                source.model(),
                source.state(),
                source.lightCoords(),
                source.overlayCoords(),
                source.tintedColor(),
                source.sprite(),
                source.sheetedDecalPose() == null ? null : pose);
    }

    private static PoseBits prepareAndRender(List<ModelFeatureRenderer.Submit<?>> fixtures, boolean reference) {
        if (!Arrays.equals(viewMatrixBits, matrixBits(RenderSystem.getModelViewMatrixCopy()))
                || projectionBinding != RenderSystem.getProjectionMatrixBuffer())
            throw new IllegalStateException("camera/projection binding changed between differential passes");
        StagedVertexBuffer staging = reference ? referenceStaging : candidateStaging;
        TextureTarget target = reference ? referenceTarget : candidateTarget;
        ModelFeatureRenderer renderer = new ModelFeatureRenderer();
        FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, staging);
        DifferentialExecutionScope.Mode mode = reference
                ? DifferentialExecutionScope.Mode.REFERENCE_BYPASS
                : DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT;
        DifferentialExecutionScope.Snapshot scope;
        try (var token = DifferentialExecutionScope.enter(mode, currentFixture.canonical())) {
            renderer.prepareGroup(context, fixtures, false);
            scope = token.result();
        }
        PoseBits bits = PoseBits.capture(fixtures.getFirst().model().root());
        staging.upload();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(
                target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0.0);
        GpuTextureView oldColor = RenderSystem.outputColorTextureOverride,
                oldDepth = RenderSystem.outputDepthTextureOverride;
        try {
            RenderSystem.outputColorTextureOverride = target.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
            renderer.executeGroup(context, 0, fixtures, false);
            renderer.finishExecute(context);
        } finally {
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }
        if (reference) referenceScope = scope;
        else candidateScope = scope;
        return bits;
    }

    private static void scheduleReadbacks() {
        readbacks = new DifferentialReadbackTracker();
        referenceDepthReadTarget = encodeDepth(referenceTarget, "reference");
        candidateDepthReadTarget = encodeDepth(candidateTarget, "candidate");
        schedule(
                referenceTarget.getColorTexture(),
                DifferentialReadbackTracker.Slot.REFERENCE_COLOR,
                false,
                colorsReference,
                null);
        schedule(
                candidateTarget.getColorTexture(),
                DifferentialReadbackTracker.Slot.CANDIDATE_COLOR,
                false,
                colorsCandidate,
                null);
        schedule(
                referenceDepthReadTarget.getColorTexture(),
                DifferentialReadbackTracker.Slot.REFERENCE_DEPTH,
                true,
                null,
                depthReference);
        schedule(
                candidateDepthReadTarget.getColorTexture(),
                DifferentialReadbackTracker.Slot.CANDIDATE_DEPTH,
                true,
                null,
                depthCandidate);
    }

    private static TextureTarget encodeDepth(TextureTarget source, String label) {
        TextureTarget output = new TextureTarget(
                "Threadium differential " + label + " depth readback", WIDTH, HEIGHT, false, GpuFormat.R32_FLOAT);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(output.getColorTexture(), new Vector4f(1, 0, 0, 1));
        try (var pass = encoder.createRenderPass(
                () -> "Threadium differential depth encode", output.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(DEPTH_COPY_PIPELINE);
            pass.bindTexture(
                    "InSampler",
                    source.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        return output;
    }

    private static void schedule(
            com.mojang.blaze3d.textures.GpuTexture texture,
            DifferentialReadbackTracker.Slot slot,
            boolean depth,
            int[] colors,
            float[] depths) {
        long bytes = (long) PIXELS * texture.getFormat().blockSize();
        GpuBuffer buffer = RenderSystem.getDevice()
                .createBuffer(
                        () -> "Threadium differential readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                        bytes);
        readbackBuffers.add(buffer);
        RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToBuffer(
                        texture,
                        buffer,
                        0L,
                        () -> {
                            try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                                ByteBuffer data = view.data();
                                if (data.remaining() < bytes)
                                    throw new IllegalStateException("unexpected readback size");
                                for (int y = 0; y < HEIGHT; y++)
                                    for (int x = 0; x < WIDTH; x++) {
                                        int sourceIndex = x + y * WIDTH,
                                                targetIndex =
                                                        DifferentialReadbackTracker.normalizedIndex(
                                                                x, y, WIDTH, HEIGHT);
                                        if (depth) {
                                            float value = data.getFloat(sourceIndex * 4);
                                            if (!Float.isFinite(value))
                                                throw new IllegalStateException("non-finite depth");
                                            depths[targetIndex] = value;
                                        } else colors[targetIndex] = data.getInt(sourceIndex * 4);
                                    }
                            } catch (Throwable t) {
                                Minecraft.getInstance()
                                        .execute(() -> fail("readback decode failed: " + t.getMessage()));
                            } finally {
                                if (!buffer.isClosed()) buffer.close();
                                Minecraft.getInstance().execute(() -> {
                                    readbackBuffers.remove(buffer);
                                    readbackComplete(slot);
                                });
                            }
                        },
                        0);
    }

    private static void readbackComplete(DifferentialReadbackTracker.Slot slot) {
        if (phase != Phase.READBACK_PENDING) return;
        if (readbacks.complete(slot) && readbacks.ready()) compare();
    }

    private static void compare() {
        phase = Phase.COMPARING;
        try {
            colorMetrics = DifferentialImageComparator.compare(
                    colorsReference,
                    colorsCandidate,
                    null,
                    null,
                    new DifferentialImageComparator.Tolerance(currentFixture.colorChannelTolerance(), 0, false));
            int[] empty = new int[PIXELS];
            depthMetrics = DifferentialImageComparator.compare(
                    empty,
                    empty,
                    depthReference,
                    depthCandidate,
                    new DifferentialImageComparator.Tolerance(0, currentFixture.depthTolerance(), true));
            long covered = 0, depthWritten = 0;
            for (int i = 0; i < PIXELS; i++) {
                if ((colorsReference[i] >>> 24) != 0) covered++;
                if (depthReference[i] != 0.0f) depthWritten++;
            }
            switch (currentFixture.targetContract()) {
                case DEPTH_ONLY -> {
                    if (depthWritten == 0)
                        throw new IllegalStateException(pipelineName() + " fixture produced no covered depth pixels");
                }
                case OUTLINE -> {
                    if (covered == 0)
                        throw new IllegalStateException(pipelineName() + " fixture produced no outline pixels");
                }
                case COLOR_DEPTH -> {
                    if (covered == 0 || depthWritten == 0)
                        throw new IllegalStateException(
                                pipelineName() + " fixture produced no covered color/depth pixels");
                }
            }
            long backend = metricsAfter.backendFailures() - metricsBefore.backendFailures(),
                    submission = metricsAfter.submissionFailures() - metricsBefore.submissionFailures();
            boolean isolated = referenceScope != null
                    && referenceScope.observed() > 0
                    && candidateScope != null
                    && candidateScope.suppressions() == candidateScope.totalAccepted()
                    && candidateScope.totalFallback() == 0;
            var evidence = new DifferentialResultPolicy.Evidence(
                    true,
                    true,
                    referenceSuccess,
                    candidateSuccess,
                    isolated,
                    poseBitsIdentical,
                    candidateScope == null ? 0 : candidateScope.observed(),
                    candidateScope == null ? 0 : candidateScope.accepted(),
                    candidateScope == null ? 0 : candidateScope.fallback(),
                    backend,
                    submission,
                    colorMetrics,
                    depthMetrics);
            DifferentialResultPolicy.State result = DifferentialResultPolicy.classify(evidence);
            long sortedCollected = metricsAfter.sortedQuadsCollected() - metricsBefore.sortedQuadsCollected(),
                    sortedSubmitted = metricsAfter.sortedQuadsSubmitted() - metricsBefore.sortedQuadsSubmitted();
            if (currentFixture.sorted() && (sortedCollected == 0 || sortedSubmitted != sortedCollected))
                result = DifferentialResultPolicy.State.FAIL;
            resultState = result.name();
            phase = result == DifferentialResultPolicy.State.PASS ? Phase.COMPLETE : Phase.FAILED;
            if (result != DifferentialResultPolicy.State.PASS)
                failure = candidateScope != null && candidateScope.fallback() > 0
                        ? "candidate fallback: " + candidateScope.fallbackReason()
                        : currentFixture.sorted() && (sortedCollected == 0 || sortedSubmitted != sortedCollected)
                                ? "sorted quad coverage mismatch: collected=" + sortedCollected + ", submitted="
                                        + sortedSubmitted
                                : metricsAfter.drawCommands() == metricsBefore.drawCommands()
                                        ? "candidate accepted but issued no draw command"
                                        : "automatic comparison or acceptance policy failed";
            writeResult();
            if (result != DifferentialResultPolicy.State.PASS
                    || Boolean.getBoolean("threadium.writeDifferentialPassArtifacts")) writeArtifacts();
            notify(
                    Minecraft.getInstance(),
                    "[Threadium Differential] " + pipelineName() + ": " + resultState + " | Result: " + resultPath);
        } catch (Throwable t) {
            fail("comparison failed: " + t.getMessage());
        } finally {
            cleanupGpu();
        }
    }

    private static void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.ABORTED) return;
        failure = reason;
        resultState = "HARNESS_ERROR";
        phase = Phase.FAILED;
        writeResult();
        writeArtifacts();
        cleanupGpu();
        DifferentialExecutionScope.reset();
        notify(Minecraft.getInstance(), "[Threadium Differential] " + pipelineName() + ": HARNESS_ERROR | " + reason);
    }

    private static String failCommand(String reason) {
        fail(reason);
        return "[Threadium Differential] Run rejected: " + reason;
    }

    private static void cleanup() {
        cleanupGpu();
        DifferentialExecutionScope.reset();
        referenceSuccess = candidateSuccess = poseBitsIdentical = false;
        referenceScope = candidateScope = null;
        colorMetrics = depthMetrics = null;
        metricsBefore = metricsAfter = null;
        viewMatrixBits = null;
        projectionBinding = null;
        Arrays.fill(colorsReference, 0);
        Arrays.fill(colorsCandidate, 0);
        Arrays.fill(depthReference, 0);
        Arrays.fill(depthCandidate, 0);
    }

    private static void cleanupGpu() {
        for (GpuBuffer buffer : List.copyOf(readbackBuffers)) if (!buffer.isClosed()) buffer.close();
        readbackBuffers.clear();
        if (referenceStaging != null) {
            referenceStaging.close();
            referenceStaging = null;
        }
        if (candidateStaging != null) {
            candidateStaging.close();
            candidateStaging = null;
        }
        if (referenceTarget != null) {
            referenceTarget.destroyBuffers();
            referenceTarget = null;
        }
        if (candidateTarget != null) {
            candidateTarget.destroyBuffers();
            candidateTarget = null;
        }
        if (referenceDepthReadTarget != null) {
            referenceDepthReadTarget.destroyBuffers();
            referenceDepthReadTarget = null;
        }
        if (candidateDepthReadTarget != null) {
            candidateDepthReadTarget.destroyBuffers();
            candidateDepthReadTarget = null;
        }
        if (fixtureProjectionBuffer != null) {
            fixtureProjectionBuffer.close();
            fixtureProjectionBuffer = null;
        }
    }

    private static void notify(Minecraft client, String message) {
        if (client != null && client.player != null) client.player.sendSystemMessage(Component.literal(message));
        ThreadiumClient.LOGGER.info(message);
    }

    private static void writeResult() {
        try {
            Path dir = Path.of("pipeline-differential");
            Files.createDirectories(dir);
            resultPath = dir.resolve(Instant.now().toString().replace(':', '-') + "_" + pipelineName() + ".json");
            Files.writeString(
                    resultPath,
                    json().replace("\"pipeline\": \"entity_solid\"", "\"pipeline\": \"" + pipelineName() + "\""));
        } catch (IOException e) {
            failure = (failure == null ? "" : failure + "; ") + "result write failed: " + e.getMessage();
        }
    }

    private static String json() {
        long backend = delta(ModelPartRenderService.DifferentialMetrics::backendFailures),
                submission = delta(ModelPartRenderService.DifferentialMetrics::submissionFailures),
                sortedInstances = delta(ModelPartRenderService.DifferentialMetrics::sortedInstances),
                sortedCollected = delta(ModelPartRenderService.DifferentialMetrics::sortedQuadsCollected),
                sortedSubmitted = delta(ModelPartRenderService.DifferentialMetrics::sortedQuadsSubmitted),
                sortedGroups = delta(ModelPartRenderService.DifferentialMetrics::sortedGroups);
        return "{\n  \"schemaVersion\": 2,\n  \"minecraftVersion\": \"26.2\",\n  \"threadiumVersion\": \"0.1.0-SNAPSHOT\",\n  \"timestamp\": \""
                + Instant.now() + "\",\n  \"device\": \""
                + escape(RenderSystem.getDevice().getDeviceInfo().toString())
                + "\",\n  \"pipeline\": \"entity_solid\",\n  \"fixtureModelClass\": \"" + escape(fixtureModel)
                + "\",\n  \"fixtureTexture\": \"" + fixtureTexture + "\",\n  \"renderTypeFactory\": \"RenderTypes."
                + currentFixture.material().name().toLowerCase(java.util.Locale.ROOT) + "\",\n  \"animationTime\": "
                + (currentSample == null ? 0 : currentSample.animation()) + ", \"cameraYaw\": "
                + (currentSample == null ? 0 : currentSample.cameraYaw())
                + ",\n  \"width\": 256, \"height\": 256, \"colorFormat\": \"RGBA8_UNORM\", \"depthFormat\": \"D32_FLOAT\", \"rowPitch\": 1024,\n  \"referenceSuccess\": "
                + referenceSuccess + ", \"candidateSuccess\": " + candidateSuccess
                + ",\n  \"referenceInterceptionBypassed\": " + (referenceScope != null && referenceScope.observed() > 0)
                + ", \"candidateExpectedDescriptorEncountered\": "
                + (candidateScope != null && candidateScope.observed() > 0) + ",\n  \"candidateAccepted\": "
                + (candidateScope != null && candidateScope.accepted() > 0) + ", \"acceptedCount\": "
                + (candidateScope == null ? 0 : candidateScope.accepted()) + ", \"fallback\": "
                + (candidateScope == null ? 0 : candidateScope.fallback()) + ",\n  \"backendFailures\": " + backend
                + ", \"submissionFailures\": " + submission + ",\n  \"sortedInstances\": " + sortedInstances
                + ", \"sortedQuadsCollected\": " + sortedCollected + ", \"sortedQuadsSubmitted\": " + sortedSubmitted
                + ", \"sortedGroups\": " + sortedGroups + ",\n  \"color\": " + metricJson(colorMetrics)
                + ",\n  \"depth\": " + metricJson(depthMetrics) + ",\n  \"result\": \"" + resultState
                + "\", \"failureReason\": " + (failure == null ? "null" : "\"" + escape(failure) + "\"") + "\n}\n";
    }

    private static long delta(java.util.function.ToLongFunction<ModelPartRenderService.DifferentialMetrics> getter) {
        return metricsBefore == null || metricsAfter == null
                ? 0
                : getter.applyAsLong(metricsAfter) - getter.applyAsLong(metricsBefore);
    }

    private static String metricJson(DifferentialImageComparator.Metrics m) {
        if (m == null) return "null";
        return "{\"pixelsCompared\":" + m.pixelsCompared() + ",\"matchingPixels\":" + m.matchingPixels()
                + ",\"differingPixels\":" + m.differingPixels() + ",\"coverageMaskDifferences\":"
                + m.coverageMaskDifferences() + ",\"maximumRedDifference\":" + m.maximumRedDifference()
                + ",\"maximumGreenDifference\":" + m.maximumGreenDifference() + ",\"maximumBlueDifference\":"
                + m.maximumBlueDifference() + ",\"maximumAlphaDifference\":" + m.maximumAlphaDifference()
                + ",\"meanAbsoluteColorDifference\":" + m.meanAbsoluteColorDifference() + ",\"depthPixelsCompared\":"
                + m.depthPixelsCompared() + ",\"depthDifferences\":" + m.depthDifferences()
                + ",\"maximumDepthDifference\":" + m.maximumDepthDifference() + "}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static int[] matrixBits(Matrix4f matrix) {
        int[] bits = new int[16];
        float[] values = new float[16];
        matrix.get(values);
        for (int i = 0; i < 16; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }

    private static void writeArtifacts() {
        if (resultPath == null) return;

        try {
            Path base = resultPath.resolveSibling(
                    resultPath.getFileName().toString().replace(".json", ""));

            writePng(base.resolveSibling(base.getFileName() + "_reference.png"), colorsReference);
            writePng(base.resolveSibling(base.getFileName() + "_candidate.png"), colorsCandidate);

            int[] rgbDifference = new int[PIXELS];
            int[] alphaDifference = new int[PIXELS];
            int[] combinedDifference = new int[PIXELS];

            for (int i = 0; i < PIXELS; i++) {
                int reference = colorsReference[i];
                int candidate = colorsCandidate[i];

                // Readback integers are passed directly to NativeImage#setPixelABGR.
                int referenceAlpha = reference >>> 24 & 255;
                int referenceBlue = reference >>> 16 & 255;
                int referenceGreen = reference >>> 8 & 255;
                int referenceRed = reference & 255;

                int candidateAlpha = candidate >>> 24 & 255;
                int candidateBlue = candidate >>> 16 & 255;
                int candidateGreen = candidate >>> 8 & 255;
                int candidateRed = candidate & 255;

                int redDifference = Math.abs(referenceRed - candidateRed);
                int greenDifference = Math.abs(referenceGreen - candidateGreen);
                int blueDifference = Math.abs(referenceBlue - candidateBlue);
                int alphaChannelDifference = Math.abs(referenceAlpha - candidateAlpha);

                int maximumRgbDifference = Math.max(redDifference, Math.max(greenDifference, blueDifference));

                rgbDifference[i] = opaqueGrayscale(maximumRgbDifference);
                alphaDifference[i] = opaqueGrayscale(alphaChannelDifference);
                combinedDifference[i] = opaqueGrayscale(Math.max(maximumRgbDifference, alphaChannelDifference));
            }

            writePng(base.resolveSibling(base.getFileName() + "_rgb_difference.png"), rgbDifference);
            writePng(base.resolveSibling(base.getFileName() + "_alpha_difference.png"), alphaDifference);
            writePng(base.resolveSibling(base.getFileName() + "_difference.png"), combinedDifference);
        } catch (IOException e) {
            ThreadiumClient.LOGGER.warn("Failed to write differential artifacts", e);
        }
    }

    private static int opaqueGrayscale(int intensity) {
        return 0xff000000 | intensity << 16 | intensity << 8 | intensity;
    }

    private static void writePng(Path path, int[] pixels) throws IOException {
        try (var image = new com.mojang.blaze3d.platform.NativeImage(WIDTH, HEIGHT, false)) {
            for (int y = 0; y < HEIGHT; y++)
                for (int x = 0; x < WIDTH; x++) image.setPixelABGR(x, y, pixels[x + y * WIDTH]);
            image.writeToFile(path);
        }
    }

    private record PoseBits(int[] values) {
        static PoseBits capture(ModelPart root) {
            List<ModelPart> parts = root.getAllParts();
            int[] values = new int[parts.size() * 11];
            int i = 0;
            for (ModelPart p : parts) {
                values[i++] = Float.floatToRawIntBits(p.x);
                values[i++] = Float.floatToRawIntBits(p.y);
                values[i++] = Float.floatToRawIntBits(p.z);
                values[i++] = Float.floatToRawIntBits(p.xRot);
                values[i++] = Float.floatToRawIntBits(p.yRot);
                values[i++] = Float.floatToRawIntBits(p.zRot);
                values[i++] = Float.floatToRawIntBits(p.xScale);
                values[i++] = Float.floatToRawIntBits(p.yScale);
                values[i++] = Float.floatToRawIntBits(p.zScale);
                values[i++] = p.visible ? 1 : 0;
                values[i++] = p.skipDraw ? 1 : 0;
            }
            return new PoseBits(values);
        }

        void restore(ModelPart root) {
            List<ModelPart> parts = root.getAllParts();
            if (values.length != parts.size() * 11) throw new IllegalStateException("model topology changed");
            int i = 0;
            for (ModelPart p : parts) {
                p.x = Float.intBitsToFloat(values[i++]);
                p.y = Float.intBitsToFloat(values[i++]);
                p.z = Float.intBitsToFloat(values[i++]);
                p.xRot = Float.intBitsToFloat(values[i++]);
                p.yRot = Float.intBitsToFloat(values[i++]);
                p.zRot = Float.intBitsToFloat(values[i++]);
                p.xScale = Float.intBitsToFloat(values[i++]);
                p.yScale = Float.intBitsToFloat(values[i++]);
                p.zScale = Float.intBitsToFloat(values[i++]);
                p.visible = values[i++] != 0;
                p.skipDraw = values[i++] != 0;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PoseBits b && Arrays.equals(values, b.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    private record Sample(String pipeline, float animation, float cameraYaw, int index, int total) {}

    private record SampleResult(Sample sample, String result, Path path, String failure) {}
}
