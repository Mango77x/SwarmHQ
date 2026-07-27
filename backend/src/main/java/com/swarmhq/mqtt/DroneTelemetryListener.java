package com.swarmhq.mqtt;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.repository.DroneRepository;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscribes to drone telemetry and upserts the corresponding Drone row.
 * Alerting/business logic (low battery, geofencing, ...) is not this
 * listener's job - that's Sprint 8.
 */
@Component
public class DroneTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(DroneTelemetryListener.class);
    private static final String TOPIC_FILTER = "drones/+/telemetry";
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^drones/([^/]+)/telemetry$");
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final MqttClient mqttClient;
    private final DroneRepository droneRepository;
    private final ObjectMapper objectMapper;

    public DroneTelemetryListener(MqttClient mqttClient, DroneRepository droneRepository, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.droneRepository = droneRepository;
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
            applyTelemetry(externalId, payload);
        } catch (Exception e) {
            log.error("Failed to process telemetry from drone '{}': {}", externalId, e.getMessage(), e);
        }
    }

    private void applyTelemetry(String externalId, TelemetryPayload payload) {
        Drone drone = droneRepository.findByExternalId(externalId)
                .orElseGet(() -> new Drone(externalId, payload.type(), DroneStatus.valueOf(payload.status()), payload.batteryPercent()));

        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(payload.lon(), payload.lat())));
        drone.setBatteryPercent(payload.batteryPercent());
        drone.setStatus(DroneStatus.valueOf(payload.status()));
        drone.setLastUpdateAt(payload.timestamp() != null ? payload.timestamp() : Instant.now());

        droneRepository.save(drone);
    }
}
