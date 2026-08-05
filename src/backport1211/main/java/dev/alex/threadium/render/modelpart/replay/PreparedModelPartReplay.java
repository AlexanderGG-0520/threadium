package dev.alex.threadium.render.modelpart.replay;

import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.pose.ModelPartInvocationSnapshot;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.util.Objects;

/**
 * Fully validated, Minecraft-object-free vertex replay prepared before any destination consumer is mutated.
 *
 * <p>The packed float layout is position xyz, texture uv, and normal xyz. Color, overlay, and light are immutable
 * invocation values shared by all prepared vertices.
 */
public final class PreparedModelPartReplay {
    public static final int FLOATS_PER_VERTEX = 8;
    public static final int POSITION_X = 0;
    public static final int POSITION_Y = 1;
    public static final int POSITION_Z = 2;
    public static final int TEXTURE_U = 3;
    public static final int TEXTURE_V = 4;
    public static final int NORMAL_X = 5;
    public static final int NORMAL_Y = 6;
    public static final int NORMAL_Z = 7;

    private final float[] vertices;
    private final int color;
    private final int overlay;
    private final int light;

    private PreparedModelPartReplay(float[] vertices, int color, int overlay, int light) {
        this.vertices = vertices;
        this.color = color;
        this.overlay = overlay;
        this.light = light;
    }

