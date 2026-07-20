package dev.alex.threadium.render.modelpart.structure;

import java.util.Arrays;
import java.util.Objects;

/** Bounded state machine for the exact decomposed VertexConsumer protocol used by Vanilla cuboids. */
public final class ModelPartMeshCapture {
    private static final int INITIAL_VERTEX_INTS = ImmutableModelPartMesh.VERTEX_STRIDE_INTS * 16;
    private static final int INITIAL_INDICES = 24;

    private final Limits limits;
    private int[] vertexData = new int[0];
    private int[] indices = new int[0];
    private int vertexCount;
    private int indexCount;
    private int currentBone = -1;
    private Stage stage = Stage.POSITION;
    private boolean completed;
    private int xBits;
    private int yBits;
    private int zBits;
    private int uBits;
    private int vBits;

    public ModelPartMeshCapture(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public void beginNode(int boneIndex) {
        ensureOpen();
        requireCompleteBoundary("node boundary");
        if (boneIndex < 0 || boneIndex >= limits.maximumParts()) {
            throw new IllegalArgumentException("Bone index is outside the configured part limit");
        }
        currentBone = boneIndex;
    }

    public void endCuboid() {
        ensureOpen();
        requireCompleteBoundary("cuboid boundary");
    }

    public void position(float x, float y, float z) {
        requireStage(Stage.POSITION);
        if (currentBone < 0) throw new IllegalStateException("No current bone has been assigned");
        xBits = Float.floatToRawIntBits(x);
        yBits = Float.floatToRawIntBits(y);
        zBits = Float.floatToRawIntBits(z);
        stage = Stage.COLOR;
    }

    public void color(int red, int green, int blue, int alpha) {
        requireStage(Stage.COLOR);
        stage = Stage.TEXTURE;
    }

    public void texture(float u, float v) {
        requireStage(Stage.TEXTURE);
        uBits = Float.floatToRawIntBits(u);
        vBits = Float.floatToRawIntBits(v);
        stage = Stage.OVERLAY;
    }

    public void overlay(int u, int v) {
        requireStage(Stage.OVERLAY);
        stage = Stage.LIGHT;
    }

    public void light(int u, int v) {
        requireStage(Stage.LIGHT);
        stage = Stage.NORMAL;
    }

    public void normal(float x, float y, float z) {
        requireStage(Stage.NORMAL);
        appendVertex(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z));
        stage = Stage.POSITION;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int quadCount() {
        return vertexCount / 4;
    }

    public int indexCount() {
        return indexCount;
    }

    public long retainedBytes() {
        return ImmutableModelPartMesh.retainedBytes(
                Math.multiplyExact(vertexCount, ImmutableModelPartMesh.VERTEX_STRIDE_INTS), indexCount);
    }

    public ImmutableModelPartMesh complete(ModelPartStructureSnapshot structure) {
        ensureOpen();
        requireCompleteBoundary("capture completion");
        completed = true;
        int vertexInts = Math.multiplyExact(vertexCount, ImmutableModelPartMesh.VERTEX_STRIDE_INTS);
        return ImmutableModelPartMesh.takeOwnership(
                Objects.requireNonNull(structure, "structure"),
                Arrays.copyOf(vertexData, vertexInts),
                Arrays.copyOf(indices, indexCount));
    }

    private void appendVertex(int normalXBits, int normalYBits, int normalZBits) {
        long nextVertexCount = (long) vertexCount + 1;
        boolean completesQuad = nextVertexCount % 4 == 0;
        long nextQuadCount = nextVertexCount / 4;
        long nextIndexCount = (long) indexCount + (completesQuad ? 6 : 0);
        if (nextVertexCount > limits.maximumVertices()
                || nextQuadCount > limits.maximumQuads()
                || nextIndexCount > limits.maximumIndices()) {
            throw new ModelPartMeshCapacityException("ModelPart mesh element limit exceeded");
        }
        long nextVertexInts = Math.multiplyExact(nextVertexCount, ImmutableModelPartMesh.VERTEX_STRIDE_INTS);
        long nextBytes = Math.addExact(
                Math.multiplyExact(nextVertexInts, Integer.BYTES), Math.multiplyExact(nextIndexCount, Integer.BYTES));
        if (nextBytes > limits.maximumMeshBytes()) {
            throw new ModelPartMeshCapacityException("ModelPart mesh retained-byte limit exceeded");
        }

        int requiredVertexInts = Math.toIntExact(nextVertexInts);
        vertexData = grow(vertexData, requiredVertexInts, limits.maximumVertexInts(), INITIAL_VERTEX_INTS);
        int base = vertexCount * ImmutableModelPartMesh.VERTEX_STRIDE_INTS;
        vertexData[base + ImmutableModelPartMesh.POSITION_X] = xBits;
        vertexData[base + ImmutableModelPartMesh.POSITION_Y] = yBits;
        vertexData[base + ImmutableModelPartMesh.POSITION_Z] = zBits;
        vertexData[base + ImmutableModelPartMesh.NORMAL_X] = normalXBits;
        vertexData[base + ImmutableModelPartMesh.NORMAL_Y] = normalYBits;
        vertexData[base + ImmutableModelPartMesh.NORMAL_Z] = normalZBits;
        vertexData[base + ImmutableModelPartMesh.TEXTURE_U] = uBits;
        vertexData[base + ImmutableModelPartMesh.TEXTURE_V] = vBits;
        vertexData[base + ImmutableModelPartMesh.BONE_INDEX] = currentBone;
        vertexCount++;

        if (completesQuad) {
            indices = grow(indices, Math.toIntExact(nextIndexCount), limits.maximumIndices(), INITIAL_INDICES);
            int quadBase = vertexCount - 4;
            indices[indexCount++] = quadBase;
            indices[indexCount++] = quadBase + 1;
            indices[indexCount++] = quadBase + 2;
            indices[indexCount++] = quadBase + 2;
            indices[indexCount++] = quadBase + 3;
            indices[indexCount++] = quadBase;
        }
    }

    private void requireStage(Stage expected) {
        ensureOpen();
        if (stage != expected) {
            throw new IllegalStateException("Malformed capture call order: expected " + expected + " but was " + stage);
        }
    }

    private void requireCompleteBoundary(String boundary) {
        if (stage != Stage.POSITION) throw new IllegalStateException("Incomplete vertex at " + boundary);
        if (vertexCount % 4 != 0) throw new IllegalStateException("Incomplete quad at " + boundary);
    }

    private void ensureOpen() {
        if (completed) throw new IllegalStateException("Capture is already complete");
    }

    private static int[] grow(int[] current, int required, int maximum, int initial) {
        if (required <= current.length) return current;
        if (required > maximum) throw new ModelPartMeshCapacityException("Capture array limit exceeded");
        long doubled = current.length == 0 ? initial : (long) current.length * 2;
        int nextLength = (int) Math.min(maximum, Math.max(required, doubled));
        return Arrays.copyOf(current, nextLength);
    }

    private enum Stage {
        POSITION,
        COLOR,
        TEXTURE,
        OVERLAY,
        LIGHT,
        NORMAL
    }

    public record Limits(
            int maximumParts, int maximumQuads, int maximumVertices, int maximumIndices, long maximumMeshBytes) {
        public Limits {
            if (maximumParts <= 0
                    || maximumQuads < 0
                    || maximumVertices < 0
                    || maximumIndices < 0
                    || maximumMeshBytes < 0) {
                throw new IllegalArgumentException("Mesh capture limits cannot be negative or empty");
            }
            Math.multiplyExact(maximumVertices, ImmutableModelPartMesh.VERTEX_STRIDE_INTS);
        }

        int maximumVertexInts() {
            return Math.multiplyExact(maximumVertices, ImmutableModelPartMesh.VERTEX_STRIDE_INTS);
        }
    }
}
