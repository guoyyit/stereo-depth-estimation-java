package com.github.guoyyit.stereodepthestimationjava.calibration;

import com.github.guoyyit.stereodepthestimationjava.error.RectificationException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.util.Mat;

public final class RectificationEngine {

    /**
     * Upper bound (pixels) for the internal forward/inverse reprojection self-check.
     * Calibrations whose maps cannot be verified below this residual are rejected
     * explicitly instead of silently producing non-finite or inaccurate geometry.
     */
    public static final double MAX_REPROJECTION_RESIDUAL_PX = 0.1;

    public RectificationEngine() {
    }

    public RectificationMaps buildMaps(CalibrationParams params) {
        double[] k = params.k();
        double[] d = params.d();
        double[] r = params.r();
        double[] t = params.t();

        Mat rotation = Mat.rodrigues(r[0], r[1], r[2]);
        double[] om = rotationVector(rotation);
        Mat rHalf = Mat.rodrigues(om[0] / 2.0, om[1] / 2.0, om[2] / 2.0);
        Mat rHalfInverse = Mat.rodrigues(-om[0] / 2.0, -om[1] / 2.0, -om[2] / 2.0);

        // Bouguet rectification: R1 = R_rect * R^(1/2), R2 = R_rect * R^(-1/2), and the
        // rectified baseline R2 * t must be parallel to the *negative* x axis so that
        // rectified disparities stay positive (right-image point left of left-image
        // point, matching the matcher search direction and Z = f*B/d). This requires
        // the principal direction e1 = -R^(-1/2) * t / |t|: using R^(+1/2) * t leaves a
        // residual vertical epipolar error growing with rotation magnitude, and using
        // the opposite sign flips the disparity sign.
        double[] tNew = multiplyVector(rHalfInverse, t);
        double tNorm = norm(tNew);
        if (tNorm < 1e-12) {
            throw new RectificationException("stereo baseline length must not be zero");
        }

        double[] e1 = {-tNew[0] / tNorm, -tNew[1] / tNorm, -tNew[2] / tNorm};
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
                fillMapPoint(nx, ny, r1, k, d, mapXLeft, mapYLeft, index, params.version());
                fillMapPoint(nx, ny, r2, k, d, mapXRight, mapYRight, index, params.version());
            }
        }
        RectificationMaps maps = new RectificationMaps(params.version(), width, height, kNew,
                mapXLeft, mapYLeft, mapXRight, mapYRight);
        verifyMaps(maps, params, k, d, r1, r2);
        return maps;
    }

    /**
     * Internal consistency verification: every rectified pixel whose source sample falls
     * inside the source image is forward-projected through the distortion and rotation
     * model and must land back on the originating rectified pixel within
     * {@link #MAX_REPROJECTION_RESIDUAL_PX}. Any non-finite map entry or residual above
     * the bound fails explicitly with a {@link RectificationException}.
     */
    private static void verifyMaps(RectificationMaps maps, CalibrationParams params,
                                   double[] k, double[] d, Mat r1, Mat r2) {
        double[] kNew = maps.rectifiedK();
        int width = maps.width();
        int height = maps.height();
        int step = Math.max(1, Math.min(width, height) / 16);
        double maxResidual = 0.0;
        for (int side = 0; side < 2; side++) {
            float[] mapX = side == 0 ? maps.mapXLeft() : maps.mapXRight();
            float[] mapY = side == 0 ? maps.mapYLeft() : maps.mapYRight();
            Mat rotationRect = side == 0 ? r1 : r2;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int index = y * width + x;
                    float mx = mapX[index];
                    float my = mapY[index];
                    if (mx < 0f || my < 0f || mx > width - 1f || my > height - 1f) {
                        continue;
                    }
                    double[] back = forwardProject(mx, my, k, d, rotationRect, kNew,
                            params.version());
                    double residual = Math.hypot(back[0] - x, back[1] - y);
                    maxResidual = Math.max(maxResidual, residual);
                }
            }
        }
        if (!Double.isFinite(maxResidual) || maxResidual > MAX_REPROJECTION_RESIDUAL_PX) {
            throw new RectificationException("rectification self-check failed for calibration '"
                    + params.version() + "': forward/inverse reprojection residual "
                    + maxResidual + " px exceeds bound " + MAX_REPROJECTION_RESIDUAL_PX
                    + " px; distortion/rotation is outside the supported range");
        }
    }

    /**
     * Forward projection of a source pixel through undistortion, rectifying rotation and
     * the new intrinsics. This is the exact inverse of the mapping stored in the maps.
     */
    private static double[] forwardProject(double pixelX, double pixelY, double[] k, double[] d,
                                           Mat rotationRect, double[] kNew, String version) {
        double xDistorted = (pixelX - k[2]) / k[0];
        double yDistorted = (pixelY - k[5]) / k[4];
        double[] undistorted = undistortNormalized(xDistorted, yDistorted, d, version);
        double[] rotated = multiplyVector(rotationRect,
                new double[] {undistorted[0], undistorted[1], 1.0});
        if (rotated[2] <= 1e-12) {
            throw new RectificationException("rectification of calibration '" + version
                    + "' projects source points behind the camera");
        }
        double nx = rotated[0] / rotated[2];
        double ny = rotated[1] / rotated[2];
        return new double[] {kNew[0] * nx + kNew[2], kNew[1] * ny + kNew[3]};
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
                                     double[] d, float[] mapX, float[] mapY, int index,
                                     String version) {
        double[] cameraNormalized = multiplyVector(rotationRect.transpose(),
                new double[] {nx, ny, 1.0});
        if (!Double.isFinite(cameraNormalized[2]) || cameraNormalized[2] <= 1e-12) {
            throw new RectificationException("rectification of calibration '" + version
                    + "' maps a rectified pixel behind the camera; "
                    + "rotation is outside the supported range");
        }
        double x = cameraNormalized[0] / cameraNormalized[2];
        double y = cameraNormalized[1] / cameraNormalized[2];
        double r2 = x * x + y * y;
        double radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
        double xDistorted = x * radial + 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
        double yDistorted = y * radial + d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
        float mapXValue = (float) (k[0] * xDistorted + k[2]);
        float mapYValue = (float) (k[4] * yDistorted + k[5]);
        if (!Float.isFinite(mapXValue) || !Float.isFinite(mapYValue)) {
            throw new RectificationException("rectification of calibration '" + version
                    + "' produced a non-finite map entry; distortion is outside the "
                    + "supported range");
        }
        mapX[index] = mapXValue;
        mapY[index] = mapYValue;
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
                    double[] p = projectCorner(cornerX, cornerY, k, d, rotationRect,
                            params.version());
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
        double[] kNew = {f, f, cx, cy};
        for (double v : kNew) {
            if (!Double.isFinite(v)) {
                throw new RectificationException("rectification of calibration '"
                        + params.version() + "' produced non-finite rectified intrinsics");
            }
        }
        return kNew;
    }

    private static double[] projectCorner(double pixelX, double pixelY, double[] k, double[] d,
                                          Mat rotationRect, String version) {
        double xDistorted = (pixelX - k[2]) / k[0];
        double yDistorted = (pixelY - k[5]) / k[4];
        double[] undistorted = undistortNormalized(xDistorted, yDistorted, d, version);
        double[] rotated = multiplyVector(rotationRect,
                new double[] {undistorted[0], undistorted[1], 1.0});
        if (rotated[2] <= 1e-12) {
            throw new RectificationException("rectification of calibration '" + version
                    + "' projects behind the camera");
        }
        return new double[] {rotated[0] / rotated[2], rotated[1] / rotated[2]};
    }

    private static double[] undistortNormalized(double xDistorted, double yDistorted, double[] d,
                                                String version) {
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
        double residual = distortionResidual(x, y, xDistorted, yDistorted, d);
        if (!Double.isFinite(residual) || residual > 1e-6) {
            throw new RectificationException("distortion inversion of calibration '" + version
                    + "' does not converge (residual " + residual
                    + "); distortion coefficients are outside the supported range");
        }
        return new double[] {x, y};
    }

    private static double distortionResidual(double x, double y, double xDistorted,
                                             double yDistorted, double[] d) {
        double r2 = x * x + y * y;
        double radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
        double fx = x * radial + 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
        double fy = y * radial + d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
        return Math.hypot(fx - xDistorted, fy - yDistorted);
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
