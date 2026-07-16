package dev.alex.threadium.render.modelpart;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

record ModelPartUvTransform(float offsetU, float offsetV, float scaleU, float scaleV) {
    static final ModelPartUvTransform IDENTITY = new ModelPartUvTransform(0, 0, 1, 1);

    static ModelPartUvTransform from(@Nullable TextureAtlasSprite sprite) {
        return sprite == null ? IDENTITY : new ModelPartUvTransform(
                sprite.getU0(), sprite.getV0(), sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0());
    }
}
