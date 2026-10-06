package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoPipeline;
import com.github.guoyyit.stereodepthestimationjava.synthetic.IndependentSceneRenderer;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end disparity accuracy validated on synthetic data rendered by the
 * independent ray-casting model ({@link IndependentSceneRenderer}), which shares no
 * projection, distortion or mapping code with the pipeline under test. Accuracy bounds
 * are identical to {@link AccuracyTest}; nothing about the evaluation is relaxed.
 */
class IndependentAccuracyTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;

    @Test
    void disparityAccuracyOnIndependentlyRenderedSceneMeetsBounds() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        IndependentSceneRenderer.RenderedScene scene = IndependentSceneRenderer.render(calibration);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        StereoPipeline pipeline = new StereoPipeline(registry, PipelineConfig.defaults());
        StereoResult result = pipeline.process(
                new FrameRequest("independent", scene.leftRaw(), scene.rightRaw(), null),
                () -> false);

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
        assertTrue(validCount > 10_000, "too few valid pixels: " + validCount);
        Collections.sort(errors);
        double mean = errors.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double p95 = errors.get((int) (errors.size() * 0.95));
        double p99 = errors.get((int) (errors.size() * 0.99));
        assertTrue(mean <= 1.5, "mean disparity error was " + mean);
        assertTrue(p95 <= 2.0, "p95 disparity error was " + p95);
        assertTrue(p99 <= 4.0, "p99 disparity error was " + p99);
    }

    private static final int EVAL_MARGIN_X = 12;
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
                        if (gtr[j] == InvalidReason.VALID && Math.abs(gt[j] - gt[i]) > 2f) {
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
