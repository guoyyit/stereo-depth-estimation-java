package com.github.guoyyit.stereodepthestimationjava.model;

public final class PointCloud {

    private final float[] xyz;
    private final String calibrationVersion;

    public PointCloud(float[] xyz, String calibrationVersion) {
        this.xyz = xyz;
        this.calibrationVersion = calibrationVersion;
    }

    public float[] xyz() { return xyz; }

    public int pointCount() {
        int count = 0;
        for (int i = 0; i < xyz.length; i += 3) {
            if (!Float.isNaN(xyz[i])) {
                count++;
            }
        }
        return count;
    }

    public String calibrationVersion() { return calibrationVersion; }
}
