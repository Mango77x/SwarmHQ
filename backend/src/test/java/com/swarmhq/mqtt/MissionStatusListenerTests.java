package com.swarmhq.mqtt;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.MissionRepository;
import org.awaitility.Awaitility;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes a real mission-status message to Mosquitto and confirms
 * MissionStatusListener persists the outcome - the reply-side counterpart
 * to DroneTelemetryListenerTests. Not @Transactional, same reasoning as
 * that class: the listener's own save happens on a different thread/
 * transaction than this test method, so manual cleanup is used instead.
 */
@SpringBootTest
class MissionStatusListenerTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MqttClient publisher;
    private Drone drone;
    private Mission mission;

    @AfterEach
    void cleanup() throws Exception {
        if (publisher != null && publisher.isConnected()) {
            publisher.disconnect();
        }
        if (mission != null) {
            eventRepository.findAll().stream()
                    .filter(e -> e.getMission() != null && e.getMission().getId().equals(mission.getId()))
                    .forEach(eventRepository::delete);
            missionRepository.deleteById(mission.getId());
        }
        if (drone != null) {
            droneRepository.deleteById(drone.getId());
        }
    }

    @Test
    void marksMissionCompletedAndRaisesWaypointReachedEvent() throws Exception {
        givenAnActiveMission("test-drone-2");
        publishStatus(Map.of("missionId", mission.getId(), "status", "COMPLETED"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var updated = missionRepository.findById(mission.getId());
            assertTrue(updated.isPresent());
            assertEquals(MissionStatus.COMPLETED, updated.get().getStatus());
        });

        List<Event> events = missionEvents();
        assertEquals(1, events.size());
        assertEquals(EventType.WAYPOINT_REACHED, events.get(0).getType());
    }

    @Test
    void marksMissionFailedAndRaisesMissionFailedEvent() throws Exception {
        // A distinct identity from the COMPLETED test above, not a shared
        // one - avoids any possibility of interference between the two
        // (e.g. a QoS1 redelivery window overlapping) regardless of cause.
        givenAnActiveMission("test-drone-3");
        publishStatus(Map.of("missionId", mission.getId(), "status", "FAILED", "reason", "low_battery"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var updated = missionRepository.findById(mission.getId());
            assertTrue(updated.isPresent());
            assertEquals(MissionStatus.FAILED, updated.get().getStatus());
        });

        List<Event> events = missionEvents();
        assertEquals(1, events.size());
        assertEquals(EventType.MISSION_FAILED, events.get(0).getType());
        assertTrue(events.get(0).getDetail().contains("low_battery"));
    }

    private void givenAnActiveMission(String externalId) {
        // Provisioned test-only MQTT identity (infra/mosquitto/setup/generate.sh),
        // distinct from DroneTelemetryListenerTests's "test-drone-1" in case
        // both ever run concurrently - and not "drone-1" etc., which a real
        // simulator run might be using.
        drone = droneRepository.saveAndFlush(new Drone(externalId, "quadcopter", DroneStatus.ON_MISSION, 80));

        Mission newMission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(10.0, 10.0),
                        new Coordinate(10.01, 10.01)
                }),
                MissionPriority.MEDIUM);
        newMission.setAssignedDrone(drone);
        newMission.setStatus(MissionStatus.ACTIVE);
        mission = missionRepository.saveAndFlush(newMission);
    }

    private void publishStatus(Map<String, Object> payload) throws Exception {
        publisher = TestMqttPublishers.connect("mission-status-test-publisher", drone.getExternalId());

        MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
        message.setQos(1);
        publisher.publish("drones/" + drone.getExternalId() + "/mission-status", message);
    }

    private List<Event> missionEvents() {
        return eventRepository.findAll().stream()
                .filter(e -> e.getMission() != null && e.getMission().getId().equals(mission.getId()))
                .toList();
    }
}
