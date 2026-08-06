package com.swarmhq.service;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.web.CreateMissionRequest;
import com.swarmhq.web.MissionResponse;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final MissionRepository missionRepository;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MissionService(MissionRepository missionRepository, MqttClient mqttClient, ObjectMapper objectMapper) {
        this.missionRepository = missionRepository;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    public List<MissionResponse> listAll() {
        return missionRepository.findAllWithDrone().stream()
                .map(MissionResponse::from)
                .toList();
    }

    public MissionResponse create(CreateMissionRequest request) {
        Coordinate[] coordinates = request.route().stream()
                .map(point -> new Coordinate(point[0], point[1]))
                .toArray(Coordinate[]::new);
        Mission mission = new Mission(GEOMETRY_FACTORY.createLineString(coordinates), request.priority());
        return MissionResponse.from(missionRepository.save(mission));
    }

    /**
     * Cancels a mission. A PENDING one was never handed to a drone, so this
     * just marks it CANCELLED directly. An ACTIVE one needs its drone
     * recalled first - this only publishes the cancel command; the mission
     * itself stays ACTIVE until the drone's own mission-status report comes
     * back through {@code MissionStatusListener}, the same eventual-consistency
     * path COMPLETED/FAILED already use, rather than the backend guessing
     * the outcome up front. Anything already terminal (COMPLETED/FAILED/
     * CANCELLED) is rejected - there's nothing left to cancel.
     */
    public MissionResponse cancel(Long id) {
        Mission mission = missionRepository.findByIdWithOptionalDrone(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission " + id + " not found"));

        return switch (mission.getStatus()) {
            case PENDING -> {
                mission.setStatus(MissionStatus.CANCELLED);
                yield MissionResponse.from(missionRepository.save(mission));
            }
            case ACTIVE -> {
                publishCancel(mission);
                yield MissionResponse.from(mission);
            }
            case COMPLETED, FAILED, CANCELLED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mission " + id + " is already " + mission.getStatus() + ", nothing to cancel");
        };
    }

    private void publishCancel(Mission mission) {
        String externalId = mission.getAssignedDrone().getExternalId();
        Map<String, Object> payload = Map.of("missionId", mission.getId());
        try {
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            mqttClient.publish("drones/" + externalId + "/mission/cancel", message);
            log.info("Requested cancel of mission {} ({} priority) on {}",
                    mission.getId(), mission.getPriority(), externalId);
        } catch (MqttException e) {
            log.error("Failed to publish cancel for mission {} to drone {}: {}",
                    mission.getId(), externalId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reach the drone to cancel mission " + mission.getId());
        }
    }
}
