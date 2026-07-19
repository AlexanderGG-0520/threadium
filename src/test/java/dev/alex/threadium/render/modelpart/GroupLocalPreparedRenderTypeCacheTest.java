package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

class GroupLocalPreparedRenderTypeCacheTest {
    @Test
    void consecutiveExactIdentityReusesValidatedPreparation() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType type = type("a");
        PreparedRenderType prepared = prepared(RenderPipelines.ENTITY_CUTOUT);
        int prepareCalls = 0;
        int installCalls = 0;
        int reuseHits = 0;
        for (int i = 0; i < 3; i++) {
            PreparedRenderType found =
                    cache.reuse(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), 4, 7);
            if (found == null) {
                prepareCalls++;
                installCalls++;
                assertTrue(cache.install(
                        type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), prepared, 4, 7));
            } else {
                reuseHits++;
                assertSame(prepared, found);
            }
        }
        assertEquals(1, prepareCalls);
        assertEquals(1, installCalls);
        assertEquals(2, reuseHits);
    }

    @Test
    void distinctEquivalentTypesUseIdentityAndNonConsecutiveAbaMisses() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType first = type("same");
        RenderType second = type("same");
        PreparedRenderType firstPrepared = prepared(first.pipeline());
        PreparedRenderType secondPrepared = prepared(second.pipeline());
        assertNotSame(first, second);

        int prepareCalls = 0;
        for (RenderType current : List.of(first, second, first)) {
            cache.observe(current);
            assertNull(cache.reuse(current, ModelPartPipelineDescriptor.ENTITY_CUTOUT, current.pipeline(), 1, 1));
            prepareCalls++;
            assertTrue(cache.install(
                    current,
                    ModelPartPipelineDescriptor.ENTITY_CUTOUT,
                    current.pipeline(),
                    current == first ? firstPrepared : secondPrepared,
                    1,
                    1));
        }
        assertEquals(3, prepareCalls);
    }

    @Test
    void rejectedDifferentIdentityStillBreaksConsecutiveReuse() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType first = type("a");
        RenderType rejected = type("b");
        PreparedRenderType prepared = prepared(first.pipeline());
        assertTrue(cache.install(first, ModelPartPipelineDescriptor.ENTITY_CUTOUT, first.pipeline(), prepared, 1, 1));

        cache.observe(rejected); // The later queue precheck rejects B, so B is never installed.
        cache.observe(first);

        assertNull(cache.reuse(first, ModelPartPipelineDescriptor.ENTITY_CUTOUT, first.pipeline(), 1, 1));
        assertFalse(cache.populated());
    }

    @Test
    void groupGenerationAndLifecycleBoundariesInvalidateEntry() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType type = type("a");
        PreparedRenderType prepared = prepared(type.pipeline());
        assertTrue(cache.install(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), prepared, 2, 3));
        assertNull(cache.reuse(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), 2, 4));
        assertFalse(cache.populated());
        assertTrue(cache.install(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), prepared, 2, 3));
        assertNull(cache.reuse(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), 3, 3));
        assertFalse(cache.populated());
        assertTrue(cache.install(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), prepared, 2, 3));
        cache.clear();
        assertFalse(cache.populated());
    }

    @Test
    void inconsistentPreparedPipelineIsNeverInstalledOrReused() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType type = type("a");
        PreparedRenderType wrong = prepared(RenderPipelines.ENTITY_SOLID);
        assertFalse(cache.install(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), wrong, 1, 1));
        assertFalse(cache.populated());
        assertNull(cache.reuse(type, ModelPartPipelineDescriptor.ENTITY_CUTOUT, type.pipeline(), 1, 1));
    }

    @Test
    void ineligibleDescriptorNeverInstallsAReusablePreparation() {
        var cache = new GroupLocalPreparedRenderTypeCache();
        RenderType type = type("dynamic");
        int prepareCalls = 0;
        for (int i = 0; i < 2; i++) {
            PreparedRenderType found =
                    ModelPartPipelineDescriptor.ENTITY_CUTOUT_DISSOLVE.allowsGroupLocalPreparedReuse()
                            ? cache.reuse(
                                    type, ModelPartPipelineDescriptor.ENTITY_CUTOUT_DISSOLVE, type.pipeline(), 1, 1)
                            : null;
            assertNull(found);
            prepareCalls++;
            cache.clear();
        }
        assertEquals(2, prepareCalls);
        assertFalse(cache.populated());
    }

    @Test
    void ineligiblePreparedSourceValidationRemainsExactAndIdentityBased() {
        PreparedRenderType valid = prepared(RenderPipelines.ENTITY_CUTOUT_DISSOLVE);
        PreparedRenderType invalid = prepared(RenderPipelines.ENTITY_SOLID);
        assertTrue(Blaze3dModelPartBackend.preparedMatchesSource(valid, RenderPipelines.ENTITY_CUTOUT_DISSOLVE));
        assertFalse(Blaze3dModelPartBackend.preparedMatchesSource(invalid, RenderPipelines.ENTITY_CUTOUT_DISSOLVE));
    }

    private static RenderType type(String name) {
        return RenderType.create(
                name, RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT).createRenderSetup());
    }

    private static PreparedRenderType prepared(com.mojang.blaze3d.pipeline.RenderPipeline pipeline) {
        return new PreparedRenderType(pipeline, null, null, null, List.of());
    }
}
