package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.calibration.RectificationEngine;

import java.util.Random;

public final class SyntheticScene {

    private final int width;
    private final int height;
    private final CalibrationParams calibration;
    private final RectificationMaps maps;
    private final GrayscaleImage leftRaw;
    private final GrayscaleImage rightRaw;
    private final GrayscaleImage leftRectified;
    private final GrayscaleImage rightRectified;
    private final float[] groundTruthDisparity;
    private final InvalidReason[] groundTruthReasons;

    private SyntheticScene(int width, int height, CalibrationParams calibration,
                           RectificationMaps maps, GrayscaleImage leftRaw, GrayscaleImage rightRaw,
                           GrayscaleImage leftRectified, GrayscaleImage rightRectified,
                           float[] groundTruthDisparity, InvalidReason[] groundTruthReasons) {
        this.width = width;
        this.height = height;
        this.calibration = calibration;
        this.maps = maps;
        this.leftRaw = leftRaw;
        this.rightRaw = rightRaw;
        this.leftRectified = leftRectified;
        this.rightRectified = rightRectified;
        this.groundTruthDisparity = groundTruthDisparity;
        this.groundTruthReasons = groundTruthReasons;
    }

    public static SyntheticScene generate(int width, int height, CalibrationParams calibration,
                                          long seed) {
        RectificationMaps maps = new RectificationEngine().buildMaps(calibration);
        Random random = new Random(seed);

        float[] disparity = new float[width * height];
        InvalidReason[] reasons = new InvalidReason[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boolean texturelessRect =
                        (x > width * 0.55 && x < width * 0.78 && y > height * 0.55 && y < height * 0.82)
                                || (x > width * 0.08 && x < width * 0.24 && y > height * 0.12 && y < height * 0.30);
                if (texturelessRect) {
                    disparity[i] = Float.NaN;
                    reasons[i] = InvalidReason.TEXTURELESS;
                } else {
                    float base = 14f + 0.008f * x + 0.005f * y;
                    float step = (x > width * 0.35 && x < width * 0.52
                            && y > height * 0.30 && y < height * 0.75) ? 10f : 0f;
                    float ripple = 1.5f * (float) Math.sin(x * 0.05) * (float) Math.cos(y * 0.07);
                    disparity[i] = (float) Math.floor(base + step + ripple);
                    reasons[i] = InvalidReason.VALID;
                }
            }
        }

        float[] rectLeft = proceduralTexture(width, height, reasons);
        float[] rectRight = renderRight(width, height, rectLeft, disparity, reasons);
        float[] smoothLeft = boxBlur(width, height, rectLeft);
        float[] smoothRight = boxBlur(width, height, rectRight);
        float[] leftRaw = forwardDistort(width, height, smoothLeft, calibration, true);
        float[] rightRaw = forwardDistort(width, height, smoothRight, calibration, false);

