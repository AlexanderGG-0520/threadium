package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Immutable inverse pose used by Vanilla's sheeted crumbling decal projection. */
record ModelPartDecalTransform(float[] values) {
    static ModelPartDecalTransform capture(PoseStack.Pose pose) {
        Matrix4f inversePose = new Matrix4f(pose.pose()).invert();
        Matrix3f inverseNormal = new Matrix3f(pose.normal()).invert();
        float[] values = new float[28];
        inversePose.get(values, 0);
        values[16]=inverseNormal.m00();values[17]=inverseNormal.m01();values[18]=inverseNormal.m02();
        values[20]=inverseNormal.m10();values[21]=inverseNormal.m11();values[22]=inverseNormal.m12();
        values[24]=inverseNormal.m20();values[25]=inverseNormal.m21();values[26]=inverseNormal.m22();
        return new ModelPartDecalTransform(values);
    }
}
