package dev.alex.threadium.render.text;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import dev.alex.threadium.cache.RetainedTextKey;

import java.util.ArrayList;
import java.util.List;

/** Collision-safe visual sequence plus every value consumed by Font preparation. */
public record TextGeometryKey(RetainedTextKey<Glyph> value) {

    public static TextGeometryKey capture(FormattedCharSequence sequence, float x, float y, int color, boolean shadow,
                                          boolean bidirectional, int backgroundColor, int variant, long generation) {
        List<Glyph> glyphs = new ArrayList<>();
        sequence.accept((index, style, codePoint) -> { glyphs.add(new Glyph(index, codePoint, style)); return true; });
        return new TextGeometryKey(new RetainedTextKey<>(glyphs, x, y, color, shadow, bidirectional, backgroundColor, variant, generation));
    }

    public List<Glyph> glyphs() { return value.glyphs(); }
    public long resourceGeneration() { return value.resourceGeneration(); }

    public record Glyph(int visualIndex, int codePoint, Style style) { }
}
