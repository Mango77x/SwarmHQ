package com.swarmhq.web;

import java.util.List;

/**
 * POST /api/zones body - ring as [lon,lat] pairs (at least 3, a valid
 * polygon outline), same coordinate order as every other geometry-bearing
 * DTO. Doesn't need to already be closed (first point repeated last) -
 * ZoneService.create closes it, so the frontend can send exactly the
 * points an operator clicked.
 */
public record CreateZoneRequest(String name, List<double[]> ring) {
}
