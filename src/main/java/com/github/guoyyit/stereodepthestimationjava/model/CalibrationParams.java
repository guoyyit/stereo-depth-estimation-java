package com.github.guoyyit.stereodepthestimationjava.model;

import java.util.Arrays;

public final class CalibrationParams {

    private final String version;
    private final int imageWidth;
    private final int imageHeight;
    private final double[] k;
    private final double[] d;
    private final double[] r;
    private final double[] t;

    public CalibrationParams(String version, int imageWidth, int imageHeight,
                             double[] k, double[] d, double[] r, double[] t) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("calibration version must not be blank");
        }
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("calibration image dimensions must be positive");
        }
        requireLength(k, 9, "intrinsic matrix K");
        requireLength(d, 5, "distortion coefficients d");
        requireLength(r, 3, "rotation vector r");
        requireLength(t, 3, "translation vector t");
        if (k[0] <= 0 || k[4] <= 0) {
            throw new IllegalArgumentException("intrinsic focal lengths must be positive");
        }
        if (t[0] >= 0) {
            throw new IllegalArgumentException(
                    "stereo baseline translation t[0] must be negative (right camera to the right)");
        }
        this.version = version;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.k = k.clone();
        this.d = d.clone();
        this.r = r.clone();
        this.t = t.clone();
    }

    public String version() { return version; }

    public int imageWidth() { return imageWidth; }

    public int imageHeight() { return imageHeight; }

    public double[] k() { return k.clone(); }

    public double[] d() { return d.clone(); }

    public double[] r() { return r.clone(); }

    public double[] t() { return t.clone(); }

    public static CalibrationParams create(String version, int imageWidth, int imageHeight,
                                           double[] k, double[] d, double[] r, double[] t) {
        return new CalibrationParams(version, imageWidth, imageHeight, k, d, r, t);
    }

    /**
     * Full content equality: every calibration field (including the version) must be
     * exactly equal. Used by the registry to decide whether a repeated registration is
     * an idempotent no-op or a forbidden content conflict.
     */
    public boolean contentEquals(CalibrationParams other) {
        if (other == null) {
            return false;
        }
        return version.equals(other.version)
                && imageWidth == other.imageWidth
                && imageHeight == other.imageHeight
                && Arrays.equals(k, other.k)
                && Arrays.equals(d, other.d)
                && Arrays.equals(r, other.r)
                && Arrays.equals(t, other.t);
    }

    /**
     * Stable fingerprint of the full calibration content. Frames and rectification maps
     * carry this so a result can be traced to the exact calibration content that produced
     * it, not merely to a version label.
     */
    public long contentFingerprint() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, version.hashCode());
        h = mix(h, imageWidth);
        h = mix(h, imageHeight);
        for (double v : k) { h = mix(h, Double.doubleToLongBits(v)); }
        for (double v : d) { h = mix(h, Double.doubleToLongBits(v)); }
        for (double v : r) { h = mix(h, Double.doubleToLongBits(v)); }
        for (double v : t) { h = mix(h, Double.doubleToLongBits(v)); }
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        h ^= h >>> 29;
        return h;
    }

    private static void requireLength(double[] values, int expected, String name) {
        if (values == null || values.length != expected) {
            throw new IllegalArgumentException(name + " must have length " + expected);
        }
    }
}
