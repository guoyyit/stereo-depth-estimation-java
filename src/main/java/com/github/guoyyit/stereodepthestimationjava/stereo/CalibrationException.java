package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Raised when calibration parameters are missing, unknown, or mixed
 * across versions within one processing unit.
 */
public class CalibrationException extends RuntimeException {
    public CalibrationException(String message) {
        super(message);
    }
}
