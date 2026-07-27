package com.swarmhq.repository;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the JTS/Hibernate Spatial/PostGIS round-trip actually works end to
 * end, not just that the app boots. Runs against the real Postgres/PostGIS
 * from docker-compose.yml (no Testcontainers yet); rolled back after each
 * test so it doesn't leave data behind.
 */
@SpringBootTest
@Transactional
class DroneRepositoryTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private DroneRepository droneRepository;

    @Test
    void savesAndReloadsPositionAsPostgisPoint() {
        Point madridOffice = GEOMETRY_FACTORY.createPoint(new Coordinate(-3.7038, 40.4168));

        Drone drone = new Drone("drone-repository-test", "quadcopter", DroneStatus.PATROLLING, 87);
        drone.setPosition(madridOffice);
        Drone saved = droneRepository.saveAndFlush(drone);

        Drone reloaded = droneRepository.findById(saved.getId()).orElseThrow();

        assertEquals(-3.7038, reloaded.getPosition().getX(), 1e-9);
        assertEquals(40.4168, reloaded.getPosition().getY(), 1e-9);
        assertEquals(4326, reloaded.getPosition().getSRID());
        assertEquals(DroneStatus.PATROLLING, reloaded.getStatus());
        assertEquals(87, reloaded.getBatteryPercent());
    }
}
