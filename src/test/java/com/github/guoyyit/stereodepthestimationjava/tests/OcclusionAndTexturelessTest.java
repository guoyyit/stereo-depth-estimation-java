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
 * Occluded and textureless regions must produce distinguishable invalid
 * markers, never zero or neighbour-filled substitute values.
 */
class OcclusionAndTexturelessTest {
    private static final int W = 128;
    private static final int H = 96;

    private static StereoResult run() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(CalibrationParams.ideal("v1", 100.0, W / 2.0, H / 2.0, 0.1));
        StereoPipeline pipeline = new StereoPipeline(registry, StereoConfig.builder()
                .numDisparities(32)
                .blockRadius(3)
                .build());
        return pipeline.process(SyntheticDataGenerator.generate(W, H, 7L).pair(), false);
    }

    @Test
    void texturelessRegionIsMarkedTextureless() {
        StereoResult result = run();
        DisparityMap disp = result.disparity();
        // Interior of the synthetic constant-intensity rectangle
        // (generator: x in [10%,30%], y in [60%,85%] of the image),
        // inset further than the texture window radius (blockRadius 3).
        int marked = 0;
        int total = 0;
        for (int y = H * 66 / 100; y <= H * 79 / 100; y++) {
            for (int x = W * 16 / 100; x <= W * 24 / 100; x++) {
                total++;
                if (disp.reason(x, y) == InvalidReason.TEXTURELESS) {
                    marked++;
                }
            }
        }
        double ratio = (double) marked / total;
        assertTrue(ratio > 0.95, "textureless interior marked " + ratio + ", expected > 0.95");
    }

    @Test
    void occludedRegionIsMarkedOcclusionNotValid() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, 7L);
        StereoResult result = run();
        DisparityMap disp = result.disparity();
        DisparityMap gt = scene.groundTruth();

        int occludedTotal = 0;
        int occludedValid = 0;
        int occludedMarkedOcclusion = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (gt.reason(x, y) != InvalidReason.OCCLUSION) {
                    continue;
                }
                occludedTotal++;
                if (disp.isValid(x, y)) {
                    occludedValid++;
                } else if (disp.reason(x, y) == InvalidReason.OCCLUSION) {
                    occludedMarkedOcclusion++;
                }
            }
        }
        assertTrue(occludedTotal > 100, "scene should contain occluded pixels, got " + occludedTotal);
        double validRatio = (double) occludedValid / occludedTotal;
        assertTrue(validRatio < 0.10, "occluded pixels marked valid: " + validRatio);
        double occlusionRatio = (double) occludedMarkedOcclusion / occludedTotal;
        assertTrue(occlusionRatio > 0.70, "occluded pixels marked OCCLUSION: " + occlusionRatio);
    }

    @Test
    void invalidReasonsAreDistinguishable() {
        StereoResult result = run();
        boolean sawOcclusion = false;
        boolean sawTextureless = false;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                InvalidReason reason = result.disparity().reason(x, y);
                sawOcclusion |= reason == InvalidReason.OCCLUSION;
                sawTextureless |= reason == InvalidReason.TEXTURELESS;
                // The depth map carries the same reason as the disparity map.
                assertEquals(reason, result.depth().reason(x, y));
            }
        }
        assertTrue(sawOcclusion, "expected occluded pixels in output");
        assertTrue(sawTextureless, "expected textureless pixels in output");
    }
}
