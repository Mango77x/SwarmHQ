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
 * strategies, the centralized {@link MissionAssignmentService} and the
 * auction-based {@link AuctionCoordinatorService}, so a drone never has
 * to know or care which one decided it should fly a given mission. The
 * assignment contract it receives looks identical either way.
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

    /**
     * Returns whether the MQTT publish actually went out. The mission/drone
     * DB state is committed either way - a failed publish still leaves the
     * mission {@code ACTIVE} and the drone {@code ON_MISSION}, since there's
     * no clean way to roll either back once other state may already depend
     * on it (a caller in the middle of picking the *next* PENDING mission,
     * for instance). {@link MissionAssignmentService} and
     * {@link AuctionCoordinatorService} both currently ignore this return
     * value, same as before this existed - a scheduled pass has no operator
     * waiting on an HTTP response to tell. {@link MissionService#assignManually}
     * does check it, since that call is a direct operator action and can
     * meaningfully surface "the drone never actually got the order" as a
     * 503 instead of reporting success on a mission that's silently stuck.
     */
    public boolean assign(Mission mission, Drone drone) {
        mission.setAssignedDrone(drone);
        mission.setStatus(MissionStatus.ACTIVE);
        missionRepository.save(mission);
        droneService.markOnMission(drone);
        boolean published = publishAssignment(mission, drone);
        log.info("Assigned mission {} ({} priority) to {}", mission.getId(), mission.getPriority(), drone.getExternalId());
        return published;
    }

    private boolean publishAssignment(Mission mission, Drone drone) {
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
            return true;
        } catch (MqttException e) {
            log.error("Failed to publish mission assignment for mission {} to drone {}: {}",
                    mission.getId(), drone.getExternalId(), e.getMessage(), e);
            return false;
        }
    }
}
