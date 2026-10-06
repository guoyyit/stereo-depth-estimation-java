package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;

/**
 * Independent reference implementation of the stereo projection, distortion and
 * rectification geometry, written from the published model equations without reusing
 * any code path of the production RectificationEngine. Validation tests compare the
 * pipeline output against this model so that a bug in the production mapping
 * computation cannot be cancelled out by an identical bug in the test fixture.
 */
public final class IndependentStereoModel {

    private final CalibrationParams params;
    private final double[][] rotation; // R: left camera frame -> right camera frame
    private final double[][] r1;       // rectifying rotation, left camera
    private final double[][] r2;       // rectifying rotation, right camera
    private final double[] kNew;       // {fx, fy, cx, cy} of the rectified cameras

    public IndependentStereoModel(CalibrationParams params) {
        this.params = params;
        double[] r = params.r();
        this.rotation = rodrigues(r[0], r[1], r[2]);
        double[] om = rotationVector(rotation);
        double[][] rHalf = rodrigues(om[0] / 2, om[1] / 2, om[2] / 2);
        double[][] rHalfInv = rodrigues(-om[0] / 2, -om[1] / 2, -om[2] / 2);
        double[] t = params.t();
        double[] tNew = mul(rHalfInv, t); // e1 = -R^(-1/2)*t/|t| so R2*t = (-B,0,0): positive disparity
        double tn = norm(tNew);
        double[] e1 = {-tNew[0] / tn, -tNew[1] / tn, -tNew[2] / tn};
        double[] e2 = normalize(new double[] {-e1[1], e1[0], 0});
        if (norm(e2) < 1e-12) {
            e2 = normalize(new double[] {0, -e1[2], e1[1]});
        }
        double[] e3 = cross(e1, e2);
        double[][] rRect = {e1, e2, e3};
        this.r1 = mul(rRect, rHalf);
        this.r2 = mul(rRect, rHalfInv);
        this.kNew = fitIntrinsics();
    }

    /** Projects a 3D point given in the left camera frame into the raw left image. */
    public double[] projectLeft(double[] pointLeft) {
        return distortAndProject(pointLeft);
    }

    /** Projects a 3D point given in the left camera frame into the raw right image. */
    public double[] projectRight(double[] pointLeft) {
        double[] pRight = add(mul(rotation, pointLeft), params.t());
        return distortAndProject(pRight);
    }

    /** Rectified projection of a left-frame 3D point into the rectified left image. */
    public double[] projectRectifiedLeft(double[] pointLeft) {
        double[] p = mul(r1, pointLeft);
        return new double[] {kNew[0] * p[0] / p[2] + kNew[2], kNew[1] * p[1] / p[2] + kNew[3]};
    }

    /** Rectified projection of a left-frame 3D point into the rectified right image. */
    public double[] projectRectifiedRight(double[] pointLeft) {
        double[] pRight = add(mul(rotation, pointLeft), params.t());
        double[] p = mul(r2, pRight);
        return new double[] {kNew[0] * p[0] / p[2] + kNew[2], kNew[1] * p[1] / p[2] + kNew[3]};
    }

    /** Depth of a left-frame point along the rectified left optical axis. */
    public double rectifiedDepth(double[] pointLeft) {
        return mul(r1, pointLeft)[2];
    }

    /** Ideal rectified disparity for a left-frame 3D point: f * B / Z_rectified. */
    public double idealDisparity(double[] pointLeft) {
        double[] t = params.t();
        double baseline = norm(t);
        return kNew[0] * baseline / rectifiedDepth(pointLeft);
    }

    public double[] rectifiedK() {
        return kNew.clone();
    }

    public double[][] rectifyingRotationLeft() {
        return r1;
    }

    private double[] distortAndProject(double[] p) {
        double[] k = params.k();
        double[] d = params.d();
        double x = p[0] / p[2];
        double y = p[1] / p[2];
        double r2 = x * x + y * y;
        double radial = 1 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
        double xd = x * radial + 2 * d[2] * x * y + d[3] * (r2 + 2 * x * x);
        double yd = y * radial + d[2] * (r2 + 2 * y * y) + 2 * d[3] * x * y;
        return new double[] {k[0] * xd + k[2], k[4] * yd + k[5]};
    }

    /** Iterative undistortion of a raw pixel into a left-frame ray direction. */
    public double[] rayFromRawPixel(double pixelX, double pixelY) {
        double[] k = params.k();
        double[] d = params.d();
        double xd = (pixelX - k[2]) / k[0];
        double yd = (pixelY - k[5]) / k[4];
        double x = xd;
        double y = yd;
        for (int i = 0; i < 50; i++) {
            double r2 = x * x + y * y;
            double radial = 1 + d[0] * r2 + d[1] * r2 * r2 + d[4] * r2 * r2 * r2;
            double tx = 2 * d[2] * x * y + d[3] * (r2 + 2 * x * x);
            double ty = d[2] * (r2 + 2 * y * y) + 2 * d[3] * x * y;
            double nx = (xd - tx) / radial;
            double ny = (yd - ty) / radial;
            if (Math.hypot(nx - x, ny - y) < 1e-12) {
                x = nx;
                y = ny;
                break;
            }
            x = nx;
            y = ny;
        }
        return new double[] {x, y, 1.0};
    }

    private double[] fitIntrinsics() {
        double[] k = params.k();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double cx : new double[] {0, params.imageWidth() - 1.0}) {
            for (double cy : new double[] {0, params.imageHeight() - 1.0}) {
                for (double[][] rr : new double[][][] {r1, r2}) {
                    double[] ray = rayFromRawPixel(cx, cy);
                    double[] p = mul(rr, ray);
                    minX = Math.min(minX, p[0] / p[2]);
                    maxX = Math.max(maxX, p[0] / p[2]);
                    minY = Math.min(minY, p[1] / p[2]);
                    maxY = Math.max(maxY, p[1] / p[2]);
                }
            }
        }
        double f = Math.min((params.imageWidth() - 1) / (maxX - minX),
                (params.imageHeight() - 1) / (maxY - minY));
        return new double[] {f, f,
                -f * 0.5 * (maxX + minX) + 0.5 * (params.imageWidth() - 1),
                -f * 0.5 * (maxY + minY) + 0.5 * (params.imageHeight() - 1)};
    }

    private static double[][] rodrigues(double rx, double ry, double rz) {
        double theta = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (theta < 1e-14) {
            return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        }
        double x = rx / theta, y = ry / theta, z = rz / theta;
        double c = Math.cos(theta), s = Math.sin(theta), v = 1 - c;
        return new double[][] {
                {c + x * x * v, x * y * v - z * s, x * z * v + y * s},
                {y * x * v + z * s, c + y * y * v, y * z * v - x * s},
                {z * x * v - y * s, z * y * v + x * s, c + z * z * v}};
    }

    private static double[] rotationVector(double[][] m) {
        double cosTheta = (m[0][0] + m[1][1] + m[2][2] - 1) / 2;
        cosTheta = Math.max(-1, Math.min(1, cosTheta));
        double theta = Math.acos(cosTheta);
        if (theta < 1e-10) {
            return new double[] {(m[2][1] - m[1][2]) / 2, (m[0][2] - m[2][0]) / 2,
                    (m[1][0] - m[0][1]) / 2};
        }
        double f = theta / (2 * Math.sin(theta));
        return new double[] {f * (m[2][1] - m[1][2]), f * (m[0][2] - m[2][0]),
                f * (m[1][0] - m[0][1])};
    }

    private static double[] mul(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double n = norm(v);
        return new double[] {v[0] / n, v[1] / n, v[2] / n};
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
