package com.swarmhq.web;

import com.swarmhq.model.DroneStatus;

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
}
