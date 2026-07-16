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
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Render-thread-only OpenGL 4.5 implementation. It never initializes without a current context. */
public final class OpenGl45ModelPartBackend implements ModelPartGpuBackend {
    private final BackendStateMachine states;
    private final ArrayList<Queued> queued = new ArrayList<>();
    private final ArrayDeque<Integer> groups = new ArrayDeque<>();
    private int groupStart;
    private long epoch, groupId;
    private final int maxInstances, maxBones;
    private final boolean consolidate;
    private final DebugVisualMode debugMode;
    private int program, boneSsbo, instanceSsbo;
    private ByteBuffer boneStaging, instanceStaging;
    private final OpenGlRenderTarget renderTarget = new OpenGlRenderTarget();

    private record Queued(
            ModelPartBatchKey key,
            MeshHandle mesh,
            RenderType type,
            Matrix4fc pose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint) {}

    public OpenGl45ModelPartBackend(
            int maxInstances, int maxBones, boolean consolidate, DebugVisualMode debugMode, boolean enabled) {
        this.maxInstances = maxInstances;
        this.maxBones = maxBones;
        this.consolidate = consolidate;
        this.debugMode = debugMode;
        states =
                new BackendStateMachine(enabled ? ModelPartBackendState.UNINITIALIZED : ModelPartBackendState.DISABLED);
    }

    @Override
    public ModelPartBackendState state() {
        return states.state();
    }

