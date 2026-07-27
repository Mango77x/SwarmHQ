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

    // SignalMonitorService's watchdog (Sprint 13): every drone whose
    // telemetry has gone quiet for longer than the timeout, excluding ones
    // already marked SIGNAL_LOST (nothing to re-detect there until they
    // recover, which happens for free the next time real telemetry arrives).
    List<Drone> findByStatusNotAndLastUpdateAtBefore(DroneStatus status, Instant threshold);

    // MissionAssignmentService's greedy pick: nearest, fullest-battery
    // eligible drone. ::geography casts give real meters out of
    // ST_Distance instead of raw SRID-4326 degrees - the same "real
    // PostGIS query, not hand-rolled math" theme as RiskZoneRepository.
    // CAST(:missionStart AS geography), not :missionStart::geography -
    // Spring Data's named-parameter parser swallows a `::` cast that
    // immediately follows a `:param` token as part of the parameter name.
    @Query(value = """
            SELECT * FROM drones
            WHERE status = 'PATROLLING' AND battery_percent >= :minBatteryPercent AND position IS NOT NULL
            ORDER BY ST_Distance(position::geography, CAST(:missionStart AS geography)) / (battery_percent / 100.0)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Drone> findBestForMission(@Param("minBatteryPercent") int minBatteryPercent, @Param("missionStart") Point missionStart);
}
