package com.swarmhq.web;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import org.locationtech.jts.geom.Coordinate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Same "no internal ids, [lon,lat] pairs" conventions as DroneResponse/
 * ZoneResponse.
 */
public record MissionResponse(
        Long id,
        List<double[]> route,
        String assignedDroneExternalId,
        MissionStatus status,
        MissionPriority priority,
        Instant createdAt,
        String createdBy,
        String lastModifiedBy,
        Instant lastModifiedAt
) {
    public static MissionResponse from(Mission mission) {
        Coordinate[] coordinates = mission.getRoute().getCoordinates();
        List<double[]> route = Arrays.stream(coordinates)
                .map(c -> new double[] {c.x, c.y})
                .toList();
        return new MissionResponse(
                mission.getId(),
                route,
                mission.getAssignedDrone() != null ? mission.getAssignedDrone().getExternalId() : null,
                mission.getStatus(),
                mission.getPriority(),
                mission.getCreatedAt(),
                mission.getCreatedBy(),
                mission.getLastModifiedBy(),
                mission.getLastModifiedAt());
    }
}
