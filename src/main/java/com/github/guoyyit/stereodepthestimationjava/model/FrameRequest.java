package com.github.guoyyit.stereodepthestimationjava.model;

public final class FrameRequest {

    private final String frameId;
    private final GrayscaleImage left;
    private final GrayscaleImage right;
    private final String requestedCalibrationVersion;

    public FrameRequest(String frameId, GrayscaleImage left, GrayscaleImage right,
                        String requestedCalibrationVersion) {
        this.frameId = frameId;
        this.left = left;
        this.right = right;
        this.requestedCalibrationVersion = requestedCalibrationVersion;
    }

    public String frameId() { return frameId; }

    public GrayscaleImage left() { return left; }

    public GrayscaleImage right() { return right; }

    public String requestedCalibrationVersion() { return requestedCalibrationVersion; }
}
