package com.github.guoyyit.stereodepthestimationjava.util;

import com.github.guoyyit.stereodepthestimationjava.cost.BorderMode;
import com.github.guoyyit.stereodepthestimationjava.model.GrayscaleImage;

public final class ImageOps {

    private ImageOps() {
    }

    public static float sample(GrayscaleImage image, float x, float y, BorderMode borderMode) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float v00 = samplePoint(image, x0, y0, borderMode);
        float v01 = samplePoint(image, x0 + 1, y0, borderMode);
        float v10 = samplePoint(image, x0, y0 + 1, borderMode);
        float v11 = samplePoint(image, x0 + 1, y0 + 1, borderMode);
        if (Float.isNaN(v00) || Float.isNaN(v01) || Float.isNaN(v10) || Float.isNaN(v11)) {
            return Float.NaN;
        }
        float top = v00 + (v01 - v00) * fx;
        float bottom = v10 + (v11 - v10) * fx;
        return top + (bottom - top) * fy;
    }

    private static float samplePoint(GrayscaleImage image, int x, int y, BorderMode borderMode) {
        int width = image.width();
        int height = image.height();
        if (x >= 0 && x < width && y >= 0 && y < height) {
            return image.data()[y * width + x];
        }
        switch (borderMode) {
            case BORDER_INVALID:
                return Float.NaN;
            case ZERO_PAD:
                return 0f;
            case REFLECT:
                int rx = reflect(x, width);
                int ry = reflect(y, height);
                return image.data()[ry * width + rx];
            default:
                throw new IllegalStateException("unsupported border mode: " + borderMode);
        }
    }

    private static int reflect(int index, int length) {
        if (length <= 1) {
            return 0;
        }
        int period = 2 * (length - 1);
        int wrapped = ((index % period) + period) % period;
        return wrapped < length ? wrapped : period - wrapped;
    }

    public static GrayscaleImage downscale(GrayscaleImage image, float scale, long seed) {
        if (scale > 1.0f || scale <= 0.0f) {
            throw new IllegalArgumentException("resolution scale must be within (0, 1]");
        }
        if (Math.abs(scale - 1.0f) < 1e-6f) {
            return image;
        }
        int newWidth = Math.max(1, Math.round(image.width() * scale));
        int newHeight = Math.max(1, Math.round(image.height() * scale));
        float[] output = new float[newWidth * newHeight];
        int radius = Math.max(1, (int) Math.floor(0.5f / scale));
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                float sourceX = (x + 0.5f) / scale - 0.5f;
                float sourceY = (y + 0.5f) / scale - 0.5f;
                output[y * newWidth + x] = boxAverage(image, sourceX, sourceY, radius);
            }
        }
        return new GrayscaleImage(newWidth, newHeight, output);
    }

    private static float boxAverage(GrayscaleImage image, float centerX, float centerY, int radius) {
        int x0 = (int) Math.round(centerX);
        int y0 = (int) Math.round(centerY);
        double sum = 0.0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = x0 + dx;
                int y = y0 + dy;
                if (x >= 0 && x < image.width() && y >= 0 && y < image.height()) {
                    sum += image.data()[y * image.width() + x];
                    count++;
                }
            }
        }
        return count == 0 ? Float.NaN : (float) (sum / count);
    }
}
