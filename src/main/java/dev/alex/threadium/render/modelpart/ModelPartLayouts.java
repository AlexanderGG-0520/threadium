package dev.alex.threadium.render.modelpart;

public final class ModelPartLayouts {
    public static final int VERTEX_STRIDE = 36;
    public static final int BONE_STRIDE = 112; // mat4 + std430 mat3 padded as 3 vec4
    public static final int INSTANCE_STRIDE = 112;
    public static final int INSTANCE_ROOT_OFFSET=0, INSTANCE_BONE_BASE_OFFSET=64, INSTANCE_LIGHT_OFFSET=68, INSTANCE_OVERLAY_OFFSET=72, INSTANCE_TINT_OFFSET=80, INSTANCE_UV_TRANSFORM_OFFSET=96;
    public static int instanceOffset(int instance){return bytes(instance,INSTANCE_STRIDE);}
    private ModelPartLayouts() {}
    public static int bytes(int count, int stride) {
        if (count < 0 || stride < 0) throw new IllegalArgumentException("negative range");
        return Math.multiplyExact(count, stride);
    }
}
