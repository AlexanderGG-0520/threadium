package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class DifferentialFixtureRegistry1211Test {
    @Test
    void everySupportedPipelineHasOneFixture() {
        var fixtures = DifferentialFixtureRegistry1211.fixtures();
        long supported = java.util.Arrays.stream(RenderLayer1211Descriptor.Kind.values())
                .filter(RenderLayer1211Descriptor.Kind::backendSupported)
                .count();
        assertEquals(supported, fixtures.size());
        assertEquals(
                fixtures.size(),
                new HashSet<>(fixtures.stream()
                                .map(DifferentialFixtureRegistry1211.Fixture::canonical)
                                .toList())
                        .size());
        fixtures.forEach(
                fixture -> assertEquals(fixture.kind().name().toLowerCase(java.util.Locale.ROOT), fixture.canonical()));
    }

    @Test
    void phasesAndSortedFixturesAreExplicit() {
        assertFalse(DifferentialFixtureRegistry1211.phase(DifferentialFixtureRegistry1211.Phase.A)
                .isEmpty());
        assertFalse(DifferentialFixtureRegistry1211.phase(DifferentialFixtureRegistry1211.Phase.B)
                .isEmpty());
        assertFalse(DifferentialFixtureRegistry1211.phase(DifferentialFixtureRegistry1211.Phase.C)
                .isEmpty());
        DifferentialFixtureRegistry1211.fixtures().stream()
                .filter(DifferentialFixtureRegistry1211.Fixture::sorted)
                .forEach(fixture -> assertTrue(fixture.cameraYawSamples().size() >= 2, fixture.canonical()));
    }

    @Test
    void specialTargetsAreDeclared() {
        assertEquals(
                DifferentialFixtureRegistry1211.OutputTarget.OUTLINE,
                DifferentialFixtureRegistry1211.require("outline_cull").outputTarget());
        assertEquals(
                DifferentialFixtureRegistry1211.OutputTarget.ITEM_ENTITY,
                DifferentialFixtureRegistry1211.require("item_entity_translucent_cull")
                        .outputTarget());
        assertEquals(
                DifferentialFixtureRegistry1211.TargetContract.DEPTH_ONLY,
                DifferentialFixtureRegistry1211.require("water_mask").targetContract());
    }
}
