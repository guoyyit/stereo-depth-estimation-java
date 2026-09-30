package com.github.guoyyit.stereodepthestimationjava.stereo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable stereo calibration parameter set, tagged with a version id.
 *
 * <p>All matrices are row-major. {@code kLeft}/{@code kRight} are 3x3
 * intrinsic matrices, {@code distLeft}/{@code distRight} are radial
 * distortion coefficients [k1, k2] (tangential distortion unsupported),
 * {@code rectLeft}/{@code rectRight} are 3x3 rectification rotations,
 * {@code baselineMeters} is the distance between the two optical centres
 * and {@code focalPixels} the rectified focal length in pixels.</p>
 */
public final class CalibrationParams {
    private final String version;
    private final double[] kLeft;
    private final double[] kRight;
    private final double[] distLeft;
    private final double[] distRight;
    private final double[] rectLeft;
    private final double[] rectRight;
    private final double baselineMeters;
    private final double focalPixels;

    public CalibrationParams(String version,
                             double[] kLeft,
                             double[] kRight,
                             double[] distLeft,
                             double[] distRight,
                             double[] rectLeft,
                             double[] rectRight,
                             double baselineMeters,
                             double focalPixels) {
        if (version == null || version.isBlank()) {
            throw new CalibrationException("calibration version must be a non-empty string");
        }
        requireMatrix("kLeft", kLeft, 9);
        requireMatrix("kRight", kRight, 9);
        requireMatrix("rectLeft", rectLeft, 9);
        requireMatrix("rectRight", rectRight, 9);
        if (distLeft == null || distLeft.length != 2 || distRight == null || distRight.length != 2) {
            throw new CalibrationException("distortion arrays must hold exactly [k1, k2]");
        }
        if (!(baselineMeters > 0.0) || !(focalPixels > 0.0)) {
            throw new CalibrationException("baseline and focal length must be positive");
        }
        this.version = version;
        this.kLeft = kLeft.clone();
        this.kRight = kRight.clone();
        this.distLeft = distLeft.clone();
        this.distRight = distRight.clone();
        this.rectLeft = rectLeft.clone();
        this.rectRight = rectRight.clone();
        this.baselineMeters = baselineMeters;
        this.focalPixels = focalPixels;
    }

    private static void requireMatrix(String name, double[] m, int n) {
        if (m == null || m.length != n) {
            throw new CalibrationException(name + " must be a " + n + "-element row-major matrix");
        }
    }

    public String version() {
        return version;
    }

    public double[] kLeft() {
        return kLeft.clone();
    }

    public double[] kRight() {
        return kRight.clone();
    }

    public double[] distLeft() {
        return distLeft.clone();
    }

    public double[] distRight() {
        return distRight.clone();
    }

    public double[] rectLeft() {
        return rectLeft.clone();
    }

    public double[] rectRight() {
        return rectRight.clone();
    }

    public double baselineMeters() {
        return baselineMeters;
    }

    public double focalPixels() {
        return focalPixels;
    }

    /**
     * Ideal pinhole calibration with identity rectification and no
     * distortion; convenient for synthetic data.
     */
    public static CalibrationParams ideal(String version, double fx, double cx, double cy,
                                          double baselineMeters) {
        double[] k = {fx, 0, cx, 0, fx, cy, 0, 0, 1};
        double[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double[] zeroDist = {0, 0};
        return new CalibrationParams(version, k, k.clone(), zeroDist, zeroDist.clone(),
                identity, identity.clone(), baselineMeters, fx);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CalibrationParams other)) {
            return false;
        }
        return version.equals(other.version)
                && Arrays.equals(kLeft, other.kLeft)
                && Arrays.equals(kRight, other.kRight)
                && Arrays.equals(distLeft, other.distLeft)
                && Arrays.equals(distRight, other.distRight)
                && Arrays.equals(rectLeft, other.rectLeft)
                && Arrays.equals(rectRight, other.rectRight)
                && baselineMeters == other.baselineMeters
                && focalPixels == other.focalPixels;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, Arrays.hashCode(kLeft), Arrays.hashCode(kRight),
                Arrays.hashCode(distLeft), Arrays.hashCode(distRight),
                Arrays.hashCode(rectLeft), Arrays.hashCode(rectRight),
                baselineMeters, focalPixels);
    }
}
