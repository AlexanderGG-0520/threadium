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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
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
    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final int maxInstances, maxBones;
    private final boolean consolidate;
    private final ModelPartGpuMetrics metrics;
    private final ArrayList<Queued> queued = new ArrayList<>();
    private final ArrayDeque<Group> groups = new ArrayDeque<>();
    private final Map<MeshHandle, Mesh> meshes = new java.util.HashMap<>();
    private final IdentityHashMap<RenderPipeline, RenderPipeline> pipelines = new IdentityHashMap<>();
    private final PipelineValidityTable<RenderPipeline> validity = new PipelineValidityTable<>();
    private final IdentityHashMap<ModelPartBoneData, Integer> framePaletteOffsets = new IdentityHashMap<>();
    private final IdentityHashMap<ModelPartBoneData, Boolean> uploadedFramePalettes = new IdentityHashMap<>();
    private int groupStart, frameBoneCount, frameDecalCount;
    private long epoch, groupId, generation;
    private GpuBuffer boneBuffer, instanceBuffer, decalBuffer;
    private ByteBuffer boneStaging, instanceStaging, decalStaging;
    private boolean loggedUnsupported, pipelinesNeedCompilation = true;

    private record Mesh(GpuBuffer vertices, GpuBuffer indices, float[] quadCenters, int[] quadBones) {}

    private record Queued(
            ModelPartBatchKey key,
            MeshHandle mesh,
            RenderType type,
            PreparedRenderType prepared,
            Matrix4f pose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint,
            ModelPartUvTransform uvTransform,
            ModelPartDecalTransform decalTransform,
            int decalBase) {}

    private record Group(int count, boolean strictlyOrdered) {}

    private static final class PlannedDraw {
        final RenderType type;
        final PreparedRenderType prepared;
        final boolean sorted;
        final ArrayList<Integer> instances = new ArrayList<>();

        PlannedDraw(RenderType type, PreparedRenderType prepared) {
            this.type = type;
            this.prepared = prepared;
            this.sorted = type.sortOnUpload();
        }
    }

    public Blaze3dModelPartBackend(int maxInstances, int maxBones, boolean consolidate, ModelPartGpuMetrics metrics) {
        this.maxInstances = maxInstances;
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
        RenderPipeline source;
        try {
            source = type.prepare().pipeline();
        } catch (Throwable failure) {
            metrics.blaze3dPipelineValidityUnknown.increment();
            return new PipelineValidity(
                    PipelineValidityState.UNSUPPORTED_BACKEND, requestedGeneration, failure.toString());
        }
        PipelineValidity result = supported(source)
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
        if (!state().accepts()
                || !(renderType instanceof RenderType type)
                || !meshes.containsKey(mesh)
                || queued.size() >= maxInstances) return false;
        int count = bones.matrices().length / 28;
        Integer existingBase = framePaletteOffsets.get(bones);
        if (existingBase == null && frameBoneCount + count > maxBones) return false;
        PreparedRenderType prepared;
        try {
            prepared = type.prepare();
            ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());
            if (descriptor == null
                    || descriptor.submissionPolicy()
                            == ModelPartPipelineDescriptor.SubmissionPolicy.SORTED_QUAD_STREAM
                            != type.sortOnUpload()
                    || !validity.get(prepared.pipeline(), generation).permitsReplacement(generation)
                    || pipelineFor(prepared.pipeline()) == null
                    || ((descriptor == ModelPartPipelineDescriptor.CRUMBLING) != (decalTransform != null))) {
                unsupported(prepared.pipeline());
                return false;
            }
        } catch (Throwable unsupported) {
            metrics.blaze3dUnsupportedFallbacks.increment();
            return false;
        }
        int decalBase = decalTransform == null ? -1 : frameDecalCount++;
        queued.add(new Queued(
                new ModelPartBatchKey(mesh, type, epoch, groupId),
                mesh,
                type,
                prepared,
                new Matrix4f(rootPose),
                bones,
                light,
                overlay,
                tint,
                uvTransform,
                decalTransform,
                decalBase));
        if (existingBase == null) {
            framePaletteOffsets.put(bones, frameBoneCount);
            frameBoneCount = Math.addExact(frameBoneCount, count);
        }
        return true;
    }

    @Override
    public void beginFrame() {
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

    private static boolean supported(RenderPipeline pipeline) {
        return ModelPartPipelineDescriptor.from(pipeline) != null;
    }

    private RenderPipeline pipelineFor(RenderPipeline source) {
        return pipelines.computeIfAbsent(source, p -> createPipeline(ModelPartPipelineDescriptor.from(p)));
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
        groupStart = queued.size();
        groupId++;
        groups.addLast(new Group(-1, strictlyOrdered));
    }

    @Override
    public void endGroup() {
        Group pending = groups.removeLast();
        groups.addLast(new Group(queued.size() - groupStart, pending.strictlyOrdered));
    }

    @Override
    public FlushStats flushGroup() {
        Group group = groups.isEmpty() ? new Group(queued.size(), false) : groups.removeFirst();
        int entryCount = group.count;
        if (!state().accepts() || entryCount == 0) return FlushStats.EMPTY;
        try {
            ByteBuffer instances =
                    instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            int uploadedBones = 0, boneUploadCalls = 0;
            for (int i = 0; i < entryCount; i++) {
                Queued q = queued.get(i);
                int boneBase = framePaletteOffsets.get(q.bones);
                if (!uploadedFramePalettes.containsKey(q.bones)) {
                    int boneCount = q.bones.matrices().length / 28;
                    ByteBuffer bones =
                            boneStaging.clear().limit(ModelPartLayouts.bytes(boneCount, ModelPartLayouts.BONE_STRIDE));
                    long packingStart = System.nanoTime();
                    putBones(bones, q.bones);
                    metrics.bonePackingNanos.add(System.nanoTime() - packingStart);
                    bones.flip();
                    encoder.writeToBuffer(
                            boneBuffer.slice((long) boneBase * ModelPartLayouts.BONE_STRIDE, bones.remaining()), bones);
                    uploadedFramePalettes.put(q.bones, Boolean.TRUE);
                    uploadedBones = Math.addExact(uploadedBones, boneCount);
                    boneUploadCalls++;
                }
                if (q.decalTransform != null) {
                    ByteBuffer decal = decalStaging.clear();
                    for (float value : q.decalTransform.values()) decal.putFloat(value);
                    decal.flip();
                    encoder.writeToBuffer(
                            decalBuffer.slice((long) q.decalBase * ModelPartLayouts.BONE_STRIDE, decal.remaining()),
                            decal);
                }
                putInstance(instances, q, boneBase);
            }
            instances.flip();
            GpuBufferSlice uploadedInstances = instanceBuffer.slice(0, instances.remaining());
            encoder.writeToBuffer(uploadedInstances, instances);
            ArrayList<PlannedDraw> plans = planDraws(entryCount, group.strictlyOrdered);
            int calls = 0, singletons = 0, multi = 0, maximum = 0, multiInstances = 0;
            for (PlannedDraw plan : plans) {
                if (plan.sorted) {
                    int sortedCalls = submitSorted(encoder, plan, uploadedInstances);
                    calls += sortedCalls;
                    singletons += sortedCalls;
                    maximum = Math.max(maximum, 1);
                } else {
                    for (int at = 0; at < plan.instances.size(); ) {
                        int firstIndex = plan.instances.get(at), end = at + 1;
                        if (consolidate)
                            while (end < plan.instances.size()
                                    && plan.instances.get(end) == plan.instances.get(end - 1) + 1
                                    && queued.get(firstIndex).key.equals(queued.get(plan.instances.get(end)).key))
                                end++;
                        int count = end - at;
                        Queued q = queued.get(firstIndex);
                        GpuBufferSlice batchInstances = instanceSlice(uploadedInstances, firstIndex, count);
                        submit(encoder, q, batchInstances, count);
                        calls++;
                        maximum = Math.max(maximum, count);
                        if (count == 1) singletons++;
                        else {
                            multi++;
                            multiInstances += count;
                        }
                        at = end;
                    }
                }
            }
            queued.subList(0, entryCount).clear();
            if (state() == ModelPartBackendState.READY)
                states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE);
            return new FlushStats(
                    entryCount,
                    calls,
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
        }
    }

    private ArrayList<PlannedDraw> planDraws(int entryCount, boolean strictlyOrdered) {
        ArrayList<PlannedDraw> plans = new ArrayList<>();
        PlannedDraw last = null;
        for (int i = 0; i < entryCount; i++) {
            Queued q = queued.get(i);
            PlannedDraw plan = null;
            if (last != null && last.type == q.type && q.type.canConsolidateConsecutiveGeometry()) plan = last;
            else if (!strictlyOrdered && q.type.canConsolidateConsecutiveGeometry())
                for (PlannedDraw candidate : plans)
                    if (candidate.prepared.equals(q.prepared)) {
                        plan = candidate;
                        break;
                    }
            if (plan == null) {
                plan = new PlannedDraw(q.type, q.prepared);
                plans.add(plan);
            }
            plan.instances.add(i);
            last = plan;
        }
        return plans;
    }

    private static GpuBufferSlice instanceSlice(GpuBufferSlice uploaded, int first, int count) {
        var planned =
                InstanceBatchSlicePlanner.slice(uploaded.offset(), ModelPartLayouts.INSTANCE_STRIDE, first, count);
        return uploaded.buffer().slice(planned.byteOffset(), planned.byteLength());
    }

    private int submitSorted(
            com.mojang.blaze3d.systems.CommandEncoder encoder, PlannedDraw plan, GpuBufferSlice uploadedInstances) {
        long preparation = System.nanoTime();
        ArrayList<SortedModelPartQuads.Reference> refs = new ArrayList<>();
        long sequence = 0, keyStart = System.nanoTime();
        for (int instance : plan.instances) {
            Queued q = queued.get(instance);
            Mesh mesh = meshes.get(q.mesh);
            for (int quad = 0; quad < mesh.quadBones.length; quad++) {
                int bone = mesh.quadBones[quad];
                if (!visible(q.bones, bone)) continue;
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
                        q.bones,
                        q.pose));
            }
        }
        metrics.sortedKeyComputationNanos.add(System.nanoTime() - keyStart);
        metrics.sortedPreparationNanos.add(System.nanoTime() - preparation);
        metrics.sortedQuadsCollected.add(refs.size());
        long ordering = System.nanoTime();
        SortedModelPartQuads.sort(refs);
        metrics.sortedOrderingNanos.add(System.nanoTime() - ordering);
        metrics.sortedGroups.increment();
        metrics.sortedPipelineInstances.add(plan.instances.size());
        long submission = System.nanoTime();
        submitSortedPass(encoder, plan.prepared, refs, plan.instances.size(), uploadedInstances);
        metrics.sortedSubmissionNanos.add(System.nanoTime() - submission);
        metrics.sortedQuadsSubmitted.add(refs.size());
        metrics.sortedCpuFallbackDraws.add(refs.size());
        return refs.size();
    }

    private void submitSortedPass(
            com.mojang.blaze3d.systems.CommandEncoder encoder,
            PreparedRenderType prepared,
            List<SortedModelPartQuads.Reference> refs,
            int instanceCount,
            GpuBufferSlice uploadedInstances) {
        RenderTarget target = prepared.outputTarget().getRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                        ? RenderSystem.outputDepthTextureOverride
                        : target.getDepthTextureView())
                : null;
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Threadium sorted ModelPart " + prepared.pipeline(),
                color,
                Optional.empty(),
                depth,
                OptionalDouble.empty())) {
            metrics.blaze3dPassesCreated.increment();
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
            ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());
            for (SortedModelPartQuads.Reference ref : refs) {
                Queued q = queued.get(ref.instanceIndex());
                Mesh mesh = meshes.get(q.mesh);
                pass.setVertexBuffer(0, mesh.vertices.slice());
                pass.setVertexBuffer(1, instanceSlice(uploadedInstances, ref.instanceIndex(), 1));
                pass.setIndexBuffer(mesh.indices, IndexType.INT);
                pass.drawIndexed(6, 1, ref.quadIndex() * 6, 0, 0);
                metrics.pipelineDrawCommand(descriptor);
                metrics.blaze3dDrawCommands.increment();
            }
            metrics.pipelineInstances(descriptor, instanceCount);
            metrics.blaze3dInstancesSubmitted.add(instanceCount);
        }
    }

    private static boolean visible(ModelPartBoneData bones, int bone) {
        return (bones.visibility()[bone >>> 6] & (1L << (bone & 63))) != 0;
    }

    private void submit(
            com.mojang.blaze3d.systems.CommandEncoder encoder, Queued q, GpuBufferSlice batchInstances, int count) {
        PreparedRenderType prepared = q.prepared;
        RenderTarget target = prepared.outputTarget().getRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                        ? RenderSystem.outputDepthTextureOverride
                        : target.getDepthTextureView())
                : null;
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Threadium ModelPart " + prepared.pipeline(),
                color,
                Optional.empty(),
                depth,
                OptionalDouble.empty())) {
            metrics.blaze3dPassesCreated.increment();
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
            pass.setVertexBuffer(0, meshes.get(q.mesh).vertices.slice());
            pass.setVertexBuffer(1, batchInstances);
            for (PreparedRenderType.Texture texture : prepared.textures())
                pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
            pass.setIndexBuffer(meshes.get(q.mesh).indices, IndexType.INT);
            pass.drawIndexed(q.mesh.indexCount(), count, 0, 0, 0);
            metrics.pipelineDraw(ModelPartPipelineDescriptor.from(prepared.pipeline()), count);
            metrics.blaze3dDrawCommands.increment();
            metrics.blaze3dInstancesSubmitted.add(count);
        }
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

    private static void putInstance(ByteBuffer out, Queued q, int boneBase) {
        Matrix4f m = q.pose;
        out.putFloat(m.m00())
                .putFloat(m.m01())
                .putFloat(m.m02())
                .putFloat(m.m03())
                .putFloat(m.m10())
                .putFloat(m.m11())
                .putFloat(m.m12())
                .putFloat(m.m13())
                .putFloat(m.m20())
                .putFloat(m.m21())
                .putFloat(m.m22())
                .putFloat(m.m23())
                .putFloat(m.m30())
                .putFloat(m.m31())
                .putFloat(m.m32())
                .putFloat(m.m33());
        out.putInt(boneBase).putInt(q.light).putInt(q.overlay).putInt(q.decalBase);
        out.putFloat(((q.tint >>> 16) & 255) / 255f)
                .putFloat(((q.tint >>> 8) & 255) / 255f)
                .putFloat((q.tint & 255) / 255f)
                .putFloat(((q.tint >>> 24) & 255) / 255f);
        out.putFloat(q.uvTransform.offsetU())
                .putFloat(q.uvTransform.offsetV())
                .putFloat(q.uvTransform.scaleU())
                .putFloat(q.uvTransform.scaleV());
    }

    @Override
    public void destroy(MeshHandle handle) {
        Mesh mesh = meshes.remove(handle);
        if (mesh != null) {
            mesh.vertices.close();
            mesh.indices.close();
        }
    }

    @Override
    public void clear() {
        queued.clear();
        groups.clear();
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
        ModelPartBackendState state = state();
        if (state == ModelPartBackendState.INITIALIZING
                || state == ModelPartBackendState.READY
                || state == ModelPartBackendState.ACTIVE) states.transition(state, ModelPartBackendState.FAILED);
    }
}
