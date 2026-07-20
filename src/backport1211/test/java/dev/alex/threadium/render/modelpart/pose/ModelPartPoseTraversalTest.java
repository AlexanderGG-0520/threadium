package dev.alex.threadium.render.modelpart.pose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class ModelPartPoseTraversalTest {
    @Test
    void preOrderBoneIndicesAndParentCompositionMatchM1Structure() {
        Node root = new Node().translate(1, 0, 0);
        Node first = new Node().translate(0, 2, 0);
        Node grandchild = new Node().translate(0, 0, 3);
        root.children.put("first", first);
        first.children.put("grandchild", grandchild);
        root.children.put("second", new Node().translate(4, 0, 0));
        ModelPartStructureSnapshot structure = ModelPartStructureSnapshot.of(List.of(
                new ModelPartStructureSnapshot.Node(0, -1, "root", 0),
                new ModelPartStructureSnapshot.Node(1, 0, "first", 0),
                new ModelPartStructureSnapshot.Node(2, 1, "grandchild", 0),
                new ModelPartStructureSnapshot.Node(3, 0, "second", 0)));

        ImmutableModelPartBonePose pose = capture(root, structure);

        assertEquals(1.0F, value(pose.positionElementBits(0, 12)));
        assertEquals(1.0F, value(pose.positionElementBits(1, 12)));
        assertEquals(2.0F, value(pose.positionElementBits(1, 13)));
        assertEquals(3.0F, value(pose.positionElementBits(2, 14)));
        assertEquals(5.0F, value(pose.positionElementBits(3, 12)));
    }

    @Test
    void visibleFalseSuppressesSubtreeWhileHiddenSuppressesOnlyCurrentDraw() {
        Node root = new Node();
        Node hidden = new Node();
        hidden.hidden = true;
        hidden.children.put("visible-child", new Node());
        Node invisible = new Node();
        invisible.visible = false;
        invisible.children.put("suppressed-child", new Node());
        root.children.put("hidden", hidden);
        root.children.put("invisible", invisible);
        ModelPartStructureSnapshot structure = ModelPartStructureSnapshot.of(List.of(
                new ModelPartStructureSnapshot.Node(0, -1, "root", 0),
                new ModelPartStructureSnapshot.Node(1, 0, "hidden", 0),
                new ModelPartStructureSnapshot.Node(2, 1, "visible-child", 0),
                new ModelPartStructureSnapshot.Node(3, 0, "invisible", 0),
                new ModelPartStructureSnapshot.Node(4, 3, "suppressed-child", 0)));

        ImmutableModelPartBonePose pose = capture(root, structure);

        assertTrue(pose.drawVisible(0));
        assertTrue(pose.treeVisible(1));
        assertFalse(pose.drawVisible(1));
        assertTrue(pose.drawVisible(2));
        assertFalse(pose.treeVisible(3));
        assertFalse(pose.drawVisible(3));
        assertFalse(pose.treeVisible(4));
        assertFalse(pose.drawVisible(4));
    }

    @Test
    void hierarchyMismatchFailsAndEveryPushedScopeIsPopped() {
        Node root = new Node();
        root.children.put("actual", new Node());
        ModelPartStructureSnapshot wrong = ModelPartStructureSnapshot.of(List.of(
                new ModelPartStructureSnapshot.Node(0, -1, "root", 0),
                new ModelPartStructureSnapshot.Node(1, 0, "different", 0)));
        Reader reader = new Reader();
        ModelPartPoseCapture capture = new ModelPartPoseCapture(2, ModelPartPoseTestFixtures.LIMITS);

        assertThrows(
                IllegalArgumentException.class, () -> ModelPartPoseTraversal.capture(root, wrong, reader, capture, 8));
        assertEquals(1, reader.stack.size());
    }

    private static ImmutableModelPartBonePose capture(Node root, ModelPartStructureSnapshot structure) {
        return ModelPartPoseTraversal.capture(
                root,
                structure,
                new Reader(),
                new ModelPartPoseCapture(structure.partCount(), ModelPartPoseTestFixtures.LIMITS),
                8);
    }

    private static float value(int bits) {
        return Float.intBitsToFloat(bits);
    }

    private static final class Node {
        private final Map<String, Node> children = new LinkedHashMap<>();
        private final Matrix4f position = new Matrix4f();
        private final Matrix3f normal = new Matrix3f();
        private boolean visible = true;
        private boolean hidden;

        private Node translate(float x, float y, float z) {
            position.translate(x, y, z);
            return this;
        }
    }

    private static final class Entry {
        private final Matrix4f position;
        private final Matrix3f normal;

        private Entry() {
            this(new Matrix4f(), new Matrix3f());
        }

        private Entry(Matrix4f position, Matrix3f normal) {
            this.position = position;
            this.normal = normal;
        }

        private Entry copy() {
            return new Entry(new Matrix4f(position), new Matrix3f(normal));
        }
    }

    private static final class Reader implements ModelPartPoseTraversal.Reader<Node> {
        private final ArrayDeque<Entry> stack = new ArrayDeque<>(List.of(new Entry()));
        private final float[] positionScratch = new float[16];
        private final float[] normalScratch = new float[9];

        @Override
        public int cuboidCount(Node part) {
            return 0;
        }

        @Override
        public Map<String, Node> children(Node part) {
            return part.children;
        }

        @Override
        public boolean visible(Node part) {
            return part.visible;
        }

        @Override
        public boolean hidden(Node part) {
            return part.hidden;
        }

        @Override
        public void push() {
            stack.addLast(stack.getLast().copy());
        }

        @Override
        public void applyTransform(Node part) {
            stack.getLast().position.mul(part.position);
            stack.getLast().normal.mul(part.normal);
        }

        @Override
        public void captureMatrices(
                Node part, int boneIndex, boolean treeVisible, boolean drawVisible, ModelPartPoseCapture capture) {
            stack.getLast().position.get(positionScratch);
            stack.getLast().normal.get(normalScratch);
            capture.captureBone(boneIndex, positionScratch, normalScratch, treeVisible, drawVisible, false);
        }

        @Override
        public void pop() {
            stack.removeLast();
        }
    }
}
