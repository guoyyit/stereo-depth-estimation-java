package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.stereo.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.stereo.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.stereo.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoConfig;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoPipeline;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoProcessingException;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.stereo.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-resolution processing: the downsampled result must stay within
 * the documented deviation bounds of the full-resolution result
 * (README, "Multi-resolution").
 */
class MultiResolutionTest {
    private static final int W = 128;
    private static final int H = 96;

    private static StereoResult run(StereoConfig config, SyntheticDataGenerator.Scene scene) {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(CalibrationParams.ideal("v1", 100.0, W / 2.0, H / 2.0, 0.1));
        return new StereoPipeline(registry, config).process(scene.pair(), false);
    }

    @Test
    void downsampledResultStaysWithinDeviationBounds() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, 42L);
        DisparityMap full = run(StereoConfig.builder()
                .numDisparities(32).blockRadius(3).build(), scene).disparity();
        DisparityMap down = run(StereoConfig.builder()
                .numDisparities(32).blockRadius(3).downsampleFactor(2).build(), scene).disparity();

        assertEquals(W, down.width());
        assertEquals(H, down.height());

        List<Double> deviations = new ArrayList<>();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!full.isValid(x, y) || !down.isValid(x, y)) {
                    continue;
                }
                if (nearDiscontinuity(scene.groundTruth(), x, y)) {
                    continue;
                }
                deviations.add((double) Math.abs(full.disparity(x, y) - down.disparity(x, y)));
            }
        }
        Collections.sort(deviations);
        double mean = deviations.stream().mapToDouble(d -> d).average().orElse(0);
        double p95 = deviations.get((int) (deviations.size() * 0.95));
        assertTrue(mean <= 1.0, "mean full/down deviation " + mean + " exceeds 1.0 px");
        assertTrue(p95 <= 2.0, "p95 full/down deviation " + p95 + " exceeds 2.0 px");
    }

    @Test
    void downsampledResultMeetsGroundTruthBound() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, 42L);
        DisparityMap down = run(StereoConfig.builder()
                .numDisparities(32).blockRadius(3).downsampleFactor(2).build(), scene).disparity();
        DisparityMap gt = scene.groundTruth();
        double sum = 0;
        int count = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (gt.isValid(x, y) && down.isValid(x, y)) {
                    sum += Math.abs(down.disparity(x, y) - gt.disparity(x, y));
                    count++;
                }
            }
        }
        double mean = sum / count;
        assertTrue(mean <= 1.0, "downsampled mean ground-truth error " + mean + " exceeds 1.0 px");
    }

    @Test
    void nonDivisibleDimensionsAreRejected() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, 42L);
        StereoConfig config = StereoConfig.builder()
                .numDisparities(32).blockRadius(3).downsampleFactor(3).build();
        assertThrows(StereoProcessingException.class, () -> run(config, scene));
    }

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
}
