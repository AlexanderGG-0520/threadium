package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.systems.RenderSystem;
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
    private static final boolean CONFIGURED = Boolean.getBoolean("threadium.backport1211.gpu");
    private static final int MAXIMUM_QUEUED_INSTANCES = 4_096;
    private static final int MAXIMUM_UPLOADED_BONES = 65_536;

    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final IdentityHashMap<ImmutableModelPartMesh, MeshHandle> meshes = new IdentityHashMap<>();
    private final IdentityHashMap<Object, IdentityHashMap<RenderLayer, Batch>> queued = new IdentityHashMap<>();
    private final InstanceSubmissionOrderPlanner orderPlanner = new InstanceSubmissionOrderPlanner();
    private int program;
    private int boneBuffer;
    private int boneTexture;
    private int instanceBuffer;
    private ByteBuffer boneStaging;
    private ByteBuffer instanceStaging;
    private int queuedInstances;
    private long submittedInstances;
    private long drawCalls;
    private long meshUploads;
    private long instanceUploadCalls;
    private long boneUploadCalls;
    private long instanceBytes;
    private long boneBytes;
    private boolean closed;

    public static boolean configured() {
        return CONFIGURED;
    }

    public ModelPartBackendState state() {
        return states.state();
    }

    public boolean queue(
            Object provider,
            RenderLayer layer,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            long worldGeneration,
            long resourceGeneration) {
        requireOpenRenderThread();
        if (!CONFIGURED
                || !(provider instanceof VertexConsumerProvider.Immediate)
                || layer == null
                || mesh == null
                || pose == null
                || root == null
                || !pose.finite()
                || !root.finite()
                || pose.boneCount() != mesh.partCount()
                || queuedInstances >= MAXIMUM_QUEUED_INSTANCES
                || !ensureReady()) {
            return false;
        }
        MeshHandle handle = meshes.get(mesh);
        if (handle == null) {
            handle = upload(mesh);
            if (handle == null) return false;
            meshes.put(mesh, handle);
        }
        IdentityHashMap<RenderLayer, Batch> providerBatches =
                queued.computeIfAbsent(provider, ignored -> new IdentityHashMap<>());
        providerBatches
                .computeIfAbsent(layer, ignored -> new Batch())
                .entries
                .add(new Entry(
                        handle,
                        pose,
                        root,
                        light,
                        overlay,
                        color,
                        worldGeneration,
                        resourceGeneration));
        queuedInstances++;
        return true;
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
            instanceBuffer = GL15C.glGenBuffers();

            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER,
                    (long) MAXIMUM_UPLOADED_BONES * Gl33BoneLayout.BYTES_PER_BONE,
                    GL15C.GL_STREAM_DRAW);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, boneBuffer);

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) MAXIMUM_QUEUED_INSTANCES * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);

            boneStaging = MemoryUtil.memAlloc(
                    ModelPartLayouts.bytes(MAXIMUM_UPLOADED_BONES, ModelPartLayouts.BONE_STRIDE));
            instanceStaging = MemoryUtil.memAlloc(
                    ModelPartLayouts.bytes(MAXIMUM_QUEUED_INSTANCES, ModelPartLayouts.INSTANCE_STRIDE));
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            ibo = GL15C.glGenBuffers();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vertices = stack.malloc(Math.multiplyExact(mesh.vertexCount(), ModelPartLayouts.VERTEX_STRIDE));
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                for (int field = 0; field < 8; field++) {
                    vertices.putInt(mesh.vertexFieldBits(vertex, field));
                }
                vertices.putFloat(mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX));
            }
            vertices.flip();
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);

            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            IntBuffer indices = stack.mallocInt(mesh.indexCount());
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
            for (int location = 4; location <= 11; location++) {
                GL20C.glEnableVertexAttribArray(location);
                GL33C.glVertexAttribDivisor(location, 1);
            }
            GL30C.glBindVertexArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            meshUploads++;
            return new MeshHandle(vao, vbo, ibo, mesh.indexCount(), mesh.partCount(), mesh.retainedBytes());
        } catch (Throwable failure) {
            if (vao != 0) GL30C.glDeleteVertexArrays(vao);
            if (vbo != 0) GL15C.glDeleteBuffers(vbo);
            if (ibo != 0) GL15C.glDeleteBuffers(ibo);
            fail();
            return null;
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
                if (totalBones > MAXIMUM_UPLOADED_BONES) {
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
        boolean reorderable = !layer.isTranslucent();
        InstanceSubmissionOrderPlanner.Plan plan =
                orderPlanner.plan(sourceIndices, entryCount, keys, entryCount, 0, reorderable, true);

        ByteBuffer instances =
                instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
        for (int packed = 0; packed < entryCount; packed++) {
            int source = plan.packedSourceIndices()[packed];
            putInstance(instances, entries.get(source), boneBaseBySource[source]);
        }
        instances.flip();

        OpenGlStateSnapshot1211 previous = OpenGlStateSnapshot1211.capture();
        boolean layerStarted = false;
        try {
            layer.startDrawing();
            layerStarted = true;
            GL20C.glUseProgram(program);
            setMatrix(program, "uProjection", RenderSystem.getProjectionMatrix());
            setMatrix(program, "uModelView", RenderSystem.getModelViewMatrix());
            bindTextures(program);

            GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "Bones"), 3);

            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER,
                    (long) MAXIMUM_UPLOADED_BONES * Gl33BoneLayout.BYTES_PER_BONE,
                    GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL31C.GL_TEXTURE_BUFFER, 0, bones);
            boneUploadCalls++;
            boneBytes = Math.addExact(boneBytes, bones.remaining());

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) MAXIMUM_QUEUED_INSTANCES * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0, instances);
            instanceUploadCalls++;
            instanceBytes = Math.addExact(instanceBytes, instances.remaining());

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

    private static void putInstance(ByteBuffer output, Entry entry, int boneBase) {
        for (int element = 0; element < ImmutableRootRenderTransform.POSITION_ELEMENTS; element++) {
            output.putFloat(Float.intBitsToFloat(entry.root.positionElementBits(element)));
        }
        output.putInt(boneBase).putInt(entry.light).putInt(entry.overlay);
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
                    4 + column,
                    4,
                    GL11C.GL_FLOAT,
                    false,
                    ModelPartLayouts.INSTANCE_STRIDE,
                    base + column * 16L);
        }
        GL30C.glVertexAttribIPointer(
                8, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + ModelPartLayouts.INSTANCE_BONE_BASE_OFFSET);
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
    }

    private static void bindTextures(int program) {
        String[] uniforms = {"Sampler0", "Sampler1", "Sampler2"};
        for (int unit = 0; unit < uniforms.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, RenderSystem.getShaderTexture(unit));
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, uniforms[unit]), unit);
        }
    }

    private static void setMatrix(int program, String name, Matrix4fc matrix) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) throw new IllegalStateException("Missing Threadium shader uniform " + name);
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
        for (MeshHandle mesh : meshes.values()) {
            GL30C.glDeleteVertexArrays(mesh.vao);
            GL15C.glDeleteBuffers(mesh.vbo);
            GL15C.glDeleteBuffers(mesh.ibo);
        }
        meshes.clear();
        if (program != 0) GL20C.glDeleteProgram(program);
        if (boneBuffer != 0) GL15C.glDeleteBuffers(boneBuffer);
        if (boneTexture != 0) GL11C.glDeleteTextures(boneTexture);
        if (instanceBuffer != 0) GL15C.glDeleteBuffers(instanceBuffer);
        if (boneStaging != null) MemoryUtil.memFree(boneStaging);
        if (instanceStaging != null) MemoryUtil.memFree(instanceStaging);
        program = 0;
        boneBuffer = 0;
        boneTexture = 0;
        instanceBuffer = 0;
        boneStaging = null;
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
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException(log);
        }
        return program;
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
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            long worldGeneration,
            long resourceGeneration) {}

    private record MeshHandle(int vao, int vbo, int ibo, int indexCount, int boneCount, long bytes) {}

    public record Diagnostics(
            ModelPartBackendState state,
            int queuedInstances,
            int uploadedMeshes,
            long submittedInstances,
            long drawCalls,
            long meshUploads,
            long instanceUploadCalls,
            long boneUploadCalls,
            long instanceBytes,
            long boneBytes) {
        public ModelPartFlushStats lifetimeFlushStats() {
            return new ModelPartFlushStats(
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, submittedInstances)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, drawCalls)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, submittedInstances)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, drawCalls)),
                    0,
                    0,
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
