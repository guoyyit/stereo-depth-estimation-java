package com.github.guoyyit.stereodepthestimationjava.model;

public final class GrayscaleImage {

    private final int width;
    private final int height;
    private final float[] data;

    public GrayscaleImage(int width, int height, float[] data) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (data == null || data.length != width * height) {
            throw new IllegalArgumentException("pixel data length does not match image dimensions");
        }
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public int width() { return width; }

    public int height() { return height; }

    public float[] data() { return data; }

    public float get(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("pixel (" + x + "," + y + ") outside " + width + "x" + height);
        }
        return data[y * width + x];
    }

    public void set(int x, int y, float value) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("pixel (" + x + "," + y + ") outside " + width + "x" + height);
        }
        data[y * width + x] = value;
    }

    public float sampleBilinear(float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height) {
            return Float.NaN;
        }
        float fx = x - x0;
        float fy = y - y0;
        float v00 = data[y0 * width + x0];
        float v01 = data[y0 * width + x1];
        float v10 = data[y1 * width + x0];
        float v11 = data[y1 * width + x1];
        float top = v00 + (v01 - v00) * fx;
        float bottom = v10 + (v11 - v10) * fx;
        return top + (bottom - top) * fy;
    }

    public static GrayscaleImage create(int width, int height) {
        return new GrayscaleImage(width, height, new float[width * height]);
    }
}
