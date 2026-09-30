package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Orchestrates one frame: pins the calibration version at start,
 * rectifies, matches, converts to depth and optionally builds a cloud.
 *
 * <p>The calibration version is captured once when processing starts;
 * the whole frame uses that version even if a newer version is
 * published mid-flight. When {@code downsampleFactor > 1} the pair is
 * averaged down, matched with a proportionally scaled configuration and
 * the resulting disparities are scaled back up (nearest neighbour) so
 * the output always has the input resolution.</p>
 */
public final class StereoPipeline {
    /**
     * Package-private hook invoked right after the calibration version
     * is pinned for a frame; used by tests to prove that later version
     * updates do not affect in-flight processing.
     */
    interface VersionPinListener {
        void onVersionPinned(CalibrationParams params);
    }

    /**
     * Cooperative cancellation token checked between pipeline stages.
     */
    public static final class CancellationToken {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new ProcessingCancelledException("frame processing was cancelled");
            }
        }
    }

    private final CalibrationRegistry registry;
    private final StereoConfig config;
    private final VersionPinListener versionPinListener;

    public StereoPipeline(CalibrationRegistry registry, StereoConfig config) {
        this(registry, config, null);
    }

    StereoPipeline(CalibrationRegistry registry, StereoConfig config,
                   VersionPinListener versionPinListener) {
        this.registry = registry;
        this.config = config;
        this.versionPinListener = versionPinListener;
    }

    /**
     * Processes a pair with the calibration version current at call time.
     */
    public StereoResult process(StereoImagePair pair, boolean wantPointCloud) {
        return process(pair, wantPointCloud, new CancellationToken());
    }

    /**
     * Processes a pair, honouring cooperative cancellation between
     * stages. A cancelled frame yields no partial result.
     */
    public StereoResult process(StereoImagePair pair, boolean wantPointCloud,
                                CancellationToken token) {
        CalibrationParams params = registry.current();
        if (versionPinListener != null) {
            versionPinListener.onVersionPinned(params);
        }
        token.check();

        StereoImagePair rectified = new Rectifier(params).rectify(pair);
        token.check();

        DisparityMap disp = match(rectified);
        token.check();

        DepthMap depth = DepthMap.fromDisparity(disp, params);
        PointCloud cloud = wantPointCloud ? PointCloud.fromDepth(depth, params) : null;
        return new StereoResult(disp, depth, cloud, params.version());
    }

    private DisparityMap match(StereoImagePair rectified) {
        int factor = config.downsampleFactor();
        if (factor == 1) {
            return new StereoMatcher(config).compute(rectified.left(), rectified.right());
        }
        GrayImage smallLeft = downsample(rectified.left(), factor);
        GrayImage smallRight = downsample(rectified.right(), factor);
        StereoConfig scaled = StereoConfig.builder()
                .minDisparity(config.minDisparity() / factor)
                .numDisparities(Math.max(1, config.numDisparities() / factor))
                .blockRadius(Math.max(1, config.blockRadius() / factor))
                .cost(config.cost())
                .aggregation(config.aggregation())
                .textureThreshold(config.textureThreshold())
                .lrTolerance(config.lrTolerance() / factor)
                .subpixelEnabled(config.subpixelEnabled())
                .downsampleFactor(1)
                .randomSeed(config.randomSeed())
                .build();
        DisparityMap small = new StereoMatcher(scaled).compute(smallLeft, smallRight);
        return upsample(small, rectified.width(), rectified.height(), factor);
    }

    /**
     * Box-averages the image by {@code factor}; dimensions must be
     * divisible by the factor (deterministic, documented).
     */
    static GrayImage downsample(GrayImage img, int factor) {
        int w = img.width();
        int h = img.height();
        if (w % factor != 0 || h % factor != 0) {
            throw new StereoProcessingException(
                    "image dimensions " + w + "x" + h + " not divisible by downsample factor " + factor);
        }
        int sw = w / factor;
        int sh = h / factor;
        float[] out = new float[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                double sum = 0;
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        sum += img.get(x * factor + dx, y * factor + dy);
                    }
                }
                out[y * sw + x] = (float) (sum / (factor * factor));
            }
        }
        return new GrayImage(sw, sh, out);
    }

    /**
     * Nearest-neighbour upsample; valid disparities are multiplied by
     * the factor, invalid reasons are propagated per source pixel.
     */
    static DisparityMap upsample(DisparityMap small, int w, int h, int factor) {
        DisparityMap out = new DisparityMap(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = Math.min(x / factor, small.width() - 1);
                int sy = Math.min(y / factor, small.height() - 1);
                if (small.isValid(sx, sy)) {
                    out.setValid(x, y, small.disparity(sx, sy) * factor);
                } else {
                    out.setInvalid(x, y, small.reason(sx, sy));
                }
            }
        }
        return out;
    }
}
