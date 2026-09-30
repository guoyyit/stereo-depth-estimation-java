package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.error.InvalidFrameException;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.pipeline.FrameExecutionInfo;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoProcessingService;
import com.github.guoyyit.stereodepthestimationjava.synthetic.SyntheticScene;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureCancellationConcurrencyTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void mismatchedDimensionsAreRejected() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            GrayscaleImage left = GrayscaleImage.create(WIDTH, HEIGHT);
            GrayscaleImage right = GrayscaleImage.create(WIDTH + 10, HEIGHT);
            FrameRequest request = new FrameRequest("bad", left, right, null);
            assertThrows(InvalidFrameException.class, () -> service.submit(request));
        }
    }

    @Test
    void failingFrameDoesNotCorruptFollowingFrames() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 5L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 2)) {
            FrameRequest wrongSize = new FrameRequest("bad",
                    GrayscaleImage.create(WIDTH, HEIGHT),
                    GrayscaleImage.create(WIDTH / 2, HEIGHT), null);
            assertThrows(InvalidFrameException.class, () -> service.submit(wrongSize));

            FrameRequest good = new FrameRequest("good", scene.leftRaw(), scene.rightRaw(), null);
            StereoResult result = service.submit(good).join();
            assertEquals("good", result.frameId());
            assertEquals("calib-v1", result.calibrationVersion());
            assertEquals(1, service.stats().succeeded());
            assertEquals(0, service.stats().failed());
        }
    }

    @Test
    void cancelledFrameIsReportedAndDoesNotAffectNext() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 6L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        PipelineConfig config = PipelineConfig.defaults();
        try (StereoProcessingService service = new StereoProcessingService(registry, config, 1)) {
            FrameRequest first = new FrameRequest("cancel-me", scene.leftRaw(),
                    scene.rightRaw(), null);
            CompletableFuture<StereoResult> future = service.submit(first);
            boolean cancelled = service.cancel("cancel-me", true);
            assertTrue(cancelled);
            assertThrows(CompletionException.class, future::join);

            FrameRequest second = new FrameRequest("after-cancel", scene.leftRaw(),
                    scene.rightRaw(), null);
            StereoResult result = service.submit(second).join();
            assertEquals("after-cancel", result.frameId());
            assertTrue(service.stats().succeeded() >= 1);
            assertTrue(service.stats().cancelled() >= 1);
            assertTrue(service.history().stream()
                    .anyMatch(info -> info.cancelled() && "cancel-me".equals(info.frameId())));
        }
    }

    @Test
    void historyIsBounded() throws Exception {
        var calibration = TestCalibrations.v1(60, 50);
        SyntheticScene scene = SyntheticScene.generate(60, 50, calibration, 8L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        PipelineConfig config = new PipelineConfig(PipelineConfig.defaults().matchingCostType(),
                PipelineConfig.defaults().windowRadius(), PipelineConfig.defaults().minDisparity(),
                16, PipelineConfig.defaults().borderMode(), 0, false, 1.0f, false, 1.5f,
                1.0f, 3, PipelineConfig.defaults().uniquenessRatio());
        try (StereoProcessingService service = new StereoProcessingService(registry, config, 2)) {
            for (int i = 0; i < 20; i++) {
                FrameRequest request = new FrameRequest("frame-" + i, scene.leftRaw(),
                        scene.rightRaw(), null);
                service.submit(request).join();
            }
            assertEquals(3, service.history().size());
            List<FrameExecutionInfo> history = new ArrayList<>(service.history());
            assertEquals("frame-19", history.get(history.size() - 1).frameId());
            assertEquals(20, service.stats().submitted());
        }
    }

    @Test
    void concurrentFramesProduceIndependentlyVersionedResults() throws Exception {
        var calibration = TestCalibrations.v1(80, 60);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        PipelineConfig config = new PipelineConfig(PipelineConfig.defaults().matchingCostType(),
                2, 0, 16, PipelineConfig.defaults().borderMode(), 0, true, 1.0f, true, 1.5f,
                1.0f, 64, PipelineConfig.defaults().uniquenessRatio());
        try (StereoProcessingService service = new StereoProcessingService(registry, config, 4)) {
            List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                SyntheticScene scene = SyntheticScene.generate(80, 60, calibration, 20L);
                FrameRequest request = new FrameRequest("parallel-" + i, scene.leftRaw(),
                        scene.rightRaw(), null);
                futures.add(service.submit(request));
            }
            for (int i = 0; i < futures.size(); i++) {
                StereoResult result = futures.get(i).join();
                assertEquals("calib-v1", result.calibrationVersion());
                assertEquals("parallel-" + i, result.frameId());
                assertFalse(result.disparityMap().reasons().length == 0);
            }
            assertEquals(12, service.stats().succeeded());
        }
    }

    @Test
    void duplicateFrameIdInFlightIsRejected() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 9L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 1)) {
            FrameRequest a = new FrameRequest("same", scene.leftRaw(), scene.rightRaw(), null);
            FrameRequest b = new FrameRequest("same", scene.leftRaw(), scene.rightRaw(), null);
            service.submit(a);
            assertThrows(InvalidFrameException.class, () -> service.submit(b));
        }
    }
}
