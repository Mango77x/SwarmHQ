package com.swarmhq.service;

import org.locationtech.jts.geom.Coordinate;

import java.time.Duration;
import java.time.Instant;

/**
 * A per-drone constant-velocity 2D Kalman filter (Sprint 12) - state
 * {@code [lon, lat, vLon, vLat]}. Smooths noisy GPS measurements by fusing
 * each one with a motion-model prediction instead of trusting it verbatim
 * - real sensor fusion, not just averaging.
 *
 * Not a Spring bean: {@link KalmanFilterService} owns one mutable instance
 * per drone. Matrix operations below are small, generic 4x4/4x1 helpers
 * (not a linear-algebra library dependency) since every matrix here is a
 * fixed 4x4 or smaller - deliberately not the full continuous
 * white-noise-acceleration form for Q, which would need a much more
 * involved derivation for a marginal accuracy gain at this project's
 * scale; correctness of the predict/update recursion itself is what
 * demonstrates the sensor-fusion concept.
 */
class KalmanFilter2D {

    // Degrees^2/sec - process noise: how much the constant-velocity model
    // is trusted vs. how sharply a drone might actually maneuver.
    private static final double POSITION_PROCESS_NOISE = 1e-10;
    private static final double VELOCITY_PROCESS_NOISE = 1e-10;
    // Degrees^2 - assumed GPS measurement noise. A real filter has no
    // privileged knowledge of a simulator's *actual* injected noise; this
    // is tuned to roughly the same order of magnitude as it (see
    // simulator/config.py's GPS_NOISE_STD_METERS), the same way a real
    // system's R comes from a sensor's known characteristics, not the
    // ground truth it's trying to recover.
    private static final double MEASUREMENT_NOISE = 4e-9;

    private double[] x; // [lon, lat, vLon, vLat]
    private double[][] p; // 4x4 covariance
    private Instant lastUpdateAt;

    private KalmanFilter2D(double lon, double lat, Instant timestamp) {
        this.x = new double[] {lon, lat, 0, 0};
        this.p = new double[][] {
                {1.0, 0, 0, 0},
                {0, 1.0, 0, 0},
                {0, 0, 0.01, 0},
                {0, 0, 0, 0.01},
        };
        this.lastUpdateAt = timestamp;
    }

    static KalmanFilter2D initialize(double lon, double lat, Instant timestamp) {
        return new KalmanFilter2D(lon, lat, timestamp);
    }

    /** Predicts forward to {@code timestamp}, corrects with the new
     * measurement, and returns the resulting smoothed position estimate. */
    Coordinate update(double measuredLon, double measuredLat, Instant timestamp) {
        double dtSeconds = Math.max(0.001, Duration.between(lastUpdateAt, timestamp).toMillis() / 1000.0);
        predict(dtSeconds);
        correct(measuredLon, measuredLat);
        lastUpdateAt = timestamp;
        return new Coordinate(x[0], x[1]);
    }

    private void predict(double dt) {
        double[][] f = {
                {1, 0, dt, 0},
                {0, 1, 0, dt},
                {0, 0, 1, 0},
                {0, 0, 0, 1},
        };
        x = multiply(f, x);

        double[][] q = {
                {POSITION_PROCESS_NOISE * dt, 0, 0, 0},
                {0, POSITION_PROCESS_NOISE * dt, 0, 0},
                {0, 0, VELOCITY_PROCESS_NOISE * dt, 0},
                {0, 0, 0, VELOCITY_PROCESS_NOISE * dt},
        };
        p = add(multiply(multiply(f, p), transpose(f)), q);
    }

    private void correct(double measuredLon, double measuredLat) {
        double innovationLon = measuredLon - x[0];
        double innovationLat = measuredLat - x[1];

        // S = H P H^T + R, where H picks out [lon, lat] - so H P H^T is
        // just P's top-left 2x2 block.
        double s00 = p[0][0] + MEASUREMENT_NOISE;
        double s01 = p[0][1];
        double s10 = p[1][0];
        double s11 = p[1][1] + MEASUREMENT_NOISE;
        double determinant = s00 * s11 - s01 * s10;
        double[][] sInverse = {
                {s11 / determinant, -s01 / determinant},
                {-s10 / determinant, s00 / determinant},
        };

        // K = P H^T S^-1 - P H^T is just P's first two columns.
        double[][] pht = {
                {p[0][0], p[0][1]},
                {p[1][0], p[1][1]},
                {p[2][0], p[2][1]},
                {p[3][0], p[3][1]},
        };
        double[][] k = multiply(pht, sInverse);

        x[0] += k[0][0] * innovationLon + k[0][1] * innovationLat;
        x[1] += k[1][0] * innovationLon + k[1][1] * innovationLat;
        x[2] += k[2][0] * innovationLon + k[2][1] * innovationLat;
        x[3] += k[3][0] * innovationLon + k[3][1] * innovationLat;

        // P = (I - K H) P - K H has K's two columns in positions 0/1 (H's
        // non-zero columns), zero elsewhere.
        double[][] kh = new double[4][4];
        for (int row = 0; row < 4; row++) {
            kh[row][0] = k[row][0];
            kh[row][1] = k[row][1];
        }
        p = multiply(subtract(identity4(), kh), p);
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            double sum = 0;
            for (int col = 0; col < vector.length; col++) {
                sum += matrix[row][col] * vector[col];
            }
            result[row] = sum;
        }
        return result;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        int rows = a.length;
        int cols = b[0].length;
        int inner = b.length;
        double[][] result = new double[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double sum = 0;
                for (int k = 0; k < inner; k++) {
                    sum += a[row][k] * b[k][col];
                }
                result[row][col] = sum;
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] matrix) {
        double[][] result = new double[matrix[0].length][matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int col = 0; col < matrix[0].length; col++) {
                result[col][row] = matrix[row][col];
            }
        }
        return result;
    }

    private static double[][] add(double[][] a, double[][] b) {
        double[][] result = new double[a.length][a[0].length];
        for (int row = 0; row < a.length; row++) {
            for (int col = 0; col < a[0].length; col++) {
                result[row][col] = a[row][col] + b[row][col];
            }
        }
        return result;
    }

    private static double[][] subtract(double[][] a, double[][] b) {
        double[][] result = new double[a.length][a[0].length];
        for (int row = 0; row < a.length; row++) {
            for (int col = 0; col < a[0].length; col++) {
                result[row][col] = a[row][col] - b[row][col];
            }
        }
        return result;
    }

    private static double[][] identity4() {
        double[][] result = new double[4][4];
        for (int i = 0; i < 4; i++) {
            result[i][i] = 1;
        }
        return result;
    }
}
