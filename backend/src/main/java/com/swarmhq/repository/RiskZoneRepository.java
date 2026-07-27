package com.swarmhq.repository;

import com.swarmhq.model.RiskZone;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskZoneRepository extends JpaRepository<RiskZone, Long> {

    @Query(value = "SELECT * FROM risk_zones WHERE ST_Contains(area, :point)", nativeQuery = true)
    List<RiskZone> findContaining(@Param("point") Point point);
}
