package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.pipeline.FrameExecutionInfo;
import com.github.guoyyit.stereodepthestimationjava.pipeline.ProcessingStats;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoProcessingService;
import com.github.guoyyit.stereodepthestimationjava.synthetic.SyntheticScene;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CancellationStabilityTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void cancelUnderBacklogLeavesOtherFramesUnaffected() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 11L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        int frameCount = 8;
        List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 1)) {
            for (int i = 0; i < frameCount; i++) {
                futures.add(service.submit(new FrameRequest("frame-" + i, scene.leftRaw(),
                        scene.rightRaw(), null)));
            }
            // With parallelism 1, frame-0 is in flight and the rest are queued.
            assertTrue(service.cancel("frame-0", false), "in-flight frame must be cancellable");
            assertTrue(service.cancel("frame-4", false), "queued frame must be cancellable");

            int succeeded = 0;
            int cancelled = 0;
            for (int i = 0; i < frameCount; i++) {
                CompletableFuture<StereoResult> future = futures.get(i);
                try {
                    StereoResult result = future.get(30, TimeUnit.SECONDS);
                    assertNotNull(result);
                    succeeded++;
                } catch (ExecutionException e) {
                    assertInstanceOf(FrameCancelledException.class, e.getCause(),
                            "only FrameCancelledException is acceptable here");
                    cancelled++;
                }
            }
            assertEquals(2, cancelled, "exactly the two cancelled frames must be cancelled");
            assertEquals(frameCount - 2, succeeded,
                    "every other frame must complete normally after cancellations");

            ProcessingStats stats = service.stats();
            assertEquals(frameCount, stats.submitted());
            assertEquals(frameCount - 2, stats.succeeded());
            assertEquals(2, stats.cancelled());
            assertEquals(0, stats.failed());
            for (String id : List.of("frame-0", "frame-4")) {
                assertTrue(service.history().stream().anyMatch(
                        info -> info.frameId().equals(id) && info.cancelled()),
                        "cancelled frame " + id + " must be marked cancelled in history");
            }
        }
    }

    @Test
    void queuedFramesAreCompletedAsCancelledOnClose() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 12L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
        StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 1);
        for (int i = 0; i < 4; i++) {
            futures.add(service.submit(new FrameRequest("queued-" + i, scene.leftRaw(),
                    scene.rightRaw(), null)));
        }
        service.close();
        // No future may remain pending forever after close.
        for (CompletableFuture<StereoResult> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                assertInstanceOf(FrameCancelledException.class, e.getCause());
            }
        }
        ProcessingStats stats = service.stats();
        assertEquals(stats.submitted(), stats.succeeded() + stats.failed() + stats.cancelled(),
                "every submitted frame must reach a terminal, distinguishable state");
        assertTrue(stats.cancelled() >= 1, "queued frames must be reported as cancelled");
    }

    @Test
    void concurrentSubmissionAndCalibrationUpdateStayTraceable() throws Exception {
        var v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        var v2 = TestCalibrations.v2(WIDTH, HEIGHT);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(v1);
        var sceneV1 = SyntheticScene.generate(WIDTH, HEIGHT, v1, 13L);
        var sceneV2 = SyntheticScene.generate(WIDTH, HEIGHT, v2, 13L);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 4)) {
            List<CompletableFuture<StereoResult>> beforeUpdate = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                beforeUpdate.add(service.submit(new FrameRequest("before-" + i,
                        sceneV1.leftRaw(), sceneV1.rightRaw(), null)));
            }
            registry.register(v2);
            List<CompletableFuture<StereoResult>> afterUpdate = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                afterUpdate.add(service.submit(new FrameRequest("after-" + i,
                        sceneV2.leftRaw(), sceneV2.rightRaw(), null)));
            }
            for (int i = 0; i < beforeUpdate.size(); i++) {
                StereoResult result = beforeUpdate.get(i).get(30, TimeUnit.SECONDS);
                assertEquals("calib-v1", result.calibrationVersion(),
                        "frame submitted before the update must keep v1");
                assertEquals("calib-v1", result.depthMap().calibrationVersion());
                assertEquals("calib-v1", result.pointCloud().calibrationVersion());
            }
            for (int i = 0; i < afterUpdate.size(); i++) {
                StereoResult result = afterUpdate.get(i).get(30, TimeUnit.SECONDS);
                assertEquals("calib-v2", result.calibrationVersion(),
                        "frame submitted after the update must use v2");
                assertEquals("calib-v2", result.depthMap().calibrationVersion());
                assertEquals("calib-v2", result.pointCloud().calibrationVersion());
            }
            ProcessingStats stats = service.stats();
            assertEquals(12, stats.succeeded());
            assertEquals(0, stats.failed());
        }
    }

    @Test
    void cancellingCompletedFrameReportsFalse() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = SyntheticScene.generate(WIDTH, HEIGHT, calibration, 14L);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, PipelineConfig.defaults(), 1)) {
            CompletableFuture<StereoResult> future = service.submit(
                    new FrameRequest("done", scene.leftRaw(), scene.rightRaw(), null));
            future.get(30, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertFalse(service.cancel("done", true));
            assertEquals(1, service.stats().succeeded());
            assertEquals(0, service.stats().cancelled());
        }
    }
}