    public static PreparedModelPartReplay prepare(ModelPartInvocationSnapshot invocation) {
        Objects.requireNonNull(invocation, "invocation");
        ImmutableModelPartMesh mesh = invocation.mesh();
        ImmutableModelPartBonePose pose = invocation.pose();
        ImmutableRootRenderTransform root = invocation.rootTransform();
        if (!pose.finite() || !root.finite() || pose.boneCount() != mesh.partCount()) {
            throw new IllegalArgumentException("ModelPart replay input is not finite or structurally aligned");
        }

        int visibleVertices = 0;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int bone = mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX);
            if (pose.drawVisible(bone)) visibleVertices = Math.addExact(visibleVertices, 1);
        }
        if ((visibleVertices & 3) != 0) {
            throw new IllegalArgumentException("Visible ModelPart replay does not contain complete quads");
        }

        float[] prepared = new float[Math.multiplyExact(visibleVertices, FLOATS_PER_VERTEX)];
        int outputVertex = 0;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int bone = mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX);
            if (!pose.drawVisible(bone)) continue;

            float x = field(mesh, vertex, ImmutableModelPartMesh.POSITION_X);
            float y = field(mesh, vertex, ImmutableModelPartMesh.POSITION_Y);
            float z = field(mesh, vertex, ImmutableModelPartMesh.POSITION_Z);
            float boneX = transformPositionX(pose, bone, x, y, z);
            float boneY = transformPositionY(pose, bone, x, y, z);
            float boneZ = transformPositionZ(pose, bone, x, y, z);
            float outputX = transformPositionX(root, boneX, boneY, boneZ);
            float outputY = transformPositionY(root, boneX, boneY, boneZ);
            float outputZ = transformPositionZ(root, boneX, boneY, boneZ);

            float normalX = field(mesh, vertex, ImmutableModelPartMesh.NORMAL_X);
            float normalY = field(mesh, vertex, ImmutableModelPartMesh.NORMAL_Y);
            float normalZ = field(mesh, vertex, ImmutableModelPartMesh.NORMAL_Z);
            float boneNormalX = transformNormalX(pose, bone, normalX, normalY, normalZ);
            float boneNormalY = transformNormalY(pose, bone, normalX, normalY, normalZ);
            float boneNormalZ = transformNormalZ(pose, bone, normalX, normalY, normalZ);
            float outputNormalX = transformNormalX(root, boneNormalX, boneNormalY, boneNormalZ);
            float outputNormalY = transformNormalY(root, boneNormalX, boneNormalY, boneNormalZ);
            float outputNormalZ = transformNormalZ(root, boneNormalX, boneNormalY, boneNormalZ);
            if (pose.normalNeedsNormalization(bone) || root.normalNeedsNormalization()) {
                float squaredLength = outputNormalX * outputNormalX
                        + outputNormalY * outputNormalY
                        + outputNormalZ * outputNormalZ;
                if (!(squaredLength > 0.0F) || !Float.isFinite(squaredLength)) {
                    throw new IllegalArgumentException("ModelPart replay normal cannot be normalized");
                }
                float inverseLength = (float) (1.0D / Math.sqrt(squaredLength));
                outputNormalX *= inverseLength;
                outputNormalY *= inverseLength;
                outputNormalZ *= inverseLength;
            }

            int offset = Math.multiplyExact(outputVertex++, FLOATS_PER_VERTEX);
            prepared[offset + POSITION_X] = requireFinite(outputX);
            prepared[offset + POSITION_Y] = requireFinite(outputY);
            prepared[offset + POSITION_Z] = requireFinite(outputZ);
            prepared[offset + TEXTURE_U] = requireFinite(field(mesh, vertex, ImmutableModelPartMesh.TEXTURE_U));
            prepared[offset + TEXTURE_V] = requireFinite(field(mesh, vertex, ImmutableModelPartMesh.TEXTURE_V));
            prepared[offset + NORMAL_X] = requireFinite(outputNormalX);
            prepared[offset + NORMAL_Y] = requireFinite(outputNormalY);
            prepared[offset + NORMAL_Z] = requireFinite(outputNormalZ);
        }
        return new PreparedModelPartReplay(prepared, invocation.color(), invocation.overlay(), invocation.light());
    }

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public float field(int vertexIndex, int fieldOffset) {
        if (vertexIndex < 0 || vertexIndex >= vertexCount()) throw new IndexOutOfBoundsException(vertexIndex);
        if (fieldOffset < 0 || fieldOffset >= FLOATS_PER_VERTEX) throw new IndexOutOfBoundsException(fieldOffset);
        return vertices[Math.addExact(Math.multiplyExact(vertexIndex, FLOATS_PER_VERTEX), fieldOffset)];
    }

    public int color() {
        return color;
    }

    public int overlay() {
        return overlay;
    }

    public int light() {
        return light;
    }

    private static float field(ImmutableModelPartMesh mesh, int vertex, int field) {
        return Float.intBitsToFloat(mesh.vertexFieldBits(vertex, field));
    }

    private static float transformPositionX(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return element(pose, bone, 0) * x
                + element(pose, bone, 4) * y
                + element(pose, bone, 8) * z
                + element(pose, bone, 12);
    }

    private static float transformPositionY(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return element(pose, bone, 1) * x
                + element(pose, bone, 5) * y
                + element(pose, bone, 9) * z
                + element(pose, bone, 13);
    }

    private static float transformPositionZ(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return element(pose, bone, 2) * x
                + element(pose, bone, 6) * y
                + element(pose, bone, 10) * z
                + element(pose, bone, 14);
    }

    private static float transformPositionX(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootElement(root, 0) * x + rootElement(root, 4) * y + rootElement(root, 8) * z + rootElement(root, 12);
    }

    private static float transformPositionY(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootElement(root, 1) * x + rootElement(root, 5) * y + rootElement(root, 9) * z + rootElement(root, 13);
    }

    private static float transformPositionZ(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootElement(root, 2) * x + rootElement(root, 6) * y + rootElement(root, 10) * z + rootElement(root, 14);
    }

    private static float transformNormalX(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return normalElement(pose, bone, 0) * x
                + normalElement(pose, bone, 3) * y
                + normalElement(pose, bone, 6) * z;
    }

    private static float transformNormalY(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return normalElement(pose, bone, 1) * x
                + normalElement(pose, bone, 4) * y
                + normalElement(pose, bone, 7) * z;
    }

    private static float transformNormalZ(ImmutableModelPartBonePose pose, int bone, float x, float y, float z) {
        return normalElement(pose, bone, 2) * x
                + normalElement(pose, bone, 5) * y
                + normalElement(pose, bone, 8) * z;
    }

    private static float transformNormalX(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootNormalElement(root, 0) * x + rootNormalElement(root, 3) * y + rootNormalElement(root, 6) * z;
    }

    private static float transformNormalY(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootNormalElement(root, 1) * x + rootNormalElement(root, 4) * y + rootNormalElement(root, 7) * z;
    }

    private static float transformNormalZ(ImmutableRootRenderTransform root, float x, float y, float z) {
        return rootNormalElement(root, 2) * x + rootNormalElement(root, 5) * y + rootNormalElement(root, 8) * z;
    }

    private static float element(ImmutableModelPartBonePose pose, int bone, int element) {
        return Float.intBitsToFloat(pose.positionElementBits(bone, element));
    }

    private static float normalElement(ImmutableModelPartBonePose pose, int bone, int element) {
        return Float.intBitsToFloat(pose.normalElementBits(bone, element));
    }

    private static float rootElement(ImmutableRootRenderTransform root, int element) {
        return Float.intBitsToFloat(root.positionElementBits(element));
    }

    private static float rootNormalElement(ImmutableRootRenderTransform root, int element) {
        return Float.intBitsToFloat(root.normalElementBits(element));
    }

    private static float requireFinite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("ModelPart replay produced a non-finite value");
        return value;
    }
}
