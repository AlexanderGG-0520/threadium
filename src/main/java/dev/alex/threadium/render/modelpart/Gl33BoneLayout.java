package dev.alex.threadium.render.modelpart;

public final class Gl33BoneLayout {
    public static final int POSE_TEXELS = 4;
    public static final int NORMAL_TEXELS = 3;
    public static final int TEXELS_PER_BONE = 7;
    public static final int FLOATS_PER_BONE = 28;
    public static final int BYTES_PER_BONE = 112;

    private Gl33BoneLayout() {}

    public static int poseTexel(int bone, int column) {
        return Math.addExact(Math.multiplyExact(bone, TEXELS_PER_BONE), column);
    }

    public static int normalTexel(int bone, int column) {
        return Math.addExact(Math.multiplyExact(bone, TEXELS_PER_BONE), POSE_TEXELS + column);
    }

    public static int bytesForBones(int bones) {
        if (bones < 0) throw new IllegalArgumentException("negative bones");
        return Math.multiplyExact(bones, BYTES_PER_BONE);
    }
}
