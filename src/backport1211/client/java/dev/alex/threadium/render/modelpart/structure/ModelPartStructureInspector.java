package dev.alex.threadium.render.modelpart.structure;

import dev.alex.threadium.mixin.accessor.ModelPartAccessor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;

/** Render-thread adapter that captures immutable node-local geometry through Vanilla cuboid emission. */
public final class ModelPartStructureInspector {
    private static final int MAXIMUM_CACHED_ROOTS = 256;
    private static final int MAXIMUM_UNIQUE_MESHES = 256;
    private static final long MAXIMUM_RETAINED_BYTES = 64L * 1024 * 1024;
    private static final int MAXIMUM_DEPTH = 128;
    private static final int MAXIMUM_PARTS = 1_024;
    private static final int MAXIMUM_CUBOIDS = 8_192;
    private static final int MAXIMUM_QUADS = 49_152;
    private static final int MAXIMUM_VERTICES = MAXIMUM_QUADS * 4;
    private static final int MAXIMUM_INDICES = MAXIMUM_QUADS * 6;
    private static final long MAXIMUM_MESH_BYTES = 16L * 1024 * 1024;

    private final ModelPartMeshCache cache =
            new ModelPartMeshCache(MAXIMUM_CACHED_ROOTS, MAXIMUM_UNIQUE_MESHES, MAXIMUM_RETAINED_BYTES);

    public ImmutableModelPartMesh cached(ModelPart root) {
        return cache.get(Objects.requireNonNull(root, "root"));
    }

    public boolean canCaptureNewRoot() {
        return cache.canCacheNewRoot();
    }

    public ImmutableModelPartMesh captureAndCache(ModelPart root) {
        Objects.requireNonNull(root, "root");
        if (!cache.canCacheNewRoot()) throw new ModelPartMeshCapacityException("Root cache is full");

        ModelPartMeshCapture capture = new ModelPartMeshCapture(new ModelPartMeshCapture.Limits(
                MAXIMUM_PARTS, MAXIMUM_QUADS, MAXIMUM_VERTICES, MAXIMUM_INDICES, MAXIMUM_MESH_BYTES));
        MatrixStack identityStack = new MatrixStack();
        MinecraftReader reader = new MinecraftReader(identityStack.peek());
        ModelPartStructureSnapshot structure =
                ModelPartStructureBuilder.capture(root, reader, capture, MAXIMUM_DEPTH, MAXIMUM_PARTS, MAXIMUM_CUBOIDS);
        ImmutableModelPartMesh mesh = capture.complete(structure);
        return cache.intern(root, mesh);
    }

    public int cachedRootCount() {
        return cache.rootCount();
    }

    public int uniqueMeshCount() {
        return cache.uniqueMeshCount();
    }

    public long retainedMeshBytes() {
        return cache.retainedBytes();
    }

    public void clear() {
        cache.clear();
    }

    private static final class MinecraftReader implements ModelPartStructureBuilder.Reader<ModelPart> {
        private static final int CAPTURE_LIGHT = 0x24681357;
        private static final int CAPTURE_OVERLAY = 0x13572468;
        private static final float CAPTURE_RED = 161.0F / 255.0F;
        private static final float CAPTURE_GREEN = 178.0F / 255.0F;
        private static final float CAPTURE_BLUE = 195.0F / 255.0F;
        private static final float CAPTURE_ALPHA = 212.0F / 255.0F;

        private final MatrixStack.Entry identityEntry;

        private MinecraftReader(MatrixStack.Entry identityEntry) {
            this.identityEntry = identityEntry;
        }

        @Override
        public int cuboidCount(ModelPart part) {
            return cuboids(part).size();
        }

        @Override
        public void captureCuboids(ModelPart part, int boneIndex, ModelPartMeshCapture capture) {
            List<ModelPart.Cuboid> cuboids = cuboids(part);
            VanillaCuboidCaptureConsumer consumer = new VanillaCuboidCaptureConsumer(capture);
            capture.beginNode(boneIndex);
            for (ModelPart.Cuboid cuboid : cuboids) {
                Objects.requireNonNull(cuboid, "cuboid")
                        .renderCuboid(
                                identityEntry,
                                consumer,
                                CAPTURE_LIGHT,
                                CAPTURE_OVERLAY,
                                CAPTURE_RED,
                                CAPTURE_GREEN,
                                CAPTURE_BLUE,
                                CAPTURE_ALPHA);
                capture.endCuboid();
            }
        }

        private static List<ModelPart.Cuboid> cuboids(ModelPart part) {
            ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
            return Objects.requireNonNull(accessor.threadium$getCuboids(), "cuboids");
        }

        @Override
        public Map<String, ModelPart> children(ModelPart part) {
            ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
            return Objects.requireNonNull(accessor.threadium$getChildren(), "children");
        }
    }
}
