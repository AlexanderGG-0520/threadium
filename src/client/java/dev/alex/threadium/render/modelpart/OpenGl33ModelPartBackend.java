package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
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

/** OpenGL 3.3 core backend: bind-to-edit buffers, bone TBO and instanced root-matrix attributes. */
public final class OpenGl33ModelPartBackend implements ModelPartGpuBackend {
    private final BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
    private final ArrayList<Queued> queued = new ArrayList<>();
    private final ArrayDeque<Integer> groups = new ArrayDeque<>();
    private int groupStart;
    private long epoch, groupId;
    private final int maxInstances, maxBones;
    private final boolean consolidate;
    private final DebugVisualMode debugMode;
    private int program, boneBuffer, boneTexture, instanceBuffer;
    private ByteBuffer boneStaging, instanceStaging;
    private final OpenGlRenderTarget renderTarget = new OpenGlRenderTarget();

    private record Queued(
            ModelPartBatchKey key,
            MeshHandle mesh,
            RenderType type,
            Matrix4f pose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint) {}

    public OpenGl33ModelPartBackend(int maxInstances, int maxBones, boolean consolidate, DebugVisualMode debugMode) {
        this.maxInstances = maxInstances;
        this.maxBones = maxBones;
        this.consolidate = consolidate;
        this.debugMode = debugMode;
    }

    @Override
    public ModelPartBackendState state() {
        return states.state();
    }

