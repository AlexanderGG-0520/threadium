package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.mixin.accessor.ModelPartAccessor;
import java.util.ArrayList;
import net.minecraft.client.model.geom.ModelPart;

/** Copies vanilla's already-expanded polygon data; no animation state is baked. */
public final class GenericModelPartMeshBaker {
    public ImmutableModelPartMesh bake(GenericModelPartTopology topology, int maxVertices, int maxIndices) {
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> indices = new ArrayList<>();
        ArrayList<Float> quadCenters = new ArrayList<>();
        ArrayList<Integer> quadBones = new ArrayList<>();
        for (GenericModelPartTopology.Node node : topology.nodes()) {
            for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) node.part()).threadium$cubes()) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    if (polygon.vertices().length != 4)
                        throw new IllegalArgumentException("non-quad ModelPart polygon");
                    int base = vertices.size() / 9;
                    ModelPart.Vertex a = polygon.vertices()[0], c = polygon.vertices()[2];
                    // Keep Vanilla's opposite vertices. Transforming each vertex
                    // before averaging preserves the exact float operation order
                    // used by StagedVertexBuffer's decoded sorting points.
                    quadCenters.add(a.worldX());
                    quadCenters.add(a.worldY());
                    quadCenters.add(a.worldZ());
                    quadCenters.add(c.worldX());
                    quadCenters.add(c.worldY());
                    quadCenters.add(c.worldZ());
                    quadBones.add(node.index());
                    for (ModelPart.Vertex v : polygon.vertices()) {
                        vertices.add(v.worldX());
                        vertices.add(v.worldY());
                        vertices.add(v.worldZ());
                        vertices.add(polygon.normal().x());
                        vertices.add(polygon.normal().y());
                        vertices.add(polygon.normal().z());
                        vertices.add(v.u());
                        vertices.add(v.v());
                        vertices.add((float) node.index());
                    }
                    indices.add(base);
                    indices.add(base + 1);
                    indices.add(base + 2);
                    indices.add(base + 2);
                    indices.add(base + 3);
                    indices.add(base);
                    if (base + 4 > maxVertices || indices.size() > maxIndices)
                        throw new IllegalArgumentException("mesh limit exceeded");
                }
            }
        }
        float[] v = new float[vertices.size()];
        for (int i = 0; i < v.length; i++) v[i] = vertices.get(i);
        int[] ix = new int[indices.size()];
        for (int i = 0; i < ix.length; i++) ix[i] = indices.get(i);
        float[] centers = new float[quadCenters.size()];
        for (int i = 0; i < centers.length; i++) centers[i] = quadCenters.get(i);
        int[] bones = new int[quadBones.size()];
        for (int i = 0; i < bones.length; i++) bones[i] = quadBones.get(i);
        return new ImmutableModelPartMesh(
                v, ix, centers, bones, topology.nodes().size(), topology.key());
    }
}