        return new SyntheticScene(width, height, calibration, maps,
                new GrayscaleImage(width, height, leftRaw),
                new GrayscaleImage(width, height, rightRaw),
                new GrayscaleImage(width, height, smoothLeft),
                new GrayscaleImage(width, height, smoothRight),
                disparity, reasons);
    }

    private static float[] proceduralTexture(int width, int height, InvalidReason[] reasons) {
        float[] image = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (reasons[i] == InvalidReason.TEXTURELESS) {
                    image[i] = 128f;
                } else {
                    float pattern = 40f * (float) Math.sin(x * 0.22 + 0.7 * Math.cos(y * 0.15))
                            + 30f * (float) Math.sin((x + 1.7 * y) * 0.13 + 1.3)
                            + 20f * (float) Math.sin(y * 0.31 + 2.1 * Math.sin(x * 0.05));
                    image[i] = clamp(128f + pattern);
                }
            }
        }
        return image;
    }

    private static float[] renderRight(int width, int height, float[] rectLeft,
                                       float[] disparity, InvalidReason[] reasons) {
        float[] nearest = new float[width * height];
        java.util.Arrays.fill(nearest, Float.NaN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (reasons[i] == InvalidReason.TEXTURELESS) {
                    continue;
                }
                int xr = Math.round(x - disparity[i]);
                if (xr >= 0 && xr < width) {
                    int ri = y * width + xr;
                    if (Float.isNaN(nearest[ri]) || disparity[i] > nearest[ri]) {
                        nearest[ri] = disparity[i];
                    }
                }
            }
        }
        Random backgroundRandom = new Random(99173L);
        float[] right = new float[width * height];
        for (int i = 0; i < right.length; i++) {
            right[i] = clamp(30f + backgroundRandom.nextFloat() * 80f);
        }
        for (int y = 0; y < height; y++) {
            for (int xr = 0; xr < width; xr++) {
                int ri = y * width + xr;
                if (Float.isNaN(nearest[ri])) {
                    continue;
                } else {
                    int srcX = Math.round(xr + nearest[ri]);
                    srcX = Math.min(width - 1, Math.max(0, srcX));
                    right[ri] = rectLeft[y * width + srcX];
                }
            }
        }
        return right;
    }

    /**
     * Warps a rectified-domain image into a raw camera image using the independent
     * reference mapping ({@link IndependentRectificationReference}), never the
     * production rectification code, so that accuracy verdicts do not rely on the
     * same mapping computation that is under test.
     */
    private static float[] forwardDistort(int width, int height, float[] rectified,
                                          CalibrationParams calibration, boolean leftSide) {
        RectificationMaps engineMaps = new RectificationEngine().buildMaps(calibration);
        IndependentRectificationReference reference =
                new IndependentRectificationReference(calibration, engineMaps.rectifiedK());
        float[] raw = new float[width * height];
        float[] weight = new float[width * height];
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int p = py * width + px;
                double[] source = leftSide ? reference.mapLeft(px, py) : reference.mapRight(px, py);
                double rx = source[0];
                double ry = source[1];
                if (Double.isNaN(rx) || Double.isNaN(ry)) {
                    continue;
                }
                int x0 = (int) Math.floor(rx);
                int y0 = (int) Math.floor(ry);
                if (x0 < 0 || y0 < 0 || x0 >= width - 1 || y0 >= height - 1) {
                    continue;
                }
                float fx = (float) (rx - x0);
                float fy = (float) (ry - y0);
                float value = rectified[p];
                splat(raw, weight, width, x0, y0, (1 - fx) * (1 - fy), value);
                splat(raw, weight, width, x0 + 1, y0, fx * (1 - fy), value);
                splat(raw, weight, width, x0, y0 + 1, (1 - fx) * fy, value);
                splat(raw, weight, width, x0 + 1, y0 + 1, fx * fy, value);
            }
        }
        for (int i = 0; i < raw.length; i++) {
            raw[i] = weight[i] > 1e-6f ? raw[i] / weight[i] : 0f;
        }
        return raw;
    }

    private static void splat(float[] raw, float[] weight, int width, int x, int y,
                              float w, float value) {
        if (w <= 0f) {
            return;
        }
        int i = y * width + x;
        raw[i] += value * w;
        weight[i] += w;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(255f, v));
    }

    private static float[] boxBlur(int width, int height, float[] input) {
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(width - 1, Math.max(0, x + dx));
                        int yy = Math.min(height - 1, Math.max(0, y + dy));
                        sum += input[yy * width + xx];
                        count++;
                    }
                }
                output[y * width + x] = (float) (sum / count);
            }
        }
        return output;
    }

    public int width() { return width; }

    public int height() { return height; }

    public CalibrationParams calibration() { return calibration; }

    public RectificationMaps rectificationMaps() { return maps; }

    public GrayscaleImage leftRaw() { return leftRaw; }

    public GrayscaleImage rightRaw() { return rightRaw; }

    public GrayscaleImage leftRectified() { return leftRectified; }

    public GrayscaleImage rightRectified() { return rightRectified; }

    public float[] groundTruthDisparity() { return groundTruthDisparity.clone(); }

    public InvalidReason[] groundTruthReasons() { return groundTruthReasons.clone(); }
}
