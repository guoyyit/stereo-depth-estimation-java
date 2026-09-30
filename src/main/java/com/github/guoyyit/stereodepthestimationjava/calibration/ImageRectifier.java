package com.github.guoyyit.stereodepthestimationjava.calibration;

import com.github.guoyyit.stereodepthestimationjava.cost.BorderMode;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;
import com.github.guoyyit.stereodepthestimationjava.model.RectificationMaps;
import com.github.guoyyit.stereodepthestimationjava.util.ImageOps;

public final class ImageRectifier {

    private final BorderMode borderMode;

    public ImageRectifier(BorderMode borderMode) {
        this.borderMode = borderMode;
    }

    public GrayscaleImage rectify(GrayscaleImage image, float[] mapX, float[] mapY,
                                  RectificationMaps maps) {
        if (image.width() != maps.width() || image.height() != maps.height()) {
            throw new IllegalArgumentException(
                    "input image dimensions do not match rectification maps");
        }
        int pixels = maps.width() * maps.height();
        float[] output = new float[pixels];
        for (int i = 0; i < pixels; i++) {
            float sourceX = mapX[i];
            float sourceY = mapY[i];
            if (Float.isNaN(sourceX) || Float.isNaN(sourceY)) {
                output[i] = borderMode == BorderMode.ZERO_PAD ? 0f : Float.NaN;
            } else if (sourceX < 0f || sourceY < 0f
                    || sourceX > maps.width() - 1f || sourceY > maps.height() - 1f) {
                output[i] = borderMode == BorderMode.ZERO_PAD ? 0f : Float.NaN;
            } else {
                float value = ImageOps.sample(image, sourceX, sourceY, borderMode);
                output[i] = value;
            }
        }
        return new GrayscaleImage(maps.width(), maps.height(), output);
    }
}
