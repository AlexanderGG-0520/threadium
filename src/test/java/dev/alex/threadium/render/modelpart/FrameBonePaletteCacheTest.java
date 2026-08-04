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
    void repeatedExactHitUsesRecentEntryOnlyAfterGeneralReuseConfirmation() {
        Fixture fixture = fixture();
        ModelPartBoneData palette = palette(1);

        assertNull(fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.store(palette));
        assertSame(palette, fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        assertSame(palette, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
        assertEquals(1, fixture.cache.size());
    }

    @Test
    void everyCapturedPoseFieldInvalidatesRecentEntry() {
        assertPoseChangeMiss(part -> part.x = 1);
        assertPoseChangeMiss(part -> part.y = 1);
        assertPoseChangeMiss(part -> part.z = 1);
        assertPoseChangeMiss(part -> part.xRot = 0.25f);
        assertPoseChangeMiss(part -> part.yRot = 0.25f);
        assertPoseChangeMiss(part -> part.zRot = 0.25f);
        assertPoseChangeMiss(part -> part.xScale = 2);
        assertPoseChangeMiss(part -> part.yScale = 2);
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
        ModelPartBoneData first = palette(1);
        fixture.cache.store(first);
        assertSame(first, fixture.cache.findWithHashForTest(fixture.topology, 7));
        fixture.part.x = 3;
        assertNull(fixture.cache.findWithHashForTest(fixture.topology, 7));
        assertFalse(fixture.cache.lastLookupUsedRecent());
    }

    @Test
    void alternatingReusablePosesUpdateRecentEntryAfterGeneralHits() {
        Fixture fixture = fixture();
        ModelPartBoneData first = palette(1);
        ModelPartBoneData second = palette(2);

        assertNull(fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.store(first));
        fixture.part.x = 2;
        assertNull(fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.store(second));

        fixture.part.x = 0;
        assertSame(first, fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        fixture.part.x = 2;
        assertSame(second, fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        assertSame(second, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
        fixture.part.x = 0;
        assertSame(first, fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        assertSame(first, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
        assertEquals(2, fixture.cache.size());
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
    void distinctPopulationBypassesAfterBoundedProbes() {
        Fixture fixture = fixture();
        int lookups = 0;
        int directPacked = 0;
        for (int i = 0; i < 256; i++) {
            fixture.part.x = i;
            assertNull(fixture.cache.find(fixture.topology));
            if (fixture.cache.lastLookupPerformed()) lookups++;
            if (!fixture.cache.store(palette(i))) directPacked++;
        }
        assertEquals(FrameBonePaletteCache.MISS_PROBE_LIMIT, lookups);
        assertEquals(FrameBonePaletteCache.MISS_PROBE_LIMIT - 1, fixture.cache.size());
        assertEquals(256 - FrameBonePaletteCache.MISS_PROBE_LIMIT + 1, directPacked);
    }

    @Test
    void newFrameReprobesButStopsWhenUniquePosesDominate() {
        Fixture fixture = fixture();
        for (int i = 0; i < 256; i++) {
            fixture.part.x = i;
            assertNull(fixture.cache.find(fixture.topology));
            fixture.cache.store(palette(i));
        }

        fixture.cache.beginFrame();
        fixture.part.x = 7;
        assertNull(fixture.cache.find(fixture.topology));
        ModelPartBoneData shared = palette(7);
        assertTrue(fixture.cache.store(shared));
        assertSame(shared, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupPerformed());

        int lookups = 0;
        int directPacked = 0;
        for (int i = 0; i < 256; i++) {
            fixture.part.x = 1000 + i;
            assertNull(fixture.cache.find(fixture.topology));
            if (fixture.cache.lastLookupPerformed()) lookups++;
            if (!fixture.cache.store(palette(i))) directPacked++;
        }
        assertEquals(FrameBonePaletteCache.MISS_PROBE_LIMIT - 1, lookups);
        assertEquals(256 - (FrameBonePaletteCache.MISS_PROBE_LIMIT - 2), directPacked);
        assertEquals(FrameBonePaletteCache.MISS_PROBE_LIMIT - 1, fixture.cache.size());
    }

    @Test
    void bypassIsScopedToOneTopology() {
        Fixture fixture = fixture();
        for (int i = 0; i < 256; i++) {
            fixture.part.x = i;
            assertNull(fixture.cache.find(fixture.topology));
            fixture.cache.store(palette(i));
        }

        ModelPart secondPart = new ModelPart(List.of(), Map.of());
        GenericModelPartTopology secondTopology = new GenericModelPartTopology(
                List.of(new GenericModelPartTopology.Node(secondPart, -1, 0, "root")),
                new GenericModelPartTopology.StructuralKey(2, List.of(2L)));
        assertNull(fixture.cache.find(secondTopology));
        ModelPartBoneData shared = palette(2);
        assertTrue(fixture.cache.store(shared));
        assertSame(shared, fixture.cache.find(secondTopology));
    }

    @Test
    void frameBoundaryDropsPaletteReferences() {
        Fixture fixture = fixture();
        fixture.cache.find(fixture.topology);
        ModelPartBoneData first = palette(1);
        fixture.cache.store(first);
        assertSame(first, fixture.cache.find(fixture.topology));
        assertSame(first, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
        fixture.cache.beginFrame();
        assertEquals(0, fixture.cache.size());
        assertNull(fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        ModelPartBoneData second = palette(2);
        assertTrue(fixture.cache.store(second));
        assertSame(second, fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
        assertSame(second, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
    }

    @Test
    void recentEntriesRemainScopedToTheirExactTopology() {
        Fixture first = fixture();
        Fixture second = fixture();
        ModelPartBoneData firstPalette = palette(1);
        ModelPartBoneData secondPalette = palette(2);

        assertNull(first.cache.find(first.topology));
        assertTrue(first.cache.store(firstPalette));
        assertSame(firstPalette, first.cache.find(first.topology));
        assertSame(firstPalette, first.cache.find(first.topology));
        assertTrue(first.cache.lastLookupUsedRecent());

        assertNull(first.cache.find(second.topology));
        assertTrue(first.cache.store(secondPalette));
        assertSame(secondPalette, first.cache.find(second.topology));
        assertFalse(first.cache.lastLookupUsedRecent());
        assertSame(secondPalette, first.cache.find(second.topology));
        assertTrue(first.cache.lastLookupUsedRecent());

        assertSame(firstPalette, first.cache.find(first.topology));
        assertTrue(first.cache.lastLookupUsedRecent());
    }

    @Test
    void clearDropsRecentEntriesAndPaletteReferences() {
        Fixture fixture = fixture();
        ModelPartBoneData first = palette(1);
        assertNull(fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.store(first));
        assertSame(first, fixture.cache.find(fixture.topology));
        assertSame(first, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());

        fixture.cache.clear();

        assertEquals(0, fixture.cache.size());
        assertNull(fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
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
        ModelPartBoneData palette = palette(1);
        fixture.cache.store(palette);
        assertSame(palette, fixture.cache.find(fixture.topology));
        assertSame(palette, fixture.cache.find(fixture.topology));
        assertTrue(fixture.cache.lastLookupUsedRecent());
        change.accept(fixture.part);
        assertNull(fixture.cache.find(fixture.topology));
        assertFalse(fixture.cache.lastLookupUsedRecent());
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
