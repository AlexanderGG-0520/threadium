package dev.alex.threadium.render.modelpart.pose;

import dev.alex.threadium.mixin.accessor.ModelPartAccessor;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;

/** Render-thread adapter for exact local-to-root bone pose and incoming root-transform capture. */
public final class ModelPartPoseInspector {
    private static final int MAXIMUM_DEPTH = 128;
    private static final int MAXIMUM_BONES = 1_024;
    private static final long MAXIMUM_MATRIX_ELEMENTS = (long) MAXIMUM_BONES
            * (ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE
                    + ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE);
    private static final int MAXIMUM_VISIBILITY_WORDS = (MAXIMUM_BONES + 63) / 64;
    private static final long MAXIMUM_POSE_BYTES = 128L * 1024;
    private static final int MAXIMUM_UNIQUE_POSES_PER_FRAME = 1_024;
    private static final long MAXIMUM_RETAINED_POSE_BYTES_PER_FRAME = 64L * 1024 * 1024;

    private static final ModelPartPoseCapture.Limits CAPTURE_LIMITS = new ModelPartPoseCapture.Limits(
            MAXIMUM_BONES, MAXIMUM_MATRIX_ELEMENTS, MAXIMUM_VISIBILITY_WORDS, MAXIMUM_POSE_BYTES);

    private final ModelPartPoseCache cache =
            new ModelPartPoseCache(MAXIMUM_UNIQUE_POSES_PER_FRAME, MAXIMUM_RETAINED_POSE_BYTES_PER_FRAME);

    public ImmutableRootRenderTransform captureRoot(MatrixStack.Entry incomingEntry) {
        Objects.requireNonNull(incomingEntry, "incomingEntry");
        float[] position = new float[ImmutableRootRenderTransform.POSITION_ELEMENTS];
        float[] normal = new float[ImmutableRootRenderTransform.NORMAL_ELEMENTS];
        incomingEntry.getPositionMatrix().get(position);
        incomingEntry.getNormalMatrix().get(normal);
        return ImmutableRootRenderTransform.fromFloats(position, normal, false);
    }

    public PoseObservation capturePose(ModelPart root, ImmutableModelPartMesh mesh) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mesh, "mesh");

        ModelPartPoseCapture capture = new ModelPartPoseCapture(mesh.partCount(), CAPTURE_LIMITS);
        MinecraftReader reader = new MinecraftReader(new MatrixStack());
        ImmutableModelPartBonePose candidate =
                ModelPartPoseTraversal.capture(root, mesh.structure(), reader, capture, MAXIMUM_DEPTH);
        ModelPartPoseCache.InternResult interned = cache.intern(candidate);
        return new PoseObservation(interned.pose(), interned.hit());
    }

    public int uniquePoseCount() {
        return cache.uniquePoseCount();
    }

    public long retainedPoseBytes() {
        return cache.retainedBytes();
    }

    public void beginFrame() {
        cache.clear();
    }

    public void clear() {
        cache.clear();
    }

    public record PoseObservation(ImmutableModelPartBonePose pose, boolean poseCacheHit) {}

    private static final class MinecraftReader implements ModelPartPoseTraversal.Reader<ModelPart> {
        private final MatrixStack matrices;
        private final float[] positionScratch = new float[ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE];
        private final float[] normalScratch = new float[ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE];

        private MinecraftReader(MatrixStack matrices) {
            this.matrices = matrices;
        }

        @Override
        public int cuboidCount(ModelPart part) {
            return cuboids(part).size();
        }

        @Override
        public Map<String, ModelPart> children(ModelPart part) {
            return Objects.requireNonNull(((ModelPartAccessor) (Object) part).threadium$getChildren(), "children");
        }

        @Override
        public boolean visible(ModelPart part) {
            return part.visible;
        }

        @Override
        public boolean hidden(ModelPart part) {
            return part.hidden;
        }

        @Override
        public void push() {
            matrices.push();
        }

        @Override
        public void applyTransform(ModelPart part) {
            part.rotate(matrices);
        }

        @Override
        public void captureMatrices(
                ModelPart part, int boneIndex, boolean treeVisible, boolean drawVisible, ModelPartPoseCapture capture) {
            MatrixStack.Entry entry = matrices.peek();
            entry.getPositionMatrix().get(positionScratch);
            entry.getNormalMatrix().get(normalScratch);
            capture.captureBone(boneIndex, positionScratch, normalScratch, treeVisible, drawVisible, false);
        }

        @Override
        public void pop() {
            matrices.pop();
        }

        private static List<ModelPart.Cuboid> cuboids(ModelPart part) {
            return Objects.requireNonNull(((ModelPartAccessor) (Object) part).threadium$getCuboids(), "cuboids");
        }
    }
}
