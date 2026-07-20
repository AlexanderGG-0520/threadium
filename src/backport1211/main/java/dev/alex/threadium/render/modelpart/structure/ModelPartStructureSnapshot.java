package dev.alex.threadium.render.modelpart.structure;

import java.util.List;
import java.util.Objects;

/** Immutable, Minecraft-object-free description of a ModelPart hierarchy's structural geometry. */
public final class ModelPartStructureSnapshot {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final List<Node> nodes;
    private final ModelPartStructureFingerprint fingerprint;
    private final int cuboidCount;

    private ModelPartStructureSnapshot(List<Node> nodes) {
        this(nodes, fingerprint(nodes));
    }

    ModelPartStructureSnapshot(List<Node> nodes, ModelPartStructureFingerprint fingerprint) {
        this.nodes = List.copyOf(nodes);
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        validateNodeOrder(this.nodes);
        this.cuboidCount =
                this.nodes.stream().mapToInt(node -> node.cuboids().size()).sum();
    }

    public static ModelPartStructureSnapshot of(List<Node> nodes) {
        return new ModelPartStructureSnapshot(nodes);
    }

    public List<Node> nodes() {
        return nodes;
    }

    public ModelPartStructureFingerprint fingerprint() {
        return fingerprint;
    }

    public int partCount() {
        return nodes.size();
    }

    public int cuboidCount() {
        return cuboidCount;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ModelPartStructureSnapshot snapshot && nodes.equals(snapshot.nodes));
    }

    /** Exact node equality remains authoritative when fingerprints collide. */
    @Override
    public int hashCode() {
        return Long.hashCode(fingerprint.value());
    }

    private static void validateNodeOrder(List<Node> nodes) {
        if (nodes.isEmpty()) throw new IllegalArgumentException("A structure snapshot requires a root node");
        for (int position = 0; position < nodes.size(); position++) {
            Node node = nodes.get(position);
            if (node.index() != position) {
                throw new IllegalArgumentException("Node index does not match deterministic list position");
            }
            if (position == 0 ? node.parentIndex() != -1 : node.parentIndex() < 0 || node.parentIndex() >= position) {
                throw new IllegalArgumentException("Node parent must precede its child");
            }
        }
    }

    private static ModelPartStructureFingerprint fingerprint(List<Node> nodes) {
        long hash = mix(FNV_OFFSET_BASIS, nodes.size());
        for (Node node : nodes) {
            hash = mix(hash, node.index());
            hash = mix(hash, node.parentIndex());
            hash = mixString(hash, node.childName());
            hash = mix(hash, node.cuboids().size());
            for (Cuboid cuboid : node.cuboids()) {
                hash = mix(hash, cuboid.minXBits());
                hash = mix(hash, cuboid.minYBits());
                hash = mix(hash, cuboid.minZBits());
                hash = mix(hash, cuboid.maxXBits());
                hash = mix(hash, cuboid.maxYBits());
                hash = mix(hash, cuboid.maxZBits());
                hash = mix(hash, cuboid.polygons().size());
                for (Polygon polygon : cuboid.polygons()) {
                    hash = mix(hash, polygon.normalXBits());
                    hash = mix(hash, polygon.normalYBits());
                    hash = mix(hash, polygon.normalZBits());
                    hash = mix(hash, polygon.vertices().size());
                    for (Vertex vertex : polygon.vertices()) {
                        hash = mix(hash, vertex.xBits());
                        hash = mix(hash, vertex.yBits());
                        hash = mix(hash, vertex.zBits());
                        hash = mix(hash, vertex.uBits());
                        hash = mix(hash, vertex.vBits());
                    }
                }
            }
        }
        return new ModelPartStructureFingerprint(hash);
    }

    private static long mixString(long hash, String value) {
        hash = mix(hash, value.length());
        for (int index = 0; index < value.length(); index++) hash = mix(hash, value.charAt(index));
        return hash;
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * FNV_PRIME;
    }

    public record Node(int index, int parentIndex, String childName, List<Cuboid> cuboids) {
        public Node {
            Objects.requireNonNull(childName, "childName");
            cuboids = List.copyOf(cuboids);
        }
    }

    /**
     * Original undilated bounds plus final emitted polygons. The polygons encode dilation, selected faces, texture
     * coordinates, and mirroring effects that Minecraft 1.21.1 does not retain as constructor fields.
     */
    public record Cuboid(
            int minXBits,
            int minYBits,
            int minZBits,
            int maxXBits,
            int maxYBits,
            int maxZBits,
            List<Polygon> polygons) {
        public Cuboid {
            polygons = List.copyOf(polygons);
        }

        public static Cuboid fromBounds(
                float minX, float minY, float minZ, float maxX, float maxY, float maxZ, List<Polygon> polygons) {
            return new Cuboid(
                    Float.floatToRawIntBits(minX),
                    Float.floatToRawIntBits(minY),
                    Float.floatToRawIntBits(minZ),
                    Float.floatToRawIntBits(maxX),
                    Float.floatToRawIntBits(maxY),
                    Float.floatToRawIntBits(maxZ),
                    polygons);
        }
    }

    public record Polygon(int normalXBits, int normalYBits, int normalZBits, List<Vertex> vertices) {
        public Polygon {
            vertices = List.copyOf(vertices);
        }

        public static Polygon fromNormal(float normalX, float normalY, float normalZ, List<Vertex> vertices) {
            return new Polygon(
                    Float.floatToRawIntBits(normalX),
                    Float.floatToRawIntBits(normalY),
                    Float.floatToRawIntBits(normalZ),
                    vertices);
        }
    }

    public record Vertex(int xBits, int yBits, int zBits, int uBits, int vBits) {
        public static Vertex from(float x, float y, float z, float u, float v) {
            return new Vertex(
                    Float.floatToRawIntBits(x),
                    Float.floatToRawIntBits(y),
                    Float.floatToRawIntBits(z),
                    Float.floatToRawIntBits(u),
                    Float.floatToRawIntBits(v));
        }
    }
}
