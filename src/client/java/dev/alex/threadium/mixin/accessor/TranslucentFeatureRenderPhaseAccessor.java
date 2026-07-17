package dev.alex.threadium.mixin.accessor;

import it.unimi.dsi.fastutil.floats.FloatList;
import java.util.List;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TranslucentFeatureRenderPhase.class)
public interface TranslucentFeatureRenderPhaseAccessor {
    @Accessor("submits")
    List<TranslucentSubmit> threadium$getSubmits();

    @Accessor("distances")
    FloatList threadium$getDistances();
}