    @Override
    public boolean ensureReady() {
        if (state().accepts()) return true;
        if (state() != ModelPartBackendState.UNINITIALIZED || !RenderSystem.isOnRenderThread()) return false;
        states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);
        try {
            program = link(
                    read("/assets/threadium/shaders/modelpart_gl33.vert"),
                    read("/assets/threadium/shaders/modelpart_gl33.frag"));
            boneBuffer = GL15C.glGenBuffers();
            boneTexture = GL11C.glGenTextures();
            instanceBuffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER, (long) maxBones * Gl33BoneLayout.BYTES_PER_BONE, GL15C.GL_STREAM_DRAW);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, boneBuffer);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) maxInstances * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);
            boneStaging = MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxBones, ModelPartLayouts.BONE_STRIDE));
            instanceStaging =
                    MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxInstances, ModelPartLayouts.INSTANCE_STRIDE));
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY);
            return true;
        } catch (Throwable failure) {
            release();
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.FAILED);
            return false;
        }
    }

    @Override
    public MeshHandle upload(ImmutableModelPartMesh mesh) {
        if (!state().accepts()) return null;
        int vao = 0, vbo = 0, ibo = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            ibo = GL15C.glGenBuffers();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            FloatBuffer vb = stack.mallocFloat(mesh.vertices().length)
                    .put(mesh.vertices())
                    .flip();
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vb, GL15C.GL_STATIC_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            var ib = stack.mallocInt(mesh.indices().length).put(mesh.indices()).flip();
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, ib, GL15C.GL_STATIC_DRAW);
            int[] sizes = {3, 3, 2, 1}, offsets = {0, 12, 24, 32};
            for (int i = 0; i < 4; i++) {
                GL20C.glEnableVertexAttribArray(i);
                GL20C.glVertexAttribPointer(
                        i, sizes[i], GL11C.GL_FLOAT, false, ModelPartLayouts.VERTEX_STRIDE, offsets[i]);
            }
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            for (int location = 4; location <= 11; location++) {
                GL20C.glEnableVertexAttribArray(location);
                GL33C.glVertexAttribDivisor(location, 1);
            }
            GL30C.glBindVertexArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            return new MeshHandle(vao, vbo, ibo, mesh.indices().length, mesh.boneCount(), mesh.byteSize());
        } catch (Throwable failure) {
            if (vao != 0) GL30C.glDeleteVertexArrays(vao);
            if (vbo != 0) GL15C.glDeleteBuffers(vbo);
            if (ibo != 0) GL15C.glDeleteBuffers(ibo);
            fail();
            return null;
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
        if (!state().accepts() || !(renderType instanceof RenderType type) || queued.size() >= maxInstances)
            return false;
        int used = 0;
        for (Queued value : queued) used += value.bones.matrices().length / 28;
        int count = bones.matrices().length / 28;
        if (used + count > maxBones) return false;
        queued.add(new Queued(
                new ModelPartBatchKey(mesh, type, epoch, groupId),
                mesh,
                type,
                new Matrix4f(rootPose),
                bones,
                light,
                overlay,
                tint));
        return true;
    }

    @Override
    public void beginGroup(boolean strictlyOrdered) {
        groupStart = queued.size();
        groupId++;
    }

    @Override
    public void endGroup() {
        groups.addLast(queued.size() - groupStart);
    }

    @Override
    public FlushStats flushGroup() {
        int entryCount = groups.isEmpty() ? queued.size() : groups.removeFirst();
        if (!state().accepts() || entryCount == 0) return FlushStats.EMPTY;
        OpenGlStateSnapshot old = OpenGlStateSnapshot.capture();
        ByteBuffer boneUpload = null, instanceUpload = null;
        try {
            int totalBones = 0;
            for (int i = 0; i < entryCount; i++)
                totalBones = Math.addExact(totalBones, queued.get(i).bones.matrices().length / 28);
            boneUpload = boneStaging.clear().limit(Gl33BoneLayout.bytesForBones(totalBones));
            instanceUpload =
                    instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
            int boneBase = 0;
            for (int i = 0; i < entryCount; i++) {
                Queued q = queued.get(i);
                float[] values = q.bones.matrices();
                int bones = values.length / 28;
                for (int bone = 0; bone < bones; bone++) {
                    boolean visible = (q.bones.visibility()[bone >>> 6] & (1L << (bone & 63))) != 0;
                    for (int component = 0; component < 28; component++)
                        boneUpload.putFloat(visible ? values[bone * 28 + component] : 0f);
                }
                putInstance(instanceUpload, q, boneBase);
                boneBase += bones;
            }
            boneUpload.flip();
            instanceUpload.flip();
            GL20C.glUseProgram(program);
            var projection = RenderSystem.getProjectionMatrixBuffer();
            if (projection.buffer() instanceof GlBuffer gl)
                GL30C.glBindBufferRange(
                        GL31C.GL_UNIFORM_BUFFER, 0, gl.handle(), projection.offset(), projection.length());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uDebugMode"), debugMode.shaderCode());
            if (debugMode == DebugVisualMode.MESH_NO_DEPTH) {
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthMask(false);
            }
            if (debugMode == DebugVisualMode.MESH_NO_CULL) GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, boneTexture);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "Bones"), 3);
            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, boneBuffer);
            GL15C.glBufferData(
                    GL31C.GL_TEXTURE_BUFFER, (long) maxBones * Gl33BoneLayout.BYTES_PER_BONE, GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL31C.GL_TEXTURE_BUFFER, 0, boneUpload);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
            GL15C.glBufferData(
                    GL15C.GL_ARRAY_BUFFER,
                    (long) maxInstances * ModelPartLayouts.INSTANCE_STRIDE,
                    GL15C.GL_STREAM_DRAW);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0, instanceUpload);
            int calls = 0, singletons = 0, multi = 0, max = 0, multiInstances = 0;
            for (int first = 0; first < entryCount; ) {
                int end = first + 1;
                if (consolidate) while (end < entryCount && compatible(queued.get(first), queued.get(end))) end++;
                int count = end - first;
                Queued q = queued.get(first);
                PreparedRenderType prepared = q.type.prepare();
                var target = renderTarget.bind(prepared);
                if (target.status() != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    VisualDiagnosticMetrics.incomplete.incrementAndGet();
                    throw new IllegalStateException(
                            "incomplete ModelPart framebuffer: 0x" + Integer.toHexString(target.status()));
                }
                bindTextures(prepared);
                GL30C.glBindVertexArray(q.mesh.vao());
                bindInstanceRange(first);
                GL31C.glDrawElementsInstanced(GL11C.GL_TRIANGLES, q.mesh.indexCount(), GL11C.GL_UNSIGNED_INT, 0, count);
                if (debugMode != DebugVisualMode.OFF) {
                    VisualDiagnosticMetrics.meshes.incrementAndGet();
                    if (GL11C.glGetError() != GL11C.GL_NO_ERROR) VisualDiagnosticMetrics.glErrors.incrementAndGet();
                }
                calls++;
                max = Math.max(max, count);
                if (count == 1) singletons++;
                else {
                    multi++;
                    multiInstances += count;
                }
                first = end;
            }
            queued.subList(0, entryCount).clear();
            if (state() == ModelPartBackendState.READY)
                states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE);
            return new FlushStats(
                    entryCount,
                    calls,
                    singletons,
                    multi,
                    max,
                    multiInstances,
                    1,
                    1,
                    (long) entryCount * ModelPartLayouts.INSTANCE_STRIDE,
                    (long) totalBones * Gl33BoneLayout.BYTES_PER_BONE);
        } catch (Throwable failure) {
            queued.clear();
            groups.clear();
            fail();
            return FlushStats.EMPTY;
        } finally {
            old.restore();
        }
    }

    private static boolean compatible(Queued a, Queued b) {
        return a.key.equals(b.key);
    }

    private static void putInstance(ByteBuffer out, Queued q, int boneBase) {
        putMatrix(out, q.pose);
        out.putInt(boneBase).putInt(q.light).putInt(q.overlay);
        out.put((byte) (q.tint >>> 16))
                .put((byte) (q.tint >>> 8))
                .put((byte) q.tint)
                .put((byte) (q.tint >>> 24));
        while (out.position() % ModelPartLayouts.INSTANCE_STRIDE != 0) out.put((byte) 0);
    }

    private static void putMatrix(ByteBuffer b, Matrix4f m) {
        b.putFloat(m.m00())
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
    }

    private void bindInstanceRange(int first) {
        long base = (long) first * ModelPartLayouts.INSTANCE_STRIDE;
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceBuffer);
        for (int column = 0; column < 4; column++)
            GL20C.glVertexAttribPointer(
                    4 + column, 4, GL11C.GL_FLOAT, false, ModelPartLayouts.INSTANCE_STRIDE, base + column * 16L);
        GL30C.glVertexAttribIPointer(8, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + 64);
        GL30C.glVertexAttribIPointer(9, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + 68);
        GL30C.glVertexAttribIPointer(10, 1, GL11C.GL_INT, ModelPartLayouts.INSTANCE_STRIDE, base + 72);
        GL20C.glVertexAttribPointer(11, 4, GL11C.GL_UNSIGNED_BYTE, true, ModelPartLayouts.INSTANCE_STRIDE, base + 76);
    }

    private static void bindTextures(PreparedRenderType prepared) {
        for (PreparedRenderType.Texture texture : prepared.textures())
            if (texture.textureView() instanceof GlTextureView view) {
                int unit =
                        switch (texture.name()) {
                            case "Sampler0" -> 0;
                            case "Sampler1" -> 1;
                            case "Sampler2" -> 2;
                            default -> -1;
                        };
                if (unit >= 0) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, view.glId());
                    GL20C.glUniform1i(
                            GL20C.glGetUniformLocation(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), texture.name()),
                            unit);
                }
            }
    }

    @Override
    public void clear() {
        queued.clear();
        groups.clear();
        epoch++;
    }

    @Override
    public void destroy(MeshHandle h) {
        if (RenderSystem.isOnRenderThread()) {
            GL30C.glDeleteVertexArrays(h.vao());
            GL15C.glDeleteBuffers(h.vbo());
            GL15C.glDeleteBuffers(h.ibo());
        }
    }

    @Override
    public void close() {
        clear();
        if (RenderSystem.isOnRenderThread()) release();
    }

    private void release() {
        renderTarget.close();
        if (program != 0) GL20C.glDeleteProgram(program);
        if (boneBuffer != 0) GL15C.glDeleteBuffers(boneBuffer);
        if (boneTexture != 0) GL11C.glDeleteTextures(boneTexture);
        if (instanceBuffer != 0) GL15C.glDeleteBuffers(instanceBuffer);
        if (boneStaging != null) MemoryUtil.memFree(boneStaging);
        if (instanceStaging != null) MemoryUtil.memFree(instanceStaging);
        boneStaging = instanceStaging = null;
        program = boneBuffer = boneTexture = instanceBuffer = 0;
    }

    private void fail() {
        ModelPartBackendState s = state();
        if (s == ModelPartBackendState.READY || s == ModelPartBackendState.ACTIVE)
            states.transition(s, ModelPartBackendState.FAILED);
        release();
    }

    private static String read(String path) throws IOException {
        try (var in = OpenGl33ModelPartBackend.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException(path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int link(String vs, String fs) {
        int v = compile(GL20C.GL_VERTEX_SHADER, vs),
                f = compile(GL20C.GL_FRAGMENT_SHADER, fs),
                p = GL20C.glCreateProgram();
        GL20C.glAttachShader(p, v);
        GL20C.glAttachShader(p, f);
        GL20C.glLinkProgram(p);
        GL20C.glDeleteShader(v);
        GL20C.glDeleteShader(f);
        if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0)
            throw new IllegalStateException(GL20C.glGetProgramInfoLog(p));
        int block = GL31C.glGetUniformBlockIndex(p, "Projection");
        if (block >= 0) GL31C.glUniformBlockBinding(p, block, 0);
        return p;
    }

    private static int compile(int type, String source) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, source);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException(GL20C.glGetShaderInfoLog(s));
        return s;
    }
}
