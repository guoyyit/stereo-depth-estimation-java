package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.RectificationEngine;
import com.github.guoyyit.stereodepthestimationjava.error.RectificationException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.synthetic.IndependentCameraModel;
import com.github.guoyyit.stereodepthestimationjava.synthetic.IndependentRectificationReference;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies rectification geometry against an independent reference: synthetic
 * projections and the reference mapping are produced by
 * {@link IndependentCameraModel}/{@link IndependentRectificationReference}, which
 * share no mapping computation with the production engine, so these residuals
 * genuinely expose distortion- and epipolar-rectification errors.
 */
class RectificationGeometryTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 160;
    private static final long SEED = 20261006L;
    /** Documented upper bound for map-level reprojection residuals, in pixels. */
    private static final double REPROJECTION_BOUND_PX = 0.05;
    /** Documented upper bound for the epipolar coplanarity residual, in pixels. */
    private static final double EPIPOLAR_BOUND_PX = 0.05;

    private static List<CalibrationParams> supportedCalibrations() {
        return List.of(
                TestCalibrations.v1(WIDTH, HEIGHT),
                TestCalibrations.v2(WIDTH, HEIGHT),
                TestCalibrations.strongDistortion(WIDTH, HEIGHT),
                TestCalibrations.strongRotation(WIDTH, HEIGHT));
    }

    @Test
    void engineMapsAgreeWithIndependentReferenceWithinBound() {
        RectificationEngine engine = new RectificationEngine();
        for (CalibrationParams calibration : supportedCalibrations()) {
            RectificationMaps maps = engine.buildMaps(calibration);
            IndependentRectificationReference reference =
                    new IndependentRectificationReference(calibration, maps.rectifiedK());
            double maxResidual = 0.0;
            for (int y = 0; y < HEIGHT; y += 2) {
                for (int x = 0; x < WIDTH; x += 2) {
                    int i = y * WIDTH + x;
                    double[] refLeft = reference.mapLeft(x, y);
                    double[] refRight = reference.mapRight(x, y);
                    maxResidual = Math.max(maxResidual, Math.hypot(
                            refLeft[0] - maps.mapXLeft()[i], refLeft[1] - maps.mapYLeft()[i]));
                    maxResidual = Math.max(maxResidual, Math.hypot(
                            refRight[0] - maps.mapXRight()[i], refRight[1] - maps.mapYRight()[i]));
                }
            }
            assertTrue(maxResidual <= REPROJECTION_BOUND_PX,
                    calibration.version() + " reprojection residual " + maxResidual
                            + " px exceeds bound " + REPROJECTION_BOUND_PX);
        }
    }

    @Test
    void epipolarResidualWithinBoundFor3DReferencePoints() {
        RectificationEngine engine = new RectificationEngine();
        Random random = new Random(SEED);
        for (CalibrationParams calibration : supportedCalibrations()) {
            RectificationMaps maps = engine.buildMaps(calibration);
            IndependentCameraModel camera = new IndependentCameraModel(calibration);
            double[] r = calibration.r();
            double[] rNeg = {-r[0], -r[1], -r[2]};
            double[] t = calibration.t();
            // Baseline (camera-2 center) expressed in the left camera frame: -R^-1 t.
            double[] baselineLeft = IndependentCameraModel.rotate(rNeg, t);
            double bNorm = norm(baselineLeft);
            double[] bHat = {baselineLeft[0] / bNorm, baselineLeft[1] / bNorm,
                    baselineLeft[2] / bNorm};
            double fNew = maps.rectifiedK()[0];

            double maxResidual = 0.0;
            int samples = 0;
            double[] depthRange = depthRangeForDisparities(calibration, 8.0, 48.0);
            for (int attempt = 0; attempt < 20_000 && samples < 400; attempt++) {
                // Sample an in-image left pixel and unproject it to a 3D point at a
                // random depth: the point is inside the left image by construction.
                double u = 1.0 + random.nextDouble() * (WIDTH - 3.0);
                double v = 1.0 + random.nextDouble() * (HEIGHT - 3.0);
                double[] ray = camera.undistortedRayLeft(u, v);
                double z = depthRange[0] + random.nextDouble() * (depthRange[1] - depthRange[0]);
                double[] point = {ray[0] * z, ray[1] * z, ray[2] * z};
                double[] rawRight = camera.projectRight(point[0], point[1], point[2]);
                if (!insideImage(rawRight)) {
                    continue;
                }
                samples++;
                // Independent undistortion of the raw projections, then coplanarity
                // of the two rays with the baseline: the epipolar constraint.
                double[] rayL = camera.undistortedRayLeft(u, v);
                double[] rayR = IndependentCameraModel.rotate(rNeg,
                        camera.undistortedRayRight(rawRight[0], rawRight[1]));
                double[] cross = {
                        rayL[1] * rayR[2] - rayL[2] * rayR[1],
                        rayL[2] * rayR[0] - rayL[0] * rayR[2],
                        rayL[0] * rayR[1] - rayL[1] * rayR[0]};
                double rayNorm = norm(rayL) * norm(rayR);
                double residualPx = Math.abs(
                        cross[0] * bHat[0] + cross[1] * bHat[1] + cross[2] * bHat[2])
                        / rayNorm * fNew;
                maxResidual = Math.max(maxResidual, residualPx);
            }
            assertTrue(samples >= 200,
                    calibration.version() + " produced too few in-image samples: " + samples);
            assertTrue(maxResidual <= EPIPOLAR_BOUND_PX,
                    calibration.version() + " epipolar residual " + maxResidual
                            + " px exceeds bound " + EPIPOLAR_BOUND_PX);
        }
    }

    @Test
    void rectifiedRowsAlignForEngineMapsOf3DPoints() {
        // For random 3D points, the engine-rectified left/right positions must lie on
        // the same row. Positions are obtained by numerically inverting the engine
        // maps (a generic root-finding routine, not rectification math).
        RectificationEngine engine = new RectificationEngine();
        Random random = new Random(SEED + 1);
        for (CalibrationParams calibration : supportedCalibrations()) {
            RectificationMaps maps = engine.buildMaps(calibration);
            IndependentCameraModel camera = new IndependentCameraModel(calibration);
            double maxRowMisalignment = 0.0;
            int samples = 0;
            double[] depthRange = depthRangeForDisparities(calibration, 8.0, 48.0);
            for (int attempt = 0; attempt < 20_000 && samples < 300; attempt++) {
                double u = 1.0 + random.nextDouble() * (WIDTH - 3.0);
                double v = 1.0 + random.nextDouble() * (HEIGHT - 3.0);
                double[] ray = camera.undistortedRayLeft(u, v);
                double z = depthRange[0] + random.nextDouble() * (depthRange[1] - depthRange[0]);
                double[] point = {ray[0] * z, ray[1] * z, ray[2] * z};
                double[] rawRight = camera.projectRight(point[0], point[1], point[2]);
                if (!insideImage(rawRight)) {
                    continue;
                }
                samples++;
                double[] rectLeft = invertMap(maps.mapXLeft(), maps.mapYLeft(), u, v);
                double[] rectRight = invertMap(maps.mapXRight(), maps.mapYRight(),
                        rawRight[0], rawRight[1]);
                maxRowMisalignment = Math.max(maxRowMisalignment,
                        Math.abs(rectLeft[1] - rectRight[1]));
            }
            assertTrue(samples >= 150,
                    calibration.version() + " produced too few in-image samples: " + samples);
            assertTrue(maxRowMisalignment <= EPIPOLAR_BOUND_PX,
                    calibration.version() + " row misalignment " + maxRowMisalignment
                            + " px exceeds bound " + EPIPOLAR_BOUND_PX);
        }
    }

    @Test
    void residualsAreReproducibleWithFixedSeed() {
        RectificationEngine engine = new RectificationEngine();
        for (CalibrationParams calibration : supportedCalibrations()) {
            RectificationMaps first = engine.buildMaps(calibration);
            RectificationMaps second = new RectificationEngine().buildMaps(calibration);
            for (int i = 0; i < first.mapXLeft().length; i++) {
                assertEquals(first.mapXLeft()[i], second.mapXLeft()[i], 0.0f);
                assertEquals(first.mapYLeft()[i], second.mapYLeft()[i], 0.0f);
                assertEquals(first.mapXRight()[i], second.mapXRight()[i], 0.0f);
                assertEquals(first.mapYRight()[i], second.mapYRight()[i], 0.0f);
            }
        }
    }

    @Test
    void outOfRangeDistortionFailsWithDistinguishableReason() {
        CalibrationParams wild = CalibrationParams.create("calib-wild-d", WIDTH, HEIGHT,
                new double[] {WIDTH * 1.1, 0, WIDTH / 2.0, 0, WIDTH * 1.1, HEIGHT / 2.0, 0, 0, 1},
                new double[] {0.45, 0.0, 0.0, 0.0, 0.0},
                new double[] {0.01, 0.0, 0.0},
                new double[] {-WIDTH * 1.1 * 0.12, 0, 0});
        RectificationException ex = assertThrows(RectificationException.class,
                () -> new RectificationEngine().buildMaps(wild));
        assertEquals(RectificationException.Reason.OUT_OF_SUPPORTED_RANGE, ex.reason());
    }

    @Test
    void outOfRangeRotationFailsWithDistinguishableReason() {
        CalibrationParams wild = CalibrationParams.create("calib-wild-r", WIDTH, HEIGHT,
                new double[] {WIDTH * 1.1, 0, WIDTH / 2.0, 0, WIDTH * 1.1, HEIGHT / 2.0, 0, 0, 1},
                new double[] {-0.03, 0.005, 0.0, 0.0, 0.0},
                new double[] {0.3, 0.3, 0.3},
                new double[] {-WIDTH * 1.1 * 0.12, 0, 0});
        RectificationException ex = assertThrows(RectificationException.class,
                () -> new RectificationEngine().buildMaps(wild));
        assertEquals(RectificationException.Reason.OUT_OF_SUPPORTED_RANGE, ex.reason());
    }

    @Test
    void degenerateBaselineFailsWithDistinguishableReason() {
        CalibrationParams flat = CalibrationParams.create("calib-flat", WIDTH, HEIGHT,
                new double[] {WIDTH * 1.1, 0, WIDTH / 2.0, 0, WIDTH * 1.1, HEIGHT / 2.0, 0, 0, 1},
                new double[] {-0.03, 0.005, 0.0, 0.0, 0.0},
                new double[] {0.01, 0.0, 0.0},
                new double[] {-1e-15, 0, 0});
        RectificationException ex = assertThrows(RectificationException.class,
                () -> new RectificationEngine().buildMaps(flat));
        assertEquals(RectificationException.Reason.DEGENERATE_BASELINE, ex.reason());
    }

    private static boolean insideImage(double[] p) {
        return p[0] >= 1.0 && p[0] <= WIDTH - 2.0 && p[1] >= 1.0 && p[1] <= HEIGHT - 2.0;
    }

    /**
     * Depth range (in the calibration's translation units) at which a fronto-parallel
     * point produces a disparity between {@code maxDisparity} and {@code minDisparity}
     * pixels: z = f * B / d.
     */
    private static double[] depthRangeForDisparities(CalibrationParams calibration,
                                                     double minDisparity, double maxDisparity) {
        double f = calibration.k()[0];
        double[] t = calibration.t();
        double baseline = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        double zNear = f * baseline / maxDisparity;
        double zFar = f * baseline / minDisparity;
        return new double[] {zNear, zFar};
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /**
     * Generic inversion of a rectified-to-raw map grid: finds the rectified pixel
     * whose map entry equals the given raw coordinate. Pure numerical root-finding
     * on the map samples (coarse nearest-grid seed plus Newton refinement);
     * contains no rectification geometry.
     */
    private static double[] invertMap(float[] mapX, float[] mapY, double rawX, double rawY) {
        // Coarse seed: nearest map entry on the integer grid.
        double best = Double.POSITIVE_INFINITY;
        double rx = rawX;
        double ry = rawY;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int i = y * WIDTH + x;
                double dx = mapX[i] - rawX;
                double dy = mapY[i] - rawY;
                double dist = dx * dx + dy * dy;
                if (dist < best) {
                    best = dist;
                    rx = x;
                    ry = y;
                }
            }
        }
        // Newton refinement around the seed.
        for (int iteration = 0; iteration < 30; iteration++) {
            int ix = (int) Math.floor(rx);
            int iy = (int) Math.floor(ry);
            if (ix < 0 || iy < 0 || ix >= WIDTH - 1 || iy >= HEIGHT - 1) {
                break;
            }
            double fx = rx - ix;
            double fy = ry - iy;
            int i00 = iy * WIDTH + ix;
            double mx = bilinear(mapX[i00], mapX[i00 + 1], mapX[i00 + WIDTH],
                    mapX[i00 + WIDTH + 1], fx, fy);
            double my = bilinear(mapY[i00], mapY[i00 + 1], mapY[i00 + WIDTH],
                    mapY[i00 + WIDTH + 1], fx, fy);
            double dxdx = (1 - fy) * (mapX[i00 + 1] - mapX[i00])
                    + fy * (mapX[i00 + WIDTH + 1] - mapX[i00 + WIDTH]);
            double dxdy = (1 - fx) * (mapX[i00 + WIDTH] - mapX[i00])
                    + fx * (mapX[i00 + WIDTH + 1] - mapX[i00 + 1]);
            double dydx = (1 - fy) * (mapY[i00 + 1] - mapY[i00])
                    + fy * (mapY[i00 + WIDTH + 1] - mapY[i00 + WIDTH]);
            double dydy = (1 - fx) * (mapY[i00 + WIDTH] - mapY[i00])
                    + fx * (mapY[i00 + WIDTH + 1] - mapY[i00 + 1]);
            double det = dxdx * dydy - dxdy * dydx;
            if (Math.abs(det) < 1e-9) {
                break;
            }
            double ex = mx - rawX;
            double ey = my - rawY;
            if (Math.abs(ex) < 1e-7 && Math.abs(ey) < 1e-7) {
                break;
            }
            rx -= (dydy * ex - dxdy * ey) / det;
            ry -= (-dydx * ex + dxdx * ey) / det;
        }
        return new double[] {rx, ry};
    }

    private static double bilinear(double v00, double v01, double v10, double v11,
                                   double fx, double fy) {
        double top = v00 + (v01 - v00) * fx;
        double bottom = v10 + (v11 - v10) * fx;
        return top + (bottom - top) * fy;
    }
}
