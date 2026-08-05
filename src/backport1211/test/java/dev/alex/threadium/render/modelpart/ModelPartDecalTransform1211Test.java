package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ModelPartDecalTransform1211Test {
    private static final float EPSILON = 1.0E-6F;

    @Test
    void projectsAllVanillaCardinalFacings() {
        ModelPartDecalTransform1211 transform =
                ModelPartDecalTransform1211.capture(new Matrix4f(), new Matrix3f(), 1.0F);

        assertUv(transform.project(2.0F, 3.0F, 5.0F, 0.0F, 1.0F, 0.0F), 2.0F, 5.0F);
        assertUv(transform.project(2.0F, 3.0F, 5.0F, 0.0F, -1.0F, 0.0F), 2.0F, -5.0F);
        assertUv(transform.project(2.0F, 3.0F, 5.0F, 0.0F, 0.0F, 1.0F), 2.0F, -3.0F);
        assertUv(transform.project(2.0F, 3.0F, 5.0F, 0.0F, 0.0F, -1.0F), -2.0F, -3.0F);
        assertUv(transform.project(2.0F, 3.0F, 5.0F, 1.0F, 0.0F, 0.0F), 5.0F, -3.0F);
        assertUv(transform.project(2.0F, 3.0F, 5.0F, -1.0F, 0.0F, 0.0F), -5.0F, -3.0F);
    }

    @Test
    void preservesVanillaFacingTiePriority() {
        assertUv(ModelPartDecalTransform1211.projectFacing(2, 3, 5, 1, 1, 1, 1), 2, 5);
        assertUv(ModelPartDecalTransform1211.projectFacing(2, 3, 5, 1, 0, 1, 1), 2, -3);
    }

    @Test
    void appliesCapturedInversePoseAndTextureScale() {
        Matrix4f inverseTexture = new Matrix4f().translation(2.0F, 3.0F, 4.0F);
        ModelPartDecalTransform1211 transform =
                ModelPartDecalTransform1211.capture(inverseTexture, new Matrix3f(), 2.0F);

        assertTrue(transform.finite());
        assertEquals(2.0F, transform.textureScale(), EPSILON);
        assertUv(transform.project(1.0F, 2.0F, 3.0F, 0.0F, 1.0F, 0.0F), 6.0F, 14.0F);
    }

    @Test
    void rejectsNonFiniteOrNonPositiveScale() {
        assertFalse(ModelPartDecalTransform1211.capture(new Matrix4f(), new Matrix3f(), Float.NaN)
                .finite());
        assertFalse(ModelPartDecalTransform1211.capture(new Matrix4f(), new Matrix3f(), 0.0F)
                .finite());
    }

    private static void assertUv(ModelPartDecalTransform1211.Uv actual, float expectedU, float expectedV) {
        assertEquals(expectedU, actual.u(), EPSILON);
        assertEquals(expectedV, actual.v(), EPSILON);
    }
}
