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

    /**
     * Flips a drone to {@code ON_MISSION} the moment
     * {@code MissionAssignmentService} assigns it a mission (Sprint 10) -
     * not waiting for the simulator's next telemetry tick to confirm it,
     * since the backend is the one deciding the assignment and needs the
     * drone to read as unavailable immediately (otherwise the same
     * assignment pass could hand it a second mission before its own
     * telemetry catches up). Battery/position are unchanged, so only the
     * status-change check in {@link AlertService#evaluate} does anything
     * here. The reverse transition (mission ends → back to
     * {@code PATROLLING}) needs no equivalent method - the simulator
     * resumes patrolling on its own and reports it via ordinary telemetry,
     * same as any other status change.
     */
    public void markOnMission(Drone drone) {
        DroneStatus previousStatus = drone.getStatus();
        drone.setStatus(DroneStatus.ON_MISSION);
        droneRepository.save(drone);
        alertService.evaluate(drone, drone.getBatteryPercent(), previousStatus, drone.getPosition());
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
