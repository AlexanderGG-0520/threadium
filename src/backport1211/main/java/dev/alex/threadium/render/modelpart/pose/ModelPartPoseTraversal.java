package dev.alex.threadium.render.modelpart.pose;

import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared deterministic pre-order traversal that validates pose indices against the immutable M1 structure. */
public final class ModelPartPoseTraversal {
    private ModelPartPoseTraversal() {}

    public static <T> ImmutableModelPartBonePose capture(
            T root,
            ModelPartStructureSnapshot structure,
            Reader<T> reader,
            ModelPartPoseCapture capture,
            int maximumDepth) {
        return capture(root, structure, reader, capture, maximumDepth, new Scratch<>());
    }

    public static <T> ImmutableModelPartBonePose capture(
            T root,
            ModelPartStructureSnapshot structure,
            Reader<T> reader,
            ModelPartPoseCapture capture,
            int maximumDepth,
            Scratch<T> scratch) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(capture, "capture");
        Scratch<T> state = Objects.requireNonNull(scratch, "scratch");
        state.reset();
        visit(root, -1, "root", 0, true, structure, reader, capture, maximumDepth, state.visited, state.cursor);
        if (state.cursor.nextBone != structure.partCount()) {
            throw new IllegalArgumentException("Pose hierarchy ended before the immutable mesh structure");
        }
        return capture.complete();
    }

    private static <T> void visit(
            T part,
            int parentIndex,
            String childName,
            int depth,
            boolean parentTreeVisible,
            ModelPartStructureSnapshot structure,
            Reader<T> reader,
            ModelPartPoseCapture capture,
            int maximumDepth,
            IdentityHashMap<T, Boolean> visited,
            Cursor cursor) {
        if (depth > maximumDepth || cursor.nextBone >= structure.partCount()) {
            throw new ModelPartPoseCapacityException("ModelPart pose hierarchy limit exceeded");
        }
        if (visited.put(part, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("ModelPart pose hierarchy is cyclic or shares a child instance");
        }

        int boneIndex = cursor.nextBone++;
        ModelPartStructureSnapshot.Node expected = structure.nodes().get(boneIndex);
        if (expected.index() != boneIndex
                || expected.parentIndex() != parentIndex
                || !expected.childName().equals(childName)
                || expected.cuboidCount() != reader.cuboidCount(part)) {
            throw new IllegalArgumentException("ModelPart pose hierarchy no longer matches its immutable mesh");
        }

        reader.push();
        try {
            reader.applyTransform(part);
            boolean treeVisible = parentTreeVisible && reader.visible(part);
            boolean drawVisible = treeVisible && !reader.hidden(part);
            reader.captureMatrices(part, boneIndex, treeVisible, drawVisible, capture);
            for (Map.Entry<String, T> child :
                    Objects.requireNonNull(reader.children(part), "children").entrySet()) {
                visit(
                        Objects.requireNonNull(child.getValue(), "child"),
                        boneIndex,
                        Objects.requireNonNull(child.getKey(), "child name"),
                        depth + 1,
                        treeVisible,
                        structure,
                        reader,
                        capture,
                        maximumDepth,
                        visited,
                        cursor);
            }
        } finally {
            reader.pop();
        }
    }

    public interface Reader<T> {
        int cuboidCount(T part);

        Map<String, T> children(T part);

        boolean visible(T part);

        boolean hidden(T part);

        void push();

        void applyTransform(T part);

        void captureMatrices(
                T part, int boneIndex, boolean treeVisible, boolean drawVisible, ModelPartPoseCapture capture);

        void pop();
    }

    /** Reusable traversal bookkeeping for render-thread-owned pose capture. */
    public static final class Scratch<T> {
        private final IdentityHashMap<T, Boolean> visited = new IdentityHashMap<>();
        private final Cursor cursor = new Cursor();

        private void reset() {
            visited.clear();
            cursor.nextBone = 0;
        }
    }

    private static final class Cursor {
        private int nextBone;
    }
}
