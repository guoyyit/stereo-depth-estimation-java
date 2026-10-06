package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.cost.BorderMode;
import com.github.guoyyit.stereodepthestimationjava.pipeline.FrameExecutionInfo;
import com.github.guoyyit.stereodepthestimationjava.pipeline.ProcessingStats;
import com.github.guoyyit.stereodepthestimationjava.pipeline.StereoProcessingService;
import com.github.guoyyit.stereodepthestimationjava.synthetic.SyntheticScene;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancellation and concurrency stability: no request may hang, no worker may be
 * killed, no later frame may be blocked, cancelled frames stay distinguishable,
 * and every frame remains traceable to exactly one calibration content even when
 * registrations interleave with submissions.
 */
class ConcurrencyCancellationStabilityTest {

    private static final int WIDTH = 80;
    private static final int HEIGHT = 60;

    private static PipelineConfig fastConfig() {
        PipelineConfig d = PipelineConfig.defaults();
        return new PipelineConfig(d.matchingCostType(), 2, 0, 16, BorderMode.BORDER_INVALID,
                0, true, 1.0f, true, 1.5f, 1.0f, 64, d.uniquenessRatio());
    }

    private static SyntheticScene scene(CalibrationParams calibration) {
        return SyntheticScene.generate(WIDTH, HEIGHT, calibration, 11L);
    }

