package dev.alex.threadium.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DifferentialImageComparatorTest {
    private static final DifferentialImageComparator.Tolerance EXACT=new DifferentialImageComparator.Tolerance(0,0,true);
    @Test void exactColorAndDepthPass(){assertTrue(DifferentialImageComparator.compare(new int[]{0xff123456},new int[]{0xff123456},new float[]{.5f},new float[]{.5f},EXACT).passes());}
    @Test void colorMismatchFails(){var m=DifferentialImageComparator.compare(new int[]{0xff123456},new int[]{0xff133456},new float[]{.5f},new float[]{.5f},EXACT);assertEquals(1,m.differingPixels());assertFalse(m.passes());}
    @Test void coverageMaskMismatchFailsIndependently(){var m=DifferentialImageComparator.compare(new int[]{0x00123456},new int[]{0xff123456},new float[]{1},new float[]{1},new DifferentialImageComparator.Tolerance(255,0,true));assertEquals(1,m.coverageMaskDifferences());assertFalse(m.passes());}
    @Test void depthMismatchFailsWhenRequired(){var m=DifferentialImageComparator.compare(new int[]{0},new int[]{0},new float[]{.2f},new float[]{.3f},EXACT);assertEquals(1,m.depthDifferences());}
    @Test void missingDepthIsHarnessInputError(){assertThrows(IllegalArgumentException.class,()->DifferentialImageComparator.compare(new int[]{0},new int[]{0},null,null,EXACT));}
}
