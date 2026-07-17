package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class FrameBonePaletteCacheTest {
    @Test
    void exactPoseAndTopologyReusePalette() {
        Fixture fixture = fixture();
        ModelPartBoneData palette = palette(1);
        assertNull(fixture.cache.find(fixture.topology));
        fixture.cache.store(palette);
        assertSame(palette, fixture.cache.find(fixture.topology));
    }

    @Test
    void translationRotationScaleAndVisibilityDifferencesMiss() {
        assertPoseChangeMiss(part -> part.x = 1);
        assertPoseChangeMiss(part -> part.yRot = 0.25f);
        assertPoseChangeMiss(part -> part.zScale = 2);
        assertPoseChangeMiss(part -> part.visible = false);
        assertPoseChangeMiss(part -> part.skipDraw = true);
    }

    @Test
    void differentTopologyNeverShares() {
        Fixture first = fixture();
        Fixture second = fixture();
        first.cache.find(first.topology);
        first.cache.store(palette(1));
        assertNull(first.cache.find(second.topology));
    }

    @Test
    void hashCollisionStillRequiresExactComparison() {
        Fixture fixture = fixture();
        assertNull(fixture.cache.findWithHashForTest(fixture.topology, 7));
        fixture.cache.store(palette(1));
        fixture.part.x = 3;
        assertNull(fixture.cache.findWithHashForTest(fixture.topology, 7));
    }

    @Test
    void identicalPopulationCreatesOnePaletteAnd255Hits() {
        Fixture fixture = fixture();
        int hits = 0;
        for (int i = 0; i < 256; i++) {
            ModelPartBoneData found = fixture.cache.find(fixture.topology);
            if (found == null) fixture.cache.store(palette(i));
            else hits++;
        }
        assertEquals(1, fixture.cache.size());
        assertEquals(255, hits);
    }

    @Test
    void distinctPopulationCreates256Palettes() {
        Fixture fixture = fixture();
        for (int i = 0; i < 256; i++) {
            fixture.part.x = i;
            assertNull(fixture.cache.find(fixture.topology));
            fixture.cache.store(palette(i));
        }
        assertEquals(256, fixture.cache.size());
    }

    @Test
    void frameBoundaryDropsPaletteReferences() {
        Fixture fixture = fixture();
        fixture.cache.find(fixture.topology);
        fixture.cache.store(palette(1));
        fixture.cache.beginFrame();
        assertEquals(0, fixture.cache.size());
        assertNull(fixture.cache.find(fixture.topology));
    }

    @Test
    void rootTransformsRemainIndependentOfSharedLocalPalette() {
        Fixture fixture = fixture();
        fixture.cache.find(fixture.topology);
        ModelPartBoneData palette = palette(1);
        fixture.cache.store(palette);
        assertSame(palette, fixture.cache.find(fixture.topology));
        Matrix4f firstRoot = new Matrix4f().translate(1, 0, 0);
        Matrix4f secondRoot = new Matrix4f().translate(2, 0, 0);
        assertNotEquals(firstRoot, secondRoot);
    }

    private static void assertPoseChangeMiss(java.util.function.Consumer<ModelPart> change) {
        Fixture fixture = fixture();
        fixture.cache.find(fixture.topology);
        fixture.cache.store(palette(1));
        change.accept(fixture.part);
        assertNull(fixture.cache.find(fixture.topology));
    }

    private static Fixture fixture() {
        ModelPart part = new ModelPart(List.of(), Map.of());
        GenericModelPartTopology topology = new GenericModelPartTopology(
                List.of(new GenericModelPartTopology.Node(part, -1, 0, "root")),
                new GenericModelPartTopology.StructuralKey(1, List.of(1L)));
        return new Fixture(new FrameBonePaletteCache(), part, topology);
    }

    private static ModelPartBoneData palette(int value) {
        return new ModelPartBoneData(new float[] {value}, new long[] {1});
    }

    private record Fixture(FrameBonePaletteCache cache, ModelPart part, GenericModelPartTopology topology) {}
}
