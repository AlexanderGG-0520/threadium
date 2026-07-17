package dev.alex.threadium.mixin;

import dev.alex.threadium.render.text.RetainedTextManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TextFeatureRenderer.class)
abstract class TextFeatureRendererMixin {
    /** 26.2 offsets 119 and 186: normal and outlined base text share this exact preparation call. */
    @Redirect(
            method = "buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"),
            require = 2,
            expect = 2)
    private Font.PreparedText threadium$retainText(
            Font font,
            FormattedCharSequence sequence,
            float x,
            float y,
            int color,
            boolean shadow,
            boolean bidirectional,
            int background) {
        return RetainedTextManager.retain(
                sequence,
                x,
                y,
                color,
                shadow,
                bidirectional,
                background,
                0,
                () -> font.prepareText(sequence, x, y, color, shadow, bidirectional, background));
    }

    /** 26.2 offset 157: outline glyph preparation, retained separately from the base glyph pass. */
    @Redirect(
            method = "buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/Font;prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private Font.PreparedText threadium$retainOutline(
            Font font, FormattedCharSequence sequence, float x, float y, int color) {
        return RetainedTextManager.retain(
                sequence, x, y, color, false, false, 0, 1, () -> font.prepare8xTextOutline(sequence, x, y, color));
    }
}
