package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Dense depth map in metres, derived from a disparity map and the exact
 * calibration version used to compute it. Invalid pixels are NaN and
 * keep the same {@link InvalidReason} as the source disparity pixel.
 */
public final class DepthMap {
    private final int width;
    private final int height;
    private final float[] depth;
    private final InvalidReason[] reasons;
    private final String calibrationVersion;

    private DepthMap(int width, int height, float[] depth, InvalidReason[] reasons,
                     String calibrationVersion) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.reasons = reasons;
        this.calibrationVersion = calibrationVersion;
    }

    /**
     * Converts disparities to depth via z = f * B / d. Disparities at or
     * below zero cannot produce a finite positive depth and are marked
     * {@link InvalidReason#OUT_OF_RANGE}.
     */
    public static DepthMap fromDisparity(DisparityMap disp, CalibrationParams params) {
        int w = disp.width();
        int h = disp.height();
        float[] depth = new float[w * h];
        InvalidReason[] reasons = new InvalidReason[w * h];
        double fb = params.focalPixels() * params.baselineMeters();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                reasons[i] = disp.reason(x, y);
                if (reasons[i] != InvalidReason.VALID) {
                    depth[i] = Float.NaN;
                    continue;
                }
                float d = disp.disparity(x, y);
                if (!(d > 0.0f)) {
                    reasons[i] = InvalidReason.OUT_OF_RANGE;
                    depth[i] = Float.NaN;
                } else {
                    depth[i] = (float) (fb / d);
                }
            }
        }
        return new DepthMap(w, h, depth, reasons, params.version());
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float depth(int x, int y) {
        return depth[y * width + x];
    }

    public InvalidReason reason(int x, int y) {
        return reasons[y * width + x];
    }

    public boolean isValid(int x, int y) {
        return reasons[y * width + x] == InvalidReason.VALID;
    }

    public String calibrationVersion() {
        return calibrationVersion;
    }
}
