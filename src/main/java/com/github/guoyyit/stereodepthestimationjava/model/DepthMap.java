package com.github.guoyyit.stereodepthestimationjava.model;

public final class DepthMap {

    private final int width;
    private final int height;
    private final float[] depth;
    private final InvalidReason[] reasons;
    private final String calibrationVersion;

    public DepthMap(int width, int height, float[] depth, InvalidReason[] reasons,
                    String calibrationVersion) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.reasons = reasons;
        this.calibrationVersion = calibrationVersion;
    }

    public int width() { return width; }

    public int height() { return height; }

    public float[] depth() { return depth; }

    public InvalidReason[] reasons() { return reasons; }

    public String calibrationVersion() { return calibrationVersion; }

    public boolean isValid(int x, int y) {
        return reasons[y * width + x] == InvalidReason.VALID;
    }
}
