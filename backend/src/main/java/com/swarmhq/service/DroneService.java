package com.swarmhq.service;

import com.swarmhq.model.Drone;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.web.DroneResponse;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DroneService {

    private final DroneRepository droneRepository;

    public DroneService(DroneRepository droneRepository) {
        this.droneRepository = droneRepository;
    }

    public List<DroneResponse> listAll() {
        return droneRepository.findAll().stream()
                .map(DroneService::toResponse)
                .toList();
    }

    private static DroneResponse toResponse(Drone drone) {
        Point position = drone.getPosition();
        Double lat = position != null ? position.getY() : null;
        Double lon = position != null ? position.getX() : null;
        return new DroneResponse(
                drone.getExternalId(),
                drone.getType(),
                lat,
                lon,
                drone.getBatteryPercent(),
                drone.getStatus(),
                drone.getLastUpdateAt());
    }
}
