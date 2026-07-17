package dev.alex.threadium.cache;

import java.util.List;

/** Generic collision-safe value key; client glyph tokens supply the style-aware elements. */
public record RetainedTextKey<T>(
        List<T> glyphs,
        float x,
        float y,
        int color,
        boolean shadow,
        boolean bidirectional,
        int backgroundColor,
        int variant,
        long resourceGeneration) {
    public RetainedTextKey {
        glyphs = List.copyOf(glyphs);
    }
}
