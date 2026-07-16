package dev.alex.threadium.render.modelpart;

import java.util.ArrayList;
import java.util.List;

/** Stable adjacent-only consolidation; it never reorders entries. */
public final class AdjacentBatchRanges {
    public record Range(int first, int count) {}

    private AdjacentBatchRanges() {}

    public static <T> List<Range> build(List<T> keys, boolean consolidate) {
        ArrayList<Range> result = new ArrayList<>();
        for (int first = 0; first < keys.size(); ) {
            int end = first + 1;
            if (consolidate)
                while (end < keys.size() && java.util.Objects.equals(keys.get(first), keys.get(end))) end++;
            result.add(new Range(first, end - first));
            first = end;
        }
        return List.copyOf(result);
    }
}
