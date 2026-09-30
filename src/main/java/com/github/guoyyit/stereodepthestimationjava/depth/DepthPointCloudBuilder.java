package com.github.guoyyit.stereodepthestimationjava.depth;

import com.github.guoyyit.stereodepthestimationjava.model.DepthMap;
import com.github.guoyyit.stereodepthestimationjava.model.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.PointCloud;

public final class DepthPointCloudBuilder {

    private final float focalLengthPixels;
    private final float baselineMeters;

    public DepthPointCloudBuilder(float focalLengthPixels, float baselineMeters) {
        if (focalLengthPixels <= 0f || baselineMeters <= 0f) {
            throw new IllegalArgumentException("focal length and baseline must be positive");
        }
        this.focalLengthPixels = focalLengthPixels;
        this.baselineMeters = baselineMeters;
    }

    public DepthMap toDepth(DisparityMap disparity, String calibrationVersion) {
        int pixels = disparity.width() * disparity.height();
        float[] depth = new float[pixels];
        InvalidReason[] reasons = new InvalidReason[pixels];
        for (int i = 0; i < pixels; i++) {
            InvalidReason reason = disparity.reasons()[i];
            reasons[i] = reason;
            if (reason == InvalidReason.VALID) {
                float d = disparity.disparity()[i];
                depth[i] = d > 0f ? focalLengthPixels * baselineMeters / d : Float.NaN;
                if (!(d > 0f)) {
                    reasons[i] = InvalidReason.BORDER;
                }
            } else {
                depth[i] = Float.NaN;
            }
        }
        return new DepthMap(disparity.width(), disparity.height(), depth, reasons,
                calibrationVersion);
    }

    public PointCloud toPointCloud(DisparityMap disparity, double[] rectifiedK,
                                   String calibrationVersion) {
        double fx = rectifiedK[0];
        double fy = rectifiedK[1];
        double cx = rectifiedK[2];
        double cy = rectifiedK[3];
        float[] xyz = new float[disparity.width() * disparity.height() * 3];
        for (int y = 0; y < disparity.height(); y++) {
            for (int x = 0; x < disparity.width(); x++) {
                int pixelIndex = y * disparity.width() + x;
                int coordIndex = pixelIndex * 3;
                if (disparity.reasons()[pixelIndex] != InvalidReason.VALID) {
                    xyz[coordIndex] = Float.NaN;
                    xyz[coordIndex + 1] = Float.NaN;
                    xyz[coordIndex + 2] = Float.NaN;
                    continue;
                }
                float d = disparity.disparity()[pixelIndex];
                if (!(d > 0f)) {
                    xyz[coordIndex] = Float.NaN;
                    xyz[coordIndex + 1] = Float.NaN;
                    xyz[coordIndex + 2] = Float.NaN;
                    continue;
                }
                float z = (float) (fx * baselineMeters / d);
                xyz[coordIndex] = (float) ((x - cx) * z / fx);
                xyz[coordIndex + 1] = (float) ((y - cy) * z / fy);
                xyz[coordIndex + 2] = z;
            }
        }
        return new PointCloud(xyz, calibrationVersion);
    }
}
