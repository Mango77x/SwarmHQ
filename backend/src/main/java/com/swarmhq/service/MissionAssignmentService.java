package com.swarmhq.service;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * The "constrained mission assignment" engine (Sprint 10) - a greedy
 * scheduled pass matching {@code PENDING} missions to eligible drones,
 * turning the project from "a panel that shows data" into "a system that
 * makes decisions" (see PROJECT_OVERVIEW.md).
 *
 * This is the {@code centralized} assignment strategy - the alternative,
 * {@code auction} (Sprint 14, {@link AuctionCoordinatorService}), only
 * decides differently *which* drone gets a mission; both hand it off the
 * same way via {@link MissionAssigner}. Which one is actually active is
 * decided by {@link MissionAssignmentModeHolder}, checked fresh on every
 * tick rather than fixed for the app's lifetime - this bean and
 * {@code AuctionCoordinatorService} both always exist, they just no-op on
 * whichever tick isn't theirs.
 */
@Service
public class MissionAssignmentService {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * A drone this close to needing to RTB shouldn't be handed a mission
     * it might not finish. Built off AlertService's threshold rather than
     * a second unrelated magic number.
     */
    static final int MIN_BATTERY_PERCENT = AlertService.LOW_BATTERY_THRESHOLD + 10;

    private final MissionRepository missionRepository;
    private final DroneRepository droneRepository;
    private final MissionAssigner missionAssigner;
    private final MissionAssignmentModeHolder modeHolder;
    private final boolean schedulerEnabled;

    public MissionAssignmentService(MissionRepository missionRepository, DroneRepository droneRepository,
            MissionAssigner missionAssigner, MissionAssignmentModeHolder modeHolder,
            @Value("${swarmhq.mission-assignment.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.missionRepository = missionRepository;
        this.droneRepository = droneRepository;
        this.missionAssigner = missionAssigner;
        this.modeHolder = modeHolder;
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Disabled in tests (see src/test/resources/application.properties) -
     * a background tick racing against tests that assert on Mission/KPI
     * state would make them flaky, since MissionAssignmentServiceTests
     * already calls {@link #assignPendingMissions()} directly and doesn't
     * need the schedule itself under test. Also a no-op whenever the mode
     * holder currently says {@code AUCTION} - both strategies running at
     * once would race each other to claim the same PENDING mission.
     */
    @Scheduled(fixedDelay = 5000)
    void scheduledAssignment() {
        if (schedulerEnabled && modeHolder.get() == MissionAssignmentModeHolder.Mode.CENTRALIZED) {
            assignPendingMissions();
        }
    }

    /**
     * Re-evaluates every PENDING mission each tick (not just newly created
     * ones) so a mission left unassigned because no drone qualified last
     * time gets picked up automatically once one does (e.g. a drone
     * recharges past {@link #MIN_BATTERY_PERCENT}).
     */
    public void assignPendingMissions() {
        List<Mission> pending = missionRepository.findByStatusOrderByCreatedAtAsc(MissionStatus.PENDING);
        // Stable sort: priority order first, oldest-within-priority second.
        pending.sort(Comparator.comparing(Mission::getPriority).reversed());

        for (Mission mission : pending) {
            // Queried fresh per mission (not a single snapshot up front) so
            // a drone claimed by an earlier mission in this same pass is
            // already excluded - MissionAssigner.assign() commits
            // ON_MISSION synchronously before moving to the next mission.
            droneRepository.findBestForMission(MIN_BATTERY_PERCENT, missionStart(mission))
                    .ifPresent(drone -> missionAssigner.assign(mission, drone));
        }
    }

    private Point missionStart(Mission mission) {
        return GEOMETRY_FACTORY.createPoint(mission.getRoute().getCoordinateN(0));
    }
}
