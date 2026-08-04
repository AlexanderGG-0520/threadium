package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Production ModelPart backend entirely expressed through Blaze3D buffers and render passes. */
public final class Blaze3dModelPartBackend implements ModelPartGpuBackend {
    private static final VertexFormat MESH_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("BoneIndex", GpuFormat.R32_FLOAT)
            .build();
    private static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
            .addAttribute("RootMatrix", GpuFormat.RGBA32_FLOAT, 4)
            .addAttribute("BoneBase", GpuFormat.R32_SINT)
            .addAttribute("PackedLight", GpuFormat.R32_SINT)
            .addAttribute("PackedOverlay", GpuFormat.R32_SINT)
            .addAttribute("DecalBase", GpuFormat.R32_SINT)
            .addAttribute("Tint", GpuFormat.RGBA32_FLOAT)
            .addAttribute("SpriteUvTransform", GpuFormat.RGBA32_FLOAT)
            .build();
    private static final BindGroupLayout BONES = BindGroupLayout.builder()
            .withUniform("Bones", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .build();
    private static final BindGroupLayout DECALS = BindGroupLayout.builder()
            .withUniform("Decals", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .build();
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final List<RenderPipeline> SOURCES = ModelPartPipelineDescriptor.SOURCES;
    private static final boolean PROFILE_FLUSH = Boolean.getBoolean("threadium.modelpart.profileFlush");
    private static final boolean PROFILE_QUEUE = Boolean.getBoolean("threadium.modelpart.profileIntercept");
    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final int maxInstances, maxBones;
    private final boolean consolidate;
    private final ModelPartGpuMetrics metrics;
    private final QueuedModelPartArena queued;
    private final ArrayDeque<Group> groups = new ArrayDeque<>();
    private final Map<MeshHandle, Mesh> meshes = new java.util.HashMap<>();
    private final IdentityHashMap<RenderPipeline, RenderPipeline> pipelines = new IdentityHashMap<>();
    private final PipelineValidityTable<RenderPipeline> validity = new PipelineValidityTable<>();
    private final GroupLocalPreparedRenderTypeCache recentPrepared = new GroupLocalPreparedRenderTypeCache();
    private final RecentModelPartBatchKeyCache recentBatchKey = new RecentModelPartBatchKeyCache();
    private final IdentityHashMap<ModelPartBoneData, Integer> framePaletteOffsets = new IdentityHashMap<>();
    private final IdentityHashMap<ModelPartBoneData, Boolean> uploadedFramePalettes = new IdentityHashMap<>();
    private final ArrayList<PlannedDraw> plannedDraws = new ArrayList<>();
    private ModelPartBatchKey[] exactKeysBySourceIndex = new ModelPartBatchKey[0];
    private int[] packedIndexBySource = new int[0];
    private Object[] drawTableKeys = new Object[0];
    private PlannedDraw[] drawTableValues = new PlannedDraw[0];
    private int[] touchedDrawSlots = new int[0];
    private int touchedDrawSlotCount;
    private int activePlannedDrawCount;
    private int exactKeyCount;
    private int groupStart, frameBoneCount, frameDecalCount;
    private long epoch, groupId, generation;
    private GpuBuffer boneBuffer, instanceBuffer, decalBuffer;
    private ByteBuffer boneStaging, instanceStaging, decalStaging;
    private boolean loggedUnsupported, pipelinesNeedCompilation = true;

    private record Mesh(GpuBuffer vertices, GpuBuffer indices, float[] quadCenters, int[] quadBones) {}

    private record Group(int count, boolean strictlyOrdered) {}

    private static final class PlannedDraw implements Supplier<String> {
        private final InstanceSubmissionOrderPlanner planner = new InstanceSubmissionOrderPlanner();
        private int[] sourceIndices = new int[0];
        private int sourceCount;
        private RenderType type;
        private PreparedRenderType prepared;
        private boolean sorted;
        private Object labelPipeline;
        private boolean labelSorted;
        private String label;
        private InstanceSubmissionOrderPlanner.Plan submission;

        void reset(RenderType type, PreparedRenderType prepared) {
            this.type = type;
            this.prepared = prepared;
            this.sorted = type.sortOnUpload();
            if (labelPipeline != prepared.pipeline() || labelSorted != sorted) {
                labelPipeline = prepared.pipeline();
                labelSorted = sorted;
                label = (sorted ? "Threadium sorted ModelPart " : "Threadium ModelPart ") + labelPipeline;
            }
            sourceCount = 0;
        }

        void addSourceIndex(int sourceIndex) {
            if (sourceCount == sourceIndices.length) {
                int capacity = Math.max(sourceCount + 1, sourceIndices.length + (sourceIndices.length >> 1) + 1);
                sourceIndices = java.util.Arrays.copyOf(sourceIndices, capacity);
            }
            sourceIndices[sourceCount++] = sourceIndex;
        }

        @Override
        public String get() {
            return label;
        }

        void clear() {
            planner.clear();
            sourceCount = 0;
            type = null;
            prepared = null;
            sorted = false;
            labelPipeline = null;
            labelSorted = false;
            label = null;
            submission = null;
        }
    }

    public Blaze3dModelPartBackend(int maxInstances, int maxBones, boolean consolidate, ModelPartGpuMetrics metrics) {
        this.maxInstances = maxInstances;
        queued = new QueuedModelPartArena(maxInstances);
        this.maxBones = maxBones;
        this.consolidate = consolidate;
        this.metrics = metrics;
        validity.initialize(SOURCES, 0);
    }

    @Override
    public ModelPartBackendState state() {
        return states.state();
    }

    @Override
    public boolean ensureReady() {
        if (state().accepts()) {
            if (pipelinesNeedCompilation) {
                compilePipelines();
                return false;
            }
            return true;
        }
        if (state() != ModelPartBackendState.UNINITIALIZED || !RenderSystem.isOnRenderThread()) return false;
        states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);
        try {
            var device = RenderSystem.getDevice();
            boneBuffer = device.createBuffer(
                    () -> "Threadium ModelPart bones",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                    (long) maxBones * ModelPartLayouts.BONE_STRIDE);
            instanceBuffer = device.createBuffer(
                    () -> "Threadium ModelPart instances",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    (long) maxInstances * ModelPartLayouts.INSTANCE_STRIDE);
            decalBuffer = device.createBuffer(
                    () -> "Threadium ModelPart decals",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                    (long) maxInstances * ModelPartLayouts.BONE_STRIDE);
            boneStaging = MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxBones, ModelPartLayouts.BONE_STRIDE));
            instanceStaging =
                    MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxInstances, ModelPartLayouts.INSTANCE_STRIDE));
            decalStaging = MemoryUtil.memAlloc(ModelPartLayouts.BONE_STRIDE);
            compilePipelines();
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY);
            return true;
        } catch (Throwable failure) {
            fail();
            return false;
        }
    }

    private void compilePipelines() {
        if (!RenderSystem.isOnRenderThread()) return;
        var device = RenderSystem.getDevice();
        String backend = device.getDeviceInfo().toString();
        for (RenderPipeline source : SOURCES) {
            PipelineValidity current = validity.get(source, generation);
            if (current.state() != PipelineValidityState.UNINITIALIZED
                    && current.state() != PipelineValidityState.STALE) continue;
            RenderPipeline pipeline = pipelineFor(source);
            metrics.blaze3dPipelineCompileAttempts.increment();
            try {
                CompiledRenderPipeline compiled = device.precompilePipeline(pipeline);
                if (compiled == null) {
                    metrics.blaze3dPipelineValidityUnknown.increment();
                    validity.put(
                            source,
                            new PipelineValidity(
                                    PipelineValidityState.UNSUPPORTED_BACKEND,
                                    generation,
                                    "precompile returned no compiled pipeline"));
                    logInvalid(source, pipeline, backend, "validity unavailable");
                } else if (compiled.isValid()) {
                    metrics.blaze3dPipelineCompileValid.increment();
                    validity.put(source, new PipelineValidity(PipelineValidityState.VALID, generation, "valid"));
                    dev.alex.threadium.ThreadiumClient.LOGGER.info(
                            "Threadium ModelPart pipeline valid: source={}, pipeline={}, generation={}, backend={}",
                            source.getLocation(),
                            pipeline.getLocation(),
                            generation,
                            backend);
                } else {
                    metrics.blaze3dPipelineCompileInvalid.increment();
                    validity.put(
                            source,
                            new PipelineValidity(
                                    PipelineValidityState.INVALID, generation, "compiled pipeline is invalid"));
                    logInvalid(source, pipeline, backend, "compiled pipeline is invalid");
                }
            } catch (Throwable failure) {
                metrics.blaze3dPipelineCompileExceptions.increment();
                metrics.blaze3dPipelineCompileInvalid.increment();
                validity.put(
                        source, new PipelineValidity(PipelineValidityState.INVALID, generation, failure.toString()));
                logInvalid(source, pipeline, backend, failure.toString());
            }
        }
        pipelinesNeedCompilation = false;
    }

    private void logInvalid(RenderPipeline source, RenderPipeline pipeline, String backend, String reason) {
        dev.alex.threadium.ThreadiumClient.LOGGER.error(
                "Threadium ModelPart pipeline invalid: source={}, pipeline={}, generation={}, backend={}, reason={}",
                source.getLocation(),
                pipeline.getLocation(),
                generation,
                backend,
                reason);
    }

    @Override
    public PipelineValidity pipelineValidity(Object renderType, long requestedGeneration) {
        if (!(renderType instanceof RenderType type)) {
            metrics.blaze3dPipelineValidityUnknown.increment();
            return PipelineValidity.uninitialized(requestedGeneration);
        }
        RenderPipeline source = type.pipeline();
        PipelineValidity result = ModelPartPipelineDescriptor.from(source) != null
                ? validity.get(source, requestedGeneration)
                : new PipelineValidity(
                        PipelineValidityState.UNSUPPORTED_BACKEND, requestedGeneration, "unsupported source pipeline");
        switch (result.state()) {
            case INVALID -> metrics.blaze3dInvalidPipelineFallbacks.increment();
            case STALE -> metrics.blaze3dPipelineStaleFallbacks.increment();
            case UNSUPPORTED_BACKEND -> metrics.blaze3dUnsupportedBackendFallbacks.increment();
            case UNINITIALIZED -> metrics.blaze3dPipelineValidityUnknown.increment();
            default -> {}
        }
        return result;
    }

    @Override
    public void invalidatePipelines(long nextGeneration) {
        recentPrepared.clear();
        recentBatchKey.clear();
        validity.markStale(nextGeneration);
        generation = nextGeneration;
        pipelinesNeedCompilation = true;
        if (state().accepts() && RenderSystem.isOnRenderThread()) compilePipelines();
    }

    @Override
    public MeshHandle upload(ImmutableModelPartMesh mesh) {
        if (!state().accepts()) return null;
        ByteBuffer vertices = null, indices = null;
        try {
            vertices = MemoryUtil.memAlloc(mesh.vertices().length * Float.BYTES);
            for (float value : mesh.vertices()) vertices.putFloat(value);
            vertices.flip();
            indices = MemoryUtil.memAlloc(mesh.indices().length * Integer.BYTES);
            for (int value : mesh.indices()) indices.putInt(value);
            indices.flip();
            var device = RenderSystem.getDevice();
            GpuBuffer vertexBuffer = device.createBuffer(
                    () -> "Threadium ModelPart vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertices);
            GpuBuffer indexBuffer = device.createBuffer(
                    () -> "Threadium ModelPart indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indices);
            MeshHandle handle = new MeshHandle(
                    TOKENS.incrementAndGet(), 0, 0, mesh.indices().length, mesh.boneCount(), mesh.byteSize());
            meshes.put(handle, new Mesh(vertexBuffer, indexBuffer, mesh.quadCenters(), mesh.quadBones()));
            return handle;
        } catch (Throwable failure) {
            fail();
            return null;
        } finally {
            if (vertices != null) MemoryUtil.memFree(vertices);
            if (indices != null) MemoryUtil.memFree(indices);
        }
    }

    @Override
    public boolean queue(
            MeshHandle mesh,
            Object renderType,
            Matrix4fc rootPose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint,
            ModelPartUvTransform uvTransform,
            ModelPartDecalTransform decalTransform) {
        long precheckStart = queueProfileNow();
        if (!state().accepts() || !(renderType instanceof RenderType type)) return false;
        recentPrepared.observe(type);
        if (!meshes.containsKey(mesh) || queued.isFull()) return false;
        int count = bones.matrices().length / 28;
        Integer existingBase = framePaletteOffsets.get(bones);
        if (existingBase == null && frameBoneCount + count > maxBones) return false;
        RenderPipeline source = type.pipeline();
        ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(source);
        if (descriptor == null
                || descriptor.submissionPolicy()
                        == ModelPartPipelineDescriptor.SubmissionPolicy.SORTED_QUAD_STREAM
                        != type.sortOnUpload()
                || !validity.get(source, generation).permitsReplacement(generation)
                || pipelineFor(source, descriptor) == null
                || ((descriptor == ModelPartPipelineDescriptor.CRUMBLING) != (decalTransform != null))) {
            unsupported(source);
            return false;
        }
        long prepareStart = queueProfileNow();
        boolean reuseEligible = descriptor.allowsGroupLocalPreparedReuse();
        PreparedRenderType prepared =
                reuseEligible ? recentPrepared.reuse(type, descriptor, source, generation, groupId) : null;
        boolean reusedPrepared = prepared != null;
        if (reusedPrepared) {
            metrics.backendQueuePrepareReuseHits.increment();
        } else {
            recentPrepared.clear();
            metrics.backendQueuePrepareCalls.increment();
            try {
                prepared = type.prepare();
            } catch (Throwable unsupported) {
                metrics.blaze3dUnsupportedFallbacks.increment();
                return false;
            }
        }
        long preparedValidationStart = queueProfileNow();
        if (!reusedPrepared) {
            if (reuseEligible) {
                if (!recentPrepared.install(type, descriptor, source, prepared, generation, groupId)) {
                    unsupported(prepared.pipeline());
                    return false;
                }
            } else if (!preparedMatchesSource(prepared, source)) {
                recentPrepared.clear();
                unsupported(prepared.pipeline());
                return false;
            }
        }
        long instanceCaptureStart = queueProfileNow();
        int decalBase = decalTransform == null ? -1 : frameDecalCount++;
        ModelPartBatchKey key = recentBatchKey.getOrCreate(mesh, type, epoch, groupId);
        long insertionStart = queueProfileNow();
        queued.add(
                key,
                mesh,
                type,
                prepared,
                rootPose,
                bones,
                light,
                overlay,
                tint,
                uvTransform,
                decalTransform,
                decalBase);
        if (existingBase == null) {
            framePaletteOffsets.put(bones, frameBoneCount);
            frameBoneCount = Math.addExact(frameBoneCount, count);
        }
        long insertionEnd = queueProfileNow();
        if (PROFILE_QUEUE)
            metrics.recordBackendQueueTiming(
                    prepareStart - precheckStart,
                    preparedValidationStart - prepareStart,
                    instanceCaptureStart - preparedValidationStart,
                    insertionStart - instanceCaptureStart,
                    insertionEnd - insertionStart);
        return true;
    }

    private static long queueProfileNow() {
        return PROFILE_QUEUE ? System.nanoTime() : 0L;
    }

    static boolean preparedMatchesSource(PreparedRenderType prepared, RenderPipeline source) {
        return prepared.pipeline() == source;
    }

    RecentModelPartBatchKeyCache recentBatchKeyForTesting() {
        return recentBatchKey;
    }

    @Override
    public void beginFrame() {
        recentPrepared.clear();
        recentBatchKey.clear();
        if (!queued.isEmpty()) {
            fail();
            return;
        }
        framePaletteOffsets.clear();
        uploadedFramePalettes.clear();
        frameBoneCount = 0;
        frameDecalCount = 0;
    }

    private void unsupported(RenderPipeline pipeline) {
        metrics.blaze3dUnsupportedFallbacks.increment();
        if (!loggedUnsupported) {
            loggedUnsupported = true;
            dev.alex.threadium.ThreadiumClient.LOGGER.info(
                    "GPU ModelPart Blaze3D fallback: unsupported pipeline {}", pipeline);
        }
    }

    private RenderPipeline pipelineFor(RenderPipeline source) {
        return pipelineFor(source, ModelPartPipelineDescriptor.from(source));
    }

    private RenderPipeline pipelineFor(RenderPipeline source, ModelPartPipelineDescriptor descriptor) {
        RenderPipeline pipeline = pipelines.get(source);
        if (pipeline != null || descriptor == null) return pipeline;
        pipeline = createPipeline(descriptor);
        if (pipeline != null) pipelines.put(source, pipeline);
        return pipeline;
    }

    private static RenderPipeline createPipeline(ModelPartPipelineDescriptor descriptor) {
        if (descriptor == null) return null;
        RenderPipeline source = descriptor.source();
        var mode = descriptor.shaderMode();
        var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(
                        "threadium", "pipeline/modelpart_" + descriptor.canonicalName()))
                .withVertexShader(Identifier.fromNamespaceAndPath("threadium", "core/modelpart_blaze3d"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("threadium", "core/modelpart_blaze3d"))
                .withBindGroupLayout(BONES)
                .withBindGroupLayout(DECALS)
                .withColorTargetState(source.getColorTargetState())
                .withDepthStencilState(Optional.ofNullable(source.getDepthStencilState()))
                .withCull(source.isCull())
                .withVertexBinding(0, MESH_FORMAT)
                .withVertexBinding(1, INSTANCE_FORMAT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (mode != ModelPartPipelineDescriptor.ShaderMode.WATER_MASK) {
            if (descriptor.bindsOverlay() && descriptor.bindsLightmap())
                builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2);
            else if (descriptor.bindsOverlay())
                builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER1);
            else if (descriptor.bindsLightmap())
                builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2);
            else builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0);
        }
        if (mode.dissolveMask)
            builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DISSOLVE_MASK_SAMPLER);
        if (descriptor.perFaceLighting()) builder.withShaderDefine("THREADIUM_PER_FACE");
        if (descriptor.cardinalLighting()) builder.withShaderDefine("THREADIUM_CARDINAL");
        if (descriptor.alphaCutout()) builder.withShaderDefine("THREADIUM_ALPHA_CUTOUT");
        switch (mode) {
            case LIT_OVERLAY -> builder.withShaderDefine("THREADIUM_LIT_OVERLAY");
            case LIT_NO_OVERLAY -> builder.withShaderDefine("THREADIUM_LIT_NO_OVERLAY");
            case DISSOLVE -> builder.withShaderDefine("THREADIUM_DISSOLVE");
            case EMISSIVE_OVERLAY -> builder.withShaderDefine("THREADIUM_EMISSIVE_OVERLAY");
            case LIT_TEXTURE_MATRIX -> builder.withShaderDefine("THREADIUM_LIT_TEXTURE_MATRIX");
            case EMISSIVE_TEXTURE_MATRIX -> builder.withShaderDefine("THREADIUM_EMISSIVE_TEXTURE_MATRIX");
            case EMISSIVE -> builder.withShaderDefine("THREADIUM_EMISSIVE");
            case OUTLINE -> builder.withShaderDefine("THREADIUM_OUTLINE");
            case GLINT -> builder.withShaderDefine("THREADIUM_GLINT");
            case CRUMBLING -> builder.withShaderDefine("THREADIUM_CRUMBLING");
            case WATER_MASK -> builder.withShaderDefine("THREADIUM_WATER_MASK");
        }
        return RenderPipelines.register(builder.build());
    }

    @Override
    public void beginGroup(boolean strictlyOrdered) {
        recentPrepared.clear();
        recentBatchKey.clear();
        groupStart = queued.size();
        groupId++;
        groups.addLast(new Group(-1, strictlyOrdered));
    }

    @Override
    public void endGroup() {
        Group pending = groups.removeLast();
        groups.addLast(new Group(queued.size() - groupStart, pending.strictlyOrdered));
        recentPrepared.clear();
        recentBatchKey.clear();
    }

    @Override
    public FlushStats flushGroup() {
        Group group = groups.isEmpty() ? new Group(queued.size(), false) : groups.removeFirst();
        int entryCount = group.count;
        if (!state().accepts() || entryCount == 0) {
            clearPlanningScratch();
            return FlushStats.EMPTY;
        }
        long flushStart = profileStart();
        long packingNanos = 0, boneUploadNanos = 0, instanceUploadNanos = 0;
        long drawPlanningNanos = 0, drawSubmissionNanos = 0;
        try {
            long drawPlanningStart = profileStart();
            int planCount = planDraws(entryCount, group.strictlyOrdered);
            ensureFlushScratchCapacity(entryCount);
            exactKeyCount = 0;
            for (int sourceIndex = 0; sourceIndex < entryCount; sourceIndex++) {
                exactKeysBySourceIndex[sourceIndex] = queued.key(sourceIndex);
                exactKeyCount = sourceIndex + 1;
                packedIndexBySource[sourceIndex] = -1;
            }
            int packedBase = 0;
            for (int planIndex = 0; planIndex < planCount; planIndex++) {
                PlannedDraw plan = plannedDraws.get(planIndex);
                boolean reorderable = consolidate
                        && !group.strictlyOrdered
                        && !plan.sorted
                        && plan.type.canConsolidateConsecutiveGeometry();
                plan.submission = plan.planner.plan(
                        plan.sourceIndices,
                        plan.sourceCount,
                        exactKeysBySourceIndex,
                        entryCount,
                        packedBase,
                        reorderable,
                        consolidate && !plan.sorted);
                packedBase = Math.addExact(packedBase, plan.submission.instanceCount());
            }
            if (packedBase != entryCount) {
                throw new IllegalStateException("ModelPart packed instance coverage mismatch: planned=" + packedBase
                        + ", queued=" + entryCount);
            }
            drawPlanningNanos = profileElapsed(drawPlanningStart);

            ByteBuffer instances =
                    instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            int packedIndex = 0;
            int uploadedBones = 0;
            int boneUploadBase = -1;
            ByteBuffer bones = boneStaging.clear();
            long bonePackingStart = System.nanoTime();
            for (int sourceIndex = 0; sourceIndex < entryCount; sourceIndex++) {
                ModelPartBoneData sourceBones = queued.bones(sourceIndex);
                if (uploadedFramePalettes.containsKey(sourceBones)) continue;

                int boneBase = framePaletteOffsets.get(sourceBones);
                int boneCount = sourceBones.matrices().length / 28;
                if (boneUploadBase < 0) boneUploadBase = boneBase;
                int expectedBase = Math.addExact(boneUploadBase, uploadedBones);
                if (boneBase != expectedBase) {
                    throw new IllegalStateException("Non-contiguous ModelPart bone palette upload: expectedBase="
                            + expectedBase
                            + ", actualBase="
                            + boneBase);
                }
                putBones(bones, sourceBones);
                uploadedFramePalettes.put(sourceBones, Boolean.TRUE);
                uploadedBones = Math.addExact(uploadedBones, boneCount);
            }
            long bonePackingNanos = System.nanoTime() - bonePackingStart;
            int boneUploadCalls = 0;
            if (uploadedBones > 0) {
                int expectedBytes = ModelPartLayouts.bytes(uploadedBones, ModelPartLayouts.BONE_STRIDE);
                if (bones.position() != expectedBytes) {
                    throw new IllegalStateException("ModelPart bone staging size mismatch: expected="
                            + expectedBytes
                            + ", actual="
                            + bones.position());
                }
                metrics.bonePackingNanos.add(bonePackingNanos);
                if (PROFILE_FLUSH) packingNanos += bonePackingNanos;
                bones.flip();
                long uploadStart = profileStart();
                encoder.writeToBuffer(
                        boneBuffer.slice((long) boneUploadBase * ModelPartLayouts.BONE_STRIDE, bones.remaining()),
                        bones);
                boneUploadNanos += profileElapsed(uploadStart);
                boneUploadCalls = 1;
            }
            for (int planIndex = 0; planIndex < planCount; planIndex++) {
                PlannedDraw plan = plannedDraws.get(planIndex);
                int[] packedSourceIndices = plan.submission.packedSourceIndices();
                for (int instanceIndex = 0; instanceIndex < plan.submission.instanceCount(); instanceIndex++) {
                    int sourceIndex = packedSourceIndices[instanceIndex];
                    if (packedIndexBySource[sourceIndex] >= 0) {
                        throw new IllegalStateException("Duplicate ModelPart source instance " + sourceIndex);
                    }
                    packedIndexBySource[sourceIndex] = packedIndex++;
                    ModelPartBoneData sourceBones = queued.bones(sourceIndex);
                    int boneBase = framePaletteOffsets.get(sourceBones);
                    ModelPartDecalTransform decalTransform = queued.decalTransform(sourceIndex);
                    if (decalTransform != null) {
                        ByteBuffer decal = decalStaging.clear();
                        for (float value : decalTransform.values()) decal.putFloat(value);
                        decal.flip();
                        encoder.writeToBuffer(
                                decalBuffer.slice(
                                        (long) queued.decalBase(sourceIndex) * ModelPartLayouts.BONE_STRIDE,
                                        decal.remaining()),
                                decal);
                    }
                    long instancePackingStart = profileStart();
                    putInstance(instances, queued, sourceIndex, boneBase);
                    packingNanos += profileElapsed(instancePackingStart);
                }
            }
            if (packedIndex != entryCount) {
                throw new IllegalStateException("ModelPart packed instance coverage mismatch: packed=" + packedIndex
                        + ", queued=" + entryCount);
            }
            instances.flip();
            GpuBufferSlice uploadedInstances = instanceBuffer.slice(0, instances.remaining());
            long instanceUploadStart = profileStart();
            encoder.writeToBuffer(uploadedInstances, instances);
            instanceUploadNanos = profileElapsed(instanceUploadStart);
            int calls = 0, batchableInstances = 0, batchableCalls = 0, sortedInstances = 0, sortedCalls = 0;
            int singletons = 0, multi = 0, maximum = 0, multiInstances = 0;
            long drawSubmissionStart = profileStart();
            int planIndex = 0;
            while (planIndex < planCount) {
                PlannedDraw plan = plannedDraws.get(planIndex);
                if (plan.sorted) {
                    int planSortedCalls = submitSorted(encoder, plan, uploadedInstances, packedIndexBySource);
                    calls += planSortedCalls;
                    sortedInstances += plan.sourceCount;
                    sortedCalls += planSortedCalls;
                    planIndex++;
                    continue;
                }
                int runEnd = compatibleUnsortedRunEnd(planIndex, planCount);
                submitBatchRun(encoder, planIndex, runEnd, uploadedInstances);
                for (int runIndex = planIndex; runIndex < runEnd; runIndex++) {
                    InstanceSubmissionOrderPlanner.Plan submission = plannedDraws.get(runIndex).submission;
                    for (int batch = 0; batch < submission.batchCount(); batch++) {
                        int count = submission.instanceCounts()[batch];
                        calls++;
                        batchableCalls++;
                        batchableInstances += count;
                        maximum = Math.max(maximum, count);
                        if (count == 1) singletons++;
                        else {
                            multi++;
                            multiInstances += count;
                        }
                    }
                }
                planIndex = runEnd;
            }
            drawSubmissionNanos = profileElapsed(drawSubmissionStart);
            queued.removePrefix(entryCount);
            if (state() == ModelPartBackendState.READY)
                states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE);
            if (PROFILE_FLUSH)
                metrics.recordBlaze3dFlushTiming(
                        profileElapsed(flushStart),
                        packingNanos,
                        boneUploadNanos,
                        instanceUploadNanos,
                        drawPlanningNanos,
                        drawSubmissionNanos);
            return new FlushStats(
                    entryCount,
                    calls,
                    batchableInstances,
                    batchableCalls,
                    sortedInstances,
                    sortedCalls,
                    singletons,
                    multi,
                    maximum,
                    multiInstances,
                    1,
                    boneUploadCalls,
                    (long) entryCount * ModelPartLayouts.INSTANCE_STRIDE,
                    (long) uploadedBones * ModelPartLayouts.BONE_STRIDE);
        } catch (Throwable failure) {
            queued.clear();
            groups.clear();
            fail();
            return FlushStats.EMPTY;
        } finally {
            clearPlanningScratch();
        }
    }

    private static long profileStart() {
        return PROFILE_FLUSH ? System.nanoTime() : 0L;
    }

    private static long profileElapsed(long start) {
        return PROFILE_FLUSH ? System.nanoTime() - start : 0L;
    }

    private int planDraws(int entryCount, boolean strictlyOrdered) {
        prepareDrawTable(entryCount);
        activePlannedDrawCount = 0;
        PlannedDraw last = null;
        for (int i = 0; i < entryCount; i++) {
            RenderType type = queued.type(i);
            PreparedRenderType prepared = queued.prepared(i);
            PlannedDraw plan = null;
            boolean canConsolidate = consolidate && type.canConsolidateConsecutiveGeometry();
            if (canConsolidate && last != null && last.type == type) plan = last;
            else if (canConsolidate && !strictlyOrdered && !type.sortOnUpload()) plan = findDraw(prepared);
            if (plan == null) {
                plan = acquirePlannedDraw(activePlannedDrawCount++, type, prepared);
                if (canConsolidate && !strictlyOrdered && !plan.sorted) insertDraw(prepared, plan);
            }
            plan.addSourceIndex(i);
            last = plan;
        }
        return activePlannedDrawCount;
    }

    private PlannedDraw acquirePlannedDraw(int index, RenderType type, PreparedRenderType prepared) {
        PlannedDraw plan;
        if (index == plannedDraws.size()) {
            plan = new PlannedDraw();
            plannedDraws.add(plan);
        } else {
            plan = plannedDraws.get(index);
        }
        plan.reset(type, prepared);
        return plan;
    }

    private void ensureFlushScratchCapacity(int required) {
        if (exactKeysBySourceIndex.length >= required) return;
        int capacity = Math.max(required, exactKeysBySourceIndex.length + (exactKeysBySourceIndex.length >> 1) + 1);
        exactKeysBySourceIndex = new ModelPartBatchKey[capacity];
        packedIndexBySource = new int[capacity];
    }

    private void prepareDrawTable(int entryCount) {
        clearTouchedDrawSlots();
        int required = tableCapacity(entryCount);
        if (drawTableKeys.length < required) {
            drawTableKeys = new Object[required];
            drawTableValues = new PlannedDraw[required];
            touchedDrawSlots = new int[required];
            touchedDrawSlotCount = 0;
        }
    }

    private void clearTouchedDrawSlots() {
        for (int i = 0; i < touchedDrawSlotCount; i++) {
            int slot = touchedDrawSlots[i];
            drawTableKeys[slot] = null;
            drawTableValues[slot] = null;
        }
        touchedDrawSlotCount = 0;
    }

    private void clearPlanningScratch() {
        java.util.Arrays.fill(exactKeysBySourceIndex, 0, exactKeyCount, null);
        exactKeyCount = 0;
        clearTouchedDrawSlots();
        for (int planIndex = 0; planIndex < activePlannedDrawCount; planIndex++) {
            plannedDraws.get(planIndex).clear();
        }
        activePlannedDrawCount = 0;
    }

    private PlannedDraw findDraw(PreparedRenderType prepared) {
        int mask = drawTableKeys.length - 1;
        int slot = spread(prepared.hashCode()) & mask;
        while (true) {
            Object key = drawTableKeys[slot];
            if (key == null) return null;
            if (key.equals(prepared)) return drawTableValues[slot];
            slot = (slot + 1) & mask;
        }
    }

    private void insertDraw(PreparedRenderType prepared, PlannedDraw plan) {
        int mask = drawTableKeys.length - 1;
        int slot = spread(prepared.hashCode()) & mask;
        while (drawTableKeys[slot] != null) {
            if (drawTableKeys[slot].equals(prepared)) return;
            slot = (slot + 1) & mask;
        }
        drawTableKeys[slot] = prepared;
        drawTableValues[slot] = plan;
        touchedDrawSlots[touchedDrawSlotCount++] = slot;
    }

    private static int tableCapacity(int count) {
        if (count > (1 << 29)) throw new IllegalArgumentException("Too many ModelPart instances: " + count);
        int required = Math.max(2, count << 1);
        int capacity = 1;
        while (capacity < required) capacity <<= 1;
        return capacity;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static GpuBufferSlice instanceSlice(GpuBufferSlice uploaded, int first, int count) {
        var planned =
                InstanceBatchSlicePlanner.slice(uploaded.offset(), ModelPartLayouts.INSTANCE_STRIDE, first, count);
        return uploaded.buffer().slice(planned.byteOffset(), planned.byteLength());
    }

    private int submitSorted(
            com.mojang.blaze3d.systems.CommandEncoder encoder,
            PlannedDraw plan,
            GpuBufferSlice uploadedInstances,
            int[] packedIndexBySource) {
        long preparation = System.nanoTime();
        ArrayList<SortedModelPartQuads.Reference> refs = new ArrayList<>();
        long sequence = 0, keyStart = System.nanoTime();
        for (int instanceIndex = 0; instanceIndex < plan.sourceCount; instanceIndex++) {
            int instance = plan.sourceIndices[instanceIndex];
            Mesh mesh = meshes.get(queued.mesh(instance));
            ModelPartBoneData sourceBones = queued.bones(instance);
            for (int quad = 0; quad < mesh.quadBones.length; quad++) {
                int bone = mesh.quadBones[quad];
                if (!visible(sourceBones, bone)) continue;
                int c = quad * 6;
                refs.add(SortedModelPartQuads.referenceFromOppositeVertices(
                        instance,
                        quad,
                        sequence++,
                        mesh.quadCenters[c],
                        mesh.quadCenters[c + 1],
                        mesh.quadCenters[c + 2],
                        mesh.quadCenters[c + 3],
                        mesh.quadCenters[c + 4],
                        mesh.quadCenters[c + 5],
                        bone,
                        sourceBones,
                        queued.rootMatrices(),
                        queued.rootMatrixOffset(instance)));
            }
        }
        metrics.sortedKeyComputationNanos.add(System.nanoTime() - keyStart);
        metrics.sortedPreparationNanos.add(System.nanoTime() - preparation);
        metrics.sortedQuadsCollected.add(refs.size());
        long ordering = System.nanoTime();
        SortedModelPartQuads.sort(refs);
        metrics.sortedOrderingNanos.add(System.nanoTime() - ordering);
        metrics.sortedGroups.increment();
        metrics.sortedPipelineInstances.add(plan.sourceCount);
        long submission = System.nanoTime();
        submitSortedPass(encoder, plan, refs, uploadedInstances, packedIndexBySource);
        metrics.sortedSubmissionNanos.add(System.nanoTime() - submission);
        metrics.sortedQuadsSubmitted.add(refs.size());
        metrics.sortedCpuFallbackDraws.add(refs.size());
        return refs.size();
    }

    private void submitSortedPass(
            com.mojang.blaze3d.systems.CommandEncoder encoder,
            PlannedDraw plan,
            List<SortedModelPartQuads.Reference> refs,
            GpuBufferSlice uploadedInstances,
            int[] packedIndexBySource) {
        PreparedRenderType prepared = plan.prepared;
        RenderTarget target = prepared.outputTarget().getRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                        ? RenderSystem.outputDepthTextureOverride
                        : target.getDepthTextureView())
                : null;
        try (RenderPass pass = encoder.createRenderPass(plan, color, Optional.empty(), depth, OptionalDouble.empty())) {
            metrics.blaze3dPassesCreated.increment();
            bindPreparedPass(pass, prepared);
            ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());
            for (SortedModelPartQuads.Reference ref : refs) {
                int sourceIndex = ref.instanceIndex();
                int packedInstance = packedIndexBySource[sourceIndex];
                if (packedInstance < 0) {
                    throw new IllegalStateException("Missing packed ModelPart instance for source " + sourceIndex);
                }
                Mesh mesh = meshes.get(queued.mesh(sourceIndex));
                pass.setVertexBuffer(0, mesh.vertices.slice());
                pass.setVertexBuffer(1, instanceSlice(uploadedInstances, packedInstance, 1));
                pass.setIndexBuffer(mesh.indices, IndexType.INT);
                pass.drawIndexed(6, 1, ref.quadIndex() * 6, 0, 0);
                metrics.pipelineDrawCommand(descriptor);
                metrics.blaze3dDrawCommands.increment();
            }
            metrics.pipelineInstances(descriptor, plan.sourceCount);
            metrics.blaze3dInstancesSubmitted.add(plan.sourceCount);
        }
    }

    private int compatibleUnsortedRunEnd(int start, int planCount) {
        PlannedDraw first = plannedDraws.get(start);
        RenderTarget firstTarget = first.prepared.outputTarget().getRenderTarget();
        Object color = colorAttachment(firstTarget);
        Object depth = depthAttachment(firstTarget);
        Object scissor = first.prepared.scissorState();
        int end = start + 1;
        while (end < planCount) {
            PlannedDraw next = plannedDraws.get(end);
            if (next.sorted) break;
            RenderTarget nextTarget = next.prepared.outputTarget().getRenderTarget();
            if (!compatiblePassState(
                    color,
                    depth,
                    scissor,
                    colorAttachment(nextTarget),
                    depthAttachment(nextTarget),
                    next.prepared.scissorState())) break;
            end++;
        }
        return end;
    }

    static boolean compatiblePassState(
            Object color, Object depth, Object scissor, Object nextColor, Object nextDepth, Object nextScissor) {
        return color == nextColor && depth == nextDepth && scissor.equals(nextScissor);
    }

    private static Object colorAttachment(RenderTarget target) {
        return RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
    }

    private static Object depthAttachment(RenderTarget target) {
        if (!target.useDepth) return null;
        return RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : target.getDepthTextureView();
    }

    private void submitBatchRun(
            com.mojang.blaze3d.systems.CommandEncoder encoder, int start, int end, GpuBufferSlice uploadedInstances) {
        PlannedDraw first = plannedDraws.get(start);
        RenderTarget target = first.prepared.outputTarget().getRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                        ? RenderSystem.outputDepthTextureOverride
                        : target.getDepthTextureView())
                : null;
        try (RenderPass pass =
                encoder.createRenderPass(first, color, Optional.empty(), depth, OptionalDouble.empty())) {
            metrics.blaze3dPassesCreated.increment();
            for (int planIndex = start; planIndex < end; planIndex++)
                submitBatches(pass, plannedDraws.get(planIndex), uploadedInstances);
        }
    }

    private void submitBatches(RenderPass pass, PlannedDraw plan, GpuBufferSlice uploadedInstances) {
        PreparedRenderType prepared = plan.prepared;
        bindPreparedPass(pass, prepared);
        ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());
        InstanceSubmissionOrderPlanner.Plan submission = plan.submission;
        for (int batch = 0; batch < submission.batchCount(); batch++) {
            int sourceIndex = submission.representativeSourceIndices()[batch];
            int firstPackedInstance = submission.firstPackedInstances()[batch];
            int count = submission.instanceCounts()[batch];
            MeshHandle handle = queued.mesh(sourceIndex);
            Mesh mesh = meshes.get(handle);
            pass.setVertexBuffer(0, mesh.vertices.slice());
            pass.setVertexBuffer(1, instanceSlice(uploadedInstances, firstPackedInstance, count));
            pass.setIndexBuffer(mesh.indices, IndexType.INT);
            pass.drawIndexed(handle.indexCount(), count, 0, 0, 0);
            metrics.pipelineDraw(descriptor, count);
            metrics.blaze3dDrawCommands.increment();
            metrics.blaze3dInstancesSubmitted.add(count);
        }
    }

    private void bindPreparedPass(RenderPass pass, PreparedRenderType prepared) {
        pass.setPipeline(pipelineFor(prepared.pipeline()));
        if (prepared.scissorState().enabled())
            pass.enableScissor(
                    prepared.scissorState().x(),
                    prepared.scissorState().y(),
                    prepared.scissorState().width(),
                    prepared.scissorState().height());
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
        pass.setUniform("Bones", boneBuffer);
        pass.setUniform("Decals", decalBuffer);
        for (PreparedRenderType.Texture texture : prepared.textures())
            pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
    }

    private static boolean visible(ModelPartBoneData bones, int bone) {
        return (bones.visibility()[bone >>> 6] & (1L << (bone & 63))) != 0;
    }

    private static void putBones(ByteBuffer out, ModelPartBoneData data) {
        float[] values = data.matrices();
        int count = values.length / 28;
        for (int bone = 0; bone < count; bone++) {
            boolean visible = (data.visibility()[bone >>> 6] & (1L << (bone & 63))) != 0;
            for (int component = 0; component < 28; component++)
                out.putFloat(visible ? values[bone * 28 + component] : 0f);
        }
    }

    private static void putInstance(ByteBuffer out, QueuedModelPartArena queued, int sourceIndex, int boneBase) {
        float[] matrices = queued.rootMatrices();
        int matrixOffset = queued.rootMatrixOffset(sourceIndex);
        for (int component = 0; component < QueuedModelPartArena.ROOT_MATRIX_COMPONENTS; component++)
            out.putFloat(matrices[matrixOffset + component]);
        int tint = queued.tint(sourceIndex);
        out.putInt(boneBase)
                .putInt(queued.light(sourceIndex))
                .putInt(queued.overlay(sourceIndex))
                .putInt(queued.decalBase(sourceIndex));
        out.putFloat(((tint >>> 16) & 255) / 255f)
                .putFloat(((tint >>> 8) & 255) / 255f)
                .putFloat((tint & 255) / 255f)
                .putFloat(((tint >>> 24) & 255) / 255f);
        ModelPartUvTransform uvTransform = queued.uvTransform(sourceIndex);
        out.putFloat(uvTransform.offsetU())
                .putFloat(uvTransform.offsetV())
                .putFloat(uvTransform.scaleU())
                .putFloat(uvTransform.scaleV());
    }

    @Override
    public void destroy(MeshHandle handle) {
        recentBatchKey.clearIfReferences(handle);
        Mesh mesh = meshes.remove(handle);
        if (mesh != null) {
            mesh.vertices.close();
            mesh.indices.close();
        }
    }

    @Override
    public void clear() {
        recentPrepared.clear();
        recentBatchKey.clear();
        queued.clear();
        groups.clear();
        clearPlanningScratch();
        framePaletteOffsets.clear();
        uploadedFramePalettes.clear();
        frameBoneCount = 0;
        frameDecalCount = 0;
        epoch++;
    }

    @Override
    public void close() {
        clear();
        for (MeshHandle handle : new ArrayList<>(meshes.keySet())) destroy(handle);
        if (boneBuffer != null) boneBuffer.close();
        if (instanceBuffer != null) instanceBuffer.close();
        if (decalBuffer != null) decalBuffer.close();
        if (boneStaging != null) MemoryUtil.memFree(boneStaging);
        if (instanceStaging != null) MemoryUtil.memFree(instanceStaging);
        if (decalStaging != null) MemoryUtil.memFree(decalStaging);
        boneBuffer = instanceBuffer = decalBuffer = null;
        boneStaging = instanceStaging = decalStaging = null;
    }

    private void fail() {
        recentPrepared.clear();
        recentBatchKey.clear();
        ModelPartBackendState state = state();
        if (state == ModelPartBackendState.INITIALIZING
                || state == ModelPartBackendState.READY
                || state == ModelPartBackendState.ACTIVE) states.transition(state, ModelPartBackendState.FAILED);
    }
}
