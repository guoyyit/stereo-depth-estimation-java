package com.github.guoyyit.stereodepthestimationjava.model;

import com.github.guoyyit.stereodepthestimationjava.cost.BorderMode;
import com.github.guoyyit.stereodepthestimationjava.cost.MatchingCostType;

public final class PipelineConfig {

    private final MatchingCostType matchingCostType;
    private final int windowRadius;
    private final int minDisparity;
    private final int numDisparities;
    private final BorderMode borderMode;
    private final int aggregationRadius;
    private final boolean leftRightCheck;
    private final float leftRightThreshold;
    private final boolean subpixelRefine;
    private final float textureThreshold;
    private final float resolutionScale;
    private final int maxFrameHistory;
    private final float uniquenessRatio;

    public PipelineConfig(MatchingCostType matchingCostType, int windowRadius, int minDisparity,
                          int numDisparities, BorderMode borderMode, int aggregationRadius,
                          boolean leftRightCheck, float leftRightThreshold, boolean subpixelRefine,
                          float textureThreshold, float resolutionScale, int maxFrameHistory,
                          float uniquenessRatio) {
        if (matchingCostType == null || borderMode == null) {
            throw new IllegalArgumentException("matching cost type and border mode must not be null");
        }
        if (windowRadius < 1 || windowRadius > 31) {
            throw new IllegalArgumentException("window radius must be within [1, 31]");
        }
        if (minDisparity < 0 || numDisparities < 8 || numDisparities > 512) {
            throw new IllegalArgumentException(
                    "min disparity must be >= 0 and num disparities must be within [8, 512]");
        }
        if (aggregationRadius < 0 || aggregationRadius > 31) {
            throw new IllegalArgumentException("aggregation radius must be within [0, 31]");
        }
        if (leftRightThreshold <= 0f || leftRightThreshold > 8f) {
            throw new IllegalArgumentException("left-right threshold must be within (0, 8] pixels");
        }
        if (textureThreshold < 0f || textureThreshold > 128f) {
            throw new IllegalArgumentException("texture threshold must be within [0, 128] gray levels");
        }
        if (resolutionScale < 0.25f || resolutionScale > 1.0f) {
            throw new IllegalArgumentException("resolution scale must be within [0.25, 1.0]");
        }
        if (maxFrameHistory < 1 || maxFrameHistory > 100_000) {
            throw new IllegalArgumentException("max frame history must be within [1, 100000]");
        }
        if (uniquenessRatio < 0f || uniquenessRatio > 1.0f) {
            throw new IllegalArgumentException("uniqueness ratio must be within [0, 1]");
        }
        this.matchingCostType = matchingCostType;
        this.windowRadius = windowRadius;
        this.minDisparity = minDisparity;
        this.numDisparities = numDisparities;
        this.borderMode = borderMode;
        this.aggregationRadius = aggregationRadius;
        this.leftRightCheck = leftRightCheck;
        this.leftRightThreshold = leftRightThreshold;
        this.subpixelRefine = subpixelRefine;
        this.textureThreshold = textureThreshold;
        this.resolutionScale = resolutionScale;
        this.maxFrameHistory = maxFrameHistory;
        this.uniquenessRatio = uniquenessRatio;
    }

    public MatchingCostType matchingCostType() { return matchingCostType; }

    public int windowRadius() { return windowRadius; }

    public int minDisparity() { return minDisparity; }

    public int numDisparities() { return numDisparities; }

    public BorderMode borderMode() { return borderMode; }

    public int aggregationRadius() { return aggregationRadius; }

    public boolean leftRightCheck() { return leftRightCheck; }

    public float leftRightThreshold() { return leftRightThreshold; }

    public boolean subpixelRefine() { return subpixelRefine; }

    public float textureThreshold() { return textureThreshold; }

    public float resolutionScale() { return resolutionScale; }

    public int maxFrameHistory() { return maxFrameHistory; }

    public float uniquenessRatio() { return uniquenessRatio; }

    public static PipelineConfig defaults() {
        return new PipelineConfig(MatchingCostType.SAD, 2, 0, 64, BorderMode.BORDER_INVALID,
                0, true, 1.0f, true, 1.5f, 1.0f, 32, 0.05f);
    }
}
