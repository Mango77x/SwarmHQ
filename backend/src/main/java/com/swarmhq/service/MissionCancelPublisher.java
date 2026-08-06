package com.swarmhq.service;

import com.swarmhq.model.Mission;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Publishes the {@code drones/{externalId}/mission/cancel} MQTT command
 * for an ACTIVE mission's assigned drone - extracted out of
 * {@link MissionService} so it can be reused by {@link AlertService}'s
 * geofence auto-recall (a drone entering a RiskZone) without AlertService
 * having to depend on the whole of MissionService, which would form a
 * cycle back through MissionAssigner -> DroneService -> AlertService.
 * The mission itself stays ACTIVE until the drone's own mission-status
 * report comes back through MissionStatusListener - same
 * eventual-consistency pattern as completion/failure, this only requests
 * the cancel.
 */
@Component
public class MissionCancelPublisher {

    private static final Logger log = LoggerFactory.getLogger(MissionCancelPublisher.class);

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MissionCancelPublisher(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    public void publish(Mission mission) {
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
