package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Immutable matcher configuration. See README for parameter semantics,
 * defaults and valid ranges; every value is validated on construction.
 */
public final class StereoConfig {
    public static final int DEFAULT_MIN_DISPARITY = 0;
    public static final int DEFAULT_NUM_DISPARITIES = 64;
    public static final int DEFAULT_BLOCK_RADIUS = 4;
    public static final MatchingCost DEFAULT_COST = MatchingCost.SAD;
    public static final AggregationStrategy DEFAULT_AGGREGATION = AggregationStrategy.BOX;
    public static final double DEFAULT_TEXTURE_THRESHOLD = 0.002;
    public static final double DEFAULT_LR_TOLERANCE = 1.0;
    public static final boolean DEFAULT_SUBPIXEL_ENABLED = true;
    public static final int DEFAULT_DOWNSAMPLE_FACTOR = 1;
    public static final long DEFAULT_RANDOM_SEED = 0L;

    private final int minDisparity;
    private final int numDisparities;
    private final int blockRadius;
    private final MatchingCost cost;
    private final AggregationStrategy aggregation;
    private final double textureThreshold;
    private final double lrTolerance;
    private final boolean subpixelEnabled;
    private final int downsampleFactor;
    private final long randomSeed;

    private StereoConfig(Builder b) {
        if (b.minDisparity < 0) {
            throw new IllegalArgumentException("minDisparity must be >= 0");
        }
        if (b.numDisparities < 1 || b.numDisparities > 256) {
            throw new IllegalArgumentException("numDisparities must be in [1, 256]");
        }
        if (b.blockRadius < 0 || b.blockRadius > 32) {
            throw new IllegalArgumentException("blockRadius must be in [0, 32]");
        }
        if (b.cost == null || b.aggregation == null) {
            throw new IllegalArgumentException("cost and aggregation must be set");
        }
        if (b.aggregation == AggregationStrategy.NONE && b.blockRadius != 0) {
            throw new IllegalArgumentException("aggregation NONE requires blockRadius 0");
        }
        if (b.aggregation == AggregationStrategy.BOX && b.blockRadius < 1) {
            throw new IllegalArgumentException("aggregation BOX requires blockRadius >= 1");
        }
        if (!(b.textureThreshold >= 0.0)) {
            throw new IllegalArgumentException("textureThreshold must be >= 0");
        }
        if (!(b.lrTolerance >= 0.0)) {
            throw new IllegalArgumentException("lrTolerance must be >= 0");
        }
        if (b.downsampleFactor < 1 || b.downsampleFactor > 8) {
            throw new IllegalArgumentException("downsampleFactor must be in [1, 8]");
        }
        this.minDisparity = b.minDisparity;
        this.numDisparities = b.numDisparities;
        this.blockRadius = b.blockRadius;
        this.cost = b.cost;
        this.aggregation = b.aggregation;
        this.textureThreshold = b.textureThreshold;
        this.lrTolerance = b.lrTolerance;
        this.subpixelEnabled = b.subpixelEnabled;
        this.downsampleFactor = b.downsampleFactor;
        this.randomSeed = b.randomSeed;
    }

    public int minDisparity() {
        return minDisparity;
    }

    public int numDisparities() {
        return numDisparities;
    }

    public int blockRadius() {
        return blockRadius;
    }

    public MatchingCost cost() {
        return cost;
    }

    public AggregationStrategy aggregation() {
        return aggregation;
    }

    public double textureThreshold() {
        return textureThreshold;
    }

    public double lrTolerance() {
        return lrTolerance;
    }

    public boolean subpixelEnabled() {
        return subpixelEnabled;
    }

    public int downsampleFactor() {
        return downsampleFactor;
    }

    public long randomSeed() {
        return randomSeed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static StereoConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int minDisparity = DEFAULT_MIN_DISPARITY;
        private int numDisparities = DEFAULT_NUM_DISPARITIES;
        private int blockRadius = DEFAULT_BLOCK_RADIUS;
        private MatchingCost cost = DEFAULT_COST;
        private AggregationStrategy aggregation = DEFAULT_AGGREGATION;
        private double textureThreshold = DEFAULT_TEXTURE_THRESHOLD;
        private double lrTolerance = DEFAULT_LR_TOLERANCE;
        private boolean subpixelEnabled = DEFAULT_SUBPIXEL_ENABLED;
        private int downsampleFactor = DEFAULT_DOWNSAMPLE_FACTOR;
        private long randomSeed = DEFAULT_RANDOM_SEED;

        private Builder() {
        }

        public Builder minDisparity(int v) {
            minDisparity = v;
            return this;
        }

        public Builder numDisparities(int v) {
            numDisparities = v;
            return this;
        }

        public Builder blockRadius(int v) {
            blockRadius = v;
            return this;
        }

        public Builder cost(MatchingCost v) {
            cost = v;
            return this;
        }

        public Builder aggregation(AggregationStrategy v) {
            aggregation = v;
            return this;
        }

        public Builder textureThreshold(double v) {
            textureThreshold = v;
            return this;
        }

        public Builder lrTolerance(double v) {
            lrTolerance = v;
            return this;
        }

        public Builder subpixelEnabled(boolean v) {
            subpixelEnabled = v;
            return this;
        }

        public Builder downsampleFactor(int v) {
            downsampleFactor = v;
            return this;
        }

        public Builder randomSeed(long v) {
            randomSeed = v;
            return this;
        }

        public StereoConfig build() {
            return new StereoConfig(this);
        }
    }
}
