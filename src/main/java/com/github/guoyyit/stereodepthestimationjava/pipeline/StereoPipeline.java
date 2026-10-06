package com.github.guoyyit.stereodepthestimationjava.pipeline;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.calibration.ImageRectifier;
import com.github.guoyyit.stereodepthestimationjava.calibration.RectificationEngine;
import com.github.guoyyit.stereodepthestimationjava.cost.CostComputer;
import com.github.guoyyit.stereodepthestimationjava.depth.DepthPointCloudBuilder;
import com.github.guoyyit.stereodepthestimationjava.disparity.StereoMatcher;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.error.InvalidFrameException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.DepthMap;
import com.github.guoyyit.stereodepthestimationjava.model.DisparityMap;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.InvalidReason;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.PointCloud;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;
import com.github.guoyyit.stereodepthestimationjava.util.ImageOps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public final class StereoPipeline {

    private final CalibrationRegistry registry;
    private final PipelineConfig config;
    private final RectificationEngine rectificationEngine = new RectificationEngine();
    private final Map<String, RectificationMaps> mapsCache = new ConcurrentHashMap<>();

    public StereoPipeline(CalibrationRegistry registry, PipelineConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public StereoResult process(FrameRequest request, BooleanSupplier cancelled) {
        CalibrationParams params = request.requestedCalibrationVersion() == null
                ? registry.resolveActive()
                : registry.resolve(request.requestedCalibrationVersion());
        return processWith(request, params, cancelled);
    }

    public StereoResult processWith(FrameRequest request, CalibrationParams paramsSnapshot,
                                    BooleanSupplier cancelled) {
        validateRequest(request, paramsSnapshot);
        checkCancelled(cancelled);

        String version = paramsSnapshot.version();
        // Cache key binds the version label to the exact calibration content of this frame's
        // snapshot, so every stage of one frame (rectification, depth, point cloud) is derived
        // from one and the same calibration content, and a frame can never observe maps built
        // from different content that happens to carry the same version label.
        String mapsKey = version + "@" + paramsSnapshot.contentFingerprint();
        RectificationMaps maps = mapsCache.computeIfAbsent(mapsKey,
                key -> rectificationEngine.buildMaps(paramsSnapshot));
        checkCancelled(cancelled);

        ImageRectifier rectifier = new ImageRectifier(config.borderMode());
        GrayscaleImage leftRectified =
                rectifier.rectify(request.left(), maps.mapXLeft(), maps.mapYLeft(), maps);
        GrayscaleImage rightRectified =
                rectifier.rectify(request.right(), maps.mapXRight(), maps.mapYRight(), maps);
        checkCancelled(cancelled);

        float scale = config.resolutionScale();
        GrayscaleImage leftWork = ImageOps.downscale(leftRectified, scale, 0L);
        GrayscaleImage rightWork = ImageOps.downscale(rightRectified, scale, 0L);
        checkCancelled(cancelled);

        PipelineConfig workConfig = scaledConfig(config, scale);
        CostComputer costComputer = new CostComputer(workConfig.matchingCostType(),
                workConfig.windowRadius(), workConfig.borderMode(), workConfig.aggregationRadius());
        StereoMatcher matcher = new StereoMatcher(workConfig, costComputer);
        DisparityMap coarse = matcher.compute(leftWork, rightWork, cancelled);
        checkCancelled(cancelled);

        DisparityMap full = scale == 1.0f ? coarse : upscaleDisparity(coarse, maps.width(), maps.height(), scale);

        double[] kNew = maps.rectifiedK();
        double[] t = paramsSnapshot.t();
        float baseline = (float) Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        float focal = (float) kNew[0];
        DepthPointCloudBuilder builder = new DepthPointCloudBuilder(focal, baseline);
        DepthMap depth = builder.toDepth(full, version);
        PointCloud pointCloud = builder.toPointCloud(full, kNew, version);

        return new StereoResult(request.frameId(), version, full, depth, pointCloud);
    }

    private DisparityMap upscaleDisparity(DisparityMap coarse, int fullWidth, int fullHeight,
                                          float scale) {
        float[] disparity = new float[fullWidth * fullHeight];
        InvalidReason[] reasons = new InvalidReason[fullWidth * fullHeight];
        for (int y = 0; y < fullHeight; y++) {
            int cy = Math.min(coarse.height() - 1, (int) (y * scale));
            for (int x = 0; x < fullWidth; x++) {
                int cx = Math.min(coarse.width() - 1, (int) (x * scale));
                int coarseIndex = cy * coarse.width() + cx;
                int fullIndex = y * fullWidth + x;
                reasons[fullIndex] = coarse.reasons()[coarseIndex];
                if (reasons[fullIndex] == InvalidReason.VALID) {
                    disparity[fullIndex] = coarse.disparity()[coarseIndex] / scale;
                } else {
                    disparity[fullIndex] = Float.NaN;
                }
            }
        }
        return new DisparityMap(fullWidth, fullHeight, disparity, reasons);
    }

    private static PipelineConfig scaledConfig(PipelineConfig base, float scale) {
        int scaledMin = Math.round(base.minDisparity() * scale);
        int scaledNum = Math.max(8, Math.round(base.numDisparities() * scale));
        return new PipelineConfig(base.matchingCostType(), base.windowRadius(), scaledMin,
                scaledNum, base.borderMode(), base.aggregationRadius(), base.leftRightCheck(),
                base.leftRightThreshold() * scale, base.subpixelRefine(),
                base.textureThreshold(), 1.0f, base.maxFrameHistory(), base.uniquenessRatio());
    }

    private void validateRequest(FrameRequest request, CalibrationParams params) {
        if (request == null || request.left() == null || request.right() == null) {
            throw new InvalidFrameException("frame request and both images must not be null");
        }
        if (request.frameId() == null || request.frameId().isBlank()) {
            throw new InvalidFrameException("frame id must not be blank");
        }
        GrayscaleImage left = request.left();
        GrayscaleImage right = request.right();
        if (left.width() != right.width() || left.height() != right.height()) {
            throw new InvalidFrameException("left and right image dimensions must match");
        }
        if (left.width() != params.imageWidth() || left.height() != params.imageHeight()) {
            throw new InvalidFrameException(
                    "image dimensions do not match calibration " + params.version());
        }
        rejectNaN(left, "left");
        rejectNaN(right, "right");
    }

    private static void rejectNaN(GrayscaleImage image, String side) {
        float[] data = image.data();
        for (float v : data) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new InvalidFrameException(side + " image contains non-finite pixel values");
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new FrameCancelledException("frame processing cancelled before next stage");
        }
    }
}
