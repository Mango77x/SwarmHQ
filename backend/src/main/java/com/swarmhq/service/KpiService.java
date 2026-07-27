package com.swarmhq.service;

import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.web.KpiSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Dashboard aggregates (Sprint 9) - polled by the frontend
 * (frontend/src/api/kpis.ts) rather than pushed over STOMP like
 * DroneService/AlertService: these are aggregate counts a dashboard
 * refreshes every few seconds, not per-event state a marker/alert list
 * needs instantly, so broadcasting a full recompute on every single
 * drone/event write isn't worth the complexity here.
 */
@Service
public class KpiService {

    private static final Duration RECENT_ALERTS_WINDOW = Duration.ofHours(1);

    private final MissionRepository missionRepository;
    private final EventRepository eventRepository;
    private final DroneRepository droneRepository;

    public KpiService(MissionRepository missionRepository, EventRepository eventRepository, DroneRepository droneRepository) {
        this.missionRepository = missionRepository;
        this.eventRepository = eventRepository;
        this.droneRepository = droneRepository;
    }

    public KpiSummary summarize() {
        long active = missionRepository.countByStatus(MissionStatus.ACTIVE);
        long completed = missionRepository.countByStatus(MissionStatus.COMPLETED);
        long failed = missionRepository.countByStatus(MissionStatus.FAILED);
        Double successRate = (completed + failed) == 0 ? null : 100.0 * completed / (completed + failed);

        long recentAlertCount = eventRepository.countByOccurredAtAfter(Instant.now().minus(RECENT_ALERTS_WINDOW));
        long criticalBatteryDroneCount = droneRepository.countByBatteryPercentLessThanEqual(AlertService.LOW_BATTERY_THRESHOLD);

        return new KpiSummary(active, successRate, recentAlertCount, criticalBatteryDroneCount);
    }
}
