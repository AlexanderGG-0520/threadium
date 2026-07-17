package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.mixin.accessor.ModelPartAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;

public record GenericModelPartTopology(List<Node> nodes, StructuralKey key) {
    public record Node(ModelPart part, int parent, int index, String path) {}

    public record StructuralKey(long fingerprint, List<Long> exact) {}

    public static GenericModelPartTopology inspect(ModelPart root, int maxBones, int maxDepth, long generation) {
        ArrayList<Node> nodes = new ArrayList<>();
        ArrayList<Long> exact = new ArrayList<>();
        visit(root, -1, "root", 0, maxBones, maxDepth, nodes, exact);
        long hash = 0xcbf29ce484222325L ^ generation;
        for (long value : exact) hash = (hash ^ value) * 0x100000001b3L;
        return new GenericModelPartTopology(List.copyOf(nodes), new StructuralKey(hash, List.copyOf(exact)));
    }

    private static void visit(
            ModelPart part,
            int parent,
            String path,
            int depth,
            int maxBones,
            int maxDepth,
            List<Node> out,
            List<Long> exact) {
        if (depth > maxDepth || out.size() >= maxBones)
            throw new IllegalArgumentException("ModelPart hierarchy limit exceeded");
        int index = out.size();
        out.add(new Node(part, parent, index, path));
        ModelPartAccessor access = (ModelPartAccessor) (Object) part;
        exact.add((long) parent);
        exact.add((long) access.threadium$cubes().size());
        exact.add((long) access.threadium$children().size());
        exact.add((long) Float.floatToRawIntBits(part.getInitialPose().x()));
        exact.add((long) Float.floatToRawIntBits(part.getInitialPose().y()));
        exact.add((long) Float.floatToRawIntBits(part.getInitialPose().z()));
        for (ModelPart.Cube cube : access.threadium$cubes()) {
            exact.add((long) cube.polygons.length);
            for (ModelPart.Polygon polygon : cube.polygons) {
                exact.add((long) polygon.vertices().length);
                exact.add((long) Float.floatToRawIntBits(polygon.normal().x()));
                exact.add((long) Float.floatToRawIntBits(polygon.normal().y()));
                exact.add((long) Float.floatToRawIntBits(polygon.normal().z()));
                for (ModelPart.Vertex v : polygon.vertices()) {
                    exact.add((long) Float.floatToRawIntBits(v.worldX()));
                    exact.add((long) Float.floatToRawIntBits(v.worldY()));
                    exact.add((long) Float.floatToRawIntBits(v.worldZ()));
                    exact.add((long) Float.floatToRawIntBits(v.u()));
                    exact.add((long) Float.floatToRawIntBits(v.v()));
                }
            }
        }
        for (Map.Entry<String, ModelPart> child : access.threadium$children().entrySet())
            visit(child.getValue(), index, path + '/' + child.getKey(), depth + 1, maxBones, maxDepth, out, exact);
    }
}
