package com.swarmhq.repository;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DroneRepository extends JpaRepository<Drone, Long> {

    Optional<Drone> findByExternalId(String externalId);

    long countByBatteryPercentLessThanEqual(int threshold);

    // Feeds SignalMonitorService's watchdog: every drone whose telemetry
    // has gone quiet longer than the timeout, excluding ones already
    // marked SIGNAL_LOST (nothing to re-detect there - they clear on
    // their own once real telemetry arrives again).
    List<Drone> findByStatusNotAndLastUpdateAtBefore(DroneStatus status, Instant threshold);

    // MissionAssignmentService's greedy pick: nearest, fullest-battery
    // eligible drone. The ::geography casts get real meters out of
    // ST_Distance instead of raw SRID-4326 degrees, same idea as
    // RiskZoneRepository's own PostGIS queries. Written as
    // CAST(:missionStart AS geography) rather than :missionStart::geography
    // because Spring Data's named-parameter parser swallows a `::` cast
    // written right after a `:param` token as part of the parameter name.
    @Query(value = """
            SELECT * FROM drones
            WHERE status = 'PATROLLING' AND battery_percent >= :minBatteryPercent AND position IS NOT NULL
            ORDER BY ST_Distance(position::geography, CAST(:missionStart AS geography)) / (battery_percent / 100.0)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Drone> findBestForMission(@Param("minBatteryPercent") int minBatteryPercent, @Param("missionStart") Point missionStart);
}
