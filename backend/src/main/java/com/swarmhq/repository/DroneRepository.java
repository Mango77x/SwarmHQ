package com.swarmhq.repository;

import com.swarmhq.model.Drone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DroneRepository extends JpaRepository<Drone, Long> {

    Optional<Drone> findByExternalId(String externalId);

    long countByBatteryPercentLessThanEqual(int threshold);
}
