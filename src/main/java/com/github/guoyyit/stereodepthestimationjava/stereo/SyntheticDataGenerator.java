package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Generates synthetic stereo scenes with known ground-truth disparity,
 * purely locally and reproducibly from a seed. Used by tests and demos;
 * no external services involved.
 *
 * <p>Scene layout (relative to image size): a textured background plane
 * whose disparity grows linearly from left to right, a nearer box in
 * the middle that occludes a background strip on its left, and a
 * constant-intensity (textureless) rectangle. The right image is
 * rendered by sub-pixel backward warping with a z-buffer; right pixels
 * with no source are filled with independent noise, so occluded left
 * pixels have no true match.</p>
 */
public final class SyntheticDataGenerator {
    private SyntheticDataGenerator() {
    }

    /**
     * A generated scene: stereo pair plus ground-truth disparity.
     * Ground-truth pixels are valid only where the left pixel is
     * visible in the right view; occluded pixels are marked
     * {@link InvalidReason#OCCLUSION} and pixels projecting outside the
      * right image {@link InvalidReason#OUT_OF_RANGE}.
     */
    public record Scene(StereoImagePair pair, DisparityMap groundTruth) {
    }

    /**
     * Generates the standard scene. Identical seeds produce identical
     * scenes.
     */
    public static Scene generate(int width, int height, long seed) {
        java.util.Random rng = new java.util.Random(seed);

        float[] texture = new float[width * height];
        for (int i = 0; i < texture.length; i++) {
            texture[i] = rng.nextFloat();
        }

        // Textureless rectangle on the background.
        int tlX0 = width * 10 / 100;
        int tlX1 = width * 30 / 100;
        int tlY0 = height * 60 / 100;
        int tlY1 = height * 85 / 100;
        for (int y = tlY0; y <= tlY1; y++) {
            for (int x = tlX0; x <= tlX1; x++) {
                texture[y * width + x] = 0.5f;
            }
        }

        // Geometry: background disparity plane plus a nearer box.
        int boxX0 = width * 45 / 100;
        int boxX1 = width * 65 / 100;
        int boxY0 = height * 20 / 100;
        int boxY1 = height * 60 / 100;
        double boxDisparity = 24.0;
        float[] trueDisp = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double bg = 6.0 + 10.0 * x / width;
                boolean inBox = x >= boxX0 && x <= boxX1 && y >= boxY0 && y <= boxY1;
                trueDisp[y * width + x] = (float) (inBox ? boxDisparity : bg);
            }
        }

        // Z-buffer over right-image columns: largest disparity wins.
        double[] zbuf = new double[width * height];
        java.util.Arrays.fill(zbuf, Double.NEGATIVE_INFINITY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double d = trueDisp[y * width + x];
                int xr = (int) Math.round(x - d);
                if (xr < 0 || xr >= width) {
                    continue;
                }
                int idx = y * width + xr;
                if (d > zbuf[idx]) {
                    zbuf[idx] = d;
                }
            }
        }

        // Render the right image by backward sampling the texture at
        // the winning disparity; unwritten pixels get fresh noise.
        float[] right = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double d = zbuf[y * width + x];
                if (Double.isInfinite(d)) {
                    right[y * width + x] = rng.nextFloat();
                } else {
                    right[y * width + x] = bilinear(texture, width, height, x + d, y);
                }
            }
        }

        // Ground truth: visible left pixels keep their disparity.
        DisparityMap gt = new DisparityMap(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float d = trueDisp[y * width + x];
                int xr = (int) Math.round(x - d);
                if (xr < 0 || xr >= width) {
                    gt.setInvalid(x, y, InvalidReason.OUT_OF_RANGE);
                    continue;
                }
                double winner = zbuf[y * width + xr];
                if (Math.abs(winner - d) < 0.5) {
                    gt.setValid(x, y, d);
                } else {
                    gt.setInvalid(x, y, InvalidReason.OCCLUSION);
                }
            }
        }

        GrayImage left = new GrayImage(width, height, texture);
        return new Scene(new StereoImagePair(left, new GrayImage(width, height, right)), gt);
    }

    private static float bilinear(float[] img, int w, int h, double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        double ax = u - x0;
        double ay = v - y0;
        double top = lerp(sample(img, w, h, x0, y0), sample(img, w, h, x0 + 1, y0), ax);
        double bottom = lerp(sample(img, w, h, x0, y0 + 1), sample(img, w, h, x0 + 1, y0 + 1), ax);
        return (float) lerp(top, bottom, ay);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float sample(float[] img, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return 0.0f;
        }
        return img[y * w + x];
    }
}
