package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.mqtt.TelemetryPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives DroneService.applyTelemetry() directly (like DroneServiceWebSocketTests)
 * rather than over MQTT - DroneTelemetryListenerTests already covers that
 * transport, this is about the alerting transitions themselves. Every
 * check here is transition-based, so each test applies at least two
 * telemetry readings and asserts the *count* of matching events, not just
 * their presence - the whole point is exactly one event per crossing, not
 * one per tick spent past it.
 */
@SpringBootTest
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

    @AfterEach
    void cleanup() {
        droneRepository.findByExternalId(EXTERNAL_ID).ifPresent(drone -> {
            eventRepository.findAll().stream()
                    .filter(e -> e.getDrone().getId().equals(drone.getId()))
                    .forEach(eventRepository::delete);
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
    void raisesEnteredAndExitedRiskZoneOnBoundaryCrossings() {
        telemetry(90, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON);
        // Several readings at the same point, not one abrupt jump: since
        // Sprint 12, the *persisted* (geofence-checked) position is the
        // Kalman-smoothed estimate, which blends toward a new raw reading
        // rather than snapping to it instantly - reusing the exact same
        // point lets the estimate actually converge there before the
        // zone-boundary assertion, without weakening what's being tested
        // (still exactly one ENTERED and one EXITED for the whole
        // approach-and-leave sequence).
        for (int i = 0; i < 5; i++) {
            telemetry(90, "PATROLLING", IN_ZONE_LAT, IN_ZONE_LON); // enters (once, on convergence)
        }
        for (int i = 0; i < 5; i++) {
            telemetry(90, "PATROLLING", OUTSIDE_ZONE_LAT, OUTSIDE_ZONE_LON); // exits (once, on convergence)
        }

        assertEquals(1, eventsOfType(EventType.ENTERED_RISK_ZONE).size());
        assertEquals(1, eventsOfType(EventType.EXITED_RISK_ZONE).size());
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
