package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.model.Mission;
import com.swarmhq.model.RiskZone;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.RiskZoneRepository;
import com.swarmhq.web.EventResponse;
import org.locationtech.jts.geom.Point;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Raises {@link Event}s off telemetry transitions - low battery, status
 * changes, entering/leaving a {@link RiskZone} (Sprint 8). Called from
 * {@link DroneService#applyTelemetry} with the drone's state just before
 * that call overwrote it, since every check here is transition-based (fire
 * once when crossing a threshold/boundary, not on every single telemetry
 * tick while already past it) - a brand-new drone (no prior state) only
 * gets evaluated for geofencing, since "changed" doesn't mean anything yet
 * for its first-ever reading.
 */
@Service
public class AlertService {

    static final int LOW_BATTERY_THRESHOLD = 20;

    public static final String EVENT_UPDATES_TOPIC = "/topic/events";

    private final EventRepository eventRepository;
    private final RiskZoneRepository riskZoneRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public AlertService(EventRepository eventRepository, RiskZoneRepository riskZoneRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.eventRepository = eventRepository;
        this.riskZoneRepository = riskZoneRepository;
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
            }
        }
        for (RiskZone zone : previouslyIn) {
            if (nowIn.stream().noneMatch(z -> z.getId().equals(zone.getId()))) {
                raise(drone, EventType.EXITED_RISK_ZONE, "Left '" + zone.getName() + "'");
            }
        }
    }

    /**
     * Same persist-and-broadcast path as the transition checks above, for
     * callers outside this class that raise an event tied to a
     * {@link Mission} - {@code MissionStatusListener} (Sprint 10), on
     * mission completion/failure. {@code mission.getAssignedDrone()} is
     * always non-null here: a mission only reaches COMPLETED/FAILED after
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
