package com.github.guoyyit.stereodepthestimationjava.stereo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional point-cloud output. Points are generated from valid depth
 * pixels only, in the rectified left camera frame. The cloud is tagged
 * with the calibration version that produced it.
 */
public final class PointCloud {
    public record Point(float x, float y, float z) {
    }

    private final List<Point> points;
    private final String calibrationVersion;

    private PointCloud(List<Point> points, String calibrationVersion) {
        this.points = Collections.unmodifiableList(points);
        this.calibrationVersion = calibrationVersion;
    }

    public static PointCloud fromDepth(DepthMap depth, CalibrationParams params) {
        if (!depth.calibrationVersion().equals(params.version())) {
            throw new CalibrationException("depth map version '" + depth.calibrationVersion()
                    + "' does not match calibration version '" + params.version() + "'");
        }
        double[] k = params.kLeft();
        double fx = k[0];
        double cx = k[2];
        double cy = k[5];
        List<Point> points = new ArrayList<>();
        for (int y = 0; y < depth.height(); y++) {
            for (int x = 0; x < depth.width(); x++) {
                if (!depth.isValid(x, y)) {
                    continue;
                }
                float z = depth.depth(x, y);
                float px = (float) ((x - cx) * z / fx);
                float py = (float) ((y - cy) * z / fx);
                points.add(new Point(px, py, z));
            }
        }
        return new PointCloud(points, params.version());
    }

    public List<Point> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public String calibrationVersion() {
        return calibrationVersion;
    }
}
