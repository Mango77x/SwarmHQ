package com.swarmhq.mqtt;

import com.swarmhq.service.DroneService;
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
 * Translates drone telemetry off MQTT into a {@link DroneService} call -
 * persistence and the live STOMP push both live there (same
 * thin-listener/service-layer split as {@code DroneController}). Alerting
 * and other business logic (low battery, geofencing, ...) lives in
 * AlertService, wired in through DroneService rather than handled here.
 */
@Component
public class DroneTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(DroneTelemetryListener.class);
    private static final String TOPIC_FILTER = "drones/+/telemetry";
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^drones/([^/]+)/telemetry$");

    private final MqttClient mqttClient;
    private final DroneService droneService;
    private final ObjectMapper objectMapper;

    public DroneTelemetryListener(MqttClient mqttClient, DroneService droneService, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.droneService = droneService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void subscribe() throws MqttException {
        // Paho 1.2.5's subscribe(String, int, IMqttMessageListener) overload
        // recurses into itself indefinitely (a bug in that version) - the
        // MqttSubscription[]/IMqttMessageListener[] overload below is the
        // one that actually works.
        mqttClient.subscribe(
                new MqttSubscription[] {new MqttSubscription(TOPIC_FILTER, 1)},
                new IMqttMessageListener[] {this::onMessage});
        log.info("Subscribed to {}", TOPIC_FILTER);
    }

    private void onMessage(String topic, MqttMessage message) {
        Matcher matcher = TOPIC_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            log.warn("Ignoring telemetry on unexpected topic {}", topic);
            return;
        }
        String externalId = matcher.group(1);

        try {
            TelemetryPayload payload = objectMapper.readValue(message.getPayload(), TelemetryPayload.class);
            droneService.applyTelemetry(externalId, payload);
        } catch (Exception e) {
            log.error("Failed to process telemetry from drone '{}': {}", externalId, e.getMessage(), e);
        }
    }
}
