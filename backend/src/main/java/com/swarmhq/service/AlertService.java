package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.model.RiskZone;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.repository.RiskZoneRepository;
import com.swarmhq.web.EventResponse;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Raises {@link Event}s off telemetry transitions: low battery, status
 * changes, entering/leaving a {@link RiskZone}. Called from
 * {@link DroneService#applyTelemetry} with the drone's state just before
 * that call overwrote it. Every check here is transition-based - it
 * fires once when crossing a threshold or boundary rather than on every
 * telemetry tick while already past it. A brand-new drone with no prior
 * state only gets evaluated for geofencing, since "changed" has no
 * meaning yet on a first-ever reading.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    static final int LOW_BATTERY_THRESHOLD = 20;

    public static final String EVENT_UPDATES_TOPIC = "/topic/events";

    private final EventRepository eventRepository;
    private final RiskZoneRepository riskZoneRepository;
    private final MissionRepository missionRepository;
    private final MissionCancelPublisher missionCancelPublisher;
    private final SimpMessagingTemplate messagingTemplate;

    public AlertService(EventRepository eventRepository, RiskZoneRepository riskZoneRepository,
            MissionRepository missionRepository, MissionCancelPublisher missionCancelPublisher,
            SimpMessagingTemplate messagingTemplate) {
        this.eventRepository = eventRepository;
        this.riskZoneRepository = riskZoneRepository;
        this.missionRepository = missionRepository;
        this.missionCancelPublisher = missionCancelPublisher;
        this.messagingTemplate = messagingTemplate;
    }

    public void evaluate(Drone drone, Integer previousBatteryPercent, DroneStatus previousStatus, Point previousPosition) {
        if (previousBatteryPercent != null
                && previousBatteryPercent > LOW_BATTERY_THRESHOLD
                && drone.getBatteryPercent() <= LOW_BATTERY_THRESHOLD) {
            raise(drone, EventType.LOW_BATTERY, "Battery dropped to " + drone.getBatteryPercent() + "%");
        }

        if (previousStatus != null && previousStatus != drone.getStatus()) {
            raise(drone, EventType.STATUS_CHANGE, previousStatus + " -> " + drone.getStatus());
        }

        // Dedicated events alongside the generic STATUS_CHANGE above -
        // LOW_BATTERY already sets the precedent that more than one event
        // can fire off a single transition, since it often coincides with
        // a PATROLLING -> RETURNING STATUS_CHANGE anyway. SignalMonitorService's
        // watchdog raises SIGNAL_LOST, never telemetry itself.
        // SIGNAL_RECOVERED doesn't need a watchdog counterpart at all: it
        // just falls out once fresh telemetry reaches here with
        // previousStatus already SIGNAL_LOST.
        if (previousStatus != null && previousStatus != DroneStatus.SIGNAL_LOST && drone.getStatus() == DroneStatus.SIGNAL_LOST) {
            raise(drone, EventType.SIGNAL_LOST, "Lost signal - last known position retained");
        }
        if (previousStatus == DroneStatus.SIGNAL_LOST && drone.getStatus() != DroneStatus.SIGNAL_LOST) {
            raise(drone, EventType.SIGNAL_RECOVERED, "Signal recovered");
        }

        evaluateRiskZones(drone, previousPosition);
    }

    private void evaluateRiskZones(Drone drone, Point previousPosition) {
        Point position = drone.getPosition();
        if (position == null) {
            return;
        }

        List<RiskZone> previouslyIn = previousPosition != null
                ? riskZoneRepository.findContaining(previousPosition)
                : List.of();
        List<RiskZone> nowIn = riskZoneRepository.findContaining(position);

        for (RiskZone zone : nowIn) {
            if (previouslyIn.stream().noneMatch(z -> z.getId().equals(zone.getId()))) {
                raise(drone, EventType.ENTERED_RISK_ZONE, "Entered '" + zone.getName() + "'");
                autoRecallIfOnActiveMission(drone, zone);
            }
        }
        for (RiskZone zone : previouslyIn) {
            if (nowIn.stream().noneMatch(z -> z.getId().equals(zone.getId()))) {
                raise(drone, EventType.EXITED_RISK_ZONE, "Left '" + zone.getName() + "'");
            }
        }
    }

    /**
     * Geofence as an active constraint, not just a passive alert (the
     * hardening/parity layer's item 2): the moment a drone flying an
     * ACTIVE mission enters a RiskZone, that mission is auto-cancelled via
     * the exact same MQTT command an operator's own
     * {@code POST /api/missions/{id}/cancel} publishes
     * ({@link MissionCancelPublisher}) - MissionStatusListener's existing
     * eventual-consistency handling of the CANCELLED report doesn't need
     * to know or care whether a human or this trigger requested it. A
     * PATROLLING drone (no active mission) has nothing to recall from -
     * the ENTERED_RISK_ZONE alert just raised is all that applies to it.
     * Best-effort: this runs off telemetry processing, not an HTTP
     * request, so a race where the mission already ended on its own
     * between the lookup and the publish is logged and swallowed rather
     * than surfaced to a caller that doesn't exist.
     */
    private void autoRecallIfOnActiveMission(Drone drone, RiskZone zone) {
        missionRepository.findByAssignedDroneAndStatus(drone, MissionStatus.ACTIVE).ifPresent(mission -> {
            try {
                missionCancelPublisher.publish(mission);
                log.info("Auto-recalling {} from mission {} - entered restricted zone '{}'",
                        drone.getExternalId(), mission.getId(), zone.getName());
            } catch (ResponseStatusException e) {
                log.warn("Could not auto-recall {} from mission {} after entering '{}': {}",
                        drone.getExternalId(), mission.getId(), zone.getName(), e.getReason());
            }
        });
    }

    /**
     * Same persist-and-broadcast path as the transition checks above,
     * exposed for callers outside this class that raise an event tied to
     * a {@link Mission} - {@code MissionStatusListener} does, on mission
     * completion/failure. {@code mission.getAssignedDrone()} is always
     * non-null here, since a mission only reaches COMPLETED/FAILED after
     * MissionAssignmentService has already assigned it a drone.
     */
    public void raiseMissionEvent(Mission mission, EventType type, String detail) {
        Event event = new Event(mission.getAssignedDrone(), type, detail);
        event.setMission(mission);
        persistAndBroadcast(event);
    }

    private void raise(Drone drone, EventType type, String detail) {
        persistAndBroadcast(new Event(drone, type, detail));
    }

    private void persistAndBroadcast(Event event) {
        Event saved = eventRepository.save(event);
        messagingTemplate.convertAndSend(EVENT_UPDATES_TOPIC, EventResponse.from(saved));
    }
}
