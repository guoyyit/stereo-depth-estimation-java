package com.github.guoyyit.stereodepthestimationjava.synthetic;

import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;

public final class TestCalibrations {

    private TestCalibrations() {
    }

    public static CalibrationParams v1(int width, int height) {
        double fx = width * 1.1;
        double fy = fx;
        double cx = width / 2.0;
        double cy = height / 2.0;
        double[] k = {fx, 0, cx, 0, fy, cy, 0, 0, 1};
        double[] d = {-0.03, 0.005, 0.0002, -0.0002, 0.0};
        double[] r = {0.004, -0.003, 0.002};
        double baseline = 0.12;
        double[] t = {-fx * baseline, 0, 0};
        return CalibrationParams.create("calib-v1", width, height, k, d, r, t);
    }

    public static CalibrationParams v2(int width, int height) {
        double fx = width * 1.15;
        double fy = fx;
        double cx = width / 2.0 + 2;
        double cy = height / 2.0 - 1;
        double[] k = {fx, 0, cx, 0, fy, cy, 0, 0, 1};
        double[] d = {-0.03, 0.01, 0.0005, 0.0008, 0.0};
        double[] r = {0.02, 0.01, -0.01};
        double baseline = 0.115;
        double[] t = {-fx * baseline, 0, 0};
        return CalibrationParams.create("calib-v2", width, height, k, d, r, t);
    }

    /** Strong radial+tangential distortion, still inside the documented supported range. */
    public static CalibrationParams strongDistortion(int width, int height) {
        double fx = width * 1.1;
        double fy = fx;
        double cx = width / 2.0;
        double cy = height / 2.0;
        double[] k = {fx, 0, cx, 0, fy, cy, 0, 0, 1};
        double[] d = {-0.28, 0.15, 0.008, -0.006, -0.05};
        double[] r = {0.01, -0.008, 0.006};
        double baseline = 0.12;
        double[] t = {-fx * baseline, 0, 0};
        return CalibrationParams.create("calib-strong-d", width, height, k, d, r, t);
    }

    /** Strong inter-camera rotation, still inside the documented supported range. */
    public static CalibrationParams strongRotation(int width, int height) {
        double fx = width * 1.1;
        double fy = fx;
        double cx = width / 2.0;
        double cy = height / 2.0;
        double[] k = {fx, 0, cx, 0, fy, cy, 0, 0, 1};
        double[] d = {-0.05, 0.02, 0.001, 0.001, 0.0};
        double[] r = {0.12, -0.18, 0.08};
        double baseline = 0.12;
        double[] t = {-fx * baseline, 0.002 * fx, 0.001 * fx};
        return CalibrationParams.create("calib-strong-r", width, height, k, d, r, t);
    }
}
