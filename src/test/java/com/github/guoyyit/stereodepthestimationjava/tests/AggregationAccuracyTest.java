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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy acceptance across the matching-cost / aggregation-radius /
 * downsampling matrix, including aggregation radius &gt; 0, plus bit-identical
 * determinism of every combination under a fixed seed.
 */
class AggregationAccuracyTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;
    private static final long SEED = 42L;
    private static final int EVAL_MARGIN_X = 70;
    private static final int EVAL_MARGIN_Y = 8;

    private static SyntheticScene scene() {
        return SyntheticScene.generate(WIDTH, HEIGHT, TestCalibrations.v1(WIDTH, HEIGHT), SEED);
    }

    @Test
    void positiveAggregationRadiusMeetsDisparityBoundsForEveryCostType() {
        SyntheticScene scene = scene();
        boolean[] near = nearDiscontinuity(scene);
        for (MatchingCostType costType : MatchingCostType.values()) {
            for (int aggregationRadius : new int[] {1, 2}) {
                StereoResult result = run(scene, costType, 1.0f, aggregationRadius);
                List<Double> errors = groundTruthErrors(scene, near, result);
                Collections.sort(errors);
                double mean = errors.stream().mapToDouble(Double::doubleValue)
                        .average().orElseThrow();
                double p95 = errors.get((int) (errors.size() * 0.95));
                double p99 = errors.get((int) (errors.size() * 0.99));
                String label = costType + " agg=" + aggregationRadius;
                assertTrue(mean <= 1.5, label + " mean error was " + mean);
                assertTrue(p95 <= 2.0, label + " p95 error was " + p95);
                assertTrue(p99 <= 5.0, label + " p99 error was " + p99);
                assertTrue(countValid(result) > 10_000, label + " too few valid pixels");
            }
        }
    }

    @Test
    void costAndScaleCombinationsAgreeWithFullResolutionWithinBound() {
        SyntheticScene scene = scene();
        for (MatchingCostType costType : MatchingCostType.values()) {
            for (int aggregationRadius : new int[] {0, 2}) {
                StereoResult full = run(scene, costType, 1.0f, aggregationRadius);
                for (float scale : new float[] {0.5f, 0.25f}) {
                    StereoResult low = run(scene, costType, scale, aggregationRadius);
                    List<Double> differences = new ArrayList<>();
                    for (int i = 0; i < full.disparityMap().disparity().length; i++) {
                        if (full.disparityMap().reasons()[i] == InvalidReason.VALID
                                && low.disparityMap().reasons()[i] == InvalidReason.VALID) {
                            differences.add((double) Math.abs(
                                    full.disparityMap().disparity()[i]
                                            - low.disparityMap().disparity()[i]));
                        }
                    }
                    Collections.sort(differences);
                    double mean = differences.stream().mapToDouble(Double::doubleValue)
                            .average().orElseThrow();
                    double p90 = differences.get((int) (differences.size() * 0.9));
                    String label = costType + " agg=" + aggregationRadius + " scale=" + scale;
                    assertTrue(mean <= 1.5, label + " mean difference was " + mean);
                    assertTrue(p90 <= 3.0, label + " p90 difference was " + p90);
                }
            }
        }
    }

    @Test
    void everyCombinationIsBitIdenticalAcrossRepeatedRuns() {
        SyntheticScene scene = scene();
        for (MatchingCostType costType : MatchingCostType.values()) {
            for (int aggregationRadius : new int[] {0, 2}) {
                for (float scale : new float[] {1.0f, 0.5f, 0.25f}) {
                    StereoResult first = run(scene, costType, scale, aggregationRadius);
                    StereoResult second = run(scene, costType, scale, aggregationRadius);
                    float[] d1 = first.disparityMap().disparity();
                    float[] d2 = second.disparityMap().disparity();
                    InvalidReason[] r1 = first.disparityMap().reasons();
                    InvalidReason[] r2 = second.disparityMap().reasons();
                    String label = costType + " agg=" + aggregationRadius + " scale=" + scale;
                    assertEquals(d1.length, d2.length, label);
                    for (int i = 0; i < d1.length; i++) {
                        assertEquals(r1[i], r2[i], label + " invalid marker mismatch at " + i);
                        if (Float.isNaN(d1[i])) {
                            assertTrue(Float.isNaN(d2[i]), label + " NaN mismatch at " + i);
                        } else {
                            assertEquals(d1[i], d2[i], 0f,
                                    label + " disparity must be bit-identical at " + i);
                        }
                    }
                }
            }
        }
    }

    private static StereoResult run(SyntheticScene scene, MatchingCostType costType,
                                    float scale, int aggregationRadius) {
        PipelineConfig config = new PipelineConfig(costType, 2, 0, 64,
                BorderMode.BORDER_INVALID, aggregationRadius, true, 1.0f, true, 1.5f,
                scale, 32, 0.05f);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(scene.calibration());
        return new StereoPipeline(registry, config)
                .process(new FrameRequest("frame", scene.leftRaw(), scene.rightRaw(), null),
                        () -> false);
    }

    private static List<Double> groundTruthErrors(SyntheticScene scene, boolean[] near,
                                                  StereoResult result) {
        float[] gt = scene.groundTruthDisparity();
        InvalidReason[] gtr = scene.groundTruthReasons();
        List<Double> errors = new ArrayList<>();
        for (int i = 0; i < gt.length; i++) {
            if (result.disparityMap().reasons()[i] == InvalidReason.VALID
                    && gtr[i] == InvalidReason.VALID && !near[i]) {
                errors.add((double) Math.abs(result.disparityMap().disparity()[i] - gt[i]));
            }
        }
        return errors;
    }

    private static int countValid(StereoResult result) {
        int valid = 0;
        for (InvalidReason reason : result.disparityMap().reasons()) {
            if (reason == InvalidReason.VALID) {
                valid++;
            }
        }
        return valid;
    }

    private static boolean[] nearDiscontinuity(SyntheticScene scene) {
        float[] gt = scene.groundTruthDisparity();
        InvalidReason[] gtr = scene.groundTruthReasons();
        boolean[] near = new boolean[gt.length];
        for (int y = EVAL_MARGIN_Y; y < HEIGHT - EVAL_MARGIN_Y; y++) {
            for (int x = EVAL_MARGIN_X; x < WIDTH - EVAL_MARGIN_X; x++) {
                int i = y * WIDTH + x;
                if (gtr[i] != InvalidReason.VALID) {
                    continue;
                }
                for (int dy = -2; dy <= 2 && !near[i]; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = Math.min(WIDTH - 1, Math.max(0, x + dx));
                        int ny = Math.min(HEIGHT - 1, Math.max(0, y + dy));
                        int j = ny * WIDTH + nx;
                        if (gtr[j] == InvalidReason.VALID && Math.abs(gt[j] - gt[i]) > 5f) {
                            near[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < near.length; i++) {
            int x = i % WIDTH;
            int y = i / WIDTH;
            if (x < EVAL_MARGIN_X || x >= WIDTH - EVAL_MARGIN_X
                    || y < EVAL_MARGIN_Y || y >= HEIGHT - EVAL_MARGIN_Y) {
                near[i] = true;
            }
        }
        return near;
    }
}
