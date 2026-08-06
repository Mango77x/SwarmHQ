package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.mqtt.TelemetryPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.MissionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives DroneService.applyTelemetry() directly (like
 * DroneServiceWebSocketTests) rather than over MQTT. DroneTelemetryListenerTests
 * already covers that transport; this one is about the alerting
 * transitions themselves. Every check in AlertService is transition-based,
 * so each test applies at least two telemetry readings and asserts the
 * *count* of matching events, not just whether one showed up - the point
 * being to fire exactly once per crossing, never once per tick spent past
 * it.
 *
 * scheduler-enabled=false, same convention as MissionAssignmentServiceTests/
 * KpiServiceTests: this test's own drone is PATROLLING with a high
 * battery, which makes it an eligible target for MissionAssignmentService's
 * real scheduled pass against the seeded demo missions (V4). Without
 * disabling that, a background tick can assign it a mission mid-test,
 * which produces a second, unplanned STATUS_CHANGE event (turned up on a
 * clean-database CI run - a local dev DB with years of leftover rows
 * apparently never left the seeded missions available long enough to hit
 * this) and, on cleanup, a foreign-key violation from deleting a drone a
 * real Mission still references.
 */
@SpringBootTest
@TestPropertySource(properties = "swarmhq.mission-assignment.scheduler-enabled=false")
class AlertServiceTests {

    private static final String EXTERNAL_ID = "alert-service-test";

    // Inside the seeded "Sector 1 Perimeter Risk Zone" (V3__add_risk_zones.sql):
    // lon [-3.7048, -3.7028], lat [40.4190, 40.4210].
    private static final double IN_ZONE_LAT = 40.4200;
    private static final double IN_ZONE_LON = -3.7038;
    // Drone-1's own patrol base (simulator/routes.py) - well outside the zone.
    private static final double OUTSIDE_ZONE_LAT = 40.4168;
    private static final double OUTSIDE_ZONE_LON = -3.7038;

    @Autowired
    private DroneService droneService;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private MissionRepository missionRepository;

    @AfterEach
    void cleanup() {
        droneRepository.findByExternalId(EXTERNAL_ID).ifPresent(drone -> {
            eventRepository.findAll().stream()
                    .filter(e -> e.getDrone().getId().equals(drone.getId()))
                    .forEach(eventRepository::delete);
            missionRepository.findAllWithDrone().stream()
                    .filter(m -> m.getAssignedDrone() != null && m.getAssignedDrone().getId().equals(drone.getId()))
                    .forEach(missionRepository::delete);
            droneRepository.delete(drone);
        });
    }

    @Test
    void raisesLowBatteryOnceWhenCrossingTheThreshold() {
        telemetry(50, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // first reading: no "previous" to compare against
        telemetry(15, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // crosses AlertService.LOW_BATTERY_THRESHOLD (20)
        telemetry(10, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // still below it - no repeat

        assertEquals(1, eventsOfType(EventType.LOW_BATTERY).size());
    }

    @Test
    void raisesStatusChangeOnTransition() {
        telemetry(80, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        telemetry(78, "RETURNING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        telemetry(76, "RETURNING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // same status - no repeat

        List<Event> changes = eventsOfType(EventType.STATUS_CHANGE);
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).getDetail().contains("PATROLLING"));
        assertTrue(changes.get(0).getDetail().contains("RETURNING"));
    }

    @Test
    void raisesSignalLostAndRecoveredOnTransition() {
        // SignalMonitorService is what actually flips a real drone to
        // SIGNAL_LOST, off a telemetry timeout rather than a reading. But
        // AlertService.evaluate() doesn't care where a status transition
        // came from, and SIGNAL_LOST is a value TelemetryPayload.status()
        // can carry like any other DroneStatus, so driving it through
        // applyTelemetry() here exercises the same evaluate() branch
        // without needing the watchdog itself.
        telemetry(80, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        telemetry(78, "SIGNAL_LOST", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        telemetry(76, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);

        assertEquals(1, eventsOfType(EventType.SIGNAL_LOST).size());
        assertEquals(1, eventsOfType(EventType.SIGNAL_RECOVERED).size());
    }

    @Test
    void raisesEnteredAndExitedRiskZoneOnBoundaryCrossings() {
        telemetry(90, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        // Several readings at the same point instead of one abrupt jump:
        // the persisted, geofence-checked position is the Kalman-smoothed
        // estimate, which blends toward a new raw reading rather than
        // snapping to it instantly. Reusing the same point lets the
        // estimate actually converge there before the zone-boundary
        // assertion runs, without weakening what's being tested - still
        // exactly one ENTERED and one EXITED for the whole approach-and-leave
        // sequence.
        for (int i = 0; i < 5; i++) {
            telemetry(90, "PATROLLING", IN_ZONE_LAT, IN_ZONE_LON); // enters (once, on convergence)
        }
        for (int i = 0; i < 5; i++) {
            telemetry(90, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // exits (once, on convergence)
        }

        assertEquals(1, eventsOfType(EventType.ENTERED_RISK_ZONE).size());
        assertEquals(1, eventsOfType(EventType.EXITED_RISK_ZONE).size());
    }

    @Test
    void autoRecallsADroneWithAnActiveMissionWhenItEntersARiskZone() {
        // Geofence as an active constraint, not just a passive alert (the
        // hardening/parity layer's item 2): entering a RiskZone while
        // flying an ACTIVE mission should trigger the same MQTT cancel/RTB
        // command an operator's own POST /api/missions/{id}/cancel
        // publishes (MissionCancelPublisher), reused via AlertService.
        telemetry(80, "ON_MISSION", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        Drone drone = droneRepository.findByExternalId(EXTERNAL_ID).orElseThrow();
        Mission mission = missionRepository.saveAndFlush(activeMissionFor(drone));

        for (int i = 0; i < 5; i++) {
            telemetry(80, "ON_MISSION", IN_ZONE_LAT, IN_ZONE_LON);
        }

        assertEquals(1, eventsOfType(EventType.ENTERED_RISK_ZONE).size());
        // Auto-recall only requests the cancel over MQTT - the mission
        // stays ACTIVE until the drone's own mission-status report comes
        // back through MissionStatusListener, same eventual-consistency
        // pattern an operator-triggered cancel already uses (see
        // MissionControllerTests.cancellingAnActiveMissionPublishesTheCommandAndLeavesItActiveUntilConfirmed).
        assertEquals(MissionStatus.ACTIVE, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
    }

    private Mission activeMissionFor(Drone drone) {
        Mission mission = new Mission(
                new GeometryFactory(new PrecisionModel(), 4326).createLineString(new Coordinate[] {
                        new Coordinate(OUTSIDE_ZONE_LON, OUTSIDE_ZONE_LAT),
                        new Coordinate(IN_ZONE_LON, IN_ZONE_LAT)
                }),
                MissionPriority.MEDIUM);
        mission.setAssignedDrone(drone);
        mission.setStatus(MissionStatus.ACTIVE);
        return mission;
    }

    private void telemetry(int batteryPercent, String status, double lat, double lon) {
        droneService.applyTelemetry(EXTERNAL_ID,
                new TelemetryPayload("quadcopter", lat, lon, batteryPercent, status, Instant.now()));
    }

    private List<Event> eventsOfType(EventType type) {
        Drone drone = droneRepository.findByExternalId(EXTERNAL_ID).orElseThrow();
        return eventRepository.findAll().stream()
                .filter(e -> e.getDrone().getId().equals(drone.getId()) && e.getType() == type)
                .toList();
    }
}
