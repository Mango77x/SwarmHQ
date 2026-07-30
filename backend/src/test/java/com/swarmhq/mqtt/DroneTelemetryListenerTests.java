package com.swarmhq.mqtt;

import com.swarmhq.model.DroneStatus;
import com.swarmhq.repository.DroneRepository;
import org.awaitility.Awaitility;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes a real telemetry message to Mosquitto and confirms the app's
 * listener actually persists it - proves the MQTT ingest path end to end,
 * not just that the classes compile.
 */
@SpringBootTest
class DroneTelemetryListenerTests {

    // Provisioned test-only MQTT identity (infra/mosquitto/setup/generate.sh)
    // - not "drone-1" etc., which a real simulator run might be using
    // concurrently, and the ACL's per-drone pattern requires this to match
    // the authenticated username exactly.
    private static final String EXTERNAL_ID = "test-drone-1";

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MqttClient publisher;

    @AfterEach
    void cleanup() throws Exception {
        if (publisher != null && publisher.isConnected()) {
            publisher.disconnect();
        }
        droneRepository.findByExternalId(EXTERNAL_ID).ifPresent(droneRepository::delete);
    }

    @Test
    void ingestsTelemetryPublishedOverMqtt() throws Exception {
        publisher = TestMqttPublishers.connect("telemetry-test-publisher", EXTERNAL_ID);

        Map<String, Object> payload = Map.of(
                "type", "quadcopter",
                "lat", 40.4168,
                "lon", -3.7038,
                "batteryPercent", 76,
                "status", "PATROLLING",
                "timestamp", Instant.now().toString()
        );
        MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
        message.setQos(1);
        publisher.publish("drones/" + EXTERNAL_ID + "/telemetry", message);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            // assertTrue instead of orElseThrow, so a not-yet-persisted
            // drone raises an AssertionError Awaitility retries on rather
            // than an exception it treats as a hard failure on the first
            // poll.
            var maybeDrone = droneRepository.findByExternalId(EXTERNAL_ID);
            assertTrue(maybeDrone.isPresent(), "drone not persisted yet");
            var drone = maybeDrone.get();
            assertEquals(DroneStatus.PATROLLING, drone.getStatus());
            assertEquals(76, drone.getBatteryPercent());
            assertNotNull(drone.getPosition());
            assertEquals(-3.7038, drone.getPosition().getX(), 1e-9);
            assertEquals(40.4168, drone.getPosition().getY(), 1e-9);
        });
    }
}
