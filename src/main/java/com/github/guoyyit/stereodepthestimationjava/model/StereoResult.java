package com.github.guoyyit.stereodepthestimationjava.model;

public final class StereoResult {

    private final String frameId;
    private final String calibrationVersion;
    private final DisparityMap disparityMap;
    private final DepthMap depthMap;
    private final PointCloud pointCloud;

    public StereoResult(String frameId, String calibrationVersion,
                        DisparityMap disparityMap, DepthMap depthMap, PointCloud pointCloud) {
        this.frameId = frameId;
        this.calibrationVersion = calibrationVersion;
        this.disparityMap = disparityMap;
        this.depthMap = depthMap;
        this.pointCloud = pointCloud;
    }

    public String frameId() { return frameId; }

    public String calibrationVersion() { return calibrationVersion; }

    public DisparityMap disparityMap() { return disparityMap; }

    public DepthMap depthMap() { return depthMap; }

    public PointCloud pointCloud() { return pointCloud; }
}
