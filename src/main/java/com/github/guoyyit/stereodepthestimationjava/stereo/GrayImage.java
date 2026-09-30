package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Immutable single-channel image backed by a row-major float array,
 * values in [0, 1].
 */
public final class GrayImage {
    private final int width;
    private final int height;
    private final float[] data;

    public GrayImage(int width, int height, float[] data) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (data == null || data.length != width * height) {
            throw new IllegalArgumentException("data length must equal width*height");
        }
        this.width = width;
        this.height = height;
        this.data = data.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float get(int x, int y) {
        return data[y * width + x];
    }

    public float[] copyData() {
        return data.clone();
    }
}
