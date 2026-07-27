package com.swarmhq.repository;

import com.swarmhq.model.Drone;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DroneRepository extends JpaRepository<Drone, Long> {

    Optional<Drone> findByExternalId(String externalId);

    long countByBatteryPercentLessThanEqual(int threshold);

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
