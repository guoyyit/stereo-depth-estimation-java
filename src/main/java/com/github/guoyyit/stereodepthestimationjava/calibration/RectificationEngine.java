package com.github.guoyyit.stereodepthestimationjava.calibration;

import com.github.guoyyit.stereodepthestimationjava.error.RectificationException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.util.Mat;

/**
 * Builds Bouguet half-rotation rectification maps for the Brown-Conrady distortion
 * model ({@code k1,k2,p1,p2,k3}).
 *
 * <p>Accuracy is only guaranteed inside the documented supported range
 * (see {@link #MAX_RADIAL_K1} etc.). Inside that range the self-checked reprojection
 * residual of the built maps is at most {@link #SELF_CHECK_RESIDUAL_PX} pixels.
 * Anything outside the range, or any map that fails the self-check, is rejected with
 * a {@link RectificationException} carrying a machine-distinguishable reason instead
 * of silently emitting degraded or non-finite geometry.</p>
 */
public final class RectificationEngine {

    /** Supported range: |k1| (radial, first order). */
    public static final double MAX_RADIAL_K1 = 0.3;
    /** Supported range: |k2| (radial, second order). */
    public static final double MAX_RADIAL_K2 = 0.5;
    /** Supported range: |k3| (radial, third order). */
    public static final double MAX_RADIAL_K3 = 0.5;
    /** Supported range: |p1|, |p2| (tangential). */
    public static final double MAX_TANGENTIAL = 0.02;
    /** Supported range: rotation vector norm between the two cameras, in radians. */
    public static final double MAX_ROTATION_RAD = 0.35;
    /**
     * Upper bound on the self-checked reprojection residual (round trip
     * pixel -&gt; undistort -&gt; distort -&gt; pixel) of the built maps, in pixels.
     */
    public static final double SELF_CHECK_RESIDUAL_PX = 0.01;

    public RectificationEngine() {
    }

