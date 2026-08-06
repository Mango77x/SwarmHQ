package com.swarmhq.service;

import com.swarmhq.model.RiskZone;
import com.swarmhq.repository.RiskZoneRepository;
import com.swarmhq.web.CreateZoneRequest;
import com.swarmhq.web.ZoneResponse;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class ZoneService {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final RiskZoneRepository riskZoneRepository;

    public ZoneService(RiskZoneRepository riskZoneRepository) {
        this.riskZoneRepository = riskZoneRepository;
    }

    public List<ZoneResponse> listAll() {
        return riskZoneRepository.findAll().stream()
                .map(ZoneResponse::from)
                .toList();
    }

    /**
     * Declares a new zone at runtime - an operator marking a no-fly area
     * on the map rather than one only ever arriving via a Flyway seed.
     * The ring doesn't need to already be closed: it's exactly the points
     * an operator clicked, in order, and a polygon needs its first point
     * repeated last (JTS/PostGIS both reject an open ring), so that's
     * done here rather than pushed onto every caller.
     */
    public ZoneResponse create(CreateZoneRequest request) {
        List<double[]> points = request.ring();
        if (points.size() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A zone needs at least 3 points");
        }

        List<Coordinate> coordinates = new ArrayList<>(points.stream()
                .map(point -> new Coordinate(point[0], point[1]))
                .toList());
        Coordinate first = coordinates.get(0);
        if (!first.equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(first.x, first.y));
        }

        RiskZone zone = new RiskZone(request.name(), GEOMETRY_FACTORY.createPolygon(coordinates.toArray(new Coordinate[0])));
        return ZoneResponse.from(riskZoneRepository.save(zone));
    }
}
