package dev.alex.threadium.render.modelpart;

public final class VisibilityMask {
    private final long[] words;
    public VisibilityMask(int bones) { words = new long[(Math.max(0, bones) + 63) >>> 6]; }
    public void set(int bone, boolean visible) {
        long bit = 1L << (bone & 63);
        if (visible) words[bone >>> 6] |= bit; else words[bone >>> 6] &= ~bit;
    }
    public boolean get(int bone) { return (words[bone >>> 6] & (1L << (bone & 63))) != 0; }
    public long[] copyWords() { return words.clone(); }
}
