package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.MissionRepository;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The one place a mission actually gets handed to a drone -
 * {@code Mission}/{@code Drone} state changes plus the
 * {@code drones/{externalId}/mission} publish. Shared by both assignment
 * strategies (Sprint 10's centralized {@link MissionAssignmentService} and
 * Sprint 14's {@link AuctionCoordinatorService}) precisely so a drone never
 * has to know or care which one decided it should fly a given mission - the
 * assignment contract it receives is identical either way.
 */
@Component
public class MissionAssigner {

    private static final Logger log = LoggerFactory.getLogger(MissionAssigner.class);

    private final MissionRepository missionRepository;
    private final DroneService droneService;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MissionAssigner(MissionRepository missionRepository, DroneService droneService,
            MqttClient mqttClient, ObjectMapper objectMapper) {
        this.missionRepository = missionRepository;
        this.droneService = droneService;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    public void assign(Mission mission, Drone drone) {
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
