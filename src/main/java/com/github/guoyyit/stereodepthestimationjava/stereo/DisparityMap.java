package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Dense disparity map with a per-pixel validity reason. Invalid pixels
 * store {@link Float#NaN} in {@link #disparity} and a reason other than
 * {@link InvalidReason#VALID} in {@link #reasons}; invalid pixels never
 * carry zero or neighbour-filled substitute values.
 */
public final class DisparityMap {
    private final int width;
    private final int height;
    private final float[] disparity;
    private final InvalidReason[] reasons;

    public DisparityMap(int width, int height) {
        this.width = width;
        this.height = height;
        this.disparity = new float[width * height];
        this.reasons = new InvalidReason[width * height];
        java.util.Arrays.fill(disparity, Float.NaN);
        java.util.Arrays.fill(reasons, InvalidReason.OUT_OF_RANGE);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void setValid(int x, int y, float value) {
        int i = y * width + x;
        disparity[i] = value;
        reasons[i] = InvalidReason.VALID;
    }

    public void setInvalid(int x, int y, InvalidReason reason) {
        if (reason == InvalidReason.VALID) {
            throw new IllegalArgumentException("use setValid for valid pixels");
        }
        int i = y * width + x;
        disparity[i] = Float.NaN;
        reasons[i] = reason;
    }

    public float disparity(int x, int y) {
        return disparity[y * width + x];
    }

    public InvalidReason reason(int x, int y) {
        return reasons[y * width + x];
    }

    public boolean isValid(int x, int y) {
        return reasons[y * width + x] == InvalidReason.VALID;
    }

    public DisparityMap copy() {
        DisparityMap copy = new DisparityMap(width, height);
        System.arraycopy(disparity, 0, copy.disparity, 0, disparity.length);
        System.arraycopy(reasons, 0, copy.reasons, 0, reasons.length);
        return copy;
    }
}
