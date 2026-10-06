package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;

/**
 * Renders a synthetic stereo scene purely by ray casting through the
 * {@link IndependentStereoModel}: every raw pixel is formed by undistorting the pixel
 * into a camera ray, intersecting the 3D scene and evaluating a procedural texture at
 * the hit point. No code or map output of the production rectification pipeline is
 * used, so accuracy conclusions drawn on this data cannot be circular. Ground-truth
 * disparity is produced in the rectified pixel grid defined by the independent
 * rectification model (which the geometry tests cross-check against the engine).
 */
public final class IndependentSceneRenderer {

    public record RenderedScene(GrayscaleImage leftRaw, GrayscaleImage rightRaw,
                                float[] groundTruthDisparity, InvalidReason[] groundTruthReasons,
                                CalibrationParams calibration) {
    }

    private record Panel(double z, double xmin, double xmax, double ymin, double ymax) {
        boolean contains(double x, double y) {
            return x >= xmin && x <= xmax && y >= ymin && y <= ymax;
        }
    }

    /** Depths chosen so ground-truth disparities stay within [8, 26] px. */
    private static final Panel BACKGROUND = new Panel(480.0,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    private static final Panel MID_PANEL = new Panel(260.0, -190.0, 150.0, -110.0, 120.0);
    private static final Panel NEAR_PANEL = new Panel(170.0, -60.0, 80.0, -40.0, 90.0);
    private static final Panel[] PANELS = {NEAR_PANEL, MID_PANEL, BACKGROUND};

    /** Textureless 3D rectangle on the mid panel (constant gray). */
    private static final double[] TEXTURELESS_RECT = {-150.0, -60.0, 20.0, 90.0};

    private IndependentSceneRenderer() {
    }

    public static RenderedScene render(CalibrationParams calibration) {
        IndependentStereoModel model = new IndependentStereoModel(calibration);
        int width = calibration.imageWidth();
        int height = calibration.imageHeight();
        double[] k = calibration.k();
        double[] r = calibration.r();
        double[] t = calibration.t();
        double[][] rotation = rodrigues(r[0], r[1], r[2]);
        double[][] rotationT = transpose(rotation);
        double[] rightCenter = mul(rotationT, new double[] {-t[0], -t[1], -t[2]});

        float[] leftRaw = new float[width * height];
        float[] rightRaw = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] rayLeft = model.rayFromRawPixel(x, y);
                double[] hitLeft = intersect(new double[] {0, 0, 0}, rayLeft);
                leftRaw[y * width + x] = texture(hitLeft);

                double[] rayRightLocal = model.rayFromRawPixel(x, y);
                double[] rayRight = mul(rotationT, rayRightLocal);
                double[] hitRight = intersect(rightCenter, rayRight);
                rightRaw[y * width + x] = texture(hitRight);
            }
        }
        leftRaw = boxBlur(width, height, leftRaw);
        rightRaw = boxBlur(width, height, rightRaw);

        double[] kNew = model.rectifiedK();
        double[][] r1 = model.rectifyingRotationLeft();
        double[][] r1T = transpose(r1);
        double baseline = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        float[] gtDisparity = new float[width * height];
        InvalidReason[] gtReasons = new InvalidReason[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double nx = (x - kNew[2]) / kNew[0];
                double ny = (y - kNew[3]) / kNew[1];
                double[] rayRect = mul(r1T, new double[] {nx, ny, 1.0});
                double[] hit = intersect(new double[] {0, 0, 0}, rayRect);
                double zRect = mul(r1, hit)[2];
                gtDisparity[i] = (float) (kNew[0] * baseline / zRect);
                if (insideTextureless(hit)) {
                    gtReasons[i] = InvalidReason.TEXTURELESS;
                } else if (!visibleFrom(hit, rightCenter)) {
                    gtReasons[i] = InvalidReason.OCCLUDED;
                } else {
                    gtReasons[i] = InvalidReason.VALID;
                }
            }
        }
        return new RenderedScene(new GrayscaleImage(width, height, leftRaw),
                new GrayscaleImage(width, height, rightRaw), gtDisparity, gtReasons,
                calibration);
    }

    private static double[] intersect(double[] origin, double[] ray) {
        double bestS = Double.POSITIVE_INFINITY;
        double[] best = null;
        for (Panel panel : PANELS) {
            if (Math.abs(ray[2]) < 1e-12) {
                continue;
            }
            double s = (panel.z() - origin[2]) / ray[2];
            if (s <= 0 || s >= bestS) {
                continue;
            }
            double hx = origin[0] + s * ray[0];
            double hy = origin[1] + s * ray[1];
            if (panel.contains(hx, hy)) {
                bestS = s;
                best = new double[] {hx, hy, panel.z()};
            }
        }
        if (best == null) {
            // Rays that miss every finite panel land on the background plane.
            double s = (BACKGROUND.z() - origin[2]) / ray[2];
            best = new double[] {origin[0] + s * ray[0], origin[1] + s * ray[1], BACKGROUND.z()};
        }
        return best;
    }

    private static boolean visibleFrom(double[] point, double[] cameraCenter) {
        double[] toPoint = {point[0] - cameraCenter[0], point[1] - cameraCenter[1],
                point[2] - cameraCenter[2]};
        double dist = Math.sqrt(toPoint[0] * toPoint[0] + toPoint[1] * toPoint[1]
                + toPoint[2] * toPoint[2]);
        double[] dir = {toPoint[0] / dist, toPoint[1] / dist, toPoint[2] / dist};
        for (Panel panel : PANELS) {
            if (Math.abs(dir[2]) < 1e-12) {
                continue;
            }
            double s = (panel.z() - cameraCenter[2]) / dir[2];
            if (s <= 0 || s >= dist - 1e-6) {
                continue;
            }
            double hx = cameraCenter[0] + s * dir[0];
            double hy = cameraCenter[1] + s * dir[1];
            if (panel.contains(hx, hy)) {
                double hz = panel.z();
                double dHit = Math.abs(hz - cameraCenter[2]) / Math.abs(dir[2]);
                if (dHit < dist - 1e-3) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean insideTextureless(double[] point) {
        return point[0] >= TEXTURELESS_RECT[0] && point[0] <= TEXTURELESS_RECT[1]
                && point[1] >= TEXTURELESS_RECT[2] && point[1] <= TEXTURELESS_RECT[3]
                && Math.abs(point[2] - MID_PANEL.z()) < 1e-6;
    }

    private static float texture(double[] point) {
        if (insideTextureless(point)) {
            return 128f;
        }
        double x = point[0];
        double y = point[1];
        double v = 128.0
                + 38.0 * Math.sin(x * 0.045 + 0.7 * Math.cos(y * 0.06))
                + 26.0 * Math.sin((x + 1.7 * y) * 0.035 + 1.1)
                + 18.0 * Math.sin(y * 0.09 + 2.1 * Math.sin(x * 0.02));
        return (float) Math.max(0.0, Math.min(255.0, v));
    }

    private static float[] boxBlur(int width, int height, float[] input) {
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(width - 1, Math.max(0, x + dx));
                        int yy = Math.min(height - 1, Math.max(0, y + dy));
                        sum += input[yy * width + xx];
                        count++;
                    }
                }
                output[y * width + x] = (float) (sum / count);
            }
        }
        return output;
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

    private static double[][] transpose(double[][] m) {
        return new double[][] {{m[0][0], m[1][0], m[2][0]},
                {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }

    private static double[] mul(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }
}
