package com.github.guoyyit.stereodepthestimationjava.error;

public class CalibrationVersionException extends StereoPipelineException {

    /**
     * Machine-distinguishable failure kinds for calibration version handling.
     */
    public enum Reason {
        /** The requested version string was null or blank. */
        MISSING_VERSION,
        /** No calibration version has ever been registered. */
        NO_REGISTERED_VERSION,
        /** The requested version was never registered. */
        UNKNOWN_VERSION,
        /** The version is already registered with different content (immutable). */
        CONTENT_CONFLICT
    }

    private final Reason reason;

    public CalibrationVersionException(String message) {
        this(message, null);
    }

    public CalibrationVersionException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    /**
     * The distinguishable failure kind, or {@code null} when raised through the
     * legacy message-only constructor.
     */
    public Reason reason() {
        return reason;
    }
}
