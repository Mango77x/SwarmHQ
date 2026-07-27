package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls SignalMonitorService.checkForSignalLoss() directly rather than
 * waiting on its @Scheduled tick - same reasoning/pattern as
 * MissionAssignmentServiceTests. @Transactional so writes roll back.
 */
@SpringBootTest
@TestPropertySource(properties = "swarmhq.signal-monitor.scheduler-enabled=false")
@Transactional
class SignalMonitorServiceTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private SignalMonitorService signalMonitorService;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void marksAStaleDroneSignalLostAndRetainsItsPosition() {
        Drone drone = saveDrone("sms-stale", DroneStatus.PATROLLING, Instant.now().minusSeconds(999));

        signalMonitorService.checkForSignalLoss();

        Drone updated = droneRepository.findById(drone.getId()).orElseThrow();
        assertEquals(DroneStatus.SIGNAL_LOST, updated.getStatus());
        assertEquals(10.0, updated.getPosition().getY());
        assertEquals(20.0, updated.getPosition().getX());

        // Also raises a generic STATUS_CHANGE (PATROLLING -> SIGNAL_LOST) -
        // same "more than one event per transition" precedent as
        // LOW_BATTERY in AlertServiceTests, so this asserts on the specific
        // type rather than the total count.
        assertEquals(1, eventsOfType(drone, EventType.SIGNAL_LOST).size());
    }

    @Test
    void leavesARecentlyHeardFromDroneAlone() {
        Drone drone = saveDrone("sms-fresh", DroneStatus.PATROLLING, Instant.now());

        signalMonitorService.checkForSignalLoss();

        assertEquals(DroneStatus.PATROLLING, droneRepository.findById(drone.getId()).orElseThrow().getStatus());
        assertTrue(eventsFor(drone).isEmpty());
    }

    @Test
    void doesNotReRaiseSignalLostForADroneAlreadyMarkedLost() {
        Drone drone = saveDrone("sms-already-lost", DroneStatus.SIGNAL_LOST, Instant.now().minusSeconds(999));

        signalMonitorService.checkForSignalLoss();

        assertTrue(eventsFor(drone).isEmpty());
    }

    private Drone saveDrone(String externalId, DroneStatus status, Instant lastUpdateAt) {
        Drone drone = new Drone(externalId, "quadcopter", status, 80);
        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(20.0, 10.0)));
        drone.setLastUpdateAt(lastUpdateAt);
        return droneRepository.saveAndFlush(drone);
    }

    private List<Event> eventsFor(Drone drone) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getDrone().getId().equals(drone.getId()))
                .toList();
    }

    private List<Event> eventsOfType(Drone drone, EventType type) {
        return eventsFor(drone).stream().filter(e -> e.getType() == type).toList();
    }
}
