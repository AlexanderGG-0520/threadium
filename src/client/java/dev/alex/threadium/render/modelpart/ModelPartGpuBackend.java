package dev.alex.threadium.render.modelpart;

import org.joml.Matrix4fc;

public interface ModelPartGpuBackend extends AutoCloseable {
    ModelPartBackendState state();
    boolean ensureReady();
    default PipelineValidity pipelineValidity(Object renderType, long generation) { return PipelineValidity.uninitialized(generation); }
    default void invalidatePipelines(long generation) { }
    MeshHandle upload(ImmutableModelPartMesh mesh);
    boolean queue(MeshHandle mesh, Object renderType, Matrix4fc rootPose, ModelPartBoneData bones, int light, int overlay, int tint, ModelPartUvTransform uvTransform, ModelPartDecalTransform decalTransform);
    default void beginFrame() { }
    void beginGroup(boolean strictlyOrdered);
    void endGroup();
    FlushStats flushGroup();
    void destroy(MeshHandle mesh);
    void clear();
    @Override void close();
    record FlushStats(int instances,int drawCalls,int singletonBatches,int multiInstanceBatches,int maximumInstancesPerDraw,int totalInstancesInMultiDraws,int instanceUploadCalls,int boneUploadCalls,long instanceBytes,long boneBytes) {
        public static final FlushStats EMPTY=new FlushStats(0,0,0,0,0,0,0,0,0,0);
    }
    record MeshHandle(int vao, int vbo, int ibo, int indexCount, int boneCount, long bytes) {}
}
