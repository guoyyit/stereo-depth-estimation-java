package com.github.guoyyit.stereodepthestimationjava.model;

public final class RectificationMaps {

    private final String calibrationVersion;
    private final int width;
    private final int height;
    private final double[] rectifiedK;
    private final float[] mapXLeft;
    private final float[] mapYLeft;
    private final float[] mapXRight;
    private final float[] mapYRight;

    public RectificationMaps(String calibrationVersion, int width, int height, double[] rectifiedK,
                             float[] mapXLeft, float[] mapYLeft,
                             float[] mapXRight, float[] mapYRight) {
        this.calibrationVersion = calibrationVersion;
        this.width = width;
        this.height = height;
        this.rectifiedK = rectifiedK;
        this.mapXLeft = mapXLeft;
        this.mapYLeft = mapYLeft;
        this.mapXRight = mapXRight;
        this.mapYRight = mapYRight;
    }

    public String calibrationVersion() { return calibrationVersion; }

    public int width() { return width; }

    public int height() { return height; }

    public double[] rectifiedK() { return rectifiedK; }

    public float[] mapXLeft() { return mapXLeft; }

    public float[] mapYLeft() { return mapYLeft; }

    public float[] mapXRight() { return mapXRight; }

    public float[] mapYRight() { return mapYRight; }
}
