package com.github.guoyyit.stereodepthestimationjava.stereo;

/**
 * Applies distortion removal and epipolar rectification so that
 * corresponding points share the same image row.
 *
 * <p>For every rectified pixel the source coordinate is computed by
 * un-projecting with the camera intrinsics, rotating by the inverse of
 * the rectification rotation, applying radial distortion and projecting
 * back. Sampling is bilinear; source coordinates outside the image are
 * deterministically filled with 0.</p>
 */
public final class Rectifier {
    private final CalibrationParams params;

    public Rectifier(CalibrationParams params) {
        this.params = params;
    }

    public CalibrationParams params() {
        return params;
    }

    /**
     * Rectifies both images of the pair.
     */
    public StereoImagePair rectify(StereoImagePair pair) {
        GrayImage left = remap(pair.left(), params.kLeft(), params.distLeft(), params.rectLeft());
        GrayImage right = remap(pair.right(), params.kRight(), params.distRight(), params.rectRight());
        return new StereoImagePair(left, right);
    }

    private static GrayImage remap(GrayImage src, double[] k, double[] dist, double[] rect) {
        int w = src.width();
        int h = src.height();
        float[] out = new float[w * h];
        double fx = k[0];
        double fy = k[4];
        double cx = k[2];
        double cy = k[5];
        double k1 = dist[0];
        double k2 = dist[1];
        // Inverse of the rectification rotation (rotation matrices are
        // orthonormal, so the inverse is the transpose).
        double[] rInv = {
                rect[0], rect[3], rect[6],
                rect[1], rect[4], rect[7],
                rect[2], rect[5], rect[8]};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double xn = (x - cx) / fx;
                double yn = (y - cy) / fy;
                double rx = rInv[0] * xn + rInv[1] * yn + rInv[2];
                double ry = rInv[3] * xn + rInv[4] * yn + rInv[5];
                double rz = rInv[6] * xn + rInv[7] * yn + rInv[8];
                double xo = rx / rz;
                double yo = ry / rz;
                double r2 = xo * xo + yo * yo;
                double radial = 1.0 + k1 * r2 + k2 * r2 * r2;
                double u = fx * xo * radial + cx;
                double v = fy * yo * radial + cy;
                out[y * w + x] = bilinear(src, u, v);
            }
        }
        return new GrayImage(w, h, out);
    }

    private static float bilinear(GrayImage img, double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        double ax = u - x0;
        double ay = v - y0;
        double top = lerp(sample(img, x0, y0), sample(img, x1, y0), ax);
        double bottom = lerp(sample(img, x0, y1), sample(img, x1, y1), ax);
        return (float) lerp(top, bottom, ay);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float sample(GrayImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.width() || y >= img.height()) {
            return 0.0f;
        }
        return img.get(x, y);
    }
}
