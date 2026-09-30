package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * A captured left/right image pair. Both images must share dimensions.
 */
public record StereoImagePair(GrayImage left, GrayImage right) {
    public StereoImagePair {
        if (left == null || right == null) {
            throw new IllegalArgumentException("left and right images are required");
        }
        if (left.width() != right.width() || left.height() != right.height()) {
            throw new IllegalArgumentException("left and right image dimensions must match");
        }
    }

    public int width() {
        return left.width();
    }

    public int height() {
        return left.height();
    }
}
