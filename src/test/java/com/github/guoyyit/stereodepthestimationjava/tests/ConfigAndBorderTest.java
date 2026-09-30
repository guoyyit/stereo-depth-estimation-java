package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.calibration.ImageRectifier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndBorderTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void invalidBorderPixelsAreMarkedBorder() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 2L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        PipelineConfig config = new PipelineConfig(MatchingCostType.SAD, 2, 0, 32,
                BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 32, 0.05f);
        StereoResult result = new StereoPipeline(registry, config)
                .process(new FrameRequest("f", scene.leftRaw(), scene.rightRaw(), null),
                        () -> false);
        assertEquals(InvalidReason.BORDER, result.disparityMap().reasons()[2]);
        assertEquals(InvalidReason.BORDER, result.disparityMap().reasons()[
                (HEIGHT - 2) * WIDTH + WIDTH / 2]);
        assertTrue(Float.isNaN(result.depthMap().depth()[2]));
    }

    @Test
    void zeroPadRectifierProducesZerosAtBorder() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 2L);
        ImageRectifier rectifier = new ImageRectifier(BorderMode.ZERO_PAD);
        var maps = scene.rectificationMaps();
        var rectified = rectifier.rectify(scene.leftRaw(), maps.mapXLeft(), maps.mapYLeft(), maps);
        boolean foundZero = false;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < 8; x++) {
                if (rectified.data()[y * WIDTH + x] == 0f) {
                    foundZero = true;
                }
            }
        }
        assertTrue(foundZero, "zero-pad must place literal zeros outside the source footprint");
    }

    @Test
    void invalidConfigRangesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(MatchingCostType.SAD, 0, 0, 32,
                        BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 32, 0.05f));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(MatchingCostType.SAD, 2, 0, 4,
                        BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 32, 0.05f));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(MatchingCostType.SAD, 2, 0, 32,
                        BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 0.1f, 32, 0.05f));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(MatchingCostType.SAD, 2, 0, 32,
                        BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 0, 0.05f));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(MatchingCostType.SAD, 2, 0, 32,
                        BorderMode.BORDER_INVALID, 0, true, 1.0f, true, 1.5f, 1.0f, 32, 1.5f));
    }
}
