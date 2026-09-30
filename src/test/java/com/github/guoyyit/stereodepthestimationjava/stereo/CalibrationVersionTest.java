package com.github.guoyyit.stereodepthestimationjava.stereo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Calibration version semantics: in-flight frames keep their start
 * version, later frames use the new version, and any mixing or missing
 * version is rejected.
 */
class CalibrationVersionTest {
    private static final int W = 64;
    private static final int H = 48;

    private static CalibrationParams params(String version, double focal) {
        return CalibrationParams.ideal(version, focal, W / 2.0, H / 2.0, 0.1);
    }

    @Test
    void inFlightFrameKeepsStartVersionNextFrameUsesNewVersion() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(params("v1", 100.0));
        SyntheticDataGenerator.Scene scene = SyntheticDataGenerator.generate(W, H, 3L);
        StereoConfig config = StereoConfig.builder().numDisparities(32).blockRadius(2).build();

        // Publishing v2 the instant the version is pinned must not leak
        // into the frame that just started.
        StereoPipeline pipeline = new StereoPipeline(registry, config,
                pinned -> registry.publish(params("v2", 120.0)));
        StereoResult inFlight = pipeline.process(scene.pair(), true);
        assertEquals("v1", inFlight.calibrationVersion());
        assertEquals("v1", inFlight.depth().calibrationVersion());
        assertEquals("v1", inFlight.pointCloud().calibrationVersion());

        StereoResult next = pipeline.process(scene.pair(), true);
        assertEquals("v2", next.calibrationVersion());
        assertEquals("v2", next.depth().calibrationVersion());
        assertEquals("v2", next.pointCloud().calibrationVersion());
    }

    @Test
    void missingOrUnknownVersionsAreRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        assertThrows(CalibrationException.class, registry::current);
        assertThrows(CalibrationException.class, () -> registry.require("nope"));
        assertThrows(CalibrationException.class, () -> params(" ", 100.0));
        assertThrows(CalibrationException.class, () -> params(null, 100.0));
    }

    @Test
    void republishingVersionWithDifferentContentIsRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(params("v1", 100.0));
        assertThrows(CalibrationException.class, () -> registry.publish(params("v1", 120.0)));
        // Identical re-publish is a no-op and stays usable.
        registry.publish(params("v1", 100.0));
        assertEquals(100.0, registry.require("v1").focalPixels());
    }

    @Test
    void mixedVersionsInOneResultAreRejected() {
        CalibrationParams v1 = params("v1", 100.0);
        CalibrationParams v2 = params("v2", 120.0);
        DisparityMap disp = new DisparityMap(W, H);
        disp.setValid(10, 10, 8.0f);
        DepthMap depthV1 = DepthMap.fromDisparity(disp, v1);

        assertThrows(CalibrationException.class,
                () -> new StereoResult(disp, depthV1, null, "v2"));
        assertThrows(CalibrationException.class,
                () -> PointCloud.fromDepth(depthV1, v2));
        PointCloud cloudV1 = PointCloud.fromDepth(depthV1, v1);
        assertThrows(CalibrationException.class,
                () -> new StereoResult(disp, depthV1, cloudV1, "v2"));
        assertThrows(CalibrationException.class,
                () -> new StereoResult(disp, depthV1, null, " "));
    }
}
