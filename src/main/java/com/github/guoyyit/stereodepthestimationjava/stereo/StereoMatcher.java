package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Dense stereo matcher: cost computation, aggregation, winner-takes-all
 * selection, left/right consistency check and sub-pixel refinement.
 *
 * <p>Deterministic border semantics:</p>
 * <ul>
 * <li>Windows (cost and aggregation) are clipped to the image bounds;
 *     BOX aggregation averages over the in-bounds samples only.</li>
 * <li>A disparity d is evaluable at column x iff the matched column
 *     (x-d left-to-right, x+d right-to-left) lies inside the image;
 *     non-evaluable disparities get cost +infinity and are never
 *     selected unless nothing else is available.</li>
 * <li>Pixels with no evaluable disparity are marked
 *     {@link InvalidReason#BORDER}.</li>
 * <li>Winner-takes-all ties resolve to the smallest disparity.</li>
 * </ul>
 *
 * <p>Invalid-reason precedence: BORDER &gt; TEXTURELESS &gt; OCCLUSION.</p>
 */
public final class StereoMatcher {
    private final StereoConfig config;

    public StereoMatcher(StereoConfig config) {
        this.config = config;
    }

    /**
     * Computes a dense disparity map for a rectified pair.
     */
    public DisparityMap compute(GrayImage left, GrayImage right) {
        int w = left.width();
        int h = left.height();
        if (right.width() != w || right.height() != h) {
            throw new StereoProcessingException("left and right image dimensions must match");
        }
        int minD = config.minDisparity();
        int numD = config.numDisparities();

        float[][] volumeL = aggregatedVolume(left, right, -1, w, h, minD, numD);
        float[][] volumeR = aggregatedVolume(right, left, +1, w, h, minD, numD);

        int[] dispL = new int[w * h];
        int[] dispR = new int[w * h];
        boolean[] evaluableL = new boolean[w * h];
        winnerTakesAll(volumeL, w, h, minD, dispL, evaluableL);
        winnerTakesAll(volumeR, w, h, minD, dispR, null);

        boolean[] textureless = textureMask(left, w, h);

        DisparityMap out = new DisparityMap(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!evaluableL[i]) {
                    out.setInvalid(x, y, InvalidReason.BORDER);
                    continue;
                }
                if (textureless[i]) {
                    out.setInvalid(x, y, InvalidReason.TEXTURELESS);
                    continue;
                }
                int d = dispL[i];
                int xr = x - d;
                if (xr < 0 || xr >= w || Math.abs(dispR[y * w + xr] - d) > config.lrTolerance()) {
                    out.setInvalid(x, y, InvalidReason.OCCLUSION);
                    continue;
                }
                float value = d;
                if (config.subpixelEnabled()) {
                    value = d + subpixelOffset(volumeL, w, minD, numD, i, d);
                }
                out.setValid(x, y, value);
            }
        }
        return out;
    }

    private float[][] aggregatedVolume(GrayImage ref, GrayImage match, int sign,
                                       int w, int h, int minD, int numD) {
        float[][] volume = new float[numD][];
        for (int di = 0; di < numD; di++) {
            float[] slice = costSlice(ref, match, sign, w, h, minD + di);
            volume[di] = config.aggregation() == AggregationStrategy.BOX
                    ? boxFilter(slice, w, h, config.blockRadius())
                    : slice;
        }
        return volume;
    }

    private float[] costSlice(GrayImage ref, GrayImage match, int sign,
                              int w, int h, int d) {
        float[] slice = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int xm = x + sign * d;
                float cost;
                if (xm < 0 || xm >= w) {
                    cost = Float.POSITIVE_INFINITY;
                } else {
                    cost = switch (config.cost()) {
                        case SAD -> Math.abs(ref.get(x, y) - match.get(xm, y));
                        case SSD -> {
                            float diff = ref.get(x, y) - match.get(xm, y);
                            yield diff * diff;
                        }
                        case NCC -> nccCost(ref, match, x, y, xm, w, h);
                    };
                }
                slice[y * w + x] = cost;
            }
        }
        return slice;
    }

    /**
     * 1 - NCC over the clipped block window; windowless configs use a
     * radius-1 window. Degenerate (zero-variance) windows yield cost 1.
     */
    private float nccCost(GrayImage ref, GrayImage match, int x, int y, int xm, int w, int h) {
        int radius = Math.max(config.blockRadius(), 1);
        double sumA = 0;
        double sumB = 0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int xa = x + dx;
                int xb = xm + dx;
                if (xa < 0 || xa >= w || xb < 0 || xb >= w) {
                    continue;
                }
                sumA += ref.get(xa, yy);
                sumB += match.get(xb, yy);
                count++;
            }
        }
        if (count == 0) {
            return 1.0f;
        }
        double meanA = sumA / count;
        double meanB = sumB / count;
        double cov = 0;
        double varA = 0;
        double varB = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int xa = x + dx;
                int xb = xm + dx;
                if (xa < 0 || xa >= w || xb < 0 || xb >= w) {
                    continue;
                }
                double da = ref.get(xa, yy) - meanA;
                double db = match.get(xb, yy) - meanB;
                cov += da * db;
                varA += da * da;
                varB += db * db;
            }
        }
        double denom = Math.sqrt(varA * varB);
        if (denom == 0.0) {
            return 1.0f;
        }
        return (float) (1.0 - cov / denom);
    }

    /**
     * Averages each slice over the clipped in-bounds square window.
     * A pixel whose own cost is non-evaluable stays non-evaluable:
     * aggregation never borrows evaluability from neighbours.
     */
    private static float[] boxFilter(float[] in, int w, int h, int radius) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                if (Float.isInfinite(in[y * w + x])) {
                    out[y * w + x] = Float.POSITIVE_INFINITY;
                    continue;
                }
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(w - 1, x + radius);
                double sum = 0;
                int count = 0;
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        float v = in[yy * w + xx];
                        if (Float.isInfinite(v)) {
                            continue;
                        }
                        sum += v;
                        count++;
                    }
                }
                out[y * w + x] = count == 0 ? Float.POSITIVE_INFINITY : (float) (sum / count);
            }
        }
        return out;
    }

    private static void winnerTakesAll(float[][] volume, int w, int h, int minD,
                                       int[] dispOut, boolean[] evaluableOut) {
        for (int i = 0; i < w * h; i++) {
            float best = Float.POSITIVE_INFINITY;
            int bestD = -1;
            for (int di = 0; di < volume.length; di++) {
                float c = volume[di][i];
                if (c < best) {
                    best = c;
                    bestD = di;
                }
            }
            dispOut[i] = bestD < 0 ? 0 : minD + bestD;
            if (evaluableOut != null) {
                evaluableOut[i] = bestD >= 0;
            }
        }
    }

    /**
     * Parabolic refinement around the winning disparity; 0 at the search
     * range boundaries or when the fit is degenerate.
     */
    private static float subpixelOffset(float[][] volume, int w, int minD, int numD, int i, int d) {
        int di = d - minD;
        if (di <= 0 || di >= numD - 1) {
            return 0.0f;
        }
        float cPrev = volume[di - 1][i];
        float cCur = volume[di][i];
        float cNext = volume[di + 1][i];
        if (Float.isInfinite(cPrev) || Float.isInfinite(cNext)) {
            return 0.0f;
        }
        float denom = 2.0f * (cPrev - 2.0f * cCur + cNext);
        if (denom <= 0.0f) {
            return 0.0f;
        }
        float offset = (cPrev - cNext) / denom;
        return Math.max(-1.0f, Math.min(1.0f, offset));
    }

    /**
     * Flags pixels whose local intensity variance (over the clipped
     * block window) falls below the configured texture threshold.
     */
    private boolean[] textureMask(GrayImage img, int w, int h) {
        int radius = Math.max(config.blockRadius(), 1);
        double threshold = config.textureThreshold();
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(w - 1, x + radius);
                double sum = 0;
                double sumSq = 0;
                int count = 0;
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        double v = img.get(xx, yy);
                        sum += v;
                        sumSq += v * v;
                        count++;
                    }
                }
                double mean = sum / count;
                double variance = sumSq / count - mean * mean;
                mask[y * w + x] = variance < threshold;
            }
        }
        return mask;
    }
}
