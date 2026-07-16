package dev.alex.threadium.render.phase;

/** Immutable-by-encapsulation copies; opaque references are never inspected by worker code. */
public final class DetachedTranslucentSnapshot {
    private final Object[] opaqueReferences;
    private final float[] distances;

    public DetachedTranslucentSnapshot(Object[] opaqueReferences, float[] distances) {
        if (opaqueReferences.length != distances.length) throw new IllegalArgumentException("snapshot arrays differ in length");
        this.opaqueReferences = opaqueReferences.clone();
        this.distances = distances.clone();
    }

    public int size() { return opaqueReferences.length; }
    public Object opaqueReference(int index) { return opaqueReferences[index]; }
    public int[] sortedIndices() { return TranslucentSorter.sortIndices(distances); }
    public float distance(int index) { return distances[index]; }
}
