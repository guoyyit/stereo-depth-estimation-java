package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Reason why a pixel in a disparity/depth map is invalid.
 * {@link #VALID} means the pixel carries a usable measurement.
 */
public enum InvalidReason {
    VALID,
    OCCLUSION,
    TEXTURELESS,
    OUT_OF_RANGE,
    BORDER
}
