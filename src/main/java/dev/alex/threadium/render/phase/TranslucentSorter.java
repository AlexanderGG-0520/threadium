package dev.alex.threadium.render.phase;

import com.google.common.primitives.Floats;
import it.unimi.dsi.fastutil.ints.IntArrays;

/** Exact 26.2 comparator and fastutil unstable-sort algorithm. */
public final class TranslucentSorter {
    private TranslucentSorter() {}

    public static int[] sortIndices(float[] distances) {
        int[] indices = new int[distances.length];
        for (int index = 0; index < indices.length; index++) indices[index] = index;
        IntArrays.unstableSort(indices, (left, right) -> Floats.compare(distances[right], distances[left]));
        return indices;
    }
}
