package dev.alex.threadium.render.modelpart.structure;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure deterministic pre-order traversal shared by the Minecraft adapter and synthetic unit tests. */
final class ModelPartStructureBuilder {
    private ModelPartStructureBuilder() {}

    static <T> ModelPartStructureSnapshot inspect(
            T root, Reader<T> reader, int maximumDepth, int maximumParts, int maximumCuboids) {
        ArrayList<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        IdentityHashMap<T, Boolean> visited = new IdentityHashMap<>();
        visit(
                Objects.requireNonNull(root, "root"),
                -1,
                "root",
                0,
                reader,
                maximumDepth,
                maximumParts,
                maximumCuboids,
                nodes,
                visited,
                new int[1]);
        return ModelPartStructureSnapshot.of(nodes);
    }

    private static <T> void visit(
            T part,
            int parentIndex,
            String childName,
            int depth,
            Reader<T> reader,
            int maximumDepth,
            int maximumParts,
            int maximumCuboids,
            List<ModelPartStructureSnapshot.Node> nodes,
            IdentityHashMap<T, Boolean> visited,
            int[] cuboidCount) {
        if (depth > maximumDepth || nodes.size() >= maximumParts) {
            throw new IllegalArgumentException("ModelPart hierarchy limit exceeded");
        }
        if (visited.put(part, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("ModelPart hierarchy is cyclic or shares a child instance");
        }

        List<ModelPartStructureSnapshot.Cuboid> cuboids = List.copyOf(reader.cuboids(part));
        if ((long) cuboidCount[0] + cuboids.size() > maximumCuboids) {
            throw new IllegalArgumentException("ModelPart cuboid limit exceeded");
        }
        cuboidCount[0] += cuboids.size();

        int nodeIndex = nodes.size();
        nodes.add(new ModelPartStructureSnapshot.Node(nodeIndex, parentIndex, childName, cuboids));

        // The reader supplies Minecraft's map; entrySet preserves the same iteration order as render's values().
        for (Map.Entry<String, T> child :
                Objects.requireNonNull(reader.children(part), "children").entrySet()) {
            visit(
                    Objects.requireNonNull(child.getValue(), "child"),
                    nodeIndex,
                    Objects.requireNonNull(child.getKey(), "child name"),
                    depth + 1,
                    reader,
                    maximumDepth,
                    maximumParts,
                    maximumCuboids,
                    nodes,
                    visited,
                    cuboidCount);
        }
    }

    interface Reader<T> {
        List<ModelPartStructureSnapshot.Cuboid> cuboids(T part);

        Map<String, T> children(T part);
    }
}
