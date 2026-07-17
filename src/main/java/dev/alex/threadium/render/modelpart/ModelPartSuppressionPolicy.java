package dev.alex.threadium.render.modelpart;

public final class ModelPartSuppressionPolicy {
    private ModelPartSuppressionPolicy() {}

    public static boolean maySuppress(
            DebugVisualMode mode, boolean debugSuppressVanilla, ModelPartInterceptionResult result) {
        if (result != ModelPartInterceptionResult.GPU_REPLACED || mode.overlayOnly()) return false;
        return switch (mode) {
            case MESH_MAGENTA,
                    MESH_NO_DEPTH,
                    MESH_NO_CULL,
                    MESH_IDENTITY_BONE,
                    MESH_IDENTITY_ROOT,
                    MESH_PROJECTION_ONLY -> debugSuppressVanilla;
            case NORMAL, OFF -> true;
            case SCREEN_TRIANGLE, SCREEN_TRIANGLE_MAIN_TARGET, MESH_CLIP_SPACE -> false;
        };
    }
}
