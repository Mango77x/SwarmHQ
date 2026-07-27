package com.swarmhq.web;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/**
 * Last known drone state for the tactical map. Deliberately doesn't expose
 * the internal database id - externalId is the public identifier.
 */
public record DroneResponse(
        String externalId,
        String type,
        Double lat,
        Double lon,
        int batteryPercent,
        DroneStatus status,
        Instant lastUpdateAt
) {
    public static DroneResponse from(Drone drone) {
        Point position = drone.getPosition();
        Double lat = position != null ? position.getY() : null;
        Double lon = position != null ? position.getX() : null;
        return new DroneResponse(
                drone.getExternalId(),
                drone.getType(),
                lat,
                lon,
                drone.getBatteryPercent(),
                drone.getStatus(),
                drone.getLastUpdateAt());
    }
}
