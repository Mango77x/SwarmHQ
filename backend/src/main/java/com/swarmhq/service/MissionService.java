package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.web.CreateMissionRequest;
import com.swarmhq.web.MissionResponse;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final MissionRepository missionRepository;
    private final DroneRepository droneRepository;
    private final MissionAssigner missionAssigner;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MissionService(MissionRepository missionRepository, DroneRepository droneRepository,
            MissionAssigner missionAssigner, MqttClient mqttClient, ObjectMapper objectMapper) {
        this.missionRepository = missionRepository;
        this.droneRepository = droneRepository;
        this.missionAssigner = missionAssigner;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    public List<MissionResponse> listAll() {
        return missionRepository.findAllWithDrone().stream()
                .map(MissionResponse::from)
                .toList();
    }

    public MissionResponse create(CreateMissionRequest request) {
        Coordinate[] coordinates = request.route().stream()
                .map(point -> new Coordinate(point[0], point[1]))
                .toArray(Coordinate[]::new);
        Mission mission = new Mission(GEOMETRY_FACTORY.createLineString(coordinates), request.priority());
        return MissionResponse.from(missionRepository.save(mission));
    }

    /**
     * Cancels a mission. A PENDING one was never handed to a drone, so this
     * just marks it CANCELLED directly - via the same atomic
     * updateStatusIfPending {@code UPDATE ... WHERE status = 'PENDING'}
     * MissionRepository uses elsewhere, not a find-then-save, since a
     * find-then-save here could race MissionAssignmentService/
     * AuctionCoordinatorService assigning this exact mission in between:
     * the save() would merge our stale (still-PENDING, no-drone) in-memory
     * copy back over their just-committed assignment, silently un-assigning
     * a drone that's already been told to fly it. If the atomic update
     * affects no rows, the mission wasn't PENDING at that instant - falling
     * through to a fresh read picks up whatever it actually is now (most
     * often ACTIVE, meaning it just got assigned right under us, which the
     * ACTIVE branch below still handles correctly).
     *
     * An ACTIVE mission needs its drone recalled first - this only
     * publishes the cancel command; the mission itself stays ACTIVE until
     * the drone's own mission-status report comes back through
     * {@code MissionStatusListener}, the same eventual-consistency path
     * COMPLETED/FAILED already use, rather than the backend guessing the
     * outcome up front. Anything already terminal (COMPLETED/FAILED/
     * CANCELLED) is rejected - there's nothing left to cancel.
     */
    public MissionResponse cancel(Long id) {
        if (missionRepository.updateStatusIfPending(id, MissionStatus.CANCELLED) > 0) {
            return MissionResponse.from(missionRepository.findByIdWithOptionalDrone(id).orElseThrow());
        }

        Mission mission = missionRepository.findByIdWithOptionalDrone(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission " + id + " not found"));
        return switch (mission.getStatus()) {
            case ACTIVE -> {
                publishCancel(mission);
                yield MissionResponse.from(mission);
            }
            case PENDING, COMPLETED, FAILED, CANCELLED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mission " + id + " is " + mission.getStatus() + ", nothing to cancel");
        };
    }

    /**
     * Force-assigns a PENDING mission to a specific drone, bypassing
     * whichever assignment engine (MissionAssignmentService's centralized
     * pass or AuctionCoordinatorService's auction) is currently active -
     * the operator is naming the drone directly instead of letting either
     * algorithm pick one. Reuses {@link MissionAssigner} unchanged, the
     * same component both engines already hand off through, so a drone
     * doesn't need to know or care that this assignment came from a human
     * instead of a scheduled pass.
     *
     * Only a PENDING mission can be manually assigned (an ACTIVE one
     * already has a drone; reassigning it is a cancel-then-reassign, not
     * this). Only a PATROLLING drone is eligible - not because it has to
     * be the "best" choice (that heuristic is exactly what this bypasses),
     * but because a busy, returning, or unreachable drone genuinely can't
     * take a new order right now.
     *
     * The drone is validated before the mission is ever touched, then the
     * PENDING -> ACTIVE claim itself goes through the same atomic
     * updateStatusIfPending {@code UPDATE ... WHERE status = 'PENDING'}
     * cancel() uses - it's what actually closes the race against
     * MissionAssignmentService's/AuctionCoordinatorService's own scheduled
     * passes, which claim a PENDING mission the exact same way. Without
     * it, two engines could both read PENDING before either commits, and
     * the loser's plain save() (a detached-entity merge) would silently
     * clobber the winner's assignedDrone back to null. Validating the
     * drone first means a rejected drone never leaves a claim to undo.
     * (The drone side of this race - the same PATROLLING drone getting
     * claimed by a scheduled pass between the check above and
     * MissionAssigner.assign() actually flipping it - is narrower and
     * deliberately left as-is: closing it needs an equivalent atomic claim
     * on Drone, which none of the three assignment paths have today.)
     */
    public MissionResponse assignManually(Long missionId, String droneExternalId) {
        Drone drone = droneRepository.findByExternalId(droneExternalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drone " + droneExternalId + " not found"));
        if (drone.getStatus() != DroneStatus.PATROLLING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Drone " + droneExternalId + " is " + drone.getStatus() + ", not available for assignment");
        }

        if (missionRepository.updateStatusIfPending(missionId, MissionStatus.ACTIVE) == 0) {
            Mission existing = missionRepository.findById(missionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission " + missionId + " not found"));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mission " + missionId + " is " + existing.getStatus() + ", only a PENDING mission can be manually assigned");
        }

        Mission mission = missionRepository.findById(missionId).orElseThrow();
        if (!missionAssigner.assign(mission, drone)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reach the drone to assign mission " + missionId);
        }
        return MissionResponse.from(mission);
    }

    private void publishCancel(Mission mission) {
        String externalId = mission.getAssignedDrone().getExternalId();
        Map<String, Object> payload = Map.of("missionId", mission.getId());
        try {
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            mqttClient.publish("drones/" + externalId + "/mission/cancel", message);
            log.info("Requested cancel of mission {} ({} priority) on {}",
                    mission.getId(), mission.getPriority(), externalId);
        } catch (MqttException e) {
            log.error("Failed to publish cancel for mission {} to drone {}: {}",
                    mission.getId(), externalId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reach the drone to cancel mission " + mission.getId());
        }
    }
}
