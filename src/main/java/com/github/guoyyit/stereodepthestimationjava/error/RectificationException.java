package com.github.guoyyit.stereodepthestimationjava.error;

/**
 * Raised when rectification maps cannot be built within the documented accuracy
 * bounds. Never silently emits degraded or non-finite geometry.
 */
public class RectificationException extends StereoPipelineException {

    public enum Reason {
        /** Distortion/rotation/baseline outside the documented supported range. */
        OUT_OF_SUPPORTED_RANGE,
        /** Stereo baseline too short to define rectification geometry. */
        DEGENERATE_BASELINE,
        /** Rectification would project points behind the camera. */
        BEHIND_CAMERA,
        /** Undistortion solver failed to converge within tolerance. */
        SOLVER_DIVERGED,
        /** Produced map contains non-finite entries. */
        NON_FINITE_MAP,
        /** Self-check reprojection residual exceeds the documented bound. */
        RESIDUAL_EXCEEDS_BOUND
    }

    private final Reason reason;

    public RectificationException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
