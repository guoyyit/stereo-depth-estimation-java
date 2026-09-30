package com.github.guoyyit.stereodepthestimationjava.stereo;

import java.util.Optional;

/**
 * Output of processing one stereo pair. Always carries the disparity
 * and depth maps plus the calibration version they were computed with;
 * the point cloud is optional.
 */
public record StereoResult(DisparityMap disparity,
                           DepthMap depth,
                           PointCloud pointCloud,
                           String calibrationVersion) {
    public StereoResult {
        if (disparity == null || depth == null) {
            throw new IllegalArgumentException("disparity and depth are required");
        }
        if (calibrationVersion == null || calibrationVersion.isBlank()) {
            throw new CalibrationException("result must be labelled with a calibration version");
        }
        if (!depth.calibrationVersion().equals(calibrationVersion)) {
            throw new CalibrationException("depth map version does not match result version");
        }
        if (pointCloud != null && !pointCloud.calibrationVersion().equals(calibrationVersion)) {
            throw new CalibrationException("point cloud version does not match result version");
        }
    }

    public Optional<PointCloud> pointCloudOptional() {
        return Optional.ofNullable(pointCloud);
    }
}
