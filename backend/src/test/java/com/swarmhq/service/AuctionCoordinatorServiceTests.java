package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.mqtt.MissionBidPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives AuctionCoordinatorService's open/bid/close cycle as three
 * deterministic steps rather than through its real-clock {@code @Scheduled}
 * tick - {@code auction-window-seconds=0} means any elapsed time (even
 * ~0ms between two direct calls in the same test) counts as "expired", so
 * closing an auction doesn't need to wait on the wall clock. See
 * MissionBidListenerTests for the complementary test that goes through
 * the real MQTT path instead. Same DB hazards/mitigations as
 * MissionAssignmentServiceTests (far-from-Madrid coordinates, sidelining
 * pre-existing PENDING missions/PATROLLING drones, {@code @Transactional}
 * rollback) - see that class for the full reasoning.
 *
 * Doesn't need {@code mission-assignment.mode=auction} (Sprint 16 made it
 * a runtime-mutable {@code MissionAssignmentModeHolder}, not a startup
 * property) - every method under test here is called directly, never
 * through the real {@code @Scheduled tick()} that actually checks the
 * mode, so the mode holder is irrelevant to this class either way.
 *
 * {@code @DirtiesContext}: {@code auction-window-seconds=0} is still a
 * property set no other test class shares, so Spring still caches it as
 * a second, simultaneously-alive ApplicationContext with its own live
 * MQTT connection. Without this, that connection outlives this test class
 * for the rest of the suite, and its (unconditional) MissionStatusListener
 * bean double-processes any drones/+/mission-status message a *later*
 * test class publishes, producing a real, hard-to-place "expected 1 event
 * but was 2" failure in a completely unrelated test. Found by running
 * MissionStatusListenerTests in isolation (passed) vs. in the full suite
 * (failed) - the tell that this was cross-test-context pollution, not a
 * bug in that test itself.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "swarmhq.mission-assignment.scheduler-enabled=false",
        "swarmhq.mission-assignment.auction-window-seconds=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AuctionCoordinatorServiceTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double TEST_AREA_LAT = 20.0;
    private static final double TEST_AREA_LON = 20.0;

    @Autowired
    private AuctionCoordinatorService auctionCoordinatorService;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private DroneRepository droneRepository;

    @BeforeEach
    void sidelineOtherPendingMissionsAndDrones() {
        missionRepository.findByStatusOrderByCreatedAtAsc(MissionStatus.PENDING).forEach(mission -> {
            mission.setStatus(MissionStatus.FAILED);
            missionRepository.save(mission);
        });
        droneRepository.findAll().stream()
                .filter(drone -> drone.getStatus() == DroneStatus.PATROLLING)
                .forEach(drone -> {
                    drone.setStatus(DroneStatus.RETURNING);
                    droneRepository.save(drone);
                });
    }

    @Test
    void assignsToTheLowestBidder() {
        Drone cheap = saveDrone("auc-cheap");
        Drone expensive = saveDrone("auc-expensive");
        Mission mission = saveMission();

        auctionCoordinatorService.openNewAuctions();
        auctionCoordinatorService.recordBid(mission.getId(), new MissionBidPayload(cheap.getExternalId(), 10.0));
        auctionCoordinatorService.recordBid(mission.getId(), new MissionBidPayload(expensive.getExternalId(), 50.0));
        auctionCoordinatorService.closeExpiredAuctions();

        Mission updated = missionRepository.findById(mission.getId()).orElseThrow();
        assertEquals(MissionStatus.ACTIVE, updated.getStatus());
        assertEquals(cheap.getId(), updated.getAssignedDrone().getId());
        assertEquals(DroneStatus.ON_MISSION, droneRepository.findById(cheap.getId()).orElseThrow().getStatus());
        assertEquals(DroneStatus.PATROLLING, droneRepository.findById(expensive.getId()).orElseThrow().getStatus());
    }

    @Test
    void leavesTheMissionPendingWhenNoBidsArrive() {
        Mission mission = saveMission();

        auctionCoordinatorService.openNewAuctions();
        auctionCoordinatorService.closeExpiredAuctions();

        assertEquals(MissionStatus.PENDING, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
    }

    @Test
    void ignoresAWinningBidFromADroneThatIsNoLongerEligible() {
        Drone bidder = saveDrone("auc-ineligible");
        Mission mission = saveMission();

        auctionCoordinatorService.openNewAuctions();
        // Went ON_MISSION (e.g. claimed elsewhere) between bidding and this auction closing.
        bidder.setStatus(DroneStatus.ON_MISSION);
        droneRepository.saveAndFlush(bidder);
        auctionCoordinatorService.recordBid(mission.getId(), new MissionBidPayload(bidder.getExternalId(), 10.0));
        auctionCoordinatorService.closeExpiredAuctions();

        assertEquals(MissionStatus.PENDING, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
    }

    private Drone saveDrone(String externalId) {
        Drone drone = new Drone(externalId, "quadcopter", DroneStatus.PATROLLING, 90);
        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(TEST_AREA_LON, TEST_AREA_LAT)));
        return droneRepository.saveAndFlush(drone);
    }

    private Mission saveMission() {
        Mission mission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(TEST_AREA_LON, TEST_AREA_LAT),
                        new Coordinate(TEST_AREA_LON + 0.01, TEST_AREA_LAT + 0.01)
                }),
                MissionPriority.MEDIUM);
        return missionRepository.saveAndFlush(mission);
    }
}
