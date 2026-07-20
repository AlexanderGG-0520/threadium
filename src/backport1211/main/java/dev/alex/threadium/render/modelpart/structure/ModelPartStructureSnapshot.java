package dev.alex.threadium.render.modelpart.structure;

import java.util.List;
import java.util.Objects;

/** Immutable hierarchy metadata. Final geometry identity lives in {@link ModelPartMeshKey}. */
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
        int totalCuboids = 0;
        for (Node node : this.nodes) totalCuboids = Math.addExact(totalCuboids, node.cuboidCount());
        this.cuboidCount = totalCuboids;
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

    /** Exact node equality remains authoritative when hierarchy fingerprints collide. */
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
            if (node.cuboidCount() < 0) throw new IllegalArgumentException("Cuboid count cannot be negative");
        }
    }

    private static ModelPartStructureFingerprint fingerprint(List<Node> nodes) {
        long hash = mix(FNV_OFFSET_BASIS, nodes.size());
        for (Node node : nodes) {
            hash = mix(hash, node.index());
            hash = mix(hash, node.parentIndex());
            hash = mixString(hash, node.childName());
            hash = mix(hash, node.cuboidCount());
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

    public record Node(int index, int parentIndex, String childName, int cuboidCount) {
        public Node {
            Objects.requireNonNull(childName, "childName");
        }
    }
}
