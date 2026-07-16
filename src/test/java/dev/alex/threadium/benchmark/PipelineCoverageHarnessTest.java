package dev.alex.threadium.benchmark;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PipelineCoverageHarnessTest {
    @Test void allCanonicalPipelinesHaveOneUniqueStation(){
        var fixtures=PipelineCoverageHarness.fixtures();
        assertEquals(20,fixtures.size());
        assertEquals(20,new HashSet<>(fixtures.stream().map(PipelineCoverageHarness.Fixture::pipeline).toList()).size());
        assertEquals(20,new HashSet<>(fixtures.stream().map(f->f.column()+":"+f.row()).toList()).size());
    }
    @Test void exactNineSortedFixturesAreMarked(){
        Set<String> expected=Set.of("armor_translucent","entity_translucent","entity_translucent_cull","entity_translucent_emissive","banner_pattern","breeze_wind","energy_swirl","eyes","crumbling");
        assertEquals(expected,PipelineCoverageHarness.fixtures().stream().filter(PipelineCoverageHarness.Fixture::sorted).map(PipelineCoverageHarness.Fixture::pipeline).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void unknownCustomPipelineIsNotARequiredFixture(){
        assertTrue(PipelineCoverageHarness.fixtures().stream().noneMatch(f->f.pipeline().equals("unknown_custom")));
    }
}
