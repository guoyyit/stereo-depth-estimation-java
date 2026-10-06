package com.github.guoyyit.stereodepthestimationjava.model;

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
     * Content equality ignoring nothing: every calibration field must be bit-identical.
     * Used by the registry to enforce immutability of an already-used version identifier.
     */
    public boolean contentEquals(CalibrationParams other) {
        if (other == null) {
            return false;
        }
        return imageWidth == other.imageWidth
                && imageHeight == other.imageHeight
                && java.util.Arrays.equals(k, other.k)
                && java.util.Arrays.equals(d, other.d)
                && java.util.Arrays.equals(r, other.r)
                && java.util.Arrays.equals(t, other.t);
    }

    /**
     * Stable fingerprint of the full calibration content (excluding the version label).
     * Two params with equal content always yield equal fingerprints.
     */
    public String contentFingerprint() {
        long h = 1125899906842597L;
        h = 31 * h + imageWidth;
        h = 31 * h + imageHeight;
        h = mix(h, k);
        h = mix(h, d);
        h = mix(h, r);
        h = mix(h, t);
        return Long.toHexString(h);
    }

    private static long mix(long h, double[] values) {
        for (double v : values) {
            h = 31 * h + Double.doubleToLongBits(v);
        }
        return h;
    }

    private static void requireLength(double[] values, int expected, String name) {
        if (values == null || values.length != expected) {
            throw new IllegalArgumentException(name + " must have length " + expected);
        }
    }
}
