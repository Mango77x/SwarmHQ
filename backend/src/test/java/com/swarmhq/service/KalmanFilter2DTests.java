package com.swarmhq.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure math, no Spring context - verifies the actual claim "Kalman
 * filtering reduces noise", not just that the predict/update recursion
 * runs without throwing. A fixed seed keeps this deterministic.
 */
class KalmanFilter2DTests {

    // Matches simulator/config.py's GPS_NOISE_STD_METERS default.
    private static final double NOISE_STD_DEGREES = 5.0 / 111_320.0;

    @Test
    void smoothsNoisyMeasurementsOfAMovingTrajectoryBetterThanRawReadings() {
        Random random = new Random(42);
        double lonPerTick = 0.0006; // one patrol-leg-per-10-ticks worth of ground speed
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        double trueLon = -3.7038;
        double trueLat = 40.4168;
        KalmanFilter2D filter = KalmanFilter2D.initialize(
                trueLon + random.nextGaussian() * NOISE_STD_DEGREES,
                trueLat + random.nextGaussian() * NOISE_STD_DEGREES,
                start);

        double rawSquaredErrorSum = 0;
        double filteredSquaredErrorSum = 0;
        int steps = 60;
        for (int i = 1; i <= steps; i++) {
            trueLon += lonPerTick;
            Instant timestamp = start.plusSeconds(2L * i);

            double measuredLon = trueLon + random.nextGaussian() * NOISE_STD_DEGREES;
            double measuredLat = trueLat + random.nextGaussian() * NOISE_STD_DEGREES;

            Coordinate filtered = filter.update(measuredLon, measuredLat, timestamp);

            double rawError = measuredLon - trueLon;
            double filteredError = filtered.x - trueLon;
            rawSquaredErrorSum += rawError * rawError;
            filteredSquaredErrorSum += filteredError * filteredError;
        }

        double rawRmse = Math.sqrt(rawSquaredErrorSum / steps);
        double filteredRmse = Math.sqrt(filteredSquaredErrorSum / steps);
        assertTrue(filteredRmse < rawRmse,
                "expected filtered RMSE (" + filteredRmse + ") < raw RMSE (" + rawRmse + ")");
    }

    @Test
    void convergesTowardsTheTrueStationaryPositionDespiteNoise() {
        Random random = new Random(7);
        double trueLon = 10.0;
        double trueLat = 10.0;
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        KalmanFilter2D filter = KalmanFilter2D.initialize(trueLon, trueLat, start);
        Coordinate last = null;
        for (int i = 1; i <= 30; i++) {
            double measuredLon = trueLon + random.nextGaussian() * NOISE_STD_DEGREES;
            double measuredLat = trueLat + random.nextGaussian() * NOISE_STD_DEGREES;
            last = filter.update(measuredLon, measuredLat, start.plusSeconds(2L * i));
        }

        double distanceDegrees = Math.hypot(last.x - trueLon, last.y - trueLat);
        // A converged multi-measurement estimate should land much closer
        // to the truth than a single raw noisy reading typically would,
        // which is the proof the filter is actually averaging the noise
        // down rather than just repeating the latest measurement back.
        assertTrue(distanceDegrees < NOISE_STD_DEGREES,
                "expected convergence within " + NOISE_STD_DEGREES + " deg of true position, was " + distanceDegrees);
    }
}
