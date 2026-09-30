package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.CalibrationVersionException;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoProcessingService;
import com.github.guoyyit.stereodepthestimationjava.synthetic.SyntheticScene;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationVersionTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void unknownVersionIsRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(TestCalibrations.v1(WIDTH, HEIGHT));
        var scene = SyntheticScene.generate(WIDTH, HEIGHT,
                TestCalibrations.v1(WIDTH, HEIGHT), 1L);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            FrameRequest request = new FrameRequest("f", scene.leftRaw(), scene.rightRaw(),
                    "calib-does-not-exist");
            assertThrows(CalibrationVersionException.class, () -> service.submit(request));
        }
    }

    @Test
    void blankVersionIsRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(TestCalibrations.v1(WIDTH, HEIGHT));
        var scene = SyntheticScene.generate(WIDTH, HEIGHT,
                TestCalibrations.v1(WIDTH, HEIGHT), 1L);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            FrameRequest request = new FrameRequest("f", scene.leftRaw(), scene.rightRaw(), "  ");
            assertThrows(CalibrationVersionException.class, () -> service.submit(request));
        }
    }

    @Test
    void noRegisteredVersionIsRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        var scene = SyntheticScene.generate(WIDTH, HEIGHT,
                TestCalibrations.v1(WIDTH, HEIGHT), 1L);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            FrameRequest request = new FrameRequest("f", scene.leftRaw(), scene.rightRaw(), null);
            assertThrows(CalibrationVersionException.class, () -> service.submit(request));
        }
    }

    @Test
    void inFlightFrameKeepsVersionFromStartTime() throws Exception {
        var v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        var v2 = TestCalibrations.v2(WIDTH, HEIGHT);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(v1);

        AtomicReference<String> versionSeenInFlight = new AtomicReference<>();
        Object gate = new Object();
        PipelineConfig config = PipelineConfig.defaults();
        try (StereoProcessingService service = new StereoProcessingService(registry, config, 1)) {
            var sceneV1 = SyntheticScene.generate(WIDTH, HEIGHT, v1, 7L);
            FrameRequest slow = new FrameRequest("slow", sceneV1.leftRaw(), sceneV1.rightRaw(),
                    v1.version());

            CompletableFuture<StereoResult> future = service.submit(slow);
            synchronized (gate) {
                registry.register(v2);
                assertEquals("calib-v2", registry.activeVersion());
            }
            StereoResult result = future.join();
            versionSeenInFlight.set(result.calibrationVersion());

            var sceneV2 = SyntheticScene.generate(WIDTH, HEIGHT, v2, 7L);
            FrameRequest next = new FrameRequest("next", sceneV2.leftRaw(), sceneV2.rightRaw(),
                    null);
            StereoResult nextResult = service.submit(next).join();
            assertEquals("calib-v1", versionSeenInFlight.get(),
                    "in-flight frame must keep the version captured at submit time");
            assertEquals("calib-v2", nextResult.calibrationVersion(),
                    "later frame must use the new active version");
        }
    }

    @Test
    void explicitOldVersionStillProcessesAfterUpdate() throws Exception {
        var v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        var v2 = TestCalibrations.v2(WIDTH, HEIGHT);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(v1);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            registry.register(v2);
            var sceneV1 = SyntheticScene.generate(WIDTH, HEIGHT, v1, 3L);
            FrameRequest request = new FrameRequest("explicit-old", sceneV1.leftRaw(),
                    sceneV1.rightRaw(), "calib-v1");
            StereoResult result = service.submit(request).join();
            assertEquals("calib-v1", result.calibrationVersion());
            assertTrue(result.depthMap() != null);
        }
    }
}
