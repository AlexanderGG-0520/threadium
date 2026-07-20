package dev.alex.threadium.render.modelpart.structure;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure deterministic pre-order traversal shared by the Minecraft adapter and synthetic unit tests. */
final class ModelPartStructureBuilder {
    private ModelPartStructureBuilder() {}

    static <T> ModelPartStructureSnapshot capture(
            T root,
            Reader<T> reader,
            ModelPartMeshCapture capture,
            int maximumDepth,
            int maximumParts,
            int maximumCuboids) {
        ArrayList<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        IdentityHashMap<T, Boolean> visited = new IdentityHashMap<>();
        TraversalState state = new TraversalState();
        visit(
                Objects.requireNonNull(root, "root"),
                -1,
                "root",
                0,
                reader,
                capture,
                maximumDepth,
                maximumParts,
                maximumCuboids,
                nodes,
                visited,
                state);
        return ModelPartStructureSnapshot.of(nodes);
    }

    private static <T> void visit(
            T part,
            int parentIndex,
            String childName,
            int depth,
            Reader<T> reader,
            ModelPartMeshCapture capture,
            int maximumDepth,
            int maximumParts,
            int maximumCuboids,
            List<ModelPartStructureSnapshot.Node> nodes,
            IdentityHashMap<T, Boolean> visited,
            TraversalState state) {
        if (depth > maximumDepth || nodes.size() >= maximumParts) {
            throw new ModelPartMeshCapacityException("ModelPart hierarchy limit exceeded");
        }
        if (visited.put(part, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("ModelPart hierarchy is cyclic or shares a child instance");
        }

        int nodeIndex = nodes.size();
        int cuboidCount = reader.cuboidCount(part);
        if (cuboidCount < 0 || (long) state.cuboidCount + cuboidCount > maximumCuboids) {
            throw new ModelPartMeshCapacityException("ModelPart cuboid limit exceeded");
        }
        state.cuboidCount += cuboidCount;
        reader.captureCuboids(part, nodeIndex, capture);
        nodes.add(new ModelPartStructureSnapshot.Node(nodeIndex, parentIndex, childName, cuboidCount));

        // The reader supplies Minecraft's map; entrySet preserves the same iteration order as render's values().
        for (Map.Entry<String, T> child :
                Objects.requireNonNull(reader.children(part), "children").entrySet()) {
            visit(
                    Objects.requireNonNull(child.getValue(), "child"),
                    nodeIndex,
                    Objects.requireNonNull(child.getKey(), "child name"),
                    depth + 1,
                    reader,
                    capture,
                    maximumDepth,
                    maximumParts,
                    maximumCuboids,
                    nodes,
                    visited,
                    state);
        }
    }

    interface Reader<T> {
        int cuboidCount(T part);

        void captureCuboids(T part, int boneIndex, ModelPartMeshCapture capture);

        Map<String, T> children(T part);
    }

    private static final class TraversalState {
        private int cuboidCount;
    }
}
