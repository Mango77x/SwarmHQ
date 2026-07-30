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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Transactional so every row this test writes rolls back automatically.
 * Every assertion here compares against a "before" snapshot rather than
 * an absolute value, because the local dev database carries real data
 * from manual/simulator testing: drones, events, and missions too. The
 * scheduler is disabled (see MissionAssignmentService) so its background
 * tick can't also mutate Mission/Drone state mid-test.
 */
@SpringBootTest
@TestPropertySource(properties = "swarmhq.mission-assignment.scheduler-enabled=false")
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
    void addingPendingOrActiveMissionsDoesNotChangeTheSuccessRate() {
        Double before = kpiService.summarize().missionSuccessRatePercent();

        missionRepository.saveAndFlush(mission(MissionStatus.PENDING));
        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));

        assertEquals(before, kpiService.summarize().missionSuccessRatePercent());
    }

    @Test
    void countsActiveMissionsAndComputesSuccessRate() {
        long baselineActive = missionRepository.countByStatus(MissionStatus.ACTIVE);
        long baselineCompleted = missionRepository.countByStatus(MissionStatus.COMPLETED);
        long baselineFailed = missionRepository.countByStatus(MissionStatus.FAILED);

        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));
        missionRepository.saveAndFlush(mission(MissionStatus.ACTIVE));
        missionRepository.saveAndFlush(mission(MissionStatus.COMPLETED));
        missionRepository.saveAndFlush(mission(MissionStatus.COMPLETED));
        missionRepository.saveAndFlush(mission(MissionStatus.FAILED));

        KpiSummary summary = kpiService.summarize();

        assertEquals(baselineActive + 2, summary.activeMissions());
        long completed = baselineCompleted + 2;
        long failed = baselineFailed + 1;
        assertEquals(100.0 * completed / (completed + failed), summary.missionSuccessRatePercent(), 1e-9);
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
