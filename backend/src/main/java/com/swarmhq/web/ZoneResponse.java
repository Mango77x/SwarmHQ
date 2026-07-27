package com.swarmhq.web;

import com.swarmhq.model.RiskZone;
import org.locationtech.jts.geom.Coordinate;

import java.util.Arrays;
import java.util.List;

/**
 * A risk zone's exterior ring as [lon, lat] pairs - the same coordinate
 * order as a GeoJSON Polygon ring, so the frontend can drop this straight
 * into a `{type: "Polygon", coordinates: [ring]}` geometry with no
 * reordering.
 */
public record ZoneResponse(String name, List<double[]> ring) {

    public static ZoneResponse from(RiskZone zone) {
        Coordinate[] coordinates = zone.getArea().getExteriorRing().getCoordinates();
        List<double[]> ring = Arrays.stream(coordinates)
                .map(c -> new double[] {c.x, c.y})
                .toList();
        return new ZoneResponse(zone.getName(), ring);
    }
}
