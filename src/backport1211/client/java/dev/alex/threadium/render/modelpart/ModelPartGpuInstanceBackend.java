package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import dev.alex.threadium.mixin.accessor.RenderSystemAccessor;
import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Minecraft 1.21.1 OpenGL 3.2+ ModelPart backend.
 *
 * <p>Immutable structural meshes are uploaded once. A provider-layer flush uploads one exact frame-local bone palette
 * and one packed instance stream, then submits compatible meshes through {@code glDrawElementsInstanced}. Instanced
 * attribute divisors use OpenGL 3.3 when available and safely fall back to {@code GL_ARB_instanced_arrays} on the
 * OpenGL 3.2 contexts commonly created by Minecraft 1.21.1.
 */
public final class ModelPartGpuInstanceBackend implements AutoCloseable {
    private final ModelPart1211Metrics metrics;
    private final ThreadiumConfig config = ThreadiumRuntimeConfig.current();
    private final int maximumQueuedInstances = config.gpuMaxInstances();
    private final int maximumUploadedBones = config.gpuMaxBonesPerFrame();
    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final IdentityHashMap<ImmutableModelPartMesh, MeshHandle> meshes = new IdentityHashMap<>();
    private final IdentityHashMap<Object, IdentityHashMap<RenderLayer, Batch>> queued = new IdentityHashMap<>();
    private final ArrayDeque<Batch> recycledBatches = new ArrayDeque<>();
    private final InstanceSubmissionOrderPlanner orderPlanner = new InstanceSubmissionOrderPlanner();
    private GlInstancing1211.DivisorApi instancedAttributeDivisorApi = GlInstancing1211.DivisorApi.UNAVAILABLE;
    private int program;
    private int boneBuffer;
    private int boneTexture;
    private int decalBuffer;
    private int decalTexture;
    private int instanceBuffer;
    private ByteBuffer boneStaging;
    private ByteBuffer decalStaging;
    private ByteBuffer instanceStaging;
    private int queuedInstances;
    private long submittedInstances;
    private long drawCalls;
    private long sortedInstances;
    private long sortedDrawCalls;
    private long sortedQuads;
    private long meshUploads;
    private long instanceUploadCalls;
    private long boneUploadCalls;
    private long instanceBytes;
    private long boneBytes;
    private long cachedMeshBytes;
    private boolean closed;

