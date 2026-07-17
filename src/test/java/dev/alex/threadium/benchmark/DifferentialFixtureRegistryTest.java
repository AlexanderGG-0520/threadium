package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DifferentialFixtureRegistryTest {
    @Test
    void exactlyTwentyCanonicalFixturesAreRegistered() {
        var fixtures = DifferentialFixtureRegistry.fixtures();
        assertEquals(20, fixtures.size());
        assertEquals(
                20,
                new HashSet<>(fixtures.stream()
                                .map(DifferentialFixtureRegistry.Fixture::canonical)
                                .toList())
                        .size());
    }

    @Test
    void entitySolidBaselineContractIsRetained() {
        var fixture = DifferentialFixtureRegistry.require("entity_solid");
        assertEquals("textures/entity/player/wide/steve.png", fixture.texture().getPath());
        assertEquals(DifferentialFixtureRegistry.Material.ENTITY_SOLID, fixture.material());
        assertEquals(DifferentialFixtureRegistry.Phase.BASELINE, fixture.phase());
    }

    @Test
    void phaseSizesMatchRequiredMilestones() {
        assertEquals(
                5,
                DifferentialFixtureRegistry.phase(DifferentialFixtureRegistry.Phase.A)
                        .size());
        assertEquals(
                7,
                DifferentialFixtureRegistry.phase(DifferentialFixtureRegistry.Phase.B)
                        .size());
        assertEquals(
                7,
                DifferentialFixtureRegistry.phase(DifferentialFixtureRegistry.Phase.C)
                        .size());
    }

    @Test
    void exactNineSortedFixturesHaveMultipleCameraSamples() {
        Set<String> expected = Set.of(
                "armor_translucent",
                "entity_translucent",
                "entity_translucent_cull",
                "entity_translucent_emissive",
                "banner_pattern",
                "breeze_wind",
                "energy_swirl",
                "eyes",
                "crumbling");
        Set<String> actual = new HashSet<>(DifferentialFixtureRegistry.fixtures().stream()
                .filter(DifferentialFixtureRegistry.Fixture::sorted)
                .map(DifferentialFixtureRegistry.Fixture::canonical)
                .toList());
        assertEquals(expected, actual);
        DifferentialFixtureRegistry.fixtures().stream()
                .filter(DifferentialFixtureRegistry.Fixture::sorted)
                .forEach(fixture -> assertTrue(fixture.cameraYawSamples().size() >= 2, fixture.canonical()));
    }

    @Test
    void dynamicFixturesHaveMultipleFixedSamples() {
        for (String name : Set.of("entity_cutout_dissolve", "breeze_wind", "energy_swirl", "glint"))
            assertTrue(
                    DifferentialFixtureRegistry.require(name).animationSamples().size() >= 3, name);
    }

    @Test
    void everyFixtureDeclaresLayerMaterialAndTargets() {
        DifferentialFixtureRegistry.fixtures().forEach(fixture -> {
            assertNotNull(fixture.modelLayer(), fixture.canonical());
            assertNotNull(fixture.material(), fixture.canonical());
            assertNotNull(fixture.targetContract(), fixture.canonical());
            if (fixture.material() != DifferentialFixtureRegistry.Material.WATER_MASK)
                assertNotNull(fixture.texture(), fixture.canonical());
        });
    }
}
