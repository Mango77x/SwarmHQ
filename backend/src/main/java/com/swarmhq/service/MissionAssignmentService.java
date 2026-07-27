package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The "constrained mission assignment" engine (Sprint 10) - a greedy
 * scheduled pass matching {@code PENDING} missions to eligible drones,
 * turning the project from "a panel that shows data" into "a system that
 * makes decisions" (see PROJECT_OVERVIEW.md).
 */
@Service
public class MissionAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(MissionAssignmentService.class);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * A drone this close to needing to RTB shouldn't be handed a mission
     * it might not finish. Built off AlertService's threshold rather than
     * a second unrelated magic number.
     */
    static final int MIN_BATTERY_PERCENT = AlertService.LOW_BATTERY_THRESHOLD + 10;

    private final MissionRepository missionRepository;
    private final DroneRepository droneRepository;
    private final DroneService droneService;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final boolean schedulerEnabled;

    public MissionAssignmentService(MissionRepository missionRepository, DroneRepository droneRepository,
            DroneService droneService, MqttClient mqttClient, ObjectMapper objectMapper,
            @Value("${swarmhq.mission-assignment.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.missionRepository = missionRepository;
        this.droneRepository = droneRepository;
        this.droneService = droneService;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Disabled in tests (see src/test/resources/application.properties) -
     * a background tick racing against tests that assert on Mission/KPI
     * state would make them flaky, since MissionAssignmentServiceTests
     * already calls {@link #assignPendingMissions()} directly and doesn't
     * need the schedule itself under test.
     */
    @Scheduled(fixedDelay = 5000)
    void scheduledAssignment() {
        if (schedulerEnabled) {
            assignPendingMissions();
        }
    }

    /**
     * Re-evaluates every PENDING mission each tick (not just newly created
     * ones) so a mission left unassigned because no drone qualified last
     * time gets picked up automatically once one does (e.g. a drone
     * recharges past {@link #MIN_BATTERY_PERCENT}).
     */
    public void assignPendingMissions() {
        List<Mission> pending = missionRepository.findByStatusOrderByCreatedAtAsc(MissionStatus.PENDING);
        // Stable sort: priority order first, oldest-within-priority second.
        pending.sort(Comparator.comparing(Mission::getPriority).reversed());

        for (Mission mission : pending) {
            // Queried fresh per mission (not a single snapshot up front) so
            // a drone claimed by an earlier mission in this same pass is
            // already excluded - MissionAssignmentService.assign() commits
            // ON_MISSION synchronously before moving to the next mission.
            droneRepository.findBestForMission(MIN_BATTERY_PERCENT, missionStart(mission))
                    .ifPresent(drone -> assign(mission, drone));
        }
    }

    private Point missionStart(Mission mission) {
        return GEOMETRY_FACTORY.createPoint(mission.getRoute().getCoordinateN(0));
    }

    private void assign(Mission mission, Drone drone) {
        mission.setAssignedDrone(drone);
        mission.setStatus(MissionStatus.ACTIVE);
        missionRepository.save(mission);
        droneService.markOnMission(drone);
        publishAssignment(mission, drone);
        log.info("Assigned mission {} ({} priority) to {}", mission.getId(), mission.getPriority(), drone.getExternalId());
    }

    private void publishAssignment(Mission mission, Drone drone) {
        List<double[]> route = Arrays.stream(mission.getRoute().getCoordinates())
                .map(c -> new double[] {c.x, c.y})
                .toList();
        Map<String, Object> payload = Map.of(
                "missionId", mission.getId(),
                "route", route,
                "priority", mission.getPriority().name());
        try {
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            mqttClient.publish("drones/" + drone.getExternalId() + "/mission", message);
        } catch (MqttException e) {
            log.error("Failed to publish mission assignment for mission {} to drone {}: {}",
                    mission.getId(), drone.getExternalId(), e.getMessage(), e);
        }
    }
}
