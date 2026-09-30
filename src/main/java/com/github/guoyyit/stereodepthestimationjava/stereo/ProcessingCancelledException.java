package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Raised when a frame is cancelled while in flight. Cancellation is
 * isolated to that frame.
 */
public class ProcessingCancelledException extends RuntimeException {
    public ProcessingCancelledException(String message) {
        super(message);
    }
}
