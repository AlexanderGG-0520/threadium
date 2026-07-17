package dev.alex.threadium.render.modelpart;

public final class BoneBaseOffsets {
    private BoneBaseOffsets() {}

    public static int[] compute(int[] boneCounts, int maximum) {
        int[] result = new int[boneCounts.length];
        int base = 0;
        for (int i = 0; i < boneCounts.length; i++) {
            if (boneCounts[i] < 0) throw new IllegalArgumentException("negative bone count");
            result[i] = base;
            base = Math.addExact(base, boneCounts[i]);
            if (base > maximum) throw new IllegalArgumentException("bone capacity exceeded");
        }
        return result;
    }
}
