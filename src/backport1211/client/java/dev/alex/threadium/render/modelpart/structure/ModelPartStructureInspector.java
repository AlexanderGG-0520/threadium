package dev.alex.threadium.render.modelpart.structure;

import dev.alex.threadium.mixin.accessor.ModelPartAccessor;
import dev.alex.threadium.mixin.accessor.ModelPartCuboidAccessor;
import dev.alex.threadium.mixin.accessor.ModelPartQuadAccessor;
import dev.alex.threadium.mixin.accessor.ModelPartVertexAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.model.ModelPart;
import org.joml.Vector3f;

/** Render-thread adapter from Minecraft 1.21.1 ModelPart internals to immutable structural data. */
public final class ModelPartStructureInspector {
    private static final int MAXIMUM_CACHED_ROOTS = 256;
    private static final int MAXIMUM_DEPTH = 128;
    private static final int MAXIMUM_PARTS = 1_024;
    private static final int MAXIMUM_CUBOIDS = 8_192;
    private static final int MAXIMUM_POLYGONS = 49_152;
    private static final int MAXIMUM_VERTICES = 196_608;

    private final ModelPartStructureCache cache = new ModelPartStructureCache(MAXIMUM_CACHED_ROOTS);

    public ModelPartStructureSnapshot cached(ModelPart root) {
        return cache.get(Objects.requireNonNull(root, "root"));
    }

    public boolean canInspectNewRoot() {
        return cache.hasCapacity();
    }

    public ModelPartStructureSnapshot inspectAndCache(ModelPart root) {
        Objects.requireNonNull(root, "root");
        if (!cache.hasCapacity()) throw new IllegalStateException("ModelPart structure cache capacity exceeded");

        ModelPartStructureSnapshot snapshot = ModelPartStructureBuilder.inspect(
                root, new MinecraftReader(), MAXIMUM_DEPTH, MAXIMUM_PARTS, MAXIMUM_CUBOIDS);
        cache.put(root, snapshot);
        return snapshot;
    }

    public int cachedRootCount() {
        return cache.size();
    }

    public int uniqueStructureCount() {
        return cache.uniqueStructureCount();
    }

    public void clear() {
        cache.clear();
    }

    private static final class MinecraftReader implements ModelPartStructureBuilder.Reader<ModelPart> {
        private int polygonCount;
        private int vertexCount;

        @Override
        public List<ModelPartStructureSnapshot.Cuboid> cuboids(ModelPart part) {
            ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
            List<ModelPart.Cuboid> sourceCuboids = Objects.requireNonNull(accessor.threadium$getCuboids(), "cuboids");
            ArrayList<ModelPartStructureSnapshot.Cuboid> cuboids = new ArrayList<>(sourceCuboids.size());
            for (ModelPart.Cuboid cuboid : sourceCuboids) cuboids.add(inspectCuboid(Objects.requireNonNull(cuboid)));
            return cuboids;
        }

        @Override
        public Map<String, ModelPart> children(ModelPart part) {
            ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
            return Objects.requireNonNull(accessor.threadium$getChildren(), "children");
        }

        private ModelPartStructureSnapshot.Cuboid inspectCuboid(ModelPart.Cuboid cuboid) {
            Object[] sides = ((ModelPartCuboidAccessor) (Object) cuboid).threadium$getSides();
            Objects.requireNonNull(sides, "cuboid sides");
            if ((long) polygonCount + sides.length > MAXIMUM_POLYGONS) {
                throw new IllegalArgumentException("ModelPart polygon limit exceeded");
            }

            ArrayList<ModelPartStructureSnapshot.Polygon> polygons = new ArrayList<>(sides.length);
            for (Object side : sides) polygons.add(inspectPolygon(Objects.requireNonNull(side, "cuboid side")));
            polygonCount += sides.length;
            return ModelPartStructureSnapshot.Cuboid.fromBounds(
                    cuboid.minX, cuboid.minY, cuboid.minZ, cuboid.maxX, cuboid.maxY, cuboid.maxZ, polygons);
        }

        private ModelPartStructureSnapshot.Polygon inspectPolygon(Object side) {
            ModelPartQuadAccessor accessor = (ModelPartQuadAccessor) side;
            Object[] vertices = Objects.requireNonNull(accessor.threadium$getVertices(), "quad vertices");
            if (vertices.length != 4)
                throw new IllegalArgumentException("ModelPart quad does not contain four vertices");
            if ((long) vertexCount + vertices.length > MAXIMUM_VERTICES) {
                throw new IllegalArgumentException("ModelPart vertex limit exceeded");
            }

            ArrayList<ModelPartStructureSnapshot.Vertex> copiedVertices = new ArrayList<>(vertices.length);
            for (Object vertex : vertices)
                copiedVertices.add(inspectVertex(Objects.requireNonNull(vertex, "quad vertex")));
            vertexCount += vertices.length;

            Vector3f direction = Objects.requireNonNull(accessor.threadium$getDirection(), "quad direction");
            return ModelPartStructureSnapshot.Polygon.fromNormal(
                    direction.x(), direction.y(), direction.z(), copiedVertices);
        }

        private static ModelPartStructureSnapshot.Vertex inspectVertex(Object vertex) {
            ModelPartVertexAccessor accessor = (ModelPartVertexAccessor) vertex;
            Vector3f position = Objects.requireNonNull(accessor.threadium$getPosition(), "vertex position");
            return ModelPartStructureSnapshot.Vertex.from(
                    position.x(), position.y(), position.z(), accessor.threadium$getU(), accessor.threadium$getV());
        }
    }
}