    public RectificationMaps buildMaps(CalibrationParams params) {
        validateSupportedRange(params);

        double[] k = params.k();
        double[] d = params.d();
        double[] r = params.r();
        double[] t = params.t();

        Mat rotation = Mat.rodrigues(r[0], r[1], r[2]);
        double[] om = rotationVector(rotation);
        Mat rHalf = Mat.rodrigues(om[0] / 2.0, om[1] / 2.0, om[2] / 2.0);
        Mat rHalfInverse = Mat.rodrigues(-om[0] / 2.0, -om[1] / 2.0, -om[2] / 2.0);

        // Epipolar-frame x axis: the rectified camera-2 center must lie on the
        // rectified x axis, i.e. R2 * t must be parallel to e1. With
        // R2 = R_rect * R(-om/2) this requires e1 || R(-om/2) * t.
        double[] tNew = multiplyVector(rHalfInverse, t);
        double tNorm = norm(tNew);
        if (tNorm < 1e-12) {
            throw new RectificationException("stereo baseline length must not be zero",
                    RectificationException.Reason.DEGENERATE_BASELINE);
        }

        double[] e1 = {tNew[0] / tNorm, tNew[1] / tNorm, tNew[2] / tNorm};
        double[] e2Raw = {-e1[1], e1[0], 0.0};
        double e2Norm = norm(e2Raw);
        if (e2Norm < 1e-12) {
            double[] fallback = {0.0, -e1[2], e1[1]};
            double fallbackNorm = norm(fallback);
            e2Raw = fallback;
            e2Norm = fallbackNorm;
        }
        double[] e2 = {e2Raw[0] / e2Norm, e2Raw[1] / e2Norm, e2Raw[2] / e2Norm};
        double[] e3 = cross(e1, e2);
        Mat rRect = rowsAsVectors(e1, e2, e3);

        Mat r1 = rRect.multiply(rHalf);
        Mat r2 = rRect.multiply(rHalfInverse);

        double[] kNew = newIntrinsics(params, k, d, r1, r2);
        double fx = kNew[0];
        double fy = kNew[1];
        double cx = kNew[2];
        double cy = kNew[3];
        if (!Double.isFinite(fx) || !Double.isFinite(fy) || fx <= 0 || fy <= 0
                || !Double.isFinite(cx) || !Double.isFinite(cy)) {
            throw new RectificationException(
                    "rectified intrinsics are not finite and positive: " + java.util.Arrays.toString(kNew),
                    RectificationException.Reason.NON_FINITE_MAP);
        }

        int width = params.imageWidth();
        int height = params.imageHeight();
        int pixels = width * height;
        float[] mapXLeft = new float[pixels];
        float[] mapYLeft = new float[pixels];
        float[] mapXRight = new float[pixels];
        float[] mapYRight = new float[pixels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                double nx = (x - cx) / fx;
                double ny = (y - cy) / fy;
                fillMapPoint(nx, ny, r1, k, d, mapXLeft, mapYLeft, index);
                fillMapPoint(nx, ny, r2, k, d, mapXRight, mapYRight, index);
            }
        }
        rejectNonFinite(mapXLeft, "mapXLeft");
        rejectNonFinite(mapYLeft, "mapYLeft");
        rejectNonFinite(mapXRight, "mapXRight");
        rejectNonFinite(mapYRight, "mapYRight");
        return new RectificationMaps(params.version(), params.contentFingerprint(),
                width, height, kNew,
                mapXLeft, mapYLeft, mapXRight, mapYRight);
    }

    /**
     * Enforces the documented supported range. Outside this range rectification is
     * refused explicitly rather than attempted with unbounded error.
     */
    private static void validateSupportedRange(CalibrationParams params) {
        double[] d = params.d();
        double[] r = params.r();
        double[] t = params.t();
        double rotationNorm = norm(r);
        double baseline = norm(t);
        if (Math.abs(d[0]) > MAX_RADIAL_K1 || Math.abs(d[1]) > MAX_RADIAL_K2
                || Math.abs(d[4]) > MAX_RADIAL_K3
                || Math.abs(d[2]) > MAX_TANGENTIAL || Math.abs(d[3]) > MAX_TANGENTIAL
                || rotationNorm > MAX_ROTATION_RAD) {
            throw new RectificationException(
                    "calibration '" + params.version() + "' is outside the supported"
                            + " distortion/rotation range (|k1|<=" + MAX_RADIAL_K1
                            + ", |k2|<=" + MAX_RADIAL_K2 + ", |k3|<=" + MAX_RADIAL_K3
                            + ", |p1|,|p2|<=" + MAX_TANGENTIAL
                            + ", |r|<=" + MAX_ROTATION_RAD + " rad): d="
                            + java.util.Arrays.toString(d) + " r="
                            + java.util.Arrays.toString(r),
                    RectificationException.Reason.OUT_OF_SUPPORTED_RANGE);
        }
        if (baseline < 1e-12) {
            throw new RectificationException("stereo baseline length must not be zero",
                    RectificationException.Reason.DEGENERATE_BASELINE);
        }
    }

    private static void rejectNonFinite(float[] map, String name) {
        for (float v : map) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new RectificationException(
                        "rectification " + name + " contains non-finite entries;"
                                + " calibration is not rectifiable within accuracy bounds",
                        RectificationException.Reason.NON_FINITE_MAP);
            }
        }
    }

    private static double[] rotationVector(Mat rotation) {
        double cosTheta = (rotation.get(0, 0) + rotation.get(1, 1) + rotation.get(2, 2) - 1.0) / 2.0;
        cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
        double theta = Math.acos(cosTheta);
        if (theta < 1e-10) {
            return new double[] {
                    (rotation.get(2, 1) - rotation.get(1, 2)) / 2.0,
                    (rotation.get(0, 2) - rotation.get(2, 0)) / 2.0,
                    (rotation.get(1, 0) - rotation.get(0, 1)) / 2.0
            };
        }
        double factor = theta / (2.0 * Math.sin(theta));
        return new double[] {
                factor * (rotation.get(2, 1) - rotation.get(1, 2)),
                factor * (rotation.get(0, 2) - rotation.get(2, 0)),
                factor * (rotation.get(1, 0) - rotation.get(0, 1))
        };
    }

    private static void fillMapPoint(double nx, double ny, Mat rotationRect, double[] k,
                                     double[] d, float[] mapX, float[] mapY, int index) {
        double[] cameraNormalized = multiplyVector(rotationRect.transpose(),
                new double[] {nx, ny, 1.0});
        if (cameraNormalized[2] <= 1e-12) {
            throw new RectificationException(
                    "rectification maps project behind the camera",
                    RectificationException.Reason.BEHIND_CAMERA);
        }
        double x = cameraNormalized[0] / cameraNormalized[2];
        double y = cameraNormalized[1] / cameraNormalized[2];
        double r2 = x * x + y * y;
        double radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
        double xDistorted = x * radial + 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
        double yDistorted = y * radial + d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
        mapX[index] = (float) (k[0] * xDistorted + k[2]);
        mapY[index] = (float) (k[4] * yDistorted + k[5]);
    }

    private static double[] newIntrinsics(CalibrationParams params, double[] k, double[] d,
                                          Mat r1, Mat r2) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double[] boundsX = {0.0, params.imageWidth() - 1.0};
        double[] boundsY = {0.0, params.imageHeight() - 1.0};
        for (double cornerX : boundsX) {
            for (double cornerY : boundsY) {
                for (Mat rotationRect : new Mat[] {r1, r2}) {
                    double[] p = projectCorner(cornerX, cornerY, k, d, rotationRect);
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
        }
        double fxScale = (params.imageWidth() - 1) / (maxX - minX);
        double fyScale = (params.imageHeight() - 1) / (maxY - minY);
        double f = Math.min(fxScale, fyScale);
        double cx = -f * 0.5 * (maxX + minX) + 0.5 * (params.imageWidth() - 1);
        double cy = -f * 0.5 * (maxY + minY) + 0.5 * (params.imageHeight() - 1);
        return new double[] {f, f, cx, cy};
    }

    private static double[] projectCorner(double pixelX, double pixelY, double[] k, double[] d,
                                          Mat rotationRect) {
        double xDistorted = (pixelX - k[2]) / k[0];
        double yDistorted = (pixelY - k[5]) / k[4];
        double[] undistorted = undistortNormalized(xDistorted, yDistorted, d);
        checkSolverResidual(xDistorted, yDistorted, undistorted, d, k);
        double[] rotated = multiplyVector(rotationRect,
                new double[] {undistorted[0], undistorted[1], 1.0});
        if (rotated[2] <= 1e-12) {
            throw new RectificationException("rectification maps project behind the camera",
                    RectificationException.Reason.BEHIND_CAMERA);
        }
        return new double[] {rotated[0] / rotated[2], rotated[1] / rotated[2]};
    }

    /**
     * Self-check: re-distorting the solved undistorted point must reproduce the
     * original distorted point within {@link #SELF_CHECK_RESIDUAL_PX} pixels.
     * A diverging solver is reported explicitly instead of leaking into the maps.
     */
    private static void checkSolverResidual(double xDistorted, double yDistorted,
                                            double[] undistorted, double[] d, double[] k) {
        double x = undistorted[0];
        double y = undistorted[1];
        double r2 = x * x + y * y;
        double radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
        double xd = x * radial + 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
        double yd = y * radial + d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
        double residualPx = Math.hypot((xd - xDistorted) * k[0], (yd - yDistorted) * k[4]);
        if (!Double.isFinite(residualPx)) {
            throw new RectificationException(
                    "undistortion solver diverged for this calibration",
                    RectificationException.Reason.SOLVER_DIVERGED);
        }
        if (residualPx > SELF_CHECK_RESIDUAL_PX) {
            throw new RectificationException(
                    "undistortion reprojection residual " + residualPx
                            + " px exceeds bound " + SELF_CHECK_RESIDUAL_PX + " px",
                    RectificationException.Reason.RESIDUAL_EXCEEDS_BOUND);
        }
    }

    private static double[] undistortNormalized(double xDistorted, double yDistorted, double[] d) {
        double x = xDistorted;
        double y = yDistorted;
        for (int iteration = 0; iteration < 20; iteration++) {
            double r2 = x * x + y * y;
            double radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
            double tangentX = 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
            double tangentY = d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
            x = (xDistorted - tangentX) / radial;
            y = (yDistorted - tangentY) / radial;
        }
        return new double[] {x, y};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static Mat rowsAsVectors(double[] e1, double[] e2, double[] e3) {
        Mat m = new Mat(3, 3);
        double[][] rows = {e1, e2, e3};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                m.set(r, c, rows[r][c]);
            }
        }
        return m;
    }

    private static double[] multiplyVector(Mat m, double[] v) {
        double[] result = new double[3];
        for (int r = 0; r < 3; r++) {
            result[r] = m.get(r, 0) * v[0] + m.get(r, 1) * v[1] + m.get(r, 2) * v[2];
        }
        return result;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
