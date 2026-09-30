package com.github.guoyyit.stereodepthestimationjava.util;

public final class Mat {

    private final int rows;
    private final int cols;
    private final double[] a;

    public Mat(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows * cols];
    }

    public Mat(double[][] values) {
        this(values.length, values[0].length);
        for (int r = 0; r < rows; r++) {
            System.arraycopy(values[r], 0, a, r * cols, cols);
        }
    }

    public int rows() { return rows; }

    public int cols() { return cols; }

    public double get(int row, int col) {
        return a[row * cols + col];
    }

    public void set(int row, int col, double value) {
        a[row * cols + col] = value;
    }

    public Mat multiply(Mat other) {
        if (cols != other.rows) {
            throw new IllegalArgumentException(
                    "matrix dimension mismatch: " + rows + "x" + cols + " * " + other.rows + "x" + other.cols);
        }
        Mat result = new Mat(rows, other.cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < other.cols; c++) {
                double sum = 0.0;
                for (int k = 0; k < cols; k++) {
                    sum += get(r, k) * other.get(k, c);
                }
                result.set(r, c, sum);
            }
        }
        return result;
    }

    public Mat transpose() {
        Mat result = new Mat(cols, rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                result.set(c, r, get(r, c));
            }
        }
        return result;
    }

    public Mat inverse() {
        if (rows != cols) {
            throw new IllegalStateException("inverse requires a square matrix, got " + rows + "x" + cols);
        }
        int n = rows;
        Mat augmented = new Mat(n, 2 * n);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                augmented.set(r, c, get(r, c));
                augmented.set(r, n + c, r == c ? 1.0 : 0.0);
            }
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double best = Math.abs(augmented.get(col, col));
            for (int r = col + 1; r < n; r++) {
                double v = Math.abs(augmented.get(r, col));
                if (v > best) {
                    best = v;
                    pivot = r;
                }
            }
            if (best < 1e-12) {
                throw new IllegalStateException("matrix is singular and cannot be inverted");
            }
            if (pivot != col) {
                swapRows(augmented, pivot, col);
            }
            double divisor = augmented.get(col, col);
            for (int c = 0; c < 2 * n; c++) {
                augmented.set(col, c, augmented.get(col, c) / divisor);
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = augmented.get(r, col);
                if (factor == 0.0) {
                    continue;
                }
                for (int c = 0; c < 2 * n; c++) {
                    augmented.set(r, c, augmented.get(r, c) - factor * augmented.get(col, c));
                }
            }
        }
        Mat result = new Mat(n, n);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                result.set(r, c, augmented.get(r, n + c));
            }
        }
        return result;
    }

    public double det() {
        if (rows != cols) {
            throw new IllegalStateException("det requires a square matrix");
        }
        int n = rows;
        Mat work = copy();
        double determinant = 1.0;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double best = Math.abs(work.get(col, col));
            for (int r = col + 1; r < n; r++) {
                double v = Math.abs(work.get(r, col));
                if (v > best) {
                    best = v;
                    pivot = r;
                }
            }
            if (best < 1e-12) {
                return 0.0;
            }
            if (pivot != col) {
                swapRows(work, pivot, col);
                determinant = -determinant;
            }
            for (int r = col + 1; r < n; r++) {
                double factor = work.get(r, col) / work.get(col, col);
                for (int c = col; c < n; c++) {
                    work.set(r, c, work.get(r, c) - factor * work.get(col, c));
                }
            }
            determinant *= work.get(col, col);
        }
        return determinant;
    }

    public static Mat rodrigues(double rx, double ry, double rz) {
        double theta = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (theta < 1e-12) {
            Mat m = eye(3);
            m.set(0, 1, -rz);
            m.set(0, 2, ry);
            m.set(1, 0, rz);
            m.set(1, 2, -rx);
            m.set(2, 0, -ry);
            m.set(2, 1, rx);
            return m;
        }
        double nx = rx / theta;
        double ny = ry / theta;
        double nz = rz / theta;
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double oneMinusC = 1.0 - c;
        Mat k = new Mat(3, 3);
        k.set(0, 1, -nz);
        k.set(0, 2, ny);
        k.set(1, 0, nz);
        k.set(1, 2, -nx);
        k.set(2, 0, -ny);
        k.set(2, 1, nx);
        Mat i = eye(3);
        Mat kSquared = k.multiply(k);
        Mat result = new Mat(3, 3);
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 3; col++) {
                double value = i.get(r, col) + s * k.get(r, col) + oneMinusC * kSquared.get(r, col);
                result.set(r, col, value);
            }
        }
        return result;
    }

    private static void swapRows(Mat m, int r1, int r2) {
        for (int c = 0; c < m.cols; c++) {
            double tmp = m.get(r1, c);
            m.set(r1, c, m.get(r2, c));
            m.set(r2, c, tmp);
        }
    }

    public static Mat eye(int n) {
        Mat m = new Mat(n, n);
        for (int i = 0; i < n; i++) {
            m.set(i, i, 1.0);
        }
        return m;
    }

    public Mat copy() {
        Mat m = new Mat(rows, cols);
        System.arraycopy(a, 0, m.a, 0, a.length);
        return m;
    }

    public Mat scale(double s) {
        Mat m = new Mat(rows, cols);
        for (int i = 0; i < a.length; i++) {
            m.a[i] = a[i] * s;
        }
        return m;
    }
}
