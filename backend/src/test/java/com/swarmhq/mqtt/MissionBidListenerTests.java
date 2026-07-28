package com.swarmhq.mqtt;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real MQTT path end to end (publish a bid -> MissionBidListener
 * -> AuctionCoordinatorService.recordBid -> assignment on close), unlike
 * AuctionCoordinatorServiceTests, which drives open/bid/close as direct
 * method calls. Runs with the real scheduler (unlike that class) since
 * there's no other way to observe "the auction actually opened" - the test
 * re-publishes the bid on every Awaitility poll instead of trying to time
 * a single publish against the 1s tick; recordBid() is a no-op until an
 * auction is open, so the first publish that lands after it opens is the
 * one that counts. Not @Transactional, same reasoning as
 * MissionStatusListenerTests: the listener's own save happens on a
 * different thread/transaction than this test method.
 *
 * {@code @DirtiesContext}: see AuctionCoordinatorServiceTests's javadoc -
 * this class's {@code auction-window-seconds=2} (vs. that class's
 * {@code =0}) is itself a distinct property set from *both* the default
 * context and that one, so without this its own live MQTT connection
 * would linger and double-process messages for whatever test class
 * happens to run after it too.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "swarmhq.mission-assignment.mode=auction",
        "swarmhq.mission-assignment.auction-window-seconds=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MissionBidListenerTests {

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
            missionRepository.deleteById(mission.getId());
        }
        if (drone != null) {
            // Assignment raises a STATUS_CHANGE event (AlertService,
            // PATROLLING -> ON_MISSION) - has to go before the drone
            // itself, same FK-ordering reasoning as MissionStatusListenerTests.
            eventRepository.findAll().stream()
                    .filter(e -> e.getDrone().getId().equals(drone.getId()))
                    .forEach(eventRepository::delete);
            droneRepository.deleteById(drone.getId());
        }
    }

    @Test
    void winningBidPublishedOverMqttGetsAssigned() throws Exception {
        // Far from Madrid: keeps a real simulator/seeded mission's own
        // drones from ever out-bidding this test's drone by pure
        // proximity - not that it matters much here since it's the only
        // bidder, but consistent with every other assignment test's habit.
        drone = droneRepository.saveAndFlush(
                positioned(new Drone("test-drone-4", "quadcopter", DroneStatus.PATROLLING, 90), 30.0, 30.0));
        mission = missionRepository.saveAndFlush(new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(30.0, 30.0),
                        new Coordinate(30.01, 30.01)
                }),
                MissionPriority.MEDIUM));

        publisher = TestMqttPublishers.connect("mission-bid-test-publisher", drone.getExternalId());
        byte[] bidPayload = objectMapper.writeValueAsBytes(Map.of("droneId", drone.getExternalId(), "cost", 1.0));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            MqttMessage message = new MqttMessage(bidPayload);
            message.setQos(1);
            publisher.publish("missions/" + mission.getId() + "/bids", message);

            var updated = missionRepository.findById(mission.getId());
            assertTrue(updated.isPresent());
            assertEquals(MissionStatus.ACTIVE, updated.get().getStatus());
        });

        assertEquals(drone.getId(), missionRepository.findById(mission.getId()).orElseThrow().getAssignedDrone().getId());
        assertEquals(DroneStatus.ON_MISSION, droneRepository.findById(drone.getId()).orElseThrow().getStatus());
    }

    private static Drone positioned(Drone drone, double lat, double lon) {
        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat)));
        return drone;
    }
}
