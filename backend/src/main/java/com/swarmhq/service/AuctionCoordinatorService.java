package com.swarmhq.service;

import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.mqtt.MissionBidPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@code auction} mission-assignment strategy: the alternative to
 * the centralized {@link MissionAssignmentService}, active whenever
 * {@link MissionAssignmentModeHolder} currently says {@code AUCTION}.
 * That's checked fresh every tick rather than fixed at startup - this
 * bean always exists, same as the centralized one. Instead of the
 * backend picking a drone itself, it broadcasts each PENDING mission on
 * {@code missions/available} and lets drones bid on
 * {@code missions/{missionId}/bids} (received via
 * {@code com.swarmhq.mqtt.MissionBidListener}); the lowest bid within a
 * short window wins. Both strategies hand off the winning assignment
 * the same way, via {@link MissionAssigner} - a drone can't tell which
 * one decided its assignment.
 */
@Service
public class AuctionCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(AuctionCoordinatorService.class);

    private final MissionRepository missionRepository;
    private final DroneRepository droneRepository;
    private final MissionAssigner missionAssigner;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final MissionAssignmentModeHolder modeHolder;
    private final Duration auctionWindow;
    private final boolean schedulerEnabled;

    private final Map<Long, AuctionState> openAuctions = new ConcurrentHashMap<>();

    public AuctionCoordinatorService(MissionRepository missionRepository, DroneRepository droneRepository,
            MissionAssigner missionAssigner, MqttClient mqttClient, ObjectMapper objectMapper,
            MissionAssignmentModeHolder modeHolder,
            @Value("${swarmhq.mission-assignment.auction-window-seconds:3}") long auctionWindowSeconds,
            @Value("${swarmhq.mission-assignment.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.missionRepository = missionRepository;
        this.droneRepository = droneRepository;
        this.missionAssigner = missionAssigner;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
        this.modeHolder = modeHolder;
        this.auctionWindow = Duration.ofSeconds(auctionWindowSeconds);
        this.schedulerEnabled = schedulerEnabled;
    }

    /** Called by {@code MissionBidListener} for every bid it receives. */
    public void recordBid(Long missionId, MissionBidPayload bid) {
        AuctionState auction = openAuctions.get(missionId);
        if (auction == null) {
            log.debug("Ignoring bid from {} for mission {} - no open auction (already closed, or not yet opened)",
                    bid.droneId(), missionId);
            return;
        }
        auction.bids.add(bid);
    }

    /**
     * Disabled in tests via the same {@code scheduler-enabled} flag
     * MissionAssignmentService uses - AuctionCoordinatorServiceTests
     * drives {@link #openNewAuctions()}/{@link #closeExpiredAuctions()}
     * directly and can't have a background tick racing its own bids. Also
     * a no-op whenever the mode holder currently says {@code CENTRALIZED}.
     */
    @Scheduled(fixedDelay = 1000)
    void tick() {
        if (schedulerEnabled && modeHolder.get() == MissionAssignmentModeHolder.Mode.AUCTION) {
            openNewAuctions();
            closeExpiredAuctions();
        }
    }

    /**
     * Package-private so AuctionCoordinatorServiceTests can drive
     * opening/bidding/closing as separate deterministic steps instead of
     * only through the combined, real-clock-dependent {@link #tick()}.
     */
    void openNewAuctions() {
        for (Mission mission : missionRepository.findByStatusOrderByCreatedAtAsc(MissionStatus.PENDING)) {
            openAuctions.computeIfAbsent(mission.getId(), id -> {
                publishAvailable(mission);
                return new AuctionState();
            });
        }
    }

    void closeExpiredAuctions() {
        for (var entry : openAuctions.entrySet()) {
            AuctionState auction = entry.getValue();
            if (Duration.between(auction.openedAt, Instant.now()).compareTo(auctionWindow) < 0) {
                continue;
            }
            // Removed either way: a mission with no winning bid this round
            // stays PENDING and simply re-enters the next openNewAuctions()
            // pass, the same "re-evaluate every tick" self-healing as the
            // centralized engine.
            openAuctions.remove(entry.getKey());
            missionRepository.findById(entry.getKey()).ifPresent(mission -> closeAuction(mission, auction));
        }
    }

    private void closeAuction(Mission mission, AuctionState auction) {
        if (mission.getStatus() != MissionStatus.PENDING) {
            return; // already assigned/cancelled by the time this closed
        }
        winningBid(auction.bids).ifPresentOrElse(
                bid -> droneRepository.findByExternalId(bid.droneId())
                        .filter(drone -> drone.getStatus() == DroneStatus.PATROLLING)
                        .ifPresentOrElse(
                                drone -> missionAssigner.assign(mission, drone),
                                () -> log.warn("Winning bidder {} for mission {} is no longer eligible - leaving PENDING",
                                        bid.droneId(), mission.getId())),
                () -> log.debug("No bids received for mission {} - leaving PENDING", mission.getId()));
    }

    private Optional<MissionBidPayload> winningBid(List<MissionBidPayload> bids) {
        return bids.stream().min(Comparator.comparingDouble(MissionBidPayload::cost));
    }

    private void publishAvailable(Mission mission) {
        Coordinate start = mission.getRoute().getCoordinateN(0);
        Map<String, Object> payload = Map.of(
                "missionId", mission.getId(),
                "lat", start.y,
                "lon", start.x,
                "priority", mission.getPriority().name());
        try {
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            mqttClient.publish("missions/available", message);
            log.info("Opened auction for mission {} ({} priority)", mission.getId(), mission.getPriority());
        } catch (MqttException e) {
            log.error("Failed to publish mission {} as available for bidding: {}", mission.getId(), e.getMessage(), e);
        }
    }

    private static final class AuctionState {
        final Instant openedAt = Instant.now();
        final List<MissionBidPayload> bids = new CopyOnWriteArrayList<>();
    }
}
