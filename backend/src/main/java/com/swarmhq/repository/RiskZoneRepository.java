package com.swarmhq.repository;

import com.swarmhq.model.RiskZone;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskZoneRepository extends JpaRepository<RiskZone, Long> {

    @Query(value = "SELECT * FROM risk_zones WHERE ST_Contains(area, :point)", nativeQuery = true)
    List<RiskZone> findContaining(@Param("point") Point point);

    // MissionService's mission-creation geofence check: a route only needs
    // to touch or pass through a zone, not be fully contained by one, so
    // this is ST_Intersects rather than findContaining's ST_Contains.
    @Query(value = "SELECT * FROM risk_zones WHERE ST_Intersects(area, :route)", nativeQuery = true)
    List<RiskZone> findIntersecting(@Param("route") LineString route);
}
