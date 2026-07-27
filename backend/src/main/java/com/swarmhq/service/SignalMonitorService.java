package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.web.DroneResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The "network resilience" differentiation layer (Sprint 13) - a
 * {@code @Scheduled} watchdog, the mirror image of
 * {@link MissionAssignmentService}'s scheduled pass: instead of reacting to
 * a message that arrived, this reacts to messages that stopped arriving. A
 * drone whose telemetry goes quiet for longer than the timeout is marked
 * {@code SIGNAL_LOST} (an existing {@code DroneStatus}/{@code EventType}
 * pair, unused until now) - its last known position is left untouched,
 * since "lost signal" means exactly that: the last thing heard from it is
 * still the best guess of where it is. Recovery needs no watchdog
 * counterpart - {@link AlertService#evaluate} raises {@code SIGNAL_RECOVERED}
 * for free the moment {@link DroneService#applyTelemetry} sees a fresh
 * reading from a drone whose previous status was {@code SIGNAL_LOST}.
 */
@Service
public class SignalMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SignalMonitorService.class);

    private final DroneRepository droneRepository;
    private final AlertService alertService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Duration timeout;
    private final boolean schedulerEnabled;

    public SignalMonitorService(DroneRepository droneRepository, AlertService alertService,
            SimpMessagingTemplate messagingTemplate,
            @Value("${swarmhq.signal-monitor.timeout-seconds:15}") long timeoutSeconds,
            @Value("${swarmhq.signal-monitor.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.droneRepository = droneRepository;
        this.alertService = alertService;
        this.messagingTemplate = messagingTemplate;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Disabled in tests that assert on drone/event state (see
     * SignalMonitorServiceTests) - same reasoning as
     * MissionAssignmentService's own scheduler flag: a background tick
     * racing a test's own assertions would make them flaky.
     */
    @Scheduled(fixedDelay = 5000)
    void scheduledCheck() {
        if (schedulerEnabled) {
            checkForSignalLoss();
        }
    }

    /**
     * Re-evaluates every drone not already {@code SIGNAL_LOST} each tick,
     * same "re-check everything, not just what changed" shape as
     * {@link MissionAssignmentService#assignPendingMissions()}.
     */
    public void checkForSignalLoss() {
        Instant threshold = Instant.now().minus(timeout);
        List<Drone> stale = droneRepository.findByStatusNotAndLastUpdateAtBefore(DroneStatus.SIGNAL_LOST, threshold);
        for (Drone drone : stale) {
            DroneStatus previousStatus = drone.getStatus();
            drone.setStatus(DroneStatus.SIGNAL_LOST);
            droneRepository.save(drone);
            alertService.evaluate(drone, drone.getBatteryPercent(), previousStatus, drone.getPosition());
            messagingTemplate.convertAndSend(DroneService.DRONE_UPDATES_TOPIC, DroneResponse.from(drone));
            log.info("{} lost signal (last heard from at {})", drone.getExternalId(), drone.getLastUpdateAt());
        }
    }
}
