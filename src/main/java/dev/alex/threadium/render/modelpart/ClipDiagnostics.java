package dev.alex.threadium.render.modelpart;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Allocation-free helpers used by one-shot visual diagnostics and JVM tests. */
public final class ClipDiagnostics {
    private ClipDiagnostics() {}

    public static boolean finite(Matrix4f matrix) {
        float[] values = new float[16];
        matrix.get(values);
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    public static Vector4f shaderTransform(Matrix4f projection, Matrix4f root, Matrix4f bone, Vector4f local) {
        return new Matrix4f(projection).mul(root).mul(bone).transform(new Vector4f(local));
    }

    public static boolean insideClipVolume(Vector4f clip, boolean zeroToOneDepth) {
        if (!Float.isFinite(clip.x)
                || !Float.isFinite(clip.y)
                || !Float.isFinite(clip.z)
                || !Float.isFinite(clip.w)
                || clip.w <= 0.0f) return false;
        float minimumDepth = zeroToOneDepth ? 0.0f : -clip.w;
        return clip.x >= -clip.w
                && clip.x <= clip.w
                && clip.y >= -clip.w
                && clip.y <= clip.w
                && clip.z >= minimumDepth
                && clip.z <= clip.w;
    }
}
