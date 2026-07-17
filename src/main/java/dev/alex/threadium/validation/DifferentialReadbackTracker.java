package dev.alex.threadium.validation;

/** Pure bounded gate for the four asynchronous differential readbacks. */
public final class DifferentialReadbackTracker {
    public enum Slot {
        REFERENCE_COLOR,
        REFERENCE_DEPTH,
        CANDIDATE_COLOR,
        CANDIDATE_DEPTH
    }

    private int completedMask;

    public boolean complete(Slot slot) {
        int bit = 1 << slot.ordinal();
        boolean first = (completedMask & bit) == 0;
        completedMask |= bit;
        return first;
    }

    public boolean ready() {
        return completedMask == (1 << Slot.values().length) - 1;
    }

    public int completedCount() {
        return Integer.bitCount(completedMask);
    }

    public static int normalizedIndex(int x, int gpuY, int width, int height) {
        if (x < 0 || x >= width || gpuY < 0 || gpuY >= height) throw new IndexOutOfBoundsException();
        return x + (height - gpuY - 1) * width;
    }
}
