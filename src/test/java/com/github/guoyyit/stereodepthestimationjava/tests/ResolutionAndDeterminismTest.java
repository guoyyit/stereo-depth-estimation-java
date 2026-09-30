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

class ResolutionAndDeterminismTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;

    @Test
    void halfResolutionAgreesWithFullResolutionWithinBound() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 42L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);

        PipelineConfig fullConfig = PipelineConfig.defaults();
        PipelineConfig halfConfig = withScale(fullConfig, 0.5f);
        StereoResult full = new StereoPipeline(registry, fullConfig)
                .process(request(scene, "full"), () -> false);
        StereoResult half = new StereoPipeline(registry, halfConfig)
                .process(request(scene, "half"), () -> false);

        List<Double> differences = new ArrayList<>();
        for (int i = 0; i < full.disparityMap().disparity().length; i++) {
            if (full.disparityMap().reasons()[i] == InvalidReason.VALID
                    && half.disparityMap().reasons()[i] == InvalidReason.VALID) {
                differences.add((double) Math.abs(
                        full.disparityMap().disparity()[i] - half.disparityMap().disparity()[i]));
            }
        }
        Collections.sort(differences);
        double mean = differences.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double p90 = differences.get((int) (differences.size() * 0.9));
        assertTrue(mean <= 1.5, "half-res mean disparity difference was " + mean);
        assertTrue(p90 <= 3.0, "half-res p90 disparity difference was " + p90);
    }

    @Test
    void repeatedRunsAreBitIdenticalWithFixedSeed() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 99L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        StereoPipeline pipeline = new StereoPipeline(registry, PipelineConfig.defaults());

        StereoResult first = pipeline.process(request(scene, "a"), () -> false);
        StereoResult second = pipeline.process(request(scene, "b"), () -> false);

        float[] d1 = first.disparityMap().disparity();
        float[] d2 = second.disparityMap().disparity();
        InvalidReason[] r1 = first.disparityMap().reasons();
        InvalidReason[] r2 = second.disparityMap().reasons();
        assertEquals(d1.length, d2.length);
        for (int i = 0; i < d1.length; i++) {
            assertEquals(r1[i], r2[i], "invalid marker mismatch at " + i);
            if (Float.isNaN(d1[i])) {
                assertTrue(Float.isNaN(d2[i]), "NaN status mismatch at " + i);
            } else {
                assertEquals(d1[i], d2[i], 0f, "disparity must be bit-identical at " + i);
            }
        }
    }

    @Test
    void sceneGenerationIsReproducible() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene a = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 123L);
        SyntheticScene b = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 123L);
        for (int i = 0; i < a.leftRaw().data().length; i++) {
            assertEquals(a.leftRaw().data()[i], b.leftRaw().data()[i], 0f);
            assertEquals(a.rightRaw().data()[i], b.rightRaw().data()[i], 0f);
        }
    }

    private PipelineConfig withScale(PipelineConfig base, float scale) {
        return new PipelineConfig(MatchingCostType.SAD, base.windowRadius(), base.minDisparity(),
                base.numDisparities(), BorderMode.BORDER_INVALID, base.aggregationRadius(),
                base.leftRightCheck(), base.leftRightThreshold(), base.subpixelRefine(),
                base.textureThreshold(), scale, base.maxFrameHistory(), base.uniquenessRatio());
    }

    private FrameRequest request(SyntheticScene scene, String frameId) {
        return new FrameRequest(frameId, scene.leftRaw(), scene.rightRaw(), "calib-v1");
    }
}
