package com.swarmhq.mqtt;

import com.swarmhq.model.EventType;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.service.AlertService;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes the simulator's mission completion/failure feedback (Sprint
 * 10) - the reply half of MissionAssignmentService's assignment publish.
 * Same thin-listener/service-layer split as DroneTelemetryListener: the
 * actual persistence + broadcast is AlertService.raiseMissionEvent.
 */
@Component
public class MissionStatusListener {

    private static final Logger log = LoggerFactory.getLogger(MissionStatusListener.class);
    private static final String TOPIC_FILTER = "drones/+/mission-status";
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^drones/([^/]+)/mission-status$");

    private final MqttClient mqttClient;
    private final MissionRepository missionRepository;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public MissionStatusListener(MqttClient mqttClient, MissionRepository missionRepository,
            AlertService alertService, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.missionRepository = missionRepository;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void subscribe() throws MqttException {
        // Same Paho 1.2.5 subscribe() overload workaround as DroneTelemetryListener.
        mqttClient.subscribe(
                new MqttSubscription[] {new MqttSubscription(TOPIC_FILTER, 1)},
                new IMqttMessageListener[] {this::onMessage});
        log.info("Subscribed to {}", TOPIC_FILTER);
    }

    private void onMessage(String topic, MqttMessage message) {
        Matcher matcher = TOPIC_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            log.warn("Ignoring mission status on unexpected topic {}", topic);
            return;
        }
        String externalId = matcher.group(1);

        try {
            MissionStatusPayload payload = objectMapper.readValue(message.getPayload(), MissionStatusPayload.class);
            applyStatus(externalId, payload);
        } catch (Exception e) {
            log.error("Failed to process mission status from drone '{}': {}", externalId, e.getMessage(), e);
        }
    }

    private void applyStatus(String externalId, MissionStatusPayload payload) {
        Mission mission = missionRepository.findByIdWithDrone(payload.missionId()).orElse(null);
        if (mission == null) {
            log.warn("Ignoring status for unknown mission {} (reported by {})", payload.missionId(), externalId);
            return;
        }
        // MQTT QoS1 is "at least once" - a redelivered duplicate (e.g. the
        // publisher retransmitting because a PUBACK arrived slower than
        // its retry timer, more likely now that the handshake is TLS) is
        // expected, spec-compliant behavior, not a bug. COMPLETED/FAILED
        // are terminal, so once a mission has already reached one, a
        // repeat of the same report is a no-op instead of a second Event.
        if (mission.getStatus() != MissionStatus.ACTIVE) {
            log.debug("Ignoring mission status for {} - already {} (likely a QoS1 redelivery)",
                    payload.missionId(), mission.getStatus());
            return;
        }

        switch (payload.status()) {
            case "COMPLETED" -> {
                mission.setStatus(MissionStatus.COMPLETED);
                missionRepository.save(mission);
                alertService.raiseMissionEvent(mission, EventType.WAYPOINT_REACHED,
                        "Mission " + mission.getId() + " completed");
            }
            case "FAILED" -> {
                mission.setStatus(MissionStatus.FAILED);
                missionRepository.save(mission);
                String detail = "Mission " + mission.getId() + " failed"
                        + (payload.reason() != null ? " (" + payload.reason() + ")" : "");
                alertService.raiseMissionEvent(mission, EventType.MISSION_FAILED, detail);
            }
            default -> log.warn("Ignoring unknown mission status '{}' for mission {}", payload.status(), payload.missionId());
        }
    }
}
