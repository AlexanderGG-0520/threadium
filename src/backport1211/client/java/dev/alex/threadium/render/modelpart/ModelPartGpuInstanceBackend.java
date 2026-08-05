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
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Minecraft 1.21.1 OpenGL 3.3 ModelPart backend.
 *
 * <p>Immutable structural meshes are uploaded once. A provider-layer flush uploads one exact frame-local bone palette
 * and one packed instance stream, then submits compatible meshes through {@code glDrawElementsInstanced}. No expanded
 * entity vertex stream is built on the GPU path.
 */
public final class ModelPartGpuInstanceBackend implements AutoCloseable {
    private final ThreadiumConfig config = ThreadiumRuntimeConfig.current();
    private final int maximumQueuedInstances = config.gpuMaxInstances();
    private final int maximumUploadedBones = config.gpuMaxBonesPerFrame();
    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final IdentityHashMap<ImmutableModelPartMesh, MeshHandle> meshes = new IdentityHashMap<>();
    private final IdentityHashMap<Object, IdentityHashMap<RenderLayer, Batch>> queued = new IdentityHashMap<>();
    private final InstanceSubmissionOrderPlanner orderPlanner = new InstanceSubmissionOrderPlanner();
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

    public static boolean configured() {
        return ThreadiumRuntimeConfig.current().gpuReplacementEnabled();
    }

    public ModelPartBackendState state() {
        return states.state();
    }

