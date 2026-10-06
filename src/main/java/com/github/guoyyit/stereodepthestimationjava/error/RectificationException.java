package com.github.guoyyit.stereodepthestimationjava.error;

/**
 * Thrown when rectification geometry cannot be built within the documented accuracy
 * bounds for the given calibration (e.g. degenerate baseline, projection behind the
 * camera, non-converging distortion inversion, non-finite maps, or an internal
 * forward/inverse residual above the supported bound). Distinct from
 * {@link CalibrationVersionException} (versioning problems) and
 * {@link InvalidFrameException} (bad frame payloads).
 */
public class RectificationException extends StereoPipelineException {

    public RectificationException(String message) {
        super(message);
    }
}