    @Test
    void cancelUnderBacklogLeavesOtherFramesAndWorkersHealthy() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = scene(calibration);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, fastConfig(), 2)) {
            int total = 14;
            Set<String> toCancel = Set.of("f-1", "f-4", "f-7", "f-10", "f-12");
            List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                futures.add(service.submit(new FrameRequest("f-" + i, scene.leftRaw(),
                        scene.rightRaw(), null)));
            }
            for (String id : toCancel) {
                service.cancel(id, true);
            }
            int cancelledCount = 0;
            int succeededCount = 0;
            for (int i = 0; i < total; i++) {
                // Every future must complete; a hang fails the test via timeout.
                try {
                    StereoResult result = futures.get(i).get(60, TimeUnit.SECONDS);
                    succeededCount++;
                    assertEquals("f-" + i, result.frameId());
                    assertEquals("calib-v1", result.calibrationVersion());
                } catch (java.util.concurrent.ExecutionException e) {
                    assertInstanceOf(FrameCancelledException.class, e.getCause());
                    assertTrue(toCancel.contains("f-" + i),
                            "unexpected cancellation of f-" + i);
                    cancelledCount++;
                }
            }
            ProcessingStats stats = service.stats();
            assertEquals(total, stats.submitted());
            assertEquals(0, stats.failed());
            assertEquals(total, stats.succeeded() + stats.cancelled());
            assertEquals(succeededCount, stats.succeeded());
            assertEquals(cancelledCount, stats.cancelled());
            // Cancelled frames are distinguishable in history.
            for (FrameExecutionInfo info : service.history()) {
                if (info.cancelled()) {
                    assertTrue(toCancel.contains(info.frameId()));
                }
            }
            // Workers are alive and unblocked: later frames complete normally.
            for (int i = 0; i < 3; i++) {
                StereoResult result = service.submit(new FrameRequest("later-" + i,
                        scene.leftRaw(), scene.rightRaw(), null)).join();
                assertEquals("later-" + i, result.frameId());
            }
            assertEquals(total + 3, service.stats().submitted());
            assertEquals(0, service.stats().failed());
        }
    }

    @Test
    void queuedFrameCancellationWithSingleWorker() throws Exception {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = scene(calibration);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, fastConfig(), 1)) {
            CompletableFuture<StereoResult> a = service.submit(new FrameRequest("a",
                    scene.leftRaw(), scene.rightRaw(), null));
            CompletableFuture<StereoResult> b = service.submit(new FrameRequest("b",
                    scene.leftRaw(), scene.rightRaw(), null));
            // b is queued behind a on the single worker; cancel it while queued.
            assertTrue(service.cancel("b", false));
            CompletableFuture<StereoResult> c = service.submit(new FrameRequest("c",
                    scene.leftRaw(), scene.rightRaw(), null));

            assertEquals("a", a.join().frameId());
            CompletionException bEx = assertThrows(CompletionException.class, b::join);
            assertInstanceOf(FrameCancelledException.class, bEx.getCause());
            assertEquals("c", c.join().frameId());

            ProcessingStats stats = service.stats();
            assertEquals(3, stats.submitted());
            assertEquals(2, stats.succeeded());
            assertEquals(1, stats.cancelled());
            assertEquals(0, stats.failed());
            assertTrue(service.history().stream()
                    .anyMatch(info -> info.cancelled() && "b".equals(info.frameId())));
        }
    }

    @Test
    void interleavedRegistrationAndSubmissionYieldsTraceableVersions() throws Exception {
        var v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        var v2 = TestCalibrations.v2(WIDTH, HEIGHT);
        var v3 = CalibrationParams.create("calib-v3", WIDTH, HEIGHT,
                new double[] {WIDTH * 1.2, 0, WIDTH / 2.0, 0, WIDTH * 1.2, HEIGHT / 2.0, 0, 0, 1},
                new double[] {-0.04, 0.008, 0.0003, 0.0003, 0.0},
                new double[] {0.01, 0.005, -0.004},
                new double[] {-WIDTH * 1.2 * 0.11, 0, 0});
        SyntheticScene scene = scene(v1);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(v1);
        try (StereoProcessingService service = new StereoProcessingService(
                registry, fastConfig(), 3)) {
            // Anchors before any update: must use v1.
            assertEquals("calib-v1", service.submit(new FrameRequest("pre-1",
                    scene.leftRaw(), scene.rightRaw(), null)).join().calibrationVersion());

            // Concurrent phase: submissions interleave with registrations.
            ConcurrentLinkedQueue<String> observedVersions = new ConcurrentLinkedQueue<>();
            List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
            Thread registrar = new Thread(() -> {
                registry.register(v2);
                registry.register(v3);
            });
            registrar.start();
            for (int i = 0; i < 10; i++) {
                futures.add(service.submit(new FrameRequest("mix-" + i, scene.leftRaw(),
                        scene.rightRaw(), null), info -> observedVersions.add(
                                info.calibrationVersion())));
            }
            registrar.join();
            Set<String> known = Set.of("calib-v1", "calib-v2", "calib-v3");
            for (CompletableFuture<StereoResult> future : futures) {
                StereoResult result = future.join();
                assertTrue(known.contains(result.calibrationVersion()),
                        "result labelled with unknown version " + result.calibrationVersion());
                // The label must resolve to exactly one registered content.
                assertEquals(result.calibrationVersion(),
                        registry.resolve(result.calibrationVersion()).version());
            }
            for (String observed : observedVersions) {
                assertTrue(known.contains(observed));
            }

            // Anchors after the update: must use the latest version.
            assertEquals("calib-v3", service.submit(new FrameRequest("post-1",
                    scene.leftRaw(), scene.rightRaw(), null)).join().calibrationVersion());
            // Explicit older version still traces to its own content.
            assertEquals("calib-v1", service.submit(new FrameRequest("post-explicit",
                    scene.leftRaw(), scene.rightRaw(), "calib-v1")).join().calibrationVersion());
        }
    }

    @Test
    void inFlightFrameResultIsBitIdenticalDespiteConcurrentUpdate() throws Exception {
        var v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        var v2 = TestCalibrations.v2(WIDTH, HEIGHT);
        SyntheticScene scene = scene(v1);

        // Reference result with no concurrent update.
        CalibrationRegistry quietRegistry = new CalibrationRegistry();
        quietRegistry.register(v1);
        StereoResult reference;
        try (StereoProcessingService quiet = new StereoProcessingService(
                quietRegistry, fastConfig(), 1)) {
            reference = quiet.submit(new FrameRequest("frame", scene.leftRaw(),
                    scene.rightRaw(), "calib-v1")).join();
        }

        // Same frame, but a registration happens while it is being processed.
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(v1);
        StereoResult updated;
        try (StereoProcessingService service = new StereoProcessingService(
                registry, fastConfig(), 1)) {
            CompletableFuture<StereoResult> future = service.submit(new FrameRequest("frame",
                    scene.leftRaw(), scene.rightRaw(), "calib-v1"));
            registry.register(v2);
            updated = future.join();
            assertEquals("calib-v1", updated.calibrationVersion());
            // Frames after the update use the new content.
            assertEquals("calib-v2", service.submit(new FrameRequest("after",
                    scene.leftRaw(), scene.rightRaw(), null)).join().calibrationVersion());
        }

        float[] d1 = reference.disparityMap().disparity();
        float[] d2 = updated.disparityMap().disparity();
        assertEquals(d1.length, d2.length);
        for (int i = 0; i < d1.length; i++) {
            assertEquals(reference.disparityMap().reasons()[i],
                    updated.disparityMap().reasons()[i], "marker mismatch at " + i);
            if (Float.isNaN(d1[i])) {
                assertTrue(Float.isNaN(d2[i]), "NaN mismatch at " + i);
            } else {
                assertEquals(d1[i], d2[i], 0f,
                        "in-flight result changed under concurrent update at " + i);
            }
        }
    }

    @Test
    void closeWithQueuedFramesCompletesEveryFuture() {
        var calibration = TestCalibrations.v1(WIDTH, HEIGHT);
        SyntheticScene scene = scene(calibration);
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.register(calibration);
        StereoProcessingService service = new StereoProcessingService(registry, fastConfig(), 1);
        List<CompletableFuture<StereoResult>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(service.submit(new FrameRequest("q-" + i, scene.leftRaw(),
                    scene.rightRaw(), null)));
        }
        service.close();
        // No future may remain incomplete after close.
        for (CompletableFuture<StereoResult> future : futures) {
            assertTrue(future.isDone(), "future left incomplete by close()");
        }
        ProcessingStats stats = service.stats();
        assertEquals(6, stats.submitted());
        assertEquals(6, stats.succeeded() + stats.failed() + stats.cancelled());
    }
}
