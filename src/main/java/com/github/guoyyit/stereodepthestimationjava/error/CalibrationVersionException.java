package com.github.guoyyit.stereodepthestimationjava.error;

public class CalibrationVersionException extends StereoPipelineException {

    /**
     * Distinguishable failure categories for calibration version handling.
     */
    public enum Kind {
        /** No version was supplied, the version was blank, or nothing is registered at all. */
        VERSION_MISSING,
        /** A version was supplied but no calibration content is registered under it. */
        VERSION_UNKNOWN,
        /** The version is already registered with different content (immutability violation). */
        CONTENT_CONFLICT,
        /** Category not specified (legacy constructor). */
        UNSPECIFIED
    }

    private final Kind kind;

    public CalibrationVersionException(String message) {
        this(Kind.UNSPECIFIED, message);
    }

    public CalibrationVersionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
