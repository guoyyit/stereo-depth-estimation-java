package com.github.guoyyit.stereodepthestimationjava.disparity;

import com.github.guoyyit.stereodepthestimationjava.cost.CostComputer;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.model.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;

import java.util.function.BooleanSupplier;

public final class StereoMatcher {

    private final PipelineConfig config;
    private final CostComputer costComputer;

    public StereoMatcher(PipelineConfig config, CostComputer costComputer) {
        this.config = config;
        this.costComputer = costComputer;
    }

   public DisparityMap compute(GrayscaleImage left, GrayscaleImage right,
                               BooleanSupplier cancelled) {
        if (left.width() != right.width() || left.height() != right.height()) {
            throw new IllegalArgumentException("left and right image dimensions must match");
        }
        int width = left.width();
        int height = left.height();
        int pixels = width * height;
        float[] disparity = new float[pixels];
        InvalidReason[] reasons = new InvalidReason[pixels];

        boolean[] textureless = new boolean[pixels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float texture = costComputer.textureScore(left, x, y);
                textureless[index] = Float.isNaN(texture) || texture < config.textureThreshold();
            }
            throwIfCancelled(cancelled, y, "left disparity matching");
        }

        float[] rawDisparity = new float[pixels];
        boolean[] rawValid = new boolean[pixels];
        boolean[] ambiguous = new boolean[pixels];
        matchOneDirection(left, right, true, rawDisparity, rawValid, ambiguous, cancelled);

        boolean[] rightValid = null;
        float[] rightDisparity = null;
        if (config.leftRightCheck()) {
            rightDisparity = new float[pixels];
            rightValid = new boolean[pixels];
            boolean[] rightAmbiguous = new boolean[pixels];
            matchOneDirection(right, left, false, rightDisparity, rightValid, rightAmbiguous,
                    cancelled);
        }

        float lrThreshold = config.leftRightThreshold();
       for (int y = 0; y < height; y++) {
           for (int x = 0; x < width; x++) {
               int index = y * width + x;
                InvalidReason reason = classify(x, y, index, width, height, textureless[index],
                        ambiguous[index], rawDisparity[index], rawValid[index], right,
                        rightDisparity, rightValid, lrThreshold);
                reasons[index] = reason;
                disparity[index] = reason == InvalidReason.VALID ? rawDisparity[index] : Float.NaN;
            }
        }
       return new DisparityMap(width, height, disparity, reasons);
   }

    private InvalidReason classify(int x, int y, int index, int width, int height, boolean textureless,
                                   boolean ambiguous, float leftDisparity, boolean leftValid,
                                   GrayscaleImage right, float[] rightDisparity,
                                   boolean[] rightValid, float lrThreshold) {
        int supportRadius = config.windowRadius() + config.aggregationRadius();
        int minX = config.minDisparity() + supportRadius;
        if (!leftValid) {
            return InvalidReason.BORDER;
        }
        if (x < minX || x >= width - supportRadius
                || y < supportRadius || y >= height - supportRadius) {
            return InvalidReason.BORDER;
        }
        if (textureless) {
            return InvalidReason.TEXTURELESS;
        }
        if (ambiguous) {
            return InvalidReason.TEXTURELESS;
        }
        if (config.leftRightCheck()) {
            int xr = Math.round(x - leftDisparity);
            if (xr < minX || xr >= width - supportRadius
                    || y < supportRadius || y >= height - supportRadius
                    || !rightValid[y * width + xr]) {
                return InvalidReason.OCCLUDED;
            }
            float dRight = rightDisparity[y * width + xr];
            if (Math.abs(dRight - leftDisparity) > lrThreshold) {
                return InvalidReason.OCCLUDED;
            }
        }
        return InvalidReason.VALID;
    }

    private void matchOneDirection(GrayscaleImage base, GrayscaleImage other, boolean baseIsLeft,
                                   float[] disparityOut, boolean[] validOut,
                                   boolean[] ambiguousOut,
                                   BooleanSupplier cancelled) {
        int width = base.width();
        int height = base.height();
        int minD = config.minDisparity();
        int maxDExclusive = minD + config.numDisparities();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float bestCost = Float.POSITIVE_INFINITY;
                float secondCost = Float.POSITIVE_INFINITY;
                int bestD = Integer.MIN_VALUE;
                for (int d = minD; d < maxDExclusive; d++) {
                    float cost;
                    if (baseIsLeft) {
                        cost = costComputer.matchCost(base, other, x, y, d);
                    } else {
                        cost = costComputer.matchCost(base, other, x, y, -d);
                    }
                    if (Float.isNaN(cost)) {
                        continue;
                    }
                    if (cost < bestCost) {
                        secondCost = bestCost;
                        bestCost = cost;
                        bestD = d;
                    } else if (cost < secondCost) {
                        secondCost = cost;
                    }
                }
                if (bestD == Integer.MIN_VALUE) {
                    validOut[index] = false;
                    disparityOut[index] = Float.NaN;
                    continue;
                }
                validOut[index] = true;
                float ratio = config.uniquenessRatio();
                ambiguousOut[index] = ratio > 0f && Float.isFinite(secondCost) && bestCost > 0f
                        && (secondCost - bestCost) / bestCost < ratio;
                float refined = bestD;
                if (config.subpixelRefine() && bestD > minD && bestD < maxDExclusive - 1) {
                    float costLeft = costOrInfinity(base, other, x, y, bestD - 1, baseIsLeft);
                    float costRight = costOrInfinity(base, other, x, y, bestD + 1, baseIsLeft);
                    if (!Float.isInfinite(costLeft) && !Float.isInfinite(costRight)) {
                        float denominator = costLeft - 2.0f * bestCost + costRight;
                        if (denominator > 1e-6f) {
                            float offset = 0.5f * (costLeft - costRight) / denominator;
                            offset = Math.max(-1.0f, Math.min(1.0f, offset));
                            refined = bestD + offset;
                        }
                    }
                }
                disparityOut[index] = refined;
            }
            throwIfCancelled(cancelled, y, baseIsLeft ? "left-to-right matching" : "right-to-left matching");
        }
    }

    private float costOrInfinity(GrayscaleImage base, GrayscaleImage other, int x, int y, int d,
                                 boolean baseIsLeft) {
        float cost = baseIsLeft
                ? costComputer.matchCost(base, other, x, y, d)
                : costComputer.matchCost(base, other, x, y, -d);
        return Float.isNaN(cost) ? Float.POSITIVE_INFINITY : cost;
    }

    private void throwIfCancelled(BooleanSupplier cancelled, int row, String stage) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new FrameCancelledException("frame processing cancelled during " + stage
                    + " at row " + row);
        }
    }
}
