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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccuracyTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;
    private static final long SEED = 42L;

    @Test
    void disparityAccuracyMeetsBounds() {
        SceneRun run = runScene(PipelineConfig.defaults());
        List<Double> errors = run.errors();
        Collections.sort(errors);
        double mean = errors.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double p95 = errors.get((int) (errors.size() * 0.95));
        double p99 = errors.get((int) (errors.size() * 0.99));
        assertTrue(mean <= 1.5, "mean disparity error was " + mean);
        assertTrue(p95 <= 2.0, "p95 disparity error was " + p95);
        assertTrue(p99 <= 4.0, "p99 disparity error was " + p99);
    }

    @Test
    void everyCostTypeProducesResultsWithinBounds() {
        for (MatchingCostType costType : MatchingCostType.values()) {
            PipelineConfig config = new PipelineConfig(costType, 2, 0, 64,
                    BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 32, 0.05f);
            SceneRun run = runScene(config);
            double mean = run.errors().stream().mapToDouble(Double::doubleValue)
                    .average().orElseThrow();
            assertTrue(mean <= 2.0, costType + " mean error was " + mean);
            assertTrue(run.validCount() > 10_000,
                    costType + " produced too few valid pixels");
        }
    }

    @Test
    void invalidPixelsCarryFiniteMarkersButNoZeroDepth() {
        SceneRun run = runScene(PipelineConfig.defaults());
        int textureless = 0;
        int occluded = 0;
        int border = 0;
        for (int i = 0; i < run.result.depthMap().depth().length; i++) {
            InvalidReason reason = run.result.depthMap().reasons()[i];
            float depth = run.result.depthMap().depth()[i];
            if (reason == InvalidReason.VALID) {
                assertTrue(Float.isFinite(depth) && depth > 0f,
                        "valid depth must be positive and finite at " + i);
            } else {
                assertTrue(Float.isNaN(depth),
                        "invalid depth must be NaN, never zero or neighbour-filled at " + i);
                if (reason == InvalidReason.TEXTURELESS) {
                    textureless++;
                } else if (reason == InvalidReason.OCCLUDED) {
                    occluded++;
                } else if (reason == InvalidReason.BORDER) {
                    border++;
                }
            }
        }
        assertTrue(textureless > 200, "textureless region should be marked");
        assertTrue(occluded > 200, "occluded region should be marked");
        assertTrue(border > 0, "border band should be marked");
    }

    @Test
    void texturelessAndOccludedAreDistinguishable() {
        SceneRun run = runScene(PipelineConfig.defaults());
        int textureless = 0;
        int occluded = 0;
        for (InvalidReason reason : run.result.disparityMap().reasons()) {
            if (reason == InvalidReason.TEXTURELESS) {
                textureless++;
            } else if (reason == InvalidReason.OCCLUDED) {
                occluded++;
            }
        }
        assertTrue(textureless > 0);
        assertTrue(occluded > 0);
    }

    @Test
    void resultIsLabelledWithCalibrationVersion() {
        SceneRun run = runScene(PipelineConfig.defaults());
        assertEquals("calib-v1", run.result.calibrationVersion());
        assertEquals("calib-v1", run.result.depthMap().calibrationVersion());
        assertEquals("calib-v1", run.result.pointCloud().calibrationVersion());
        assertNotNull(run.result.pointCloud());
        assertTrue(run.result.pointCloud().pointCount() > 10_000);
    }

    private SceneRun runScene(PipelineConfig config) {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, SEED);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        StereoPipeline pipeline = new StereoPipeline(registry, config);
        FrameRequest request = new FrameRequest("frame-1", scene.leftRaw(), scene.rightRaw(), null);
        StereoResult result = pipeline.process(request, () -> false);

        float[] gt = scene.groundTruthDisparity();
        InvalidReason[] gtr = scene.groundTruthReasons();
        boolean[] nearDiscontinuity = nearDiscontinuity(gt, gtr);
        List<Double> errors = new ArrayList<>();
        int validCount = 0;
        for (int i = 0; i < gt.length; i++) {
            if (result.disparityMap().reasons()[i] == InvalidReason.VALID) {
                validCount++;
            }
            if (result.disparityMap().reasons()[i] == InvalidReason.VALID
                    && gtr[i] == InvalidReason.VALID && !nearDiscontinuity[i]) {
                errors.add((double) Math.abs(result.disparityMap().disparity()[i] - gt[i]));
            }
        }
        assertFalse(errors.isEmpty());
        return new SceneRun(result, errors, validCount);
    }

    private static final int EVAL_MARGIN_X = 70;
    private static final int EVAL_MARGIN_Y = 8;

    private boolean[] nearDiscontinuity(float[] gt, InvalidReason[] gtr) {
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

    private record SceneRun(StereoResult result, List<Double> errors, int validCount) {
    }
}
