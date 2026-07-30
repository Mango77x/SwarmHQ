package com.swarmhq.service;

import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-mutable replacement for what used to be a fixed
 * {@code swarmhq.mission-assignment.mode} read once at startup. Both
 * {@link MissionAssignmentService} and {@link AuctionCoordinatorService}
 * now always exist as beans, each checking this holder on its own tick,
 * rather than one of them being permanently absent for the app's whole
 * lifetime via {@code @ConditionalOnProperty}. This is what makes the two
 * strategies toggleable from the frontend without a restart.
 *
 * Every change goes out on two channels rather than just sitting in
 * memory: a retained MQTT message on {@link #MODE_TOPIC} so the
 * simulator's own behavior (boids movement, whether a drone bids on
 * missions) follows live instead of only reading a fixed env var at its
 * own startup, and a STOMP broadcast on {@link #MODE_UPDATES_TOPIC} so
 * every connected frontend tab stays in sync - the same push-don't-poll
 * pattern as every other live update in this project.
 */
@Component
public class MissionAssignmentModeHolder {

    public enum Mode {
        CENTRALIZED, AUCTION;

        static Mode parse(String raw) {
            return "auction".equalsIgnoreCase(raw) ? AUCTION : CENTRALIZED;
        }

        public String wireValue() {
            return this == AUCTION ? "auction" : "centralized";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MissionAssignmentModeHolder.class);

    public static final String MODE_TOPIC = "system/mission-assignment-mode";
    public static final String MODE_UPDATES_TOPIC = "/topic/mode";

    private final MqttClient mqttClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Mode> current;

    public MissionAssignmentModeHolder(MqttClient mqttClient, SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper,
            @Value("${swarmhq.mission-assignment.mode:centralized}") String initialMode) {
        this.mqttClient = mqttClient;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.current = new AtomicReference<>(Mode.parse(initialMode));
    }

    /**
     * Published retained on startup as well as on every future change - a
     * simulator connecting before anyone ever touches the toggle still
     * needs to learn the real mode immediately rather than guessing from
     * its own local default.
     */
    @PostConstruct
    void publishInitial() {
        publish(current.get());
    }

    public Mode get() {
        return current.get();
    }

    public void set(Mode mode) {
        Mode previous = current.getAndSet(mode);
        if (previous != mode) {
            log.info("Mission-assignment mode changed: {} -> {}", previous, mode);
            publish(mode);
        }
    }

    private void publish(Mode mode) {
        Map<String, String> payload = Map.of("mode", mode.wireValue());
        try {
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            message.setRetained(true);
            mqttClient.publish(MODE_TOPIC, message);
        } catch (MqttException e) {
            log.error("Failed to publish mission-assignment mode to MQTT: {}", e.getMessage(), e);
        }
        messagingTemplate.convertAndSend(MODE_UPDATES_TOPIC, payload);
    }
}
