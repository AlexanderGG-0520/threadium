package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ModelPartPipelineDescriptorTest {
    private static final Identifier A = Identifier.fromNamespaceAndPath("threadium", "a");
    private static final Identifier B = Identifier.fromNamespaceAndPath("threadium", "b");

    @Test
    void everyAuditedVanillaPipelineMapsExactlyOnce() {
        assertEquals(20, ModelPartPipelineDescriptor.values().length);
        for (var descriptor : ModelPartPipelineDescriptor.values())
            assertSame(descriptor, ModelPartPipelineDescriptor.from(descriptor.source()));
    }

    @Test
    void establishedPipelinesKeepCanonicalMappings() {
        assertEquals(
                ModelPartPipelineDescriptor.ENTITY_SOLID,
                ModelPartPipelineDescriptor.from(RenderPipelines.ENTITY_SOLID));
        assertEquals(
                ModelPartPipelineDescriptor.ENTITY_CUTOUT,
                ModelPartPipelineDescriptor.from(RenderPipelines.ENTITY_CUTOUT));
        assertEquals(
                ModelPartPipelineDescriptor.ENTITY_CUTOUT_CULL,
                ModelPartPipelineDescriptor.from(RenderPipelines.ENTITY_CUTOUT_CULL));
    }

    @Test
    void aliasesResolveThroughCanonicalSourceState() {
        assertEquals(
                ModelPartPipelineDescriptor.ENTITY_TRANSLUCENT_EMISSIVE,
                ModelPartPipelineDescriptor.from(RenderTypes.breezeEyes(A).pipeline()));
        assertEquals(
                ModelPartPipelineDescriptor.GLINT,
                ModelPartPipelineDescriptor.from(RenderTypes.entityGlint().pipeline()));
    }

    @Test
    void unknownNonModelPipelineFallsBack() {
        assertNull(ModelPartPipelineDescriptor.from(RenderPipelines.LINES));
    }

    @Test
    void blendDepthCullAndOutlineStatesRemainDistinct() {
        assertNotEquals(
                RenderPipelines.ENTITY_SOLID.getColorTargetState(),
                RenderPipelines.ENTITY_TRANSLUCENT.getColorTargetState());
        assertNotEquals(
                RenderPipelines.ARMOR_CUTOUT_NO_CULL.getDepthStencilState(),
                RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL.getDepthStencilState());
        assertNotEquals(RenderPipelines.ENTITY_CUTOUT.isCull(), RenderPipelines.ENTITY_CUTOUT_CULL.isCull());
        assertNotEquals(ModelPartPipelineDescriptor.OUTLINE_CULL, ModelPartPipelineDescriptor.OUTLINE_NO_CULL);
    }

    @Test
    void texturesAndDynamicUvStateCannotShareBatchKey() {
        ModelPartGpuBackend.MeshHandle mesh = new ModelPartGpuBackend.MeshHandle(1, 2, 3, 6, 1, 1);
        ModelPartBatchKey textureA = new ModelPartBatchKey(mesh, RenderTypes.entityCutout(A), 1, 1);
        ModelPartBatchKey textureB = new ModelPartBatchKey(mesh, RenderTypes.entityCutout(B), 1, 1);
        assertNotEquals(textureA, textureB);
        ModelPartBatchKey uvA = new ModelPartBatchKey(mesh, RenderTypes.energySwirl(A, .1f, .2f), 1, 1);
        ModelPartBatchKey uvB = new ModelPartBatchKey(mesh, RenderTypes.energySwirl(A, .2f, .2f), 1, 1);
        assertNotEquals(uvA, uvB);
    }

    @Test
    void translucentAndDynamicPipelinesAreOrderedAdjacentOnly() {
        assertEquals(
                ModelPartPipelineDescriptor.Ordering.ORDERED_ADJACENT,
                ModelPartPipelineDescriptor.ENTITY_TRANSLUCENT.ordering());
        assertEquals(
                ModelPartPipelineDescriptor.Ordering.ORDERED_ADJACENT,
                ModelPartPipelineDescriptor.ENERGY_SWIRL.ordering());
        assertEquals(
                ModelPartPipelineDescriptor.Ordering.ORDERED_ADJACENT,
                ModelPartPipelineDescriptor.CRUMBLING.ordering());
    }

    @Test
    void everyCoverageFixtureUsesItsCanonicalVanillaPipeline() {
        for (var fixture : dev.alex.threadium.benchmark.PipelineCoverageHarness.fixtures()) {
            var descriptor = ModelPartPipelineDescriptor.from(
                    dev.alex.threadium.benchmark.PipelineCoverageHarness.fixtureRenderType(fixture.pipeline(), .25f)
                            .pipeline());
            assertNotNull(descriptor, fixture.pipeline());
            assertEquals(fixture.pipeline(), descriptor.canonicalName());
        }
    }
}
