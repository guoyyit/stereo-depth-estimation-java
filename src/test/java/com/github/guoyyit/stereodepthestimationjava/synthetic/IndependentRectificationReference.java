package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;

/**
 * Independent reference implementation of the rectified-to-raw mapping, used only
 * by tests to generate synthetic raw images and to cross-check the production
 * rectification maps. All rotation algebra is done with quaternions and the
 * undistortion uses a Newton solver (see {@link IndependentCameraModel}), so no
 * mapping computation is shared with the production {@code RectificationEngine}.
 *
 * <p>The rectified intrinsics (focal/principal point) are a design degree of
 * freedom of any rectification; they are taken as given parameters here. What this
 * reference re-derives independently is the geometric mapping itself: half-rotation
 * decomposition, epipolar frame construction, distortion application.</p>
 */
public final class IndependentRectificationReference {

    private final IndependentCameraModel camera;
    private final double[] k;
    private final double fNew;
    private final double cxNew;
    private final double cyNew;
    private final double[] qRect;
    private final double[] qHalf;

    public IndependentRectificationReference(CalibrationParams params, double[] rectifiedK) {
        this.camera = new IndependentCameraModel(params);
        this.k = params.k();
        this.fNew = rectifiedK[0];
        this.cxNew = rectifiedK[2];
        this.cyNew = rectifiedK[3];

        double[] r = params.r();
        double[] t = params.t();
        this.qHalf = axisAngleToQuaternion(r[0] / 2.0, r[1] / 2.0, r[2] / 2.0);

        // e1 || R(-om/2) * t so that the rectified camera-2 center lies on the
        // rectified x axis (see the derivation note in RectificationEngine).
        double[] tNew = quatRotate(quatConjugate(qHalf), t);
        double tNorm = Math.sqrt(tNew[0] * tNew[0] + tNew[1] * tNew[1] + tNew[2] * tNew[2]);
        double[] e1 = {tNew[0] / tNorm, tNew[1] / tNorm, tNew[2] / tNorm};
        double[] e2 = {-e1[1], e1[0], 0.0};
        double e2Norm = Math.sqrt(e2[0] * e2[0] + e2[1] * e2[1]);
        if (e2Norm < 1e-12) {
            e2 = new double[] {0.0, -e1[2], e1[1]};
            e2Norm = Math.sqrt(e2[0] * e2[0] + e2[1] * e2[1] + e2[2] * e2[2]);
        }
        e2[0] /= e2Norm; e2[1] /= e2Norm; e2[2] /= e2Norm;
        double[] e3 = {
                e1[1] * e2[2] - e1[2] * e2[1],
                e1[2] * e2[0] - e1[0] * e2[2],
                e1[0] * e2[1] - e1[1] * e2[0]
        };
        this.qRect = basisToQuaternion(e1, e2, e3);
    }

    /** Raw left-image coordinates sampled by rectified-left pixel (rx, ry). */
    public double[] mapLeft(double rx, double ry) {
        return map(rx, ry, quatMul(qRect, qHalf));
    }

    /** Raw right-image coordinates sampled by rectified-right pixel (rx, ry). */
    public double[] mapRight(double rx, double ry) {
        return map(rx, ry, quatMul(qRect, quatConjugate(qHalf)));
    }

    private double[] map(double rx, double ry, double[] qSide) {
        double[] rayRect = {(rx - cxNew) / fNew, (ry - cyNew) / fNew, 1.0};
        double[] rayCam = quatRotate(quatConjugate(qSide), rayRect);
        if (rayCam[2] <= 1e-12) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double[] distorted = camera.distort(rayCam[0] / rayCam[2], rayCam[1] / rayCam[2]);
        return new double[] {k[0] * distorted[0] + k[2], k[4] * distorted[1] + k[5]};
    }

    /** Rectified-left coordinates of an undistorted left-camera ray, for tests. */
    public double[] rectifiedLeftOfRay(double[] rayCam) {
        double[] rayRect = quatRotate(quatMul(qRect, qHalf), rayCam);
        return new double[] {
                fNew * rayRect[0] / rayRect[2] + cxNew,
                fNew * rayRect[1] / rayRect[2] + cyNew
        };
    }

    /** Rectified-right coordinates of an undistorted right-camera ray, for tests. */
    public double[] rectifiedRightOfRay(double[] rayCam) {
        double[] rayRect = quatRotate(quatMul(qRect, quatConjugate(qHalf)), rayCam);
        return new double[] {
                fNew * rayRect[0] / rayRect[2] + cxNew,
                fNew * rayRect[1] / rayRect[2] + cyNew
        };
    }

    static double[] axisAngleToQuaternion(double rx, double ry, double rz) {
        double theta = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (theta < 1e-15) {
            return new double[] {1.0, 0.0, 0.0, 0.0};
        }
        double half = theta / 2.0;
        double s = Math.sin(half) / theta;
        return new double[] {Math.cos(half), rx * s, ry * s, rz * s};
    }

    static double[] quatMul(double[] a, double[] b) {
        return new double[] {
                a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
                a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
                a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
                a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        };
    }

    static double[] quatConjugate(double[] q) {
        return new double[] {q[0], -q[1], -q[2], -q[3]};
    }

    static double[] quatRotate(double[] q, double[] v) {
        double[] qv = {0.0, v[0], v[1], v[2]};
        double[] rotated = quatMul(quatMul(q, qv), quatConjugate(q));
        return new double[] {rotated[1], rotated[2], rotated[3]};
    }

    private static double[] basisToQuaternion(double[] e1, double[] e2, double[] e3) {
        double m00 = e1[0], m01 = e1[1], m02 = e1[2];
        double m10 = e2[0], m11 = e2[1], m12 = e2[2];
        double m20 = e3[0], m21 = e3[1], m22 = e3[2];
        double trace = m00 + m11 + m22;
        double w, x, y, z;
        if (trace > 0.0) {
            double s = Math.sqrt(trace + 1.0) * 2.0;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        return new double[] {w / n, x / n, y / n, z / n};
    }
}