    @Override
    public boolean ensureReady() {
        if (state().accepts()) return true;
        if (state() != ModelPartBackendState.UNINITIALIZED || !RenderSystem.isOnRenderThread()) return false;
        try {
            if (GL.getCapabilities() == null || !GL.getCapabilities().OpenGL45) return false;
            states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);
            program = link(
                    read("/assets/threadium/shaders/modelpart_gl45.vert"),
                    read("/assets/threadium/shaders/modelpart_gl45.frag"));
            boneSsbo = GL45C.glCreateBuffers();
            instanceSsbo = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(boneSsbo, (long) maxBones * ModelPartLayouts.BONE_STRIDE, GL45C.GL_STREAM_DRAW);
            GL45C.glNamedBufferData(
                    instanceSsbo, (long) maxInstances * ModelPartLayouts.INSTANCE_STRIDE, GL45C.GL_STREAM_DRAW);
            boneStaging = MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxBones, ModelPartLayouts.BONE_STRIDE));
            instanceStaging =
                    MemoryUtil.memAlloc(ModelPartLayouts.bytes(maxInstances, ModelPartLayouts.INSTANCE_STRIDE));
            states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY);
            return true;
        } catch (Throwable failure) {
            fail();
            return false;
        }
    }

    @Override
    public MeshHandle upload(ImmutableModelPartMesh mesh) {
        if (!state().accepts()) return null;
        int vao = 0, vbo = 0, ibo = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vao = GL45C.glCreateVertexArrays();
            vbo = GL45C.glCreateBuffers();
            ibo = GL45C.glCreateBuffers();
            FloatBuffer vb = stack.mallocFloat(mesh.vertices().length)
                    .put(mesh.vertices())
                    .flip();
            var ib = stack.mallocInt(mesh.indices().length).put(mesh.indices()).flip();
            GL45C.glNamedBufferData(vbo, vb, GL45C.GL_STATIC_DRAW);
            GL45C.glNamedBufferData(ibo, ib, GL45C.GL_STATIC_DRAW);
            GL45C.glVertexArrayVertexBuffer(vao, 0, vbo, 0, ModelPartLayouts.VERTEX_STRIDE);
            GL45C.glVertexArrayElementBuffer(vao, ibo);
            int[] sizes = {3, 3, 2, 1};
            int[] offsets = {0, 12, 24, 32};
            for (int i = 0; i < 4; i++) {
                GL45C.glEnableVertexArrayAttrib(vao, i);
                GL45C.glVertexArrayAttribFormat(vao, i, sizes[i], GL45C.GL_FLOAT, false, offsets[i]);
                GL45C.glVertexArrayAttribBinding(vao, i, 0);
            }
            return new MeshHandle(vao, vbo, ibo, mesh.indices().length, mesh.boneCount(), mesh.byteSize());
        } catch (Throwable t) {
            if (vao != 0) GL45C.glDeleteVertexArrays(vao);
            if (vbo != 0) GL45C.glDeleteBuffers(vbo);
            if (ibo != 0) GL45C.glDeleteBuffers(ibo);
            fail();
            return null;
        }
    }

    @Override
    public boolean queue(
            MeshHandle mesh,
            Object renderType,
            Matrix4fc pose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint,
            ModelPartUvTransform uvTransform,
            ModelPartDecalTransform decalTransform) {
        if (!state().accepts() || !(renderType instanceof RenderType type) || queued.size() >= maxInstances)
            return false;
        int count = bones.matrices().length / 28;
        int used = queued.stream().mapToInt(q -> q.bones.matrices().length / 28).sum();
        if (used + count > maxBones) return false;
        queued.add(new Queued(
                new ModelPartBatchKey(mesh, type, epoch, groupId),
                mesh,
                type,
                new org.joml.Matrix4f(pose),
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
            boneUpload = boneStaging.clear().limit(ModelPartLayouts.bytes(totalBones, ModelPartLayouts.BONE_STRIDE));
            instanceUpload =
                    instanceStaging.clear().limit(ModelPartLayouts.bytes(entryCount, ModelPartLayouts.INSTANCE_STRIDE));
            int boneBase = 0;
            for (int i = 0; i < entryCount; i++) {
                Queued q = queued.get(i);
                float[] values = q.bones.matrices();
                int bones = values.length / 28;
                for (int bone = 0; bone < bones; bone++)
                    for (int component = 0; component < 28; component++) {
                        boolean visible = (q.bones.visibility()[bone >>> 6] & (1L << (bone & 63))) != 0;
                        boneUpload.putFloat(visible ? values[bone * 28 + component] : 0f);
                    }
                putInstance(instanceUpload, q, boneBase);
                boneBase += bones;
            }
            boneUpload.flip();
            instanceUpload.flip();
            GL45C.glUseProgram(program);
            GL45C.glUniform1i(GL45C.glGetUniformLocation(program, "uDebugMode"), debugMode.shaderCode());
            if (debugMode == DebugVisualMode.MESH_NO_DEPTH) {
                GL45C.glDisable(GL45C.GL_DEPTH_TEST);
                GL45C.glDepthMask(false);
            }
            if (debugMode == DebugVisualMode.MESH_NO_CULL) GL45C.glDisable(GL45C.GL_CULL_FACE);
            var projection = RenderSystem.getProjectionMatrixBuffer();
            if (projection.buffer() instanceof GlBuffer gl)
                GL45C.glBindBufferRange(
                        GL45C.GL_UNIFORM_BUFFER, 0, gl.handle(), projection.offset(), projection.length());
            GL45C.glNamedBufferData(boneSsbo, (long) maxBones * ModelPartLayouts.BONE_STRIDE, GL45C.GL_STREAM_DRAW);
            GL45C.glNamedBufferSubData(boneSsbo, 0, boneUpload);
            GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 1, boneSsbo);
            GL45C.glNamedBufferData(
                    instanceSsbo, (long) maxInstances * ModelPartLayouts.INSTANCE_STRIDE, GL45C.GL_STREAM_DRAW);
            GL45C.glNamedBufferSubData(instanceSsbo, 0, instanceUpload);
            GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 2, instanceSsbo);
            int calls = 0, singletons = 0, multi = 0, max = 0, multiInstances = 0;
            for (int first = 0; first < entryCount; ) {
                int end = first + 1;
                if (consolidate) while (end < entryCount && compatible(queued.get(first), queued.get(end))) end++;
                int count = end - first;
                Queued q = queued.get(first);
                GL45C.glUniform1i(GL45C.glGetUniformLocation(program, "uInstanceBase"), first);
                PreparedRenderType prepared = q.type.prepare();
                var target = renderTarget.bind(prepared);
                if (target.status() != GL45C.GL_FRAMEBUFFER_COMPLETE) {
                    VisualDiagnosticMetrics.incomplete.incrementAndGet();
                    throw new IllegalStateException(
                            "incomplete ModelPart framebuffer: 0x" + Integer.toHexString(target.status()));
                }
                for (PreparedRenderType.Texture texture : prepared.textures())
                    if (texture.textureView() instanceof GlTextureView view) {
                        int unit =
                                switch (texture.name()) {
                                    case "Sampler0" -> 0;
                                    case "Sampler1" -> 1;
                                    case "Sampler2" -> 2;
                                    default -> -1;
                                };
                        if (unit >= 0) GL45C.glBindTextureUnit(unit, view.glId());
                    }
                GL45C.glBindVertexArray(q.mesh.vao());
                GL45C.glDrawElementsInstanced(GL45C.GL_TRIANGLES, q.mesh.indexCount(), GL45C.GL_UNSIGNED_INT, 0, count);
                if (debugMode != DebugVisualMode.OFF) {
                    VisualDiagnosticMetrics.meshes.incrementAndGet();
                    if (GL45C.glGetError() != GL45C.GL_NO_ERROR) VisualDiagnosticMetrics.glErrors.incrementAndGet();
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
                    (long) totalBones * ModelPartLayouts.BONE_STRIDE);
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

    private static void putInstance(ByteBuffer b, Queued q, int boneBase) {
        var m = q.pose;
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
        b.putInt(boneBase).putInt(q.light).putInt(q.overlay).putInt(0);
        b.putFloat(((q.tint >>> 16) & 255) / 255f)
                .putFloat(((q.tint >>> 8) & 255) / 255f)
                .putFloat((q.tint & 255) / 255f)
                .putFloat(((q.tint >>> 24) & 255) / 255f);
    }

    @Override
    public void clear() {
        queued.clear();
        groups.clear();
        epoch++;
    }

    @Override
    public void destroy(MeshHandle h) {
        if (!RenderSystem.isOnRenderThread()) return;
        GL45C.glDeleteVertexArrays(h.vao());
        GL45C.glDeleteBuffers(h.vbo());
        GL45C.glDeleteBuffers(h.ibo());
    }

    @Override
    public void close() {
        clear();
        if (RenderSystem.isOnRenderThread()) release();
    }

    private void release() {
        renderTarget.close();
        if (program != 0) GL45C.glDeleteProgram(program);
        if (boneSsbo != 0) GL45C.glDeleteBuffers(boneSsbo);
        if (instanceSsbo != 0) GL45C.glDeleteBuffers(instanceSsbo);
        if (boneStaging != null) MemoryUtil.memFree(boneStaging);
        if (instanceStaging != null) MemoryUtil.memFree(instanceStaging);
        boneStaging = instanceStaging = null;
        program = boneSsbo = instanceSsbo = 0;
    }

    private void fail() {
        ModelPartBackendState s = state();
        if (s == ModelPartBackendState.INITIALIZING
                || s == ModelPartBackendState.READY
                || s == ModelPartBackendState.ACTIVE) states.transition(s, ModelPartBackendState.FAILED);
        release();
    }

    private static String read(String path) throws IOException {
        try (var in = OpenGl45ModelPartBackend.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException(path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int link(String vs, String fs) {
        int v = compile(GL45C.GL_VERTEX_SHADER, vs),
                f = compile(GL45C.GL_FRAGMENT_SHADER, fs),
                p = GL45C.glCreateProgram();
        GL45C.glAttachShader(p, v);
        GL45C.glAttachShader(p, f);
        GL45C.glLinkProgram(p);
        GL45C.glDeleteShader(v);
        GL45C.glDeleteShader(f);
        if (GL45C.glGetProgrami(p, GL45C.GL_LINK_STATUS) == 0)
            throw new IllegalStateException(GL45C.glGetProgramInfoLog(p));
        GL45C.glUniformBlockBinding(p, GL45C.glGetUniformBlockIndex(p, "Projection"), 0);
        return p;
    }

    private static int compile(int type, String source) {
        int s = GL45C.glCreateShader(type);
        GL45C.glShaderSource(s, source);
        GL45C.glCompileShader(s);
        if (GL45C.glGetShaderi(s, GL45C.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException(GL45C.glGetShaderInfoLog(s));
        return s;
    }
}
