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
 * The network-resilience layer: a {@code @Scheduled} watchdog that's the
 * mirror image of {@link MissionAssignmentService}'s scheduled pass. That
 * one reacts to a message that arrived; this one reacts to messages that
 * stopped arriving. A drone whose telemetry goes quiet longer than the
 * timeout gets marked {@code SIGNAL_LOST}, and its last known position is
 * left untouched - "lost signal" means the last thing heard from it is
 * still the best guess of where it is. There's no watchdog counterpart
 * needed for recovery: {@link AlertService#evaluate} raises {@code
 * SIGNAL_RECOVERED} on its own the moment {@link
 * DroneService#applyTelemetry} sees a fresh reading from a drone whose
 * previous status was {@code SIGNAL_LOST}.
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
     * Re-evaluates every drone not already {@code SIGNAL_LOST} each tick.
     * {@link MissionAssignmentService#assignPendingMissions()} follows
     * the same shape, re-checking everything rather than tracking what
     * changed.
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
