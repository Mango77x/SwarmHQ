package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calls MissionAssignmentService.assignPendingMissions() directly rather
 * than waiting on its @Scheduled tick - fast and deterministic (the
 * scheduler is disabled here regardless, same reasoning as KpiServiceTests:
 * no background tick racing this test's own assertions). @Transactional
 * so writes roll back; drones/missions are placed far from Madrid
 * (TEST_AREA_LAT/LON) so the "nearest eligible drone" assertions can't
 * accidentally match real drones already sitting in the shared dev
 * database. Pre-existing PENDING missions (e.g. V4's seeded demo ones)
 * are a separate hazard - assignPendingMissions() processes every PENDING
 * mission in the table, not just the ones a test creates, so a real
 * seeded mission could out-compete a test's own mission for the same
 * eligible drone. sidelineOtherPendingMissions() below neutralizes them
 * for the duration of each test (reverted by the transaction rollback).
 */
@SpringBootTest
@TestPropertySource(properties = "swarmhq.mission-assignment.scheduler-enabled=false")
@Transactional
class MissionAssignmentServiceTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double TEST_AREA_LAT = 10.0;
    private static final double TEST_AREA_LON = 10.0;

    @Autowired
    private MissionAssignmentService missionAssignmentService;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private DroneRepository droneRepository;

    @BeforeEach
    void sidelineOtherPendingMissions() {
        missionRepository.findByStatusOrderByCreatedAtAsc(MissionStatus.PENDING).forEach(mission -> {
            mission.setStatus(MissionStatus.FAILED);
            missionRepository.save(mission);
        });
    }

    @Test
    void assignsToTheNearestEligibleDrone() {
        Drone near = saveDrone("mat-near", 90, TEST_AREA_LAT, TEST_AREA_LON);
        Drone far = saveDrone("mat-far", 90, TEST_AREA_LAT + 1.0, TEST_AREA_LON + 1.0);
        Mission mission = saveMission(MissionPriority.MEDIUM);

        missionAssignmentService.assignPendingMissions();

        Mission updated = missionRepository.findById(mission.getId()).orElseThrow();
        assertEquals(MissionStatus.ACTIVE, updated.getStatus());
        assertEquals(near.getId(), updated.getAssignedDrone().getId());
        assertEquals(DroneStatus.ON_MISSION, droneRepository.findById(near.getId()).orElseThrow().getStatus());
        assertEquals(DroneStatus.PATROLLING, droneRepository.findById(far.getId()).orElseThrow().getStatus());
    }

    @Test
    void skipsDronesBelowTheBatteryMargin() {
        Drone low = saveDrone("mat-low-battery", MissionAssignmentService.MIN_BATTERY_PERCENT - 1, TEST_AREA_LAT, TEST_AREA_LON);
        Mission mission = saveMission(MissionPriority.MEDIUM);

        missionAssignmentService.assignPendingMissions();

        assertEquals(MissionStatus.PENDING, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
        assertEquals(DroneStatus.PATROLLING, droneRepository.findById(low.getId()).orElseThrow().getStatus());
    }

    @Test
    void assignsHigherPriorityMissionFirstWhenOnlyOneDroneIsEligible() {
        saveDrone("mat-single", 90, TEST_AREA_LAT, TEST_AREA_LON);
        Mission low = saveMission(MissionPriority.LOW);
        Mission high = saveMission(MissionPriority.HIGH);

        missionAssignmentService.assignPendingMissions();

        assertEquals(MissionStatus.ACTIVE, missionRepository.findById(high.getId()).orElseThrow().getStatus());
        assertEquals(MissionStatus.PENDING, missionRepository.findById(low.getId()).orElseThrow().getStatus());
    }

    private Drone saveDrone(String externalId, int batteryPercent, double lat, double lon) {
        Drone drone = new Drone(externalId, "quadcopter", DroneStatus.PATROLLING, batteryPercent);
        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat)));
        return droneRepository.saveAndFlush(drone);
    }

    private Mission saveMission(MissionPriority priority) {
        Mission mission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(TEST_AREA_LON, TEST_AREA_LAT),
                        new Coordinate(TEST_AREA_LON + 0.01, TEST_AREA_LAT + 0.01)
                }),
                priority);
        return missionRepository.saveAndFlush(mission);
    }
}
