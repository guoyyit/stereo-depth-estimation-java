package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.stereo.AggregationStrategy;
import com.github.guoyyit.stereodepthestimationjava.stereo.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.stereo.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.stereo.MatchingCost;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoConfig;
import com.github.guoyyit.stereodepthestimationjava.stereo.StereoMatcher;
import com.github.guoyyit.stereodepthestimationjava.stereo.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration validation and deterministic border behaviour of the
 * supported cost/aggregation combinations.
 */
class ConfigAndBorderTest {
    @Test
    void invalidConfigurationsAreRejected() {
        StereoConfig.Builder b = StereoConfig.builder();
        assertThrows(IllegalArgumentException.class, () -> b.minDisparity(-1).build());
        assertThrows(IllegalArgumentException.class, () -> b.minDisparity(0).numDisparities(0).build());
        assertThrows(IllegalArgumentException.class, () -> b.numDisparities(257).build());
        assertThrows(IllegalArgumentException.class, () -> b.numDisparities(32).blockRadius(-1).build());
        assertThrows(IllegalArgumentException.class, () -> b.blockRadius(33).build());
        assertThrows(IllegalArgumentException.class, () -> b.blockRadius(3).textureThreshold(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> b.textureThreshold(0.002).lrTolerance(-0.5).build());
        assertThrows(IllegalArgumentException.class, () -> b.lrTolerance(1.0).downsampleFactor(0).build());
        assertThrows(IllegalArgumentException.class, () -> b.downsampleFactor(9).build());
        assertThrows(IllegalArgumentException.class,
                () -> b.downsampleFactor(1).aggregation(AggregationStrategy.NONE).blockRadius(3).build());
        assertThrows(IllegalArgumentException.class,
                () -> b.aggregation(AggregationStrategy.BOX).blockRadius(0).build());
        assertThrows(IllegalArgumentException.class, () -> b.blockRadius(3).cost(null).build());
    }

    @Test
    void defaultsAreDocumentedValues() {
        StereoConfig config = StereoConfig.defaults();
        assertEquals(StereoConfig.DEFAULT_MIN_DISPARITY, config.minDisparity());
        assertEquals(StereoConfig.DEFAULT_NUM_DISPARITIES, config.numDisparities());
        assertEquals(StereoConfig.DEFAULT_BLOCK_RADIUS, config.blockRadius());
        assertEquals(StereoConfig.DEFAULT_COST, config.cost());
        assertEquals(StereoConfig.DEFAULT_AGGREGATION, config.aggregation());
        assertEquals(StereoConfig.DEFAULT_TEXTURE_THRESHOLD, config.textureThreshold());
        assertEquals(StereoConfig.DEFAULT_LR_TOLERANCE, config.lrTolerance());
        assertEquals(StereoConfig.DEFAULT_SUBPIXEL_ENABLED, config.subpixelEnabled());
        assertEquals(StereoConfig.DEFAULT_DOWNSAMPLE_FACTOR, config.downsampleFactor());
        assertEquals(StereoConfig.DEFAULT_RANDOM_SEED, config.randomSeed());
    }

    @Test
    void pixelsWithoutEvaluableDisparityAreMarkedBorder() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(64, 48, 5L);
        StereoConfig config = StereoConfig.builder()
                .minDisparity(10)
                .numDisparities(16)
                .blockRadius(2)
                .build();
        DisparityMap disp = new StereoMatcher(config)
                .compute(scene.pair().left(), scene.pair().right());
        // Columns 0..9 cannot evaluate any disparity (x - d < 0 for all
        // d >= minDisparity): deterministic BORDER marker.
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 10; x++) {
                assertEquals(InvalidReason.BORDER, disp.reason(x, y),
                        "column " + x + " must be BORDER");
            }
        }
        // Columns beyond the search range must not be BORDER.
        for (int y = 0; y < 48; y++) {
            for (int x = 26; x < 64; x++) {
                assertTrue(disp.reason(x, y) != InvalidReason.BORDER);
            }
        }
    }

    @Test
    void allCostAggregationCombinationsAreDeterministic() {
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(64, 48, 5L);
        for (MatchingCost cost : MatchingCost.values()) {
            for (AggregationStrategy aggregation : AggregationStrategy.values()) {
                int radius = aggregation == AggregationStrategy.NONE ? 0 : 2;
                StereoConfig config = StereoConfig.builder()
                        .numDisparities(32)
                        .blockRadius(radius)
                        .cost(cost)
                        .aggregation(aggregation)
                        .build();
                DisparityMap first = new StereoMatcher(config)
                        .compute(scene.pair().left(), scene.pair().right());
                DisparityMap second = new StereoMatcher(config)
                        .compute(scene.pair().left(), scene.pair().right());
                for (int y = 0; y < 48; y++) {
                    for (int x = 0; x < 64; x++) {
                        assertEquals(first.reason(x, y), second.reason(x, y),
                                cost + "/" + aggregation + " reason mismatch");
                        float a = first.disparity(x, y);
                        float b = second.disparity(x, y);
                        if (Float.isNaN(a) && Float.isNaN(b)) {
                            continue;
                        }
                        assertEquals(Float.floatToIntBits(a), Float.floatToIntBits(b),
                                cost + "/" + aggregation + " disparity mismatch");
                    }
                }
            }
        }
    }
}
