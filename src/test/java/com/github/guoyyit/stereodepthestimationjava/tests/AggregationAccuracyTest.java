package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.cost.BorderMode;
import com.github.guoyyit.stereodepthestimationjava.cost.MatchingCostType;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoPipeline;
import com.github.guoyyit.stereodepthestimationjava.synthetic.SyntheticScene;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy acceptance with cost aggregation enabled (aggregationRadius > 0) across
 * matching cost types and down-sampling ratios, plus bit-identical determinism of
 * disparity and invalid markers for every combination under a fixed seed.
 */
class AggregationAccuracyTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;
    private static final long SEED = 4242L;
    private static final int AGGREGATION_RADIUS = 2;
    private static final float[] SCALES = {1.0f, 0.5f, 0.25f};

    @Test
    void aggregatedMatchingMeetsAccuracyBoundsForCostAndScaleCombinations() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, SEED);
        for (MatchingCostType costType : MatchingCostType.values()) {
            for (float scale : SCALES) {
                PipelineConfig config = config(costType, scale);
                StereoResult result = run(scene, calibration, config, "acc");
                List<Double> errors = errorsVsGroundTruth(scene, result);
                assertFalse(errors.isEmpty());
                double mean = errors.stream().mapToDouble(Double::doubleValue)
                        .average().orElseThrow();
                assertTrue(mean <= 2.0, costType + " at scale " + scale
                        + " with aggregation radius " + AGGREGATION_RADIUS
                        + " mean error was " + mean);
            }
        }
    }

    @Test
    void aggregatedMatchingIsBitIdenticalAcrossRepeatedRuns() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, SEED);
        for (MatchingCostType costType : MatchingCostType.values()) {
            for (float scale : SCALES) {
                PipelineConfig config = config(costType, scale);
                StereoResult first = run(scene, calibration, config, "det-a");
                StereoResult second = run(scene, calibration, config, "det-b");
                float[] d1 = first.disparityMap().disparity();
                float[] d2 = second.disparityMap().disparity();
                InvalidReason[] r1 = first.disparityMap().reasons();
                InvalidReason[] r2 = second.disparityMap().reasons();
                assertEquals(d1.length, d2.length);
                for (int i = 0; i < d1.length; i++) {
                    assertEquals(r1[i], r2[i], costType + " scale " + scale
                            + " invalid marker mismatch at " + i);
                    if (Float.isNaN(d1[i])) {
                        assertTrue(Float.isNaN(d2[i]), costType + " scale " + scale
                                + " NaN status mismatch at " + i);
                    } else {
                        assertEquals(d1[i], d2[i], 0f, costType + " scale " + scale
                                + " disparity must be bit-identical at " + i);
                    }
                }
            }
        }
    }

    private static PipelineConfig config(MatchingCostType costType, float scale) {
        return new PipelineConfig(costType, 2, 0, 64, BorderMode.BORDER_INVALID,
                AGGREGATION_RADIUS, true, 1.0f, true, 1.5f, scale, 32, 0.05f);
    }

    private static StereoResult run(SyntheticScene scene,
                                    com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams calibration,
                                    PipelineConfig config, String frameId) {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        return new StereoPipeline(registry, config).process(
                new FrameRequest(frameId, scene.leftRaw(), scene.rightRaw(), null),
                () -> false);
    }

    private static List<Double> errorsVsGroundTruth(SyntheticScene scene, StereoResult result) {
        float[] gt = scene.groundTruthDisparity();
        InvalidReason[] gtr = scene.groundTruthReasons();
        List<Double> errors = new ArrayList<>();
        for (int y = 8; y < HEIGHT - 8; y++) {
            for (int x = 70; x < WIDTH - 70; x++) {
                int i = y * WIDTH + x;
                if (result.disparityMap().reasons()[i] == InvalidReason.VALID
                        && gtr[i] == InvalidReason.VALID) {
                    errors.add((double) Math.abs(result.disparityMap().disparity()[i] - gt[i]));
                }
            }
        }
        return errors;
    }
}
