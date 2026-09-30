package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.stereo.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.stereo.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.stereo.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.stereo.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoConfig;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoPipeline;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.stereo.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy and reproducibility bounds on synthetic data, verified
 * against the documented error bounds in README.
 */
class StereoAccuracyTest {
    private static final int W = 128;
    private static final int H = 96;
    private static final long SEED = 42L;

    private static StereoResult runPipeline(StereoConfig config) {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(CalibrationParams.ideal("v1", 100.0, W / 2.0, H / 2.0, 0.1));
        StereoPipeline pipeline = new StereoPipeline(registry, config);
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, SEED);
        return pipeline.process(scene.pair(), true);
    }

    private static StereoConfig baseConfig() {
        return StereoConfig.builder()
                .numDisparities(32)
                .blockRadius(3)
                .randomSeed(SEED)
                .build();
    }

    @Test
    void disparityAndDepthMeetDocumentedErrorBounds() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, SEED);
        StereoResult result = runPipeline(baseConfig());
        DisparityMap disp = result.disparity();
        DisparityMap gt = scene.groundTruth();

        double sumAbs = 0;
        double maxAbs = 0;
        double maxAbsInterior = 0;
        double sumRelDepth = 0;
        int count = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!gt.isValid(x, y) || !disp.isValid(x, y)) {
                    continue;
                }
                if (!textured(scene.pair().left(), x, y)) {
                    continue;
                }
                double err = Math.abs(disp.disparity(x, y) - gt.disparity(x, y));
                sumAbs += err;
                maxAbs = Math.max(maxAbs, err);
                if (!nearDiscontinuity(gt, x, y)) {
                    maxAbsInterior = Math.max(maxAbsInterior, err);
                }
                double trueDepth = 10.0 / gt.disparity(x, y);
                sumRelDepth += Math.abs(result.depth().depth(x, y) - trueDepth) / trueDepth;
                count++;
            }
        }
        double coverage = (double) count / (W * H);
        assertTrue(coverage > 0.55, "valid coverage too low: " + coverage);
        double meanAbs = sumAbs / count;
        assertTrue(meanAbs <= 0.35, "mean disparity error " + meanAbs + " exceeds 0.35 px");
        assertTrue(maxAbsInterior <= 1.5,
                "max interior disparity error " + maxAbsInterior + " exceeds 1.5 px (overall max "
                        + maxAbs + " at depth discontinuities)");
        double meanRelDepth = sumRelDepth / count;
        assertTrue(meanRelDepth <= 0.06, "mean relative depth error " + meanRelDepth + " exceeds 6%");
    }

    /**
     * True when the ground-truth neighbourhood within blockRadius+1
     * spans a depth discontinuity (disparity range above 2 px). Block
     * matching can bleed a foreground disparity that far across an
     * edge, so those pixels are excluded from the max bound (README).
     */
    private static boolean nearDiscontinuity(DisparityMap gt, int x, int y) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int radius = 4;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= gt.width() || yy >= gt.height() || !gt.isValid(xx, yy)) {
                    return true;
                }
                float d = gt.disparity(xx, yy);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }
        return max - min > 2.0f;
    }

    /**
     * Accuracy bounds are stated for textured pixels: local variance of
     * the left image over a radius-4 window must clear four times the
     * configured texture threshold (see README).
     */
    private static boolean textured(com.github.guoyyit.stereodepthestimationjava.stereo.GrayImage img,
                                    int x, int y) {
        int radius = 4;
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= img.width() || yy >= img.height()) {
                    continue;
                }
                double v = img.get(xx, yy);
                sum += v;
                sumSq += v * v;
                count++;
            }
        }
        double mean = sum / count;
        return sumSq / count - mean * mean >= 4.0 * StereoConfig.DEFAULT_TEXTURE_THRESHOLD;
    }

    @Test
    void fixedSeedAndInputReproduceIdenticalOutput() {
        StereoConfig config = baseConfig();
        StereoResult first = runPipeline(config);
        StereoResult second = runPipeline(config);
        assertEquals(first.calibrationVersion(), second.calibrationVersion());
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals(first.disparity().reason(x, y), second.disparity().reason(x, y),
                        "reason mismatch at " + x + "," + y);
                float a = first.disparity().disparity(x, y);
                float b = second.disparity().disparity(x, y);
                if (Float.isNaN(a) && Float.isNaN(b)) {
                    continue;
                }
                assertEquals(Float.floatToIntBits(a), Float.floatToIntBits(b),
                        "disparity mismatch at " + x + "," + y);
                assertEquals(first.depth().reason(x, y), second.depth().reason(x, y));
            }
        }
        assertEquals(first.pointCloud().size(), second.pointCloud().size());
    }

    @Test
    void subpixelRefinementImprovesAccuracy() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, SEED);
        StereoConfig withSub = baseConfig();
        StereoConfig withoutSub = StereoConfig.builder()
                .numDisparities(32)
                .blockRadius(3)
                .subpixelEnabled(false)
                .randomSeed(SEED)
                .build();
        double errWith = meanAbsError(runPipeline(withSub).disparity(), scene.groundTruth());
        double errWithout = meanAbsError(runPipeline(withoutSub).disparity(), scene.groundTruth());
        assertTrue(errWith < errWithout,
                "subpixel (" + errWith + ") should beat integer (" + errWithout + ")");
    }

    private static double meanAbsError(DisparityMap disp, DisparityMap gt) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < gt.height(); y++) {
            for (int x = 0; x < gt.width(); x++) {
                if (gt.isValid(x, y) && disp.isValid(x, y)) {
                    sum += Math.abs(disp.disparity(x, y) - gt.disparity(x, y));
                    count++;
                }
            }
        }
        return sum / count;
    }

    @Test
    void resultIsLabelledWithCalibrationVersion() {
        StereoResult result = runPipeline(baseConfig());
        assertEquals("v1", result.calibrationVersion());
        assertEquals("v1", result.depth().calibrationVersion());
        assertEquals("v1", result.pointCloud().calibrationVersion());
        assertTrue(result.pointCloudOptional().isPresent());
    }

    @Test
    void invalidPixelsAreNanNotZero() {
        StereoResult result = runPipeline(baseConfig());
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (result.disparity().reason(x, y) != InvalidReason.VALID) {
                    assertTrue(Float.isNaN(result.disparity().disparity(x, y)));
                    assertTrue(Float.isNaN(result.depth().depth(x, y)));
                }
            }
        }
    }
}
