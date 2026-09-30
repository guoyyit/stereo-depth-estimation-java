package com.github.guoyyit.stereodepthestimationjava.cost;

import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.util.ImageOps;

public final class CostComputer {

    private static final float NCC_EPSILON = 1e-3f;

    private final MatchingCostType type;
    private final int windowRadius;
    private final BorderMode borderMode;
    private final int aggregationRadius;

    public CostComputer(MatchingCostType type, int windowRadius, BorderMode borderMode,
                        int aggregationRadius) {
        if (windowRadius < 1) {
            throw new IllegalArgumentException("window radius must be >= 1");
        }
        if (aggregationRadius < 0) {
            throw new IllegalArgumentException("aggregation radius must be >= 0");
        }
        this.type = type;
        this.windowRadius = windowRadius;
        this.borderMode = borderMode;
        this.aggregationRadius = aggregationRadius;
    }

    public float matchCost(GrayscaleImage left, GrayscaleImage right, int x, int y, int d) {
        int radius = windowRadius + aggregationRadius;
        switch (type) {
            case SAD:
                return sad(left, right, x, y, d, radius, false);
            case SSD:
                return sad(left, right, x, y, d, radius, true);
            case NCC:
                return ncc(left, right, x, y, d, radius);
            default:
                throw new IllegalStateException("unsupported matching cost: " + type);
        }
    }

    public float textureScore(GrayscaleImage image, int x, int y) {
        int radius = windowRadius;
        double sum = 0.0;
        double sumSquares = 0.0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float value = ImageOps.sample(image, x + dx, y + dy, borderMode);
                if (Float.isNaN(value)) {
                    return Float.NaN;
                }
                sum += value;
                sumSquares += value * value;
                count++;
            }
        }
        double mean = sum / count;
        double variance = sumSquares / count - mean * mean;
        if (variance < 0.0 && variance > -1e-6) {
            variance = 0.0;
        }
        return (float) Math.sqrt(Math.max(0.0, variance));
    }

    private float sad(GrayscaleImage left, GrayscaleImage right, int x, int y, int d,
                      int radius, boolean squared) {
        double total = 0.0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float lv = ImageOps.sample(left, x + dx, y + dy, borderMode);
                float rv = ImageOps.sample(right, x + dx - d, y + dy, borderMode);
                if (Float.isNaN(lv) || Float.isNaN(rv)) {
                    return Float.NaN;
                }
                double diff = lv - rv;
                total += squared ? diff * diff : Math.abs(diff);
                count++;
            }
        }
        return (float) (total / count);
    }

    private float ncc(GrayscaleImage left, GrayscaleImage right, int x, int y, int d, int radius) {
        double sumLeft = 0.0;
        double sumRight = 0.0;
        int count = 0;
        float[] leftValues = new float[(2 * radius + 1) * (2 * radius + 1)];
        float[] rightValues = new float[leftValues.length];
        int idx = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float lv = ImageOps.sample(left, x + dx, y + dy, borderMode);
                float rv = ImageOps.sample(right, x + dx - d, y + dy, borderMode);
                if (Float.isNaN(lv) || Float.isNaN(rv)) {
                    return Float.NaN;
                }
                leftValues[idx] = lv;
                rightValues[idx] = rv;
                sumLeft += lv;
                sumRight += rv;
                idx++;
                count++;
            }
        }
        double meanLeft = sumLeft / count;
        double meanRight = sumRight / count;
        double numerator = 0.0;
        double varLeft = 0.0;
        double varRight = 0.0;
        for (int i = 0; i < count; i++) {
            double dl = leftValues[i] - meanLeft;
            double dr = rightValues[i] - meanRight;
            numerator += dl * dr;
            varLeft += dl * dl;
            varRight += dr * dr;
        }
        if (varLeft < NCC_EPSILON || varRight < NCC_EPSILON) {
            return Float.NaN;
        }
        double denominator = Math.sqrt(varLeft * varRight);
        double correlation = numerator / denominator;
        correlation = Math.max(-1.0, Math.min(1.0, correlation));
        return (float) (1.0 - correlation);
    }
}