    public ModelPartGpuInstanceBackend(ModelPart1211Metrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static boolean configured() {
        return ThreadiumRuntimeConfig.current().gpuReplacementEnabled();
    }

    public ModelPartBackendState state() {
        return states.state();
    }

    public boolean queue(
            Object groupOwner,
            Object provider,
            RenderLayer1211Descriptor descriptor,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            long worldGeneration,
            long resourceGeneration) {
        return queue(
                groupOwner,
                provider,
                descriptor,
                mesh,
                pose,
                root,
                light,
                overlay,
                color,
                null,
                worldGeneration,
                resourceGeneration);
    }

    public boolean queue(
            Object groupOwner,
            Object provider,
            RenderLayer1211Descriptor descriptor,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            ModelPartDecalTransform1211 decal,
            long worldGeneration,
            long resourceGeneration) {
        requireOpenRenderThread();
        if (groupOwner == null
                || !validGeometry(mesh, pose, root, 1)
                || !validMaterial(provider, descriptor, decal)
                || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshHandle(mesh);
        if (handle == null) return false;
        append(
                provider,
                groupOwner,
                handle,
                descriptor,
                pose,
                root,
                light,
                overlay,
                color,
                decal,
                worldGeneration,
                resourceGeneration);
        metrics.recordQueued(descriptor.kind(), 1);
        return true;
    }

    public boolean queuePair(
            Object groupOwner,
            Object baseProvider,
            RenderLayer1211Descriptor baseDescriptor,
            Object crumblingProvider,
            RenderLayer1211Descriptor crumblingDescriptor,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            ModelPartDecalTransform1211 decal,
            long worldGeneration,
            long resourceGeneration) {
        requireOpenRenderThread();
        if (groupOwner == null
                || !validGeometry(mesh, pose, root, 2)
                || !validMaterial(baseProvider, baseDescriptor, null)
                || !validMaterial(crumblingProvider, crumblingDescriptor, decal)
                || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshHandle(mesh);
        if (handle == null) return false;
        appendPair(
                baseProvider,
                baseDescriptor,
                color,
                null,
                crumblingProvider,
                crumblingDescriptor,
                color,
                decal,
                groupOwner,
                handle,
                pose,
                root,
                light,
                overlay,
                worldGeneration,
                resourceGeneration);
        metrics.recordQueued(baseDescriptor.kind(), 1);
        metrics.recordQueued(crumblingDescriptor.kind(), 1);
        return true;
    }

    public boolean queueOutlinePair(
            Object groupOwner,
            Object baseProvider,
            RenderLayer1211Descriptor baseDescriptor,
            Object outlineProvider,
            RenderLayer1211Descriptor outlineDescriptor,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int baseColor,
            int outlineColor,
            long worldGeneration,
            long resourceGeneration) {
        requireOpenRenderThread();
        if (groupOwner == null
                || !validGeometry(mesh, pose, root, 2)
                || !validMaterial(baseProvider, baseDescriptor, null)
                || !validMaterial(outlineProvider, outlineDescriptor, null)
                || !isOutline(outlineDescriptor)
                || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshHandle(mesh);
        if (handle == null) return false;
        appendPair(
                baseProvider,
                baseDescriptor,
                baseColor,
                null,
                outlineProvider,
                outlineDescriptor,
                outlineColor,
                null,
                groupOwner,
                handle,
                pose,
                root,
                light,
                overlay,
                worldGeneration,
                resourceGeneration);
        metrics.recordQueued(baseDescriptor.kind(), 1);
        metrics.recordQueued(outlineDescriptor.kind(), 1);
        return true;
    }

    private static boolean isOutline(RenderLayer1211Descriptor descriptor) {
        return descriptor.kind() == RenderLayer1211Descriptor.Kind.OUTLINE_CULL
                || descriptor.kind() == RenderLayer1211Descriptor.Kind.OUTLINE_NO_CULL;
    }

    private boolean validGeometry(
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int count) {
        return configured()
                && mesh != null
                && pose != null
                && root != null
                && pose.finite()
                && root.finite()
                && pose.boneCount() == mesh.partCount()
                && pose.boneCount() <= config.gpuMaxBonesPerModel()
                && mesh.vertexCount() <= config.gpuMaxVerticesPerMesh()
                && mesh.indexCount() <= config.gpuMaxIndicesPerMesh()
                && count > 0
                && queuedInstances <= maximumQueuedInstances - count;
    }

    private static boolean validMaterial(
            Object provider, RenderLayer1211Descriptor descriptor, ModelPartDecalTransform1211 decal) {
        if (!(provider instanceof VertexConsumerProvider.Immediate)
                || descriptor == null
                || !descriptor.replacementSafe()
                || descriptor.layer() == null) {
            return false;
        }
        boolean crumbling = descriptor.kind() == RenderLayer1211Descriptor.Kind.CRUMBLING;
        return crumbling == (decal != null) && (decal == null || decal.finite());
    }

    private MeshHandle meshHandle(ImmutableModelPartMesh mesh) {
        MeshHandle handle = meshes.get(mesh);
        metrics.recordGpuMeshLookup(handle != null);
        if (handle != null) return handle;
        handle = upload(mesh);
        if (handle == null) return null;
        if (meshes.size() >= config.gpuMaxCachedMeshes()
                || Math.addExact(cachedMeshBytes, handle.bytes) > config.gpuMaxMeshBytes()) {
            handle.delete();
            return null;
        }
        meshes.put(mesh, handle);
        cachedMeshBytes = Math.addExact(cachedMeshBytes, handle.bytes);
        return handle;
    }

    private void append(
            Object provider,
            Object groupOwner,
            MeshHandle mesh,
            RenderLayer1211Descriptor descriptor,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            ModelPartDecalTransform1211 decal,
            long worldGeneration,
            long resourceGeneration) {
        batch(provider, descriptor.layer())
                .entries
                .add(
                        groupOwner,
                        mesh,
                        descriptor,
                        pose,
                        root,
                        light,
                        overlay,
                        color,
                        decal,
                        worldGeneration,
                        resourceGeneration);
        queuedInstances++;
    }

    private void appendPair(
            Object firstProvider,
            RenderLayer1211Descriptor firstDescriptor,
            int firstColor,
            ModelPartDecalTransform1211 firstDecal,
            Object secondProvider,
            RenderLayer1211Descriptor secondDescriptor,
            int secondColor,
            ModelPartDecalTransform1211 secondDecal,
            Object groupOwner,
            MeshHandle mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            long worldGeneration,
            long resourceGeneration) {
        Batch firstBatch = batch(firstProvider, firstDescriptor.layer());
        Batch secondBatch = batch(secondProvider, secondDescriptor.layer());
        int firstSize = firstBatch.entries.size();
        int secondSize = secondBatch == firstBatch ? firstSize : secondBatch.entries.size();
        try {
            firstBatch.entries.add(
                    groupOwner,
                    mesh,
                    firstDescriptor,
                    pose,
                    root,
                    light,
                    overlay,
                    firstColor,
                    firstDecal,
                    worldGeneration,
                    resourceGeneration);
            secondBatch.entries.add(
                    groupOwner,
                    mesh,
                    secondDescriptor,
                    pose,
                    root,
                    light,
                    overlay,
                    secondColor,
                    secondDecal,
                    worldGeneration,
                    resourceGeneration);
            queuedInstances += 2;
        } catch (Throwable failure) {
            firstBatch.entries.truncate(firstSize);
            if (secondBatch != firstBatch) secondBatch.entries.truncate(secondSize);
            removeEmptyBatch(firstProvider, firstDescriptor.layer(), firstBatch);
            if (secondBatch != firstBatch) removeEmptyBatch(secondProvider, secondDescriptor.layer(), secondBatch);
            throw failure;
        }
    }

    private Batch batch(Object provider, RenderLayer layer) {
        IdentityHashMap<RenderLayer, Batch> providerBatches =
                queued.computeIfAbsent(provider, ignored -> new IdentityHashMap<>());
        Batch existing = providerBatches.get(layer);
        if (existing != null) return existing;
        Batch created = acquireBatch();
        providerBatches.put(layer, created);
        return created;
    }

    private Batch acquireBatch() {
        Batch recycled = recycledBatches.pollLast();
        return recycled != null ? recycled : new Batch(maximumQueuedInstances);
    }

    private void releaseBatch(Batch batch) {
        batch.entries.clear();
        if (recycledBatches.size() < 64) recycledBatches.addLast(batch);
    }

    private void removeEmptyBatch(Object provider, RenderLayer layer, Batch expected) {
        if (!expected.entries.isEmpty()) return;
        IdentityHashMap<RenderLayer, Batch> providerBatches = queued.get(provider);
        if (providerBatches == null || providerBatches.get(layer) != expected) return;
        providerBatches.remove(layer);
        if (providerBatches.isEmpty()) queued.remove(provider);
        releaseBatch(expected);
    }

    public IdentityHashMap<Object, ModelPartFlushStats> flush(
            Object provider, RenderLayer layer, long currentWorldGeneration, long currentResourceGeneration) {
        requireOpenRenderThread();
        IdentityHashMap<RenderLayer, Batch> providerBatches = queued.get(provider);
        if (providerBatches == null) return new IdentityHashMap<>();
        Batch batch = providerBatches.remove(layer);
        if (batch == null) return new IdentityHashMap<>();
        if (providerBatches.isEmpty()) queued.remove(provider);
        queuedInstances -= batch.entries.size();
        try {
            for (int index = 0; index < batch.entries.size(); index++) {
                if (batch.entries.worldGeneration(index) != currentWorldGeneration
                        || batch.entries.resourceGeneration(index) != currentResourceGeneration) {
                    metrics.recordStaleSubmission();
                    fail();
                    throw new IllegalStateException("Threadium rejected a stale Minecraft 1.21.1 GPU submission");
                }
            }
            return drawBatch(layer, batch.entries);
        } finally {
            releaseBatch(batch);
        }
    }

    public void verifyFrameDrained() {
        requireOpenRenderThread();
        if (queuedInstances != 0 || !queued.isEmpty()) {
            metrics.recordUndrainedFrame();
            clearQueued();
            throw new IllegalStateException("Threadium GPU instance queue crossed a frame boundary without flushing");
        }
    }

    public void reset() {
        requireOpenRenderThread();
        clearQueued();
        releaseGpuResources();
        ModelPartBackendState state = states.state();
        if (state == ModelPartBackendState.FAILED) {
            states.transition(ModelPartBackendState.FAILED, ModelPartBackendState.UNINITIALIZED);
        } else if (state == ModelPartBackendState.READY || state == ModelPartBackendState.ACTIVE) {
            states.transition(state, ModelPartBackendState.DISABLED);
            states.transition(ModelPartBackendState.DISABLED, ModelPartBackendState.UNINITIALIZED);
        } else if (state == ModelPartBackendState.INITIALIZING) {
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.FAILED);
            states.transition(ModelPartBackendState.FAILED, ModelPartBackendState.UNINITIALIZED);
        } else if (state == ModelPartBackendState.DISABLED) {
            states.transition(ModelPartBackendState.DISABLED, ModelPartBackendState.UNINITIALIZED);
        }
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(
                state(),
                queuedInstances,
                meshes.size(),
                submittedInstances,
                drawCalls,
                sortedInstances,
                sortedDrawCalls,
                sortedQuads,
                meshUploads,
                instanceUploadCalls,
                boneUploadCalls,
                instanceBytes,
                boneBytes);
    }

    @Override
    public void close() {
        if (closed) return;
        requireRenderThread();
        clearQueued();
        releaseGpuResources();
        ModelPartBackendState state = states.state();
        if (state == ModelPartBackendState.READY || state == ModelPartBackendState.ACTIVE) {
            states.transition(state, ModelPartBackendState.DISABLED);
        } else if (state == ModelPartBackendState.FAILED) {
            states.transition(ModelPartBackendState.FAILED, ModelPartBackendState.DISABLED);
        } else if (state == ModelPartBackendState.UNINITIALIZED) {
            states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.DISABLED);
        }
        closed = true;
    }

    private boolean ensureReady() {
        if (states.state().accepts()) return true;
        if (states.state() != ModelPartBackendState.UNINITIALIZED || !RenderSystem.isOnRenderThread()) return false;
        states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);
        metrics.recordInitializationAttempt();
        try {
            instancedAttributeDivisorApi = GlInstancing1211.detectCurrent();
            if (instancedAttributeDivisorApi == GlInstancing1211.DivisorApi.UNAVAILABLE) {
                throw new IllegalStateException(
                        "Threadium GPU instancing requires OpenGL 3.3 or GL_ARB_instanced_arrays");
            }
            program = link(
                    read("/assets/threadium/shaders/modelpart_gl33_1211.vert"),
                    read("/assets/threadium/shaders/modelpart_gl33_1211.frag"));
            boneBuffer = GL15C.glGenBuffers();
            boneTexture = GL11C.glGenTextures();
            decalBuffer = GL15C.glGenBuffers();
            decalTexture = GL11C.glGenTextures();
            instanceBuffer = GL15C.glGenBuffers();

            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER,
                    (long) maximumUploadedBones * Gl33BoneLayout.BYTES_PER_BONE,
                    GL15C.GL_STREAM_DRAW);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, boneBuffer);

            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, decalBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER,
                    (long) maximumQueuedInstances * ModelPartLayouts.BONE_STRIDE,
                    GL15C.GL_STREAM_DRAW);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, decalTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, decalBuffer);

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) maximumQueuedInstances * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);

            boneStaging =
                    MemoryUtil.memAlloc(ModelPartLayouts.bytes(maximumUploadedBones, ModelPartLayouts.BONE_STRIDE));
            decalStaging =
                    MemoryUtil.memAlloc(ModelPartLayouts.bytes(maximumQueuedInstances, ModelPartLayouts.BONE_STRIDE));
            instanceStaging = MemoryUtil.memAlloc(
                    ModelPartLayouts.bytes(maximumQueuedInstances, ModelPartLayouts.INSTANCE_STRIDE));
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY);
            metrics.recordInitializationResult(true);
            return true;
        } catch (Throwable failure) {
            metrics.recordInitializationResult(false);
            metrics.recordBackendFailure();
            releaseGpuResources();
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.FAILED);
            return false;
        }
    }

    private MeshHandle upload(ImmutableModelPartMesh mesh) {
        int vao = 0;
        int vbo = 0;
        int ibo = 0;
        ByteBuffer vertices = null;
        IntBuffer indices = null;
        try {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            ibo = GL15C.glGenBuffers();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            vertices = MemoryUtil.memAlloc(Math.multiplyExact(mesh.vertexCount(), ModelPartLayouts.VERTEX_STRIDE));
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                for (int field = 0; field < 8; field++) vertices.putInt(mesh.vertexFieldBits(vertex, field));
                vertices.putFloat(mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX));
            }
            vertices.flip();
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);

            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            indices = MemoryUtil.memAllocInt(mesh.indexCount());
            for (int index = 0; index < mesh.indexCount(); index++) indices.put(mesh.index(index));
            indices.flip();
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices, GL15C.GL_STATIC_DRAW);

            int[] sizes = {3, 3, 2, 1};
            int[] offsets = {0, 12, 24, 32};
            for (int location = 0; location < sizes.length; location++) {
                GL20C.glEnableVertexAttribArray(location);
                GL20C.glVertexAttribPointer(
                        location,
                        sizes[location],
                        GL11C.GL_FLOAT,
                        false,
                        ModelPartLayouts.VERTEX_STRIDE,
                        offsets[location]);
            }
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            for (int location = 4; location <= 12; location++) {
                GL20C.glEnableVertexAttribArray(location);
                GlInstancing1211.vertexAttribDivisor(instancedAttributeDivisorApi, location, 1);
            }
            GL30C.glBindVertexArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            meshUploads++;
            metrics.recordMeshUpload(true);
            return new MeshHandle(vao, vbo, ibo, mesh.indexCount(), mesh.partCount(), mesh.retainedBytes(), mesh);
        } catch (Throwable failure) {
            metrics.recordMeshUpload(false);
            if (vao != 0) GL30C.glDeleteVertexArrays(vao);
            if (vbo != 0) GL15C.glDeleteBuffers(vbo);
            if (ibo != 0) GL15C.glDeleteBuffers(ibo);
            fail();
            return null;
        } finally {
            if (vertices != null) MemoryUtil.memFree(vertices);
            if (indices != null) MemoryUtil.memFree(indices);
        }
    }

    private IdentityHashMap<Object, ModelPartFlushStats> drawBatch(
            RenderLayer layer, QueuedModelPartArena1211<MeshHandle> entries) {
        if (entries.isEmpty() || !states.state().accepts()) return new IdentityHashMap<>();
        long flushStart = metrics.now();
        long planningNanos = 0L;
        long uploadNanos = 0L;
        long submissionNanos = 0L;
        int entryCount = entries.size();
        IdentityHashMap<ImmutableModelPartBonePose, Integer> boneBases = new IdentityHashMap<>();
        ArrayList<ImmutableModelPartBonePose> palette = new ArrayList<>();
        int[] boneBaseBySource = new int[entryCount];
        int totalBones = 0;
        for (int index = 0; index < entryCount; index++) {
            ImmutableModelPartBonePose pose = entries.pose(index);
            Integer existing = boneBases.get(pose);
            if (existing == null) {
                existing = totalBones;
                totalBones = Math.addExact(totalBones, pose.boneCount());
                if (totalBones > maximumUploadedBones) {
                    fail();
                    throw new IllegalStateException("Threadium bone palette capacity exceeded");
                }
                boneBases.put(pose, existing);
                palette.add(pose);
            }
            boneBaseBySource[index] = existing;
        }

        ByteBuffer bones = boneStaging.clear().limit(Gl33BoneLayout.bytesForBones(totalBones));
        for (ImmutableModelPartBonePose pose : palette) putPose(bones, pose);
        bones.flip();

        int[] sourceIndices = new int[entryCount];
        Object[] keys = new Object[entryCount];
        IdentityHashMap<Object, IdentityHashMap<MeshHandle, Object>> groupMeshKeys = new IdentityHashMap<>();
        for (int index = 0; index < entryCount; index++) {
            sourceIndices[index] = index;
            Object groupOwner = entries.groupOwner(index);
            MeshHandle mesh = entries.mesh(index);
            keys[index] = groupMeshKeys
                    .computeIfAbsent(groupOwner, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(mesh, ignored -> new Object());
        }
        RenderLayer1211Descriptor descriptor = entries.descriptor(0);
        boolean crumbling = descriptor.kind() == RenderLayer1211Descriptor.Kind.CRUMBLING;
        boolean sorted = descriptor.submissionPolicy() == RenderLayer1211Descriptor.SubmissionPolicy.SORTED_QUAD_STREAM;
        boolean reorderable =
                descriptor.submissionPolicy() == RenderLayer1211Descriptor.SubmissionPolicy.OPAQUE_BATCHED;
        long planningStart = metrics.now();
        InstanceSubmissionOrderPlanner.Plan plan = orderPlanner.plan(
                sourceIndices, entryCount, keys, entryCount, 0, reorderable, config.gpuBatchConsolidation());
        planningNanos += metrics.delta(planningStart);

        int[] packedIndexBySource = new int[entryCount];
        ByteBuffer instances =
                instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
        ByteBuffer decals = crumbling
                ? decalStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.BONE_STRIDE))
                : null;
        for (int packed = 0; packed < entryCount; packed++) {
            int source = plan.packedSourceIndices()[packed];
            packedIndexBySource[source] = packed;
            int decalBase = -1;
            if (crumbling) {
                ModelPartDecalTransform1211 decal = entries.decal(source);
                if (decal == null) throw new IllegalStateException("Missing crumbling decal transform");
                decalBase = packed;
                putDecal(decals, decal);
            }
            putInstance(instances, entries, source, boneBaseBySource[source], decalBase);
        }
        instances.flip();
        if (decals != null) decals.flip();
        planningStart = metrics.now();
        ArrayList<SortedModelPartQuads.Reference> sortedReferences = sorted ? collectSortedReferences(entries) : null;
        planningNanos += metrics.delta(planningStart);
        IdentityHashMap<Object, MutableFlushStats> mutableFeedback = new IdentityHashMap<>();
        for (int index = 0; index < entryCount; index++) {
            MutableFlushStats stats =
                    mutableFeedback.computeIfAbsent(entries.groupOwner(index), ignored -> new MutableFlushStats());
            stats.instances++;
            if (sorted) stats.sortedInstances++;
            else stats.batchableInstances++;
        }
        if (sorted) {
            for (SortedModelPartQuads.Reference reference : sortedReferences) {
                MutableFlushStats stats = mutableFeedback.get(entries.groupOwner(reference.sourceIndex()));
                stats.drawCalls++;
                stats.sortedDrawCalls++;
                stats.maximumInstancesPerDraw = Math.max(stats.maximumInstancesPerDraw, 1);
            }
        } else {
            for (int batch = 0; batch < plan.batchCount(); batch++) {
                int representative = plan.representativeSourceIndices()[batch];
                int instanceCount = plan.instanceCounts()[batch];
                MutableFlushStats stats = mutableFeedback.get(entries.groupOwner(representative));
                stats.drawCalls++;
                stats.batchableDrawCalls++;
                stats.maximumInstancesPerDraw = Math.max(stats.maximumInstancesPerDraw, instanceCount);
                if (instanceCount == 1) stats.singletonBatches++;
                else {
                    stats.multiInstanceBatches++;
                    stats.totalInstancesInMultiDraws += instanceCount;
                }
            }
        }

        int actualDrawCalls = sorted ? sortedReferences.size() : plan.batchCount();
        int maximumBatch = sorted && !sortedReferences.isEmpty() ? 1 : 0;
        int singletonCount = 0;
        int multiCount = 0;
        int instancesInMultiDraws = 0;
        if (!sorted) {
            for (int batch = 0; batch < plan.batchCount(); batch++) {
                int count = plan.instanceCounts()[batch];
                maximumBatch = Math.max(maximumBatch, count);
                if (count == 1) singletonCount++;
                else {
                    multiCount++;
                    instancesInMultiDraws += count;
                }
            }
        }
        long packingNanos = Math.max(0L, metrics.delta(flushStart) - planningNanos);
        int boneByteCount = bones.remaining();
        int instanceByteCount = instances.remaining() + (decals == null ? 0 : decals.remaining());

        OpenGlStateSnapshot1211 previous = OpenGlStateSnapshot1211.capture();
        boolean layerStarted = false;
        try {
            layer.startDrawing();
            layerStarted = true;
            GL20C.glUseProgram(program);
            setMatrix(program, "uProjection", RenderSystem.getProjectionMatrix());
            setMatrix(program, "uModelView", RenderSystem.getModelViewMatrix());
            setMatrix(program, "uTextureMatrix", RenderSystem.getTextureMatrix());
            bindTextures(program);
            applyVanillaUniforms(program, descriptor);

            GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL20C.glUniform1i(uniform(program, "Bones"), 3);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE4);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, decalTexture);
            GL20C.glUniform1i(uniform(program, "Decals"), 4);

            long uploadStart = metrics.now();
            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER,
                    (long) maximumUploadedBones * Gl33BoneLayout.BYTES_PER_BONE,
                    GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL31C.GL_TEXTURE_BUFFER, 0, bones);
            boneUploadCalls++;
            boneBytes = Math.addExact(boneBytes, bones.remaining());

            if (decals != null) {
                GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, decalBuffer);
                GL15C.glBufferData(
                        GL31C.GL_TEXTURE_BUFFER,
                        (long) maximumQueuedInstances * ModelPartLayouts.BONE_STRIDE,
                        GL15C.GL_STREAM_DRAW);
                GL15C.glBufferSubData(GL31C.GL_TEXTURE_BUFFER, 0, decals);
            }

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) maximumQueuedInstances * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0, instances);
            instanceUploadCalls++;
            instanceBytes = Math.addExact(instanceBytes, instances.remaining());
            uploadNanos = metrics.delta(uploadStart);
            metrics.recordUploads(1, 1, instanceByteCount, boneByteCount);

            long submissionStart = metrics.now();
            if (sorted) {
                drawSorted(entries, sortedReferences, packedIndexBySource);
                sortedInstances = Math.addExact(sortedInstances, entryCount);
                sortedDrawCalls = Math.addExact(sortedDrawCalls, sortedReferences.size());
                sortedQuads = Math.addExact(sortedQuads, sortedReferences.size());
            } else {
                for (int batch = 0; batch < plan.batchCount(); batch++) {
                    int representative = plan.representativeSourceIndices()[batch];
                    MeshHandle mesh = entries.mesh(representative);
                    int firstInstance = plan.firstPackedInstances()[batch];
                    int instanceCount = plan.instanceCounts()[batch];
                    GL30C.glBindVertexArray(mesh.vao);
                    bindInstanceRange(firstInstance);
                    GL31C.glDrawElementsInstanced(
                            GL11C.GL_TRIANGLES, mesh.indexCount, GL11C.GL_UNSIGNED_INT, 0L, instanceCount);
                    drawCalls++;
                }
            }
            submittedInstances = Math.addExact(submittedInstances, entryCount);
            submissionNanos = metrics.delta(submissionStart);
            if (states.state() == ModelPartBackendState.READY) {
                states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE);
            }
        } catch (Throwable failure) {
            fail();
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Threadium Minecraft 1.21.1 instanced draw failed", failure);
        } finally {
            if (layerStarted) layer.endDrawing();
            previous.restore();
        }
        metrics.recordFlush(
                descriptor.kind(),
                entryCount,
                actualDrawCalls,
                sorted ? 0 : entryCount,
                sorted ? 0 : actualDrawCalls,
                sorted ? entryCount : 0,
                sorted ? actualDrawCalls : 0,
                sorted ? sortedReferences.size() : 0,
                singletonCount,
                multiCount,
                instancesInMultiDraws,
                maximumBatch);
        metrics.recordFlushTiming(metrics.delta(flushStart), packingNanos, planningNanos, uploadNanos, submissionNanos);
        IdentityHashMap<Object, ModelPartFlushStats> feedback = new IdentityHashMap<>();
        for (var entry : mutableFeedback.entrySet())
            feedback.put(entry.getKey(), entry.getValue().freeze());
        return feedback;
    }

    private static ArrayList<SortedModelPartQuads.Reference> collectSortedReferences(
            QueuedModelPartArena1211<MeshHandle> entries) {
        ArrayList<SortedModelPartQuads.Reference> references = new ArrayList<>();
        long sequence = 0;
        for (int source = 0; source < entries.size(); source++) {
            ImmutableModelPartMesh mesh = entries.mesh(source).source;
            ImmutableModelPartBonePose pose = entries.pose(source);
            ImmutableRootRenderTransform root = entries.root(source);
            for (int quad = 0; quad < mesh.quadCount(); quad++) {
                int firstVertex = Math.multiplyExact(quad, 4);
                int bone = mesh.vertexFieldBits(firstVertex, ImmutableModelPartMesh.BONE_INDEX);
                if (!pose.drawVisible(bone)) continue;
                references.add(SortedModelPartQuads.reference(source, quad, sequence++, mesh, pose, root));
            }
        }
        SortedModelPartQuads.sort(references);
        return references;
    }

    private void drawSorted(
            QueuedModelPartArena1211<MeshHandle> entries,
            ArrayList<SortedModelPartQuads.Reference> references,
            int[] packedIndexBySource) {
        int boundVao = -1;
        for (SortedModelPartQuads.Reference reference : references) {
            int source = reference.sourceIndex();
            MeshHandle mesh = entries.mesh(source);
            if (mesh.vao != boundVao) {
                GL30C.glBindVertexArray(mesh.vao);
                boundVao = mesh.vao;
            }
            bindInstanceRange(packedIndexBySource[source]);
            long indexOffset = (long) reference.quadIndex() * 6L * Integer.BYTES;
            GL31C.glDrawElementsInstanced(GL11C.GL_TRIANGLES, 6, GL11C.GL_UNSIGNED_INT, indexOffset, 1);
            drawCalls++;
        }
    }

    private static void putPose(ByteBuffer output, ImmutableModelPartBonePose pose) {
        for (int bone = 0; bone < pose.boneCount(); bone++) {
            boolean visible = pose.drawVisible(bone);
            for (int element = 0; element < ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE; element++) {
                output.putFloat(visible ? Float.intBitsToFloat(pose.positionElementBits(bone, element)) : 0.0F);
            }
            for (int column = 0; column < 3; column++) {
                for (int row = 0; row < 3; row++) {
                    int element = column * 3 + row;
                    output.putFloat(visible ? Float.intBitsToFloat(pose.normalElementBits(bone, element)) : 0.0F);
                }
                output.putFloat(column == 0 && visible ? 1.0F : 0.0F);
            }
        }
    }

    private static void putDecal(ByteBuffer output, ModelPartDecalTransform1211 decal) {
        for (int element = 0; element < ModelPartDecalTransform1211.ELEMENTS; element++) {
            output.putFloat(Float.intBitsToFloat(decal.elementBits(element)));
        }
    }

    private static void putInstance(
            ByteBuffer output, QueuedModelPartArena1211<MeshHandle> entries, int index, int boneBase, int decalBase) {
        ImmutableRootRenderTransform root = entries.root(index);
        for (int element = 0; element < ImmutableRootRenderTransform.POSITION_ELEMENTS; element++) {
            output.putFloat(Float.intBitsToFloat(root.positionElementBits(element)));
        }
        output.putInt(boneBase)
                .putInt(entries.light(index))
                .putInt(entries.overlay(index))
                .putInt(decalBase);
        int color = entries.color(index);
        output.put((byte) (color >>> 16))
                .put((byte) (color >>> 8))
                .put((byte) color)
                .put((byte) (color >>> 24));
        while (output.position() % ModelPartLayouts.INSTANCE_STRIDE != 0) output.put((byte) 0);
    }

    private void bindInstanceRange(int firstInstance) {
        long base = (long) firstInstance * ModelPartLayouts.INSTANCE_STRIDE;
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
        for (int column = 0; column < 4; column++) {
            GL20C.glVertexAttribPointer(
                    4 + column, 4, GL11C.GL_FLOAT, false, ModelPartLayouts.INSTANCE_STRIDE, base + column * 16L);
        }
        GL30C.glVertexAttribIPointer(
                8,
                1,
                GL11C.GL_INT,
                ModelPartLayouts.INSTANCE_STRIDE,
                base + ModelPartLayouts.INSTANCE_BONE_BASE_OFFSET);
        GL30C.glVertexAttribIPointer(
                9, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + ModelPartLayouts.INSTANCE_LIGHT_OFFSET);
        GL30C.glVertexAttribIPointer(
                10, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + ModelPartLayouts.INSTANCE_OVERLAY_OFFSET);
        GL20C.glVertexAttribPointer(
                11,
                4,
                GL11C.GL_UNSIGNED_BYTE,
                true,
                ModelPartLayouts.INSTANCE_STRIDE,
                base + ModelPartLayouts.INSTANCE_TINT_OFFSET);
        GL30C.glVertexAttribIPointer(
                12,
                1,
                GL11C.GL_INT,
                ModelPartLayouts.INSTANCE_STRIDE,
                base + ModelPartLayouts.INSTANCE_DECAL_BASE_OFFSET);
    }

    private static void bindTextures(int program) {
        String[] uniforms = {"Sampler0", "Sampler1", "Sampler2"};
        for (int unit = 0; unit < uniforms.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, RenderSystem.getShaderTexture(unit));
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, uniforms[unit]), unit);
        }
    }

    private static void applyVanillaUniforms(int program, RenderLayer1211Descriptor descriptor) {
        Vector3f[] lights = RenderSystemAccessor.threadium$getShaderLightDirections();
        if (lights == null || lights.length < 2 || lights[0] == null || lights[1] == null) {
            throw new IllegalStateException("Vanilla shader light directions are unavailable");
        }
        setVector3(program, "uLight0Direction", lights[0]);
        setVector3(program, "uLight1Direction", lights[1]);
        float[] shaderColor = RenderSystem.getShaderColor();
        setVector4(program, "uColorModulator", shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        float[] fogColor = RenderSystem.getShaderFogColor();
        setVector4(program, "uFogColor", fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        setFloat(program, "uFogStart", RenderSystem.getShaderFogStart());
        setFloat(program, "uFogEnd", RenderSystem.getShaderFogEnd());
        setFloat(program, "uGlintAlpha", RenderSystem.getShaderGlintAlpha());
        setInt(program, "uFogShape", RenderSystem.getShaderFogShape().getId());
        setInt(program, "uShaderMode", descriptor.shaderMode().uniformValue());
        setInt(program, "uAlphaCutout", descriptor.alphaCutout() ? 1 : 0);
    }

    private static void setVector3(int program, String name, Vector3f value) {
        int location = uniform(program, name);
        GL20C.glUniform3f(location, value.x(), value.y(), value.z());
    }

    private static void setVector4(int program, String name, float x, float y, float z, float w) {
        GL20C.glUniform4f(uniform(program, name), x, y, z, w);
    }

    private static void setFloat(int program, String name, float value) {
        GL20C.glUniform1f(uniform(program, name), value);
    }

    private static void setInt(int program, String name, int value) {
        GL20C.glUniform1i(uniform(program, name), value);
    }

    private static int uniform(int program, String name) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) throw new IllegalStateException("Missing Threadium shader uniform " + name);
        return location;
    }

    private static void setMatrix(int program, String name, Matrix4fc matrix) {
        int location = uniform(program, name);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer values = stack.mallocFloat(16);
            matrix.get(values);
            GL20C.glUniformMatrix4fv(location, false, values);
        }
    }

    private void clearQueued() {
        for (IdentityHashMap<RenderLayer, Batch> providerBatches : queued.values()) {
            for (Batch batch : providerBatches.values()) releaseBatch(batch);
        }
        queued.clear();
        queuedInstances = 0;
        orderPlanner.clear();
    }

    private void releaseGpuResources() {
        for (MeshHandle mesh : meshes.values()) mesh.delete();
        meshes.clear();
        cachedMeshBytes = 0;
        if (program != 0) GL20C.glDeleteProgram(program);
        if (boneBuffer != 0) GL15C.glDeleteBuffers(boneBuffer);
        if (boneTexture != 0) GL11C.glDeleteTextures(boneTexture);
        if (decalBuffer != 0) GL15C.glDeleteBuffers(decalBuffer);
        if (decalTexture != 0) GL11C.glDeleteTextures(decalTexture);
        if (instanceBuffer != 0) GL15C.glDeleteBuffers(instanceBuffer);
        if (boneStaging != null) MemoryUtil.memFree(boneStaging);
        if (decalStaging != null) MemoryUtil.memFree(decalStaging);
        if (instanceStaging != null) MemoryUtil.memFree(instanceStaging);
        program = 0;
        boneBuffer = 0;
        boneTexture = 0;
        decalBuffer = 0;
        decalTexture = 0;
        instanceBuffer = 0;
        boneStaging = null;
        decalStaging = null;
        instanceStaging = null;
        instancedAttributeDivisorApi = GlInstancing1211.DivisorApi.UNAVAILABLE;
        recycledBatches.clear();
    }

    private void fail() {
        metrics.recordBackendFailure();
        ModelPartBackendState state = states.state();
        if (state == ModelPartBackendState.READY || state == ModelPartBackendState.ACTIVE) {
            states.transition(state, ModelPartBackendState.FAILED);
        }
        clearQueued();
        releaseGpuResources();
    }

    private void requireOpenRenderThread() {
        if (closed) throw new IllegalStateException("Threadium GPU instance backend is closed");
        requireRenderThread();
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Threadium GPU instance backend is not on the Render Thread");
        }
    }

    private static String read(String path) throws IOException {
        try (var input = ModelPartGpuInstanceBackend.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException(path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int link(String vertexSource, String fragmentSource) {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource);
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glLinkProgram(linked);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IllegalStateException(log);
        }
        return linked;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

    private static final class MutableFlushStats {
        private int instances;
        private int drawCalls;
        private int batchableInstances;
        private int batchableDrawCalls;
        private int sortedInstances;
        private int sortedDrawCalls;
        private int singletonBatches;
        private int multiInstanceBatches;
        private int maximumInstancesPerDraw;
        private int totalInstancesInMultiDraws;

        private ModelPartFlushStats freeze() {
            return new ModelPartFlushStats(
                    instances,
                    drawCalls,
                    batchableInstances,
                    batchableDrawCalls,
                    sortedInstances,
                    sortedDrawCalls,
                    singletonBatches,
                    multiInstanceBatches,
                    maximumInstancesPerDraw,
                    totalInstancesInMultiDraws,
                    0,
                    0,
                    0,
                    0);
        }
    }

    private static final class Batch {
        private final QueuedModelPartArena1211<MeshHandle> entries;

        private Batch(int maximumCapacity) {
            entries = new QueuedModelPartArena1211<>(Math.min(16, maximumCapacity), maximumCapacity);
        }
    }

    private record MeshHandle(
            int vao, int vbo, int ibo, int indexCount, int boneCount, long bytes, ImmutableModelPartMesh source) {
        private void delete() {
            GL30C.glDeleteVertexArrays(vao);
            GL15C.glDeleteBuffers(vbo);
            GL15C.glDeleteBuffers(ibo);
        }
    }

    public record Diagnostics(
            ModelPartBackendState state,
            int queuedInstances,
            int uploadedMeshes,
            long submittedInstances,
            long drawCalls,
            long sortedInstances,
            long sortedDrawCalls,
            long sortedQuads,
            long meshUploads,
            long instanceUploadCalls,
            long boneUploadCalls,
            long instanceBytes,
            long boneBytes) {
        public ModelPartFlushStats lifetimeFlushStats() {
            return new ModelPartFlushStats(
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, submittedInstances)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, drawCalls)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, submittedInstances - sortedInstances)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, drawCalls - sortedDrawCalls)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, sortedInstances)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, sortedDrawCalls)),
                    0,
                    0,
                    0,
                    0,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, instanceUploadCalls)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, boneUploadCalls)),
                    instanceBytes,
                    boneBytes);
        }
    }
}
