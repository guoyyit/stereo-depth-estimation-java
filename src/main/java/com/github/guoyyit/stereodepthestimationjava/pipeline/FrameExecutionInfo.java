package com.github.guoyyit.stereodepthestimationjava.pipeline;

public final class FrameExecutionInfo {

    private final String frameId;
    private final String calibrationVersion;
    private final boolean success;
    private final boolean cancelled;
    private final String errorMessage;
    private final long durationMillis;

    public FrameExecutionInfo(String frameId, String calibrationVersion, boolean success,
                              boolean cancelled, String errorMessage, long durationMillis) {
        this.frameId = frameId;
        this.calibrationVersion = calibrationVersion;
        this.success = success;
        this.cancelled = cancelled;
        this.errorMessage = errorMessage;
        this.durationMillis = durationMillis;
    }

    public String frameId() { return frameId; }

    public String calibrationVersion() { return calibrationVersion; }

    public boolean success() { return success; }

    public boolean cancelled() { return cancelled; }

    public String errorMessage() { return errorMessage; }

    public long durationMillis() { return durationMillis; }
}
