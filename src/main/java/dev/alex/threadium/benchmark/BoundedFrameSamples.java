package dev.alex.threadium.benchmark;

import java.util.Arrays;

public final class BoundedFrameSamples {
    private final long[] values;
    private int size;
    private boolean overflowed;

    public BoundedFrameSamples(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        values = new long[capacity];
    }

    public boolean add(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("frame time must not be negative");
        if (size == values.length) { overflowed = true; return false; }
        values[size++] = nanos;
        return true;
    }

    public int size() { return size; }
    public int capacity() { return values.length; }
    public boolean overflowed() { return overflowed; }
    public long[] copy() { return Arrays.copyOf(values, size); }
}
