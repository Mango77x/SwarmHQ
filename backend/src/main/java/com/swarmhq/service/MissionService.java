package com.swarmhq.service;

import com.swarmhq.model.Mission;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.web.CreateMissionRequest;
import com.swarmhq.web.MissionResponse;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MissionService {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final MissionRepository missionRepository;

    public MissionService(MissionRepository missionRepository) {
        this.missionRepository = missionRepository;
    }

    public List<MissionResponse> listAll() {
        return missionRepository.findAllWithDrone().stream()
                .map(MissionResponse::from)
                .toList();
    }

    public MissionResponse create(CreateMissionRequest request) {
        Coordinate[] coordinates = request.route().stream()
                .map(point -> new Coordinate(point[0], point[1]))
                .toArray(Coordinate[]::new);
        Mission mission = new Mission(GEOMETRY_FACTORY.createLineString(coordinates), request.priority());
        return MissionResponse.from(missionRepository.save(mission));
    }
}
