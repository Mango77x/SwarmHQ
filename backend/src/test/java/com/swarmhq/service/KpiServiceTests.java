package com.swarmhq.service;

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
import com.swarmhq.web.KpiSummary;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @Transactional so every row this test writes rolls back automatically -
 * important since the local dev database also carries real data from
 * manual/simulator testing sessions (drones, events). Mission-derived
 * assertions can be exact (nothing else in the codebase writes Mission
 * rows yet - mission assignment is a post-MVP differentiation layer);
 * drone/event-derived ones compare against a "before" snapshot instead,
 * since those tables do carry pre-existing real data.
 */
@SpringBootTest
@Transactional
class KpiServiceTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private KpiService kpiService;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DroneRepository droneRepository;

    @Test
    void noSuccessRateUntilAMissionHasCompletedOrFailed() {
        missionRepository.saveAndFlush(mission(MissionStatus.PENDING));
        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));

        assertNull(kpiService.summarize().missionSuccessRatePercent());
    }

    @Test
    void countsActiveMissionsAndComputesSuccessRate() {
        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));
        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));
        missionRepository.saveAndFlush(mission(MissionStatus.COMPLETED));
        missionRepository.saveAndFlush(mission(MissionStatus.COMPLETED));
        missionRepository.saveAndFlush(mission(MissionStatus.FAILED));

        KpiSummary summary = kpiService.summarize();

        assertEquals(2, summary.activeMissions());
        assertEquals(200.0 / 3, summary.missionSuccessRatePercent(), 1e-9);
    }

    @Test
    void countsAlertsRaisedJustNowWithinTheRecentWindow() {
        KpiSummary before = kpiService.summarize();

        Drone drone = droneRepository.saveAndFlush(new Drone("kpi-service-test-drone", "quadcopter", DroneStatus.PATROLLING, 50));
        eventRepository.saveAndFlush(new Event(drone, EventType.STATUS_CHANGE, "test event"));
        eventRepository.saveAndFlush(new Event(drone, EventType.STATUS_CHANGE, "test event"));

        assertEquals(before.recentAlertCount() + 2, kpiService.summarize().recentAlertCount());
    }

    @Test
    void excludesEventsOlderThanTheThreshold() {
        Drone drone = droneRepository.saveAndFlush(new Drone("kpi-service-window-test-drone", "quadcopter", DroneStatus.PATROLLING, 50));
        eventRepository.saveAndFlush(new Event(drone, EventType.STATUS_CHANGE, "test event"));

        // Event's occurredAt is always Instant.now() (append-only entity,
        // no setter) - a threshold in the future is guaranteed to exclude
        // it (and everything else) without needing to backdate anything.
        assertEquals(0, eventRepository.countByOccurredAtAfter(Instant.now().plusSeconds(60)));
    }

    @Test
    void countsDronesAtOrBelowTheLowBatteryThreshold() {
        KpiSummary before = kpiService.summarize();

        droneRepository.saveAndFlush(new Drone("kpi-service-critical-1", "quadcopter", DroneStatus.PATROLLING, AlertService.LOW_BATTERY_THRESHOLD));
        droneRepository.saveAndFlush(new Drone("kpi-service-critical-2", "quadcopter", DroneStatus.RETURNING, AlertService.LOW_BATTERY_THRESHOLD - 5));
        droneRepository.saveAndFlush(new Drone("kpi-service-healthy", "quadcopter", DroneStatus.PATROLLING, AlertService.LOW_BATTERY_THRESHOLD + 5));

        assertEquals(before.criticalBatteryDroneCount() + 2, kpiService.summarize().criticalBatteryDroneCount());
    }

    private Mission mission(MissionStatus status) {
        Mission mission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(-3.7038, 40.4168),
                        new Coordinate(-3.6978, 40.4228)
                }),
                MissionPriority.MEDIUM);
        mission.setStatus(status);
        return mission;
    }
}
