package dev.alex.threadium.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetainedTextKeyTest {
    @Test
    void equalValuesAreEqualAndEveryFieldAffectsEquality() {
        RetainedTextKey<String> base = key(List.of("a"), 1, 2, 3, false, false, 4, 5, 6);
        assertEquals(base, key(List.of("a"), 1, 2, 3, false, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("b"), 1, 2, 3, false, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 9, 2, 3, false, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 9, 3, false, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 9, false, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 3, true, false, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 3, false, true, 4, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 3, false, false, 9, 5, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 3, false, false, 4, 9, 6));
        assertNotEquals(base, key(List.of("a"), 1, 2, 3, false, false, 4, 5, 9));
    }

    @Test
    void inputListIsDefensivelyCopied() {
        java.util.ArrayList<String> glyphs = new java.util.ArrayList<>(List.of("a"));
        RetainedTextKey<String> key = key(glyphs, 1, 2, 3, false, false, 4, 5, 6);
        glyphs.set(0, "changed");
        assertEquals(List.of("a"), key.glyphs());
    }

    private static RetainedTextKey<String> key(
            List<String> glyphs,
            float x,
            float y,
            int color,
            boolean shadow,
            boolean bidi,
            int background,
            int variant,
            long generation) {
        return new RetainedTextKey<>(glyphs, x, y, color, shadow, bidi, background, variant, generation);
    }
}
