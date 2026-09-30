package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.stereo.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.stereo.GrayImage;
import com.github.guoyyit.stereodepthestimationjava.stereo.Rectifier;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoImagePair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distortion removal and epipolar rectification behaviour.
 */
class RectifierTest {
    private static final int W = 64;
    private static final int H = 48;

    private static GrayImage smoothImage() {
        float[] data = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                data[y * W + x] = (float) (0.5 + 0.3 * Math.sin(x * 0.31) * Math.cos(y * 0.27));
            }
        }
        return new GrayImage(W, H, data);
    }

    @Test
    void idealCalibrationIsIdentity() {
        GrayImage image = smoothImage();
        CalibrationParams params = CalibrationParams.ideal("v1", 100.0, W / 2.0, H / 2.0, 0.1);
        StereoImagePair out = new Rectifier(params)
                .rectify(new StereoImagePair(image, image));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals(image.get(x, y), out.left().get(x, y), 1e-6f);
                assertEquals(image.get(x, y), out.right().get(x, y), 1e-6f);
            }
        }
    }

    @Test
    void distortionRoundTripRestoresImage() {
        GrayImage ideal = smoothImage();
        double fx = 100.0;
        double cx = W / 2.0;
        double cy = H / 2.0;
        double k1 = 0.15;
        double k2 = 0.02;
        double[] k = {fx, 0, cx, 0, fx, cy, 0, 0, 1};
        double[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        CalibrationParams params = new CalibrationParams("v1", k, k, new double[]{k1, k2},
                new double[]{k1, k2}, identity, identity, 0.1, fx);

        // Synthesise the distorted capture: every distorted pixel samples
        // the ideal image at its undistorted coordinate.
        float[] distorted = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double xn = (x - cx) / fx;
                double yn = (y - cy) / fx;
                double xu = xn;
                double yu = yn;
                for (int it = 0; it < 8; it++) {
                    double r2 = xu * xu + yu * yu;
                    double radial = 1.0 + k1 * r2 + k2 * r2 * r2;
                    xu = xn / radial;
                    yu = yn / radial;
                }
                distorted[y * W + x] = bilinear(ideal, fx * xu + cx, fx * yu + cy);
            }
        }

        GrayImage rectified = new Rectifier(params)
                .rectify(new StereoImagePair(new GrayImage(W, H, distorted), new GrayImage(W, H, distorted)))
                .left();

        // Interior comparison: borders see out-of-image samples (zero-filled).
        double sumAbs = 0;
        double maxAbs = 0;
        int count = 0;
        for (int y = 4; y < H - 4; y++) {
            for (int x = 4; x < W - 4; x++) {
                double err = Math.abs(rectified.get(x, y) - ideal.get(x, y));
                sumAbs += err;
                maxAbs = Math.max(maxAbs, err);
                count++;
            }
        }
        double mean = sumAbs / count;
        assertTrue(mean <= 0.01, "mean round-trip error " + mean + " exceeds 0.01");
        assertTrue(maxAbs <= 0.05, "max round-trip error " + maxAbs + " exceeds 0.05");
    }

    @Test
    void halfTurnRectificationFlipsImage() {
        GrayImage image = smoothImage();
        double fx = 100.0;
        double[] k = {fx, 0, W / 2.0, 0, fx, H / 2.0, 0, 0, 1};
        double[] halfTurn = {-1, 0, 0, 0, -1, 0, 0, 0, 1};
        double[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        CalibrationParams params = new CalibrationParams("v1", k, k, new double[]{0, 0},
                new double[]{0, 0}, halfTurn, identity, 0.1, fx);
        GrayImage rectified = new Rectifier(params)
                .rectify(new StereoImagePair(image, image)).left();
        // A 180-degree rotation about the optical axis maps (x, y) to
        // (2cx - x, 2cy - y); cx/cy are half-integers so sample exactly.
        double cx = W / 2.0;
        double cy = H / 2.0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double u = 2 * cx - x;
                double v = 2 * cy - y;
                float expected = bilinear(image, u, v);
                assertEquals(expected, rectified.get(x, y), 1e-5f);
            }
        }
    }

    private static float bilinear(GrayImage img, double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        double ax = u - x0;
        double ay = v - y0;
        double top = lerp(sample(img, x0, y0), sample(img, x0 + 1, y0), ax);
        double bottom = lerp(sample(img, x0, y0 + 1), sample(img, x0 + 1, y0 + 1), ax);
        return (float) lerp(top, bottom, ay);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float sample(GrayImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.width() || y >= img.height()) {
            return 0.0f;
        }
        return img.get(x, y);
    }
}
