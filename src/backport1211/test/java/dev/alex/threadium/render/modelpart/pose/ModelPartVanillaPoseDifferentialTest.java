package dev.alex.threadium.render.modelpart.pose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartMeshCapture;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class ModelPartVanillaPoseDifferentialTest {
    private static final ModelPartPoseCapture.Limits POSE_LIMITS =
            new ModelPartPoseCapture.Limits(64, 1_600, 1, 64 * 1024);
    private static final ModelPartMeshCapture.Limits MESH_LIMITS =
            new ModelPartMeshCapture.Limits(64, 384, 1_536, 2_304, 256 * 1024);

    @TestFactory
    Stream<DynamicTest> vanillaEmissionMatchesCapturedSingleBoneTransforms() {
        return Stream.of(
                        poseCase("identity", part -> {}),
                        poseCase("pivot translation", part -> part.setPivot(8, -4, 16)),
                        poseCase("X rotation", part -> part.pitch = 0.37F),
                        poseCase("Y rotation", part -> part.yaw = -0.61F),
                        poseCase("Z rotation", part -> part.roll = 1.13F),
                        poseCase("combined ZYX rotations", part -> {
                            part.pitch = 0.37F;
                            part.yaw = -0.61F;
                            part.roll = 1.13F;
                        }),
                        poseCase("uniform scale", part -> {
                            part.xScale = 2;
                            part.yScale = 2;
                            part.zScale = 2;
                        }),
                        poseCase("non-uniform scale", part -> {
                            part.xScale = 2;
                            part.yScale = 3;
                            part.zScale = 4;
                        }),
                        poseCase("negative scale", part -> {
                            part.xScale = -2;
                            part.yScale = 2;
                            part.zScale = -2;
                        }))
                .map(testCase -> DynamicTest.dynamicTest(
                        testCase.name, () -> assertDifferential(testCase.tree, identityRoot())));
    }

    @Test
    void parentChildAndMultipleDepthCompositionMatchesVanilla() {
        Tree tree = tree(threeLevelModel());
        tree.root.setPivot(4, 2, -3);
        tree.root.yaw = 0.3F;
        ModelPart child = tree.children.get(tree.root).get("child");
        child.setPivot(-2, 6, 1);
        child.roll = -0.7F;
        child.xScale = 1.5F;
        ModelPart grandchild = tree.children.get(child).get("grandchild");
        grandchild.setPivot(3, -5, 7);
        grandchild.pitch = 0.45F;

        assertDifferential(tree, identityRoot());
    }

    @Test
    void nonIdentityIncomingPositionAndNormalMatricesMatchVanillaAndAreRestored() {
        Tree tree = tree(singlePartModel());
        tree.root.roll = 0.4F;
        RootStack root = identityRoot();
        root.matrices.translate(2.5F, -1.25F, 7.0F);
        root.matrices.multiply(new Quaternionf().rotationZYX(0.2F, -0.3F, 0.1F));
        root.matrices.scale(2.0F, 3.0F, 4.0F);
        root.normalNeedsNormalization = true;

        assertDifferential(tree, root);
    }

    @Test
    void visibilityAndHiddenSemanticsMatchVanillaEmission() {
        Tree hiddenTree = tree(twoLevelModel());
        hiddenTree.root.hidden = true;
        Differential hidden = assertDifferential(hiddenTree, identityRoot());
        assertFalse(hidden.pose.drawVisible(0));
        assertTrue(hidden.pose.treeVisible(1));
        assertTrue(hidden.pose.drawVisible(1));

        Tree invisibleTree = tree(twoLevelModel());
        invisibleTree.root.visible = false;
        Differential invisible = assertDifferential(invisibleTree, identityRoot());
        assertEquals(0, invisible.actual.size());
        assertFalse(invisible.pose.treeVisible(0));
        assertFalse(invisible.pose.treeVisible(1));
    }

    @Test
    void poseChangesDoNotChangeMeshAndRepeatedExtractionIsDeterministic() {
        Tree tree = tree(twoLevelModel());
        ImmutableModelPartMesh before = captureMesh(tree);
        ImmutableModelPartBonePose first = capturePose(tree, before.structure());
        tree.root.pitch = 0.75F;
        ImmutableModelPartMesh after = captureMesh(tree);
        ImmutableModelPartBonePose changed = capturePose(tree, after.structure());
        ImmutableModelPartBonePose repeated = capturePose(tree, after.structure());

        assertEquals(before, after);
        assertFalse(first.equals(changed));
        assertEquals(changed, repeated);
    }

    @Test
    void identicalPosesAcrossDistinctModelPartRootsShareTheExactFrameEntry() {
        Tree firstTree = tree(twoLevelModel());
        Tree secondTree = tree(twoLevelModel());
        ImmutableModelPartBonePose firstPose =
                capturePose(firstTree, captureMesh(firstTree).structure());
        ImmutableModelPartBonePose secondPose =
                capturePose(secondTree, captureMesh(secondTree).structure());
        ModelPartPoseCache cache = new ModelPartPoseCache(4, 64 * 1024);

        ModelPartPoseCache.InternResult first = cache.intern(firstPose);
        ModelPartPoseCache.InternResult second = cache.intern(secondPose);

        assertFalse(first.hit());
        assertTrue(second.hit());
        assertTrue(first.pose() == second.pose());
    }

    @Test
    void singularAndNonFiniteTransformsAreObservedWithoutCanonicalization() {
        Tree singular = tree(singlePartModel());
        singular.root.xScale = 0;
        singular.root.yScale = 1;
        singular.root.zScale = 2;
        ImmutableModelPartMesh singularMesh = captureMesh(singular);
        ImmutableModelPartBonePose singularPose = capturePose(singular, singularMesh.structure());
        assertFalse(singularPose.finite());
        assertTrue(singularPose.normalNeedsNormalization(0));

        Tree nan = tree(singlePartModel());
        nan.root.pitch = Float.intBitsToFloat(0x7fc01234);
        ImmutableModelPartBonePose nanPose = capturePose(nan, captureMesh(nan).structure());
        assertFalse(nanPose.finite());
    }

    private static TestCase poseCase(String name, Consumer<ModelPart> pose) {
        Tree tree = tree(singlePartModel());
        pose.accept(tree.root);
        return new TestCase(name, tree);
    }

    private static Differential assertDifferential(Tree tree, RootStack root) {
        ImmutableModelPartMesh mesh = captureMesh(tree);
        ImmutableModelPartBonePose pose = capturePose(tree, mesh.structure());
        ImmutableRootRenderTransform rootTransform = captureRoot(root);
        float[] beforePosition = new float[16];
        float[] beforeNormal = new float[9];
        root.matrices.peek().getPositionMatrix().get(beforePosition);
        root.matrices.peek().getNormalMatrix().get(beforeNormal);

        ActualConsumer consumer = new ActualConsumer();
        tree.root.render(
                root.matrices,
                consumer,
                0x12345678,
                0x23456789,
                0xBB / 255.0F,
                0xCC / 255.0F,
                0xDD / 255.0F,
                0xAA / 255.0F);

        float[] afterPosition = new float[16];
        float[] afterNormal = new float[9];
        root.matrices.peek().getPositionMatrix().get(afterPosition);
        root.matrices.peek().getNormalMatrix().get(afterNormal);
        assertArrayEquals(beforePosition, afterPosition);
        assertArrayEquals(beforeNormal, afterNormal);

        List<CapturedVertex> reconstructed = reconstruct(mesh, pose, rootTransform);
        assertEquals(consumer.vertices.size(), reconstructed.size());
        for (int index = 0; index < reconstructed.size(); index++) {
            assertVertexEquals(consumer.vertices.get(index), reconstructed.get(index), "vertex " + index);
        }
        return new Differential(pose, List.copyOf(consumer.vertices));
    }

    private static ImmutableModelPartBonePose capturePose(Tree tree, ModelPartStructureSnapshot structure) {
        VanillaPoseReader reader = new VanillaPoseReader(tree);
        return ModelPartPoseTraversal.capture(
                tree.root, structure, reader, new ModelPartPoseCapture(structure.partCount(), POSE_LIMITS), 32);
    }

    private static ImmutableRootRenderTransform captureRoot(RootStack root) {
        float[] position = new float[16];
        float[] normal = new float[9];
        root.matrices.peek().getPositionMatrix().get(position);
        root.matrices.peek().getNormalMatrix().get(normal);
        return ImmutableRootRenderTransform.fromFloats(position, normal, root.normalNeedsNormalization);
    }

    private static ImmutableModelPartMesh captureMesh(Tree tree) {
        List<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        ModelPartMeshCapture capture = new ModelPartMeshCapture(MESH_LIMITS);
        MatrixStack.Entry identity = new MatrixStack().peek();
        MeshConsumer consumer = new MeshConsumer(capture);
        captureMeshNode(tree, tree.root, -1, "root", nodes, capture, identity, consumer);
        return capture.complete(ModelPartStructureSnapshot.of(nodes));
    }

    private static void captureMeshNode(
            Tree tree,
            ModelPart part,
            int parent,
            String name,
            List<ModelPartStructureSnapshot.Node> nodes,
            ModelPartMeshCapture capture,
            MatrixStack.Entry identity,
            MeshConsumer consumer) {
        int bone = nodes.size();
        List<ModelPart.Cuboid> cuboids = tree.cuboids.get(part);
        nodes.add(new ModelPartStructureSnapshot.Node(bone, parent, name, cuboids.size()));
        capture.beginNode(bone);
        for (ModelPart.Cuboid cuboid : cuboids) {
            cuboid.renderCuboid(identity, consumer, 1, 2, 1.0F, 1.0F, 1.0F, 1.0F);
            capture.endCuboid();
        }
        for (Map.Entry<String, ModelPart> child : tree.children.get(part).entrySet()) {
            captureMeshNode(tree, child.getValue(), bone, child.getKey(), nodes, capture, identity, consumer);
        }
    }

    private static List<CapturedVertex> reconstruct(
            ImmutableModelPartMesh mesh, ImmutableModelPartBonePose pose, ImmutableRootRenderTransform root) {
        Matrix4f rootPosition = new Matrix4f().set(floats(root.copyPositionMatrixBits()));
        Matrix3f rootNormal = new Matrix3f().set(floats(root.copyNormalMatrixBits()));
        List<CapturedVertex> result = new ArrayList<>();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int bone = mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX);
            if (!pose.drawVisible(bone)) continue;
            Matrix4f position = new Matrix4f(rootPosition)
                    .mul(new Matrix4f()
                            .set(slice(
                                    pose.copyPositionMatrixBits(),
                                    bone * ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE,
                                    ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE)));
            Matrix3f normal = new Matrix3f(rootNormal)
                    .mul(new Matrix3f()
                            .set(slice(
                                    pose.copyNormalMatrixBits(),
                                    bone * ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE,
                                    ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE)));
            Vector3f transformedPosition = position.transformPosition(new Vector3f(
                    value(mesh, vertex, ImmutableModelPartMesh.POSITION_X),
                    value(mesh, vertex, ImmutableModelPartMesh.POSITION_Y),
                    value(mesh, vertex, ImmutableModelPartMesh.POSITION_Z)));
            Vector3f transformedNormal = normal.transform(new Vector3f(
                    value(mesh, vertex, ImmutableModelPartMesh.NORMAL_X),
                    value(mesh, vertex, ImmutableModelPartMesh.NORMAL_Y),
                    value(mesh, vertex, ImmutableModelPartMesh.NORMAL_Z)));
            result.add(new CapturedVertex(
                    transformedPosition.x,
                    transformedPosition.y,
                    transformedPosition.z,
                    value(mesh, vertex, ImmutableModelPartMesh.TEXTURE_U),
                    value(mesh, vertex, ImmutableModelPartMesh.TEXTURE_V),
                    transformedNormal.x,
                    transformedNormal.y,
                    transformedNormal.z));
        }
        return result;
    }

    private static void assertVertexEquals(CapturedVertex expected, CapturedVertex actual, String label) {
        assertFloat(expected.x, actual.x, label + " x");
        assertFloat(expected.y, actual.y, label + " y");
        assertFloat(expected.z, actual.z, label + " z");
        assertFloat(expected.u, actual.u, label + " u");
        assertFloat(expected.v, actual.v, label + " v");
        assertFloat(expected.normalX, actual.normalX, label + " normalX");
        assertFloat(expected.normalY, actual.normalY, label + " normalY");
        assertFloat(expected.normalZ, actual.normalZ, label + " normalZ");
    }

    private static void assertFloat(float expected, float actual, String label) {
        if (Float.isNaN(expected)) assertTrue(Float.isNaN(actual), label);
        else if (Float.isInfinite(expected)) assertEquals(expected, actual, label);
        else assertEquals(expected, actual, 1.0E-5F, label);
    }

    private static float value(ImmutableModelPartMesh mesh, int vertex, int field) {
        return Float.intBitsToFloat(mesh.vertexFieldBits(vertex, field));
    }

    private static float[] floats(int[] bits) {
        return slice(bits, 0, bits.length);
    }

    private static float[] slice(int[] bits, int offset, int length) {
        float[] values = new float[length];
        for (int index = 0; index < length; index++) values[index] = Float.intBitsToFloat(bits[offset + index]);
        return values;
    }

    private static RootStack identityRoot() {
        return new RootStack(new MatrixStack(), false);
    }

    private static PartData singlePartModel() {
        return new PartData(cuboids(), new LinkedHashMap<>());
    }

    private static PartData twoLevelModel() {
        LinkedHashMap<String, PartData> children = new LinkedHashMap<>();
        children.put("child", singlePartModel());
        return new PartData(cuboids(), children);
    }

    private static PartData threeLevelModel() {
        LinkedHashMap<String, PartData> grandchildren = new LinkedHashMap<>();
        grandchildren.put("grandchild", singlePartModel());
        LinkedHashMap<String, PartData> children = new LinkedHashMap<>();
        children.put("child", new PartData(cuboids(), grandchildren));
        return new PartData(cuboids(), children);
    }

    private static List<ModelPart.Cuboid> cuboids() {
        return List.of(new ModelPart.Cuboid(
                0, 0, -2, -3, -4, 4, 6, 8, 0, 0, 0, false, 64, 32, EnumSet.allOf(Direction.class)));
    }

    private static Tree tree(PartData data) {
        IdentityHashMap<ModelPart, List<ModelPart.Cuboid>> cuboids = new IdentityHashMap<>();
        IdentityHashMap<ModelPart, Map<String, ModelPart>> children = new IdentityHashMap<>();
        ModelPart root = build(data, cuboids, children);
        return new Tree(root, cuboids, children);
    }

    private static ModelPart build(
            PartData data,
            IdentityHashMap<ModelPart, List<ModelPart.Cuboid>> cuboids,
            IdentityHashMap<ModelPart, Map<String, ModelPart>> children) {
        LinkedHashMap<String, ModelPart> childParts = new LinkedHashMap<>();
        for (Map.Entry<String, PartData> child : data.children.entrySet()) {
            childParts.put(child.getKey(), build(child.getValue(), cuboids, children));
        }
        ModelPart part = new ModelPart(data.cuboids, childParts);
        cuboids.put(part, data.cuboids);
        children.put(part, childParts);
        return part;
    }

    private static final class VanillaPoseReader implements ModelPartPoseTraversal.Reader<ModelPart> {
        private final Tree tree;
        private final MatrixStack matrices = new MatrixStack();
        private final ArrayDeque<Boolean> normalNeedsNormalization = new ArrayDeque<>(List.of(false));
        private final float[] positionScratch = new float[16];
        private final float[] normalScratch = new float[9];

        private VanillaPoseReader(Tree tree) {
            this.tree = tree;
        }

        @Override
        public int cuboidCount(ModelPart part) {
            return tree.cuboids.get(part).size();
        }

        @Override
        public Map<String, ModelPart> children(ModelPart part) {
            return tree.children.get(part);
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
            normalNeedsNormalization.addLast(normalNeedsNormalization.getLast());
        }

        @Override
        public void applyTransform(ModelPart part) {
            part.rotate(matrices);
            boolean uniformAbsoluteScale =
                    Math.abs(part.xScale) == Math.abs(part.yScale) && Math.abs(part.yScale) == Math.abs(part.zScale);
            if (!uniformAbsoluteScale) {
                normalNeedsNormalization.removeLast();
                normalNeedsNormalization.addLast(true);
            }
        }

        @Override
        public void captureMatrices(
                ModelPart part, int boneIndex, boolean treeVisible, boolean drawVisible, ModelPartPoseCapture capture) {
            MatrixStack.Entry entry = matrices.peek();
            entry.getPositionMatrix().get(positionScratch);
            entry.getNormalMatrix().get(normalScratch);
            capture.captureBone(
                    boneIndex,
                    positionScratch,
                    normalScratch,
                    treeVisible,
                    drawVisible,
                    normalNeedsNormalization.getLast());
        }

        @Override
        public void pop() {
            matrices.pop();
            normalNeedsNormalization.removeLast();
        }
    }

    private static final class MeshConsumer implements VertexConsumer {
        private final ModelPartMeshCapture capture;

        private MeshConsumer(ModelPartMeshCapture capture) {
            this.capture = capture;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            capture.position((float) x, (float) y, (float) z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            capture.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            capture.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            capture.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            capture.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            capture.normal(x, y, z);
            return this;
        }

        @Override
        public void next() {}

        @Override
        public void fixedColor(int red, int green, int blue, int alpha) {
            throw new UnsupportedOperationException("Fixed colors are outside this differential test");
        }

        @Override
        public void unfixColor() {
            throw new UnsupportedOperationException("Fixed colors are outside this differential test");
        }
    }

    private static final class ActualConsumer implements VertexConsumer {
        private final List<CapturedVertex> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float normalX, float normalY, float normalZ) {
            vertices.add(new CapturedVertex(x, y, z, u, v, normalX, normalY, normalZ));
            return this;
        }

        @Override
        public void next() {}

        @Override
        public void fixedColor(int red, int green, int blue, int alpha) {
            throw new UnsupportedOperationException("Fixed colors are outside this differential test");
        }

        @Override
        public void unfixColor() {
            throw new UnsupportedOperationException("Fixed colors are outside this differential test");
        }
    }

    private record CapturedVertex(
            float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ) {}

    private record PartData(List<ModelPart.Cuboid> cuboids, LinkedHashMap<String, PartData> children) {}

    private record Tree(
            ModelPart root,
            IdentityHashMap<ModelPart, List<ModelPart.Cuboid>> cuboids,
            IdentityHashMap<ModelPart, Map<String, ModelPart>> children) {}

    private static final class RootStack {
        private final MatrixStack matrices;
        private boolean normalNeedsNormalization;

        private RootStack(MatrixStack matrices, boolean normalNeedsNormalization) {
            this.matrices = matrices;
            this.normalNeedsNormalization = normalNeedsNormalization;
        }
    }

    private record TestCase(String name, Tree tree) {}

    private record Differential(ImmutableModelPartBonePose pose, List<CapturedVertex> actual) {}
}
