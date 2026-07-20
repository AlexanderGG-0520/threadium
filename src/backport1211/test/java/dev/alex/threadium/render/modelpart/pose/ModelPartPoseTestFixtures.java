package dev.alex.threadium.render.modelpart.pose;

final class ModelPartPoseTestFixtures {
    static final ModelPartPoseCapture.Limits LIMITS = new ModelPartPoseCapture.Limits(128, 3_200, 2, 64 * 1024);

    private ModelPartPoseTestFixtures() {}

    static ImmutableModelPartBonePose pose(int bones, float translationX, boolean treeVisible, boolean drawVisible) {
        ModelPartPoseCapture capture = new ModelPartPoseCapture(bones, LIMITS);
        float[] position = identity4();
        float[] normal = identity3();
        for (int bone = 0; bone < bones; bone++) {
            position[12] = bone == 0 ? translationX : translationX + bone;
            capture.captureBone(bone, position, normal, treeVisible, drawVisible, false);
        }
        return capture.complete();
    }

    static float[] identity4() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    static float[] identity3() {
        return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }
}
