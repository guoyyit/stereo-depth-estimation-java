package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Raised when a single frame fails to process. The failure is isolated
 * to that frame and leaves no partial state behind.
 */
public class StereoProcessingException extends RuntimeException {
    public StereoProcessingException(String message) {
        super(message);
    }

    public StereoProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
