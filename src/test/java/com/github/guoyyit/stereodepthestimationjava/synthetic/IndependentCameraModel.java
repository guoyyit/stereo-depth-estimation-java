package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;

/**
 * Independent reference camera model used only by tests. It re-derives every
 * geometric quantity from the calibration parameters with its own code path
 * (quaternion-based rotations, Newton undistortion with an analytic Jacobian),
 * sharing no mapping computation with the production rectification code, so that
 * reprojection residuals measured against it are genuine cross-checks.
 *
 * <p>Convention (same as the calibration contract): the world frame is the left
 * camera frame; a world point P is seen by the right camera at R*P + t where R is
 * the rotation of the calibration rotation vector and t the calibration
 * translation. Both cameras share the intrinsic matrix K and Brown-Conrady
 * distortion (k1,k2,p1,p2,k3).</p>
 */
public final class IndependentCameraModel {

    private final double[] k;
    private final double[] d;
    private final double[] r;
    private final double[] t;

    public IndependentCameraModel(CalibrationParams params) {
        this.k = params.k();
        this.d = params.d();
        this.r = params.r();
        this.t = params.t();
    }

    /** Projects a world-frame 3D point into the left raw image. */
    public double[] projectLeft(double px, double py, double pz) {
        return projectCamera(px, py, pz);
    }

    /** Projects a world-frame 3D point into the right raw image. */
    public double[] projectRight(double px, double py, double pz) {
        double[] rotated = rotate(r, new double[] {px, py, pz});
        return projectCamera(rotated[0] + t[0], rotated[1] + t[1], rotated[2] + t[2]);
    }

    /**
     * Undistorted normalized ray (unit z) of a left raw pixel, expressed in the
     * left camera frame. Uses Newton iterations with an analytic Jacobian.
     */
    public double[] undistortedRayLeft(double pixelX, double pixelY) {
        double xd = (pixelX - k[2]) / k[0];
        double yd = (pixelY - k[5]) / k[4];
        return undistortNewton(xd, yd);
    }

    /** Undistorted normalized ray of a right raw pixel, in the right camera frame. */
    public double[] undistortedRayRight(double pixelX, double pixelY) {
        return undistortedRayLeft(pixelX, pixelY);
    }

    /** Transforms a direction/point from the left (world) frame to the right frame. */
    public double[] leftToRightRotation(double[] v) {
        return rotate(r, v);
    }

    public double[] translation() {
        return t.clone();
    }

    public double[] intrinsics() {
        return k.clone();
    }

    private double[] projectCamera(double x, double y, double z) {
        double xn = x / z;
        double yn = y / z;
        double[] distorted = distort(xn, yn);
        return new double[] {
                k[0] * distorted[0] + k[2],
                k[4] * distorted[1] + k[5]
        };
    }

    /** Brown-Conrady forward distortion, independently implemented. */
    public double[] distort(double x, double y) {
        double r2 = x * x + y * y;
        double r4 = r2 * r2;
        double r6 = r4 * r2;
        double radial = 1.0 + d[0] * r2 + d[1] * r4 + d[4] * r6;
        double xd = x * radial + 2.0 * d[2] * x * y + d[3] * (r2 + 2.0 * x * x);
        double yd = y * radial + d[2] * (r2 + 2.0 * y * y) + 2.0 * d[3] * x * y;
        return new double[] {xd, yd};
    }

    /** Newton-Raphson undistortion with analytic Jacobian (independent solver). */
    private double[] undistortNewton(double xd, double yd) {
        double x = xd;
        double y = yd;
        for (int i = 0; i < 50; i++) {
            double[] f = distort(x, y);
            double ex = f[0] - xd;
            double ey = f[1] - yd;
            if (Math.abs(ex) < 1e-13 && Math.abs(ey) < 1e-13) {
                break;
            }
            double r2 = x * x + y * y;
            double r4 = r2 * r2;
            double radial = 1.0 + d[0] * r2 + d[1] * r4 + d[4] * r4 * r2;
            double dRadialDx = 2.0 * d[0] * x + 4.0 * d[1] * r2 * x + 6.0 * d[4] * r4 * x;
            double dRadialDy = 2.0 * d[0] * y + 4.0 * d[1] * r2 * y + 6.0 * d[4] * r4 * y;
            double j00 = radial + x * dRadialDx + 2.0 * d[2] * y + 6.0 * d[3] * x;
            double j01 = x * dRadialDy + 2.0 * d[2] * x + 2.0 * d[3] * y;
            double j10 = y * dRadialDx + 2.0 * d[2] * x + 2.0 * d[3] * y;
            double j11 = radial + y * dRadialDy + 6.0 * d[2] * y + 2.0 * d[3] * x;
            double det = j00 * j11 - j01 * j10;
            if (Math.abs(det) < 1e-18) {
                break;
            }
            x -= (j11 * ex - j01 * ey) / det;
            y -= (-j10 * ex + j00 * ey) / det;
        }
        return new double[] {x, y, 1.0};
    }

    /** Quaternion-based axis-angle rotation, independent of the production code. */
    public static double[] rotate(double[] rotationVector, double[] v) {
        double theta = Math.sqrt(rotationVector[0] * rotationVector[0]
                + rotationVector[1] * rotationVector[1]
                + rotationVector[2] * rotationVector[2]);
        if (theta < 1e-15) {
            return v.clone();
        }
        double ax = rotationVector[0] / theta;
        double ay = rotationVector[1] / theta;
        double az = rotationVector[2] / theta;
        double half = theta / 2.0;
        double qw = Math.cos(half);
        double qx = ax * Math.sin(half);
        double qy = ay * Math.sin(half);
        double qz = az * Math.sin(half);
        // q * (0,v) * q^-1 expanded:
        double ux = qy * v[2] - qz * v[1];
        double uy = qz * v[0] - qx * v[2];
        double uz = qx * v[1] - qy * v[0];
        return new double[] {
                v[0] + 2.0 * (qw * ux + qy * uz - qz * uy),
                v[1] + 2.0 * (qw * uy + qz * ux - qx * uz),
                v[2] + 2.0 * (qw * uz + qx * uy - qy * ux)
        };
    }
}
