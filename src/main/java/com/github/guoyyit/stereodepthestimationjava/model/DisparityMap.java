package com.github.guoyyit.stereodepthestimationjava.model;

public final class DisparityMap {

    private final int width;
    private final int height;
    private final float[] disparity;
    private final InvalidReason[] reasons;

    public DisparityMap(int width, int height, float[] disparity, InvalidReason[] reasons) {
        this.width = width;
        this.height = height;
        this.disparity = disparity;
        this.reasons = reasons;
    }

    public int width() { return width; }

    public int height() { return height; }

    public float[] disparity() { return disparity; }

    public InvalidReason[] reasons() { return reasons; }

    public boolean isValid(int x, int y) {
        return reasons[y * width + x] == InvalidReason.VALID;
    }
}
