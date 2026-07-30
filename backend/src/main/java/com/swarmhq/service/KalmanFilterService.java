package com.swarmhq.service;

import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooths noisy GPS telemetry per drone before it's persisted,
 * broadcast, or geofence-checked. Fuses each raw measurement with a
 * constant-velocity motion-model prediction ({@link KalmanFilter2D})
 * instead of trusting it verbatim, the way a real navigation system
 * handles sensor noise. Called from {@link DroneService#applyTelemetry}.
 *
 * Filter state only lives in memory, one {@link KalmanFilter2D} per
 * drone external id. A backend restart just means each filter
 * re-initializes from the next reading and re-converges over the
 * following few updates - an acceptable simplification for a local demo,
 * and arguably realistic too, since a real navigation filter also
 * reinitializes on a cold restart.
 */
@Service
public class KalmanFilterService {

    private final Map<String, KalmanFilter2D> filtersByDrone = new ConcurrentHashMap<>();

    /**
     * A drone's very first-ever reading has nothing to fuse with yet, so
     * it's returned unmodified (the standard Kalman filter initialization:
     * the first state estimate *is* the first measurement).
     */
    public Coordinate smooth(String externalId, double lon, double lat, Instant timestamp) {
        KalmanFilter2D existing = filtersByDrone.get(externalId);
        if (existing == null) {
            filtersByDrone.put(externalId, KalmanFilter2D.initialize(lon, lat, timestamp));
            return new Coordinate(lon, lat);
        }
        return existing.update(lon, lat, timestamp);
    }
}
