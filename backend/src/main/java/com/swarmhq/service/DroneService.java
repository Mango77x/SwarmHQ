package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.mqtt.TelemetryPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.web.DroneResponse;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class DroneService {

    /**
     * Every client subscribes to this single topic and upserts by
     * externalId - simpler than a per-drone destination, and the expected
     * fleet size (single-digit to low-tens of drones) doesn't need the
     * fan-out savings a per-drone topic would give.
     */
    public static final String DRONE_UPDATES_TOPIC = "/topic/drones";

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final DroneRepository droneRepository;
    private final AlertService alertService;
    private final SimpMessagingTemplate messagingTemplate;

    public DroneService(DroneRepository droneRepository, AlertService alertService, SimpMessagingTemplate messagingTemplate) {
        this.droneRepository = droneRepository;
        this.alertService = alertService;
        this.messagingTemplate = messagingTemplate;
    }

    public List<DroneResponse> listAll() {
        return droneRepository.findAll().stream()
                .map(DroneService::toResponse)
                .toList();
    }

    /**
     * Upserts the drone identified by externalId with a freshly received
     * telemetry reading, then pushes its new state to every client
     * subscribed to {@link #DRONE_UPDATES_TOPIC} - the live counterpart to
     * {@link #listAll()}'s REST snapshot (Sprint 7).
     */
    public void applyTelemetry(String externalId, TelemetryPayload payload) {
        Optional<Drone> existing = droneRepository.findByExternalId(externalId);
        Integer previousBatteryPercent = existing.map(Drone::getBatteryPercent).orElse(null);
        DroneStatus previousStatus = existing.map(Drone::getStatus).orElse(null);
        Point previousPosition = existing.map(Drone::getPosition).orElse(null);

        Drone drone = existing
                .orElseGet(() -> new Drone(externalId, payload.type(), DroneStatus.valueOf(payload.status()), payload.batteryPercent()));

        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(payload.lon(), payload.lat())));
        drone.setBatteryPercent(payload.batteryPercent());
        drone.setStatus(DroneStatus.valueOf(payload.status()));
        drone.setLastUpdateAt(payload.timestamp() != null ? payload.timestamp() : Instant.now());

        droneRepository.save(drone);
        alertService.evaluate(drone, previousBatteryPercent, previousStatus, previousPosition);
        messagingTemplate.convertAndSend(DRONE_UPDATES_TOPIC, toResponse(drone));
    }

    private static DroneResponse toResponse(Drone drone) {
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
