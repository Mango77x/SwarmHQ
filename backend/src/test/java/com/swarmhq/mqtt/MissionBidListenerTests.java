package com.swarmhq.mqtt;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.service.MissionAssignmentModeHolder;
import org.awaitility.Awaitility;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real MQTT path end to end (publish a bid -> MissionBidListener
 * -> AuctionCoordinatorService.recordBid -> assignment on close), unlike
 * AuctionCoordinatorServiceTests, which drives open/bid/close as direct
 * method calls. Runs with the real scheduler since there's no other way to
 * observe "the auction actually opened" - the test re-publishes the bid on
 * every Awaitility poll instead of trying to time a single publish against
 * the 1s tick; recordBid() is a no-op until an auction is open, so the
 * first publish that lands after it opens is the one that counts. Not
 * @Transactional, same reasoning as MissionStatusListenerTests: the
 * listener's own save happens on a different thread/transaction than this
 * test method.
 *
 * No {@code @TestPropertySource}/{@code @DirtiesContext} needed anymore
 * (Sprint 16): auction mode is a runtime-mutable
 * {@link MissionAssignmentModeHolder}, not a startup property, so this
 * class shares the same cached default context every other bare
 * {@code @SpringBootTest} class uses instead of spinning up (and having to
 * clean up) its own. That holder is a real process-wide singleton though -
 * {@link #enableAuctionMode()}/{@link #cleanup()} set it to AUCTION before
 * this test and reset it back to CENTRALIZED after, regardless of outcome,
 * or AuctionCoordinatorService's real tick would keep actually running for
 * every test class sharing this context afterward.
 */
@SpringBootTest
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

    @Autowired
    private MissionAssignmentModeHolder modeHolder;

    private MqttClient publisher;
    private Drone drone;
    private Mission mission;

    @BeforeEach
    void enableAuctionMode() {
        modeHolder.set(MissionAssignmentModeHolder.Mode.AUCTION);
    }

    @AfterEach
    void cleanup() throws Exception {
        modeHolder.set(MissionAssignmentModeHolder.Mode.CENTRALIZED);
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

        // 20s, not 10s: this waits on two real @Scheduled ticks
        // (AuctionCoordinatorService opening the auction, then closing it
        // after auction-window-seconds), sharing a task scheduler thread
        // with MissionAssignmentService's and SignalMonitorService's own
        // ticks (see WebSocketConfig.taskScheduler() for why that pool was
        // undersized in the first place) - CI runners are slower/more
        // contended than a dev machine, and 10s cut it too close there.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
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