    public boolean queue(
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
        if (!validGeometry(mesh, pose, root, 1) || !validMaterial(provider, descriptor, decal) || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshHandle(mesh);
        if (handle == null) return false;
        append(
                provider,
                new Entry(
                        handle,
                        descriptor,
                        pose,
                        root,
                        light,
                        overlay,
                        color,
                        decal,
                        worldGeneration,
                        resourceGeneration));
        return true;
    }

    public boolean queuePair(
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
        if (!validGeometry(mesh, pose, root, 2)
                || !validMaterial(baseProvider, baseDescriptor, null)
                || !validMaterial(crumblingProvider, crumblingDescriptor, decal)
                || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshHandle(mesh);
        if (handle == null) return false;
        Entry base = new Entry(
                handle, baseDescriptor, pose, root, light, overlay, color, null, worldGeneration, resourceGeneration);
        Entry crumbling = new Entry(
                handle,
                crumblingDescriptor,
                pose,
                root,
                light,
                overlay,
                color,
                decal,
                worldGeneration,
                resourceGeneration);
        appendPair(baseProvider, base, crumblingProvider, crumbling);
        return true;
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

    private void append(Object provider, Entry entry) {
        batch(provider, entry.descriptor.layer()).entries.add(entry);
        queuedInstances++;
    }

    private void appendPair(Object firstProvider, Entry first, Object secondProvider, Entry second) {
        Batch firstBatch = batch(firstProvider, first.descriptor.layer());
        Batch secondBatch = batch(secondProvider, second.descriptor.layer());
        int firstSize = firstBatch.entries.size();
        int secondSize = secondBatch == firstBatch ? firstSize : secondBatch.entries.size();
        try {
            firstBatch.entries.add(first);
            secondBatch.entries.add(second);
            queuedInstances += 2;
        } catch (Throwable failure) {
            truncate(firstBatch.entries, firstSize);
            if (secondBatch != firstBatch) truncate(secondBatch.entries, secondSize);
            removeEmptyBatch(firstProvider, first.descriptor.layer(), firstBatch);
            removeEmptyBatch(secondProvider, second.descriptor.layer(), secondBatch);
            throw failure;
        }
    }

    private Batch batch(Object provider, RenderLayer layer) {
        return queued.computeIfAbsent(provider, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(layer, ignored -> new Batch());
    }

    private void removeEmptyBatch(Object provider, RenderLayer layer, Batch expected) {
        if (!expected.entries.isEmpty()) return;
        IdentityHashMap<RenderLayer, Batch> providerBatches = queued.get(provider);
        if (providerBatches == null || providerBatches.get(layer) != expected) return;
        providerBatches.remove(layer);
        if (providerBatches.isEmpty()) queued.remove(provider);
    }

    private static void truncate(ArrayList<Entry> entries, int size) {
        while (entries.size() > size) entries.removeLast();
    }

    public boolean flush(
            Object provider, RenderLayer layer, long currentWorldGeneration, long currentResourceGeneration) {
        requireOpenRenderThread();
        IdentityHashMap<RenderLayer, Batch> providerBatches = queued.get(provider);
        if (providerBatches == null) return false;
        Batch batch = providerBatches.remove(layer);
        if (batch == null) return false;
        if (providerBatches.isEmpty()) queued.remove(provider);
        queuedInstances -= batch.entries.size();
        for (Entry entry : batch.entries) {
            if (entry.worldGeneration != currentWorldGeneration
                    || entry.resourceGeneration != currentResourceGeneration) {
                fail();
                throw new IllegalStateException("Threadium rejected a stale Minecraft 1.21.1 GPU submission");
            }
        }
        drawBatch(layer, batch.entries);
        return true;
    }

    public void verifyFrameDrained() {
        requireOpenRenderThread();
        if (queuedInstances != 0 || !queued.isEmpty()) {
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
        try {
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
            return true;
        } catch (Throwable failure) {
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
                GL33C.glVertexAttribDivisor(location, 1);
            }
            GL30C.glBindVertexArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            meshUploads++;
            return new MeshHandle(vao, vbo, ibo, mesh.indexCount(), mesh.partCount(), mesh.retainedBytes(), mesh);
        } catch (Throwable failure) {
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

    private void drawBatch(RenderLayer layer, ArrayList<Entry> entries) {
        if (entries.isEmpty() || !states.state().accepts()) return;
        int entryCount = entries.size();
        IdentityHashMap<ImmutableModelPartBonePose, Integer> boneBases = new IdentityHashMap<>();
        ArrayList<ImmutableModelPartBonePose> palette = new ArrayList<>();
        int[] boneBaseBySource = new int[entryCount];
        int totalBones = 0;
        for (int index = 0; index < entryCount; index++) {
            ImmutableModelPartBonePose pose = entries.get(index).pose;
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
        for (int index = 0; index < entryCount; index++) {
            sourceIndices[index] = index;
            keys[index] = entries.get(index).mesh;
        }
        RenderLayer1211Descriptor descriptor = entries.getFirst().descriptor;
        boolean crumbling = descriptor.kind() == RenderLayer1211Descriptor.Kind.CRUMBLING;
        boolean sorted = descriptor.submissionPolicy() == RenderLayer1211Descriptor.SubmissionPolicy.SORTED_QUAD_STREAM;
        boolean reorderable =
                descriptor.submissionPolicy() == RenderLayer1211Descriptor.SubmissionPolicy.OPAQUE_BATCHED;
        InstanceSubmissionOrderPlanner.Plan plan = orderPlanner.plan(
                sourceIndices, entryCount, keys, entryCount, 0, reorderable, config.gpuBatchConsolidation());

        int[] packedIndexBySource = new int[entryCount];
        ByteBuffer instances =
                instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
        ByteBuffer decals = crumbling
                ? decalStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.BONE_STRIDE))
                : null;
        for (int packed = 0; packed < entryCount; packed++) {
            int source = plan.packedSourceIndices()[packed];
            Entry entry = entries.get(source);
            packedIndexBySource[source] = packed;
            int decalBase = -1;
            if (crumbling) {
                if (entry.decal == null) throw new IllegalStateException("Missing crumbling decal transform");
                decalBase = packed;
                putDecal(decals, entry.decal);
            }
            putInstance(instances, entry, boneBaseBySource[source], decalBase);
        }
        instances.flip();
        if (decals != null) decals.flip();
        ArrayList<SortedModelPartQuads.Reference> sortedReferences = sorted ? collectSortedReferences(entries) : null;

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

            if (sorted) {
                drawSorted(entries, sortedReferences, packedIndexBySource);
                sortedInstances = Math.addExact(sortedInstances, entryCount);
                sortedDrawCalls = Math.addExact(sortedDrawCalls, sortedReferences.size());
                sortedQuads = Math.addExact(sortedQuads, sortedReferences.size());
            } else {
                for (int batch = 0; batch < plan.batchCount(); batch++) {
                    int representative = plan.representativeSourceIndices()[batch];
                    MeshHandle mesh = entries.get(representative).mesh;
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
    }

    private static ArrayList<SortedModelPartQuads.Reference> collectSortedReferences(ArrayList<Entry> entries) {
        ArrayList<SortedModelPartQuads.Reference> references = new ArrayList<>();
        long sequence = 0;
        for (int source = 0; source < entries.size(); source++) {
            Entry entry = entries.get(source);
            ImmutableModelPartMesh mesh = entry.mesh.source;
            for (int quad = 0; quad < mesh.quadCount(); quad++) {
                int firstVertex = Math.multiplyExact(quad, 4);
                int bone = mesh.vertexFieldBits(firstVertex, ImmutableModelPartMesh.BONE_INDEX);
                if (!entry.pose.drawVisible(bone)) continue;
                references.add(SortedModelPartQuads.reference(source, quad, sequence++, mesh, entry.pose, entry.root));
            }
        }
        SortedModelPartQuads.sort(references);
        return references;
    }

    private void drawSorted(
            ArrayList<Entry> entries, ArrayList<SortedModelPartQuads.Reference> references, int[] packedIndexBySource) {
        int boundVao = -1;
        for (SortedModelPartQuads.Reference reference : references) {
            int source = reference.sourceIndex();
            Entry entry = entries.get(source);
            MeshHandle mesh = entry.mesh;
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

    private static void putInstance(ByteBuffer output, Entry entry, int boneBase, int decalBase) {
        for (int element = 0; element < ImmutableRootRenderTransform.POSITION_ELEMENTS; element++) {
            output.putFloat(Float.intBitsToFloat(entry.root.positionElementBits(element)));
        }
        output.putInt(boneBase).putInt(entry.light).putInt(entry.overlay).putInt(decalBase);
        output.put((byte) (entry.color >>> 16))
                .put((byte) (entry.color >>> 8))
                .put((byte) entry.color)
                .put((byte) (entry.color >>> 24));
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
    }

    private void fail() {
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

    private static final class Batch {
        private final ArrayList<Entry> entries = new ArrayList<>();
    }

    private record Entry(
            MeshHandle mesh,
            RenderLayer1211Descriptor descriptor,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            ModelPartDecalTransform1211 decal,
            long worldGeneration,
            long resourceGeneration) {}

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
