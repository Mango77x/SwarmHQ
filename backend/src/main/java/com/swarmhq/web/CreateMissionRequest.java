package com.swarmhq.web;

import com.swarmhq.model.MissionPriority;

import java.util.List;

/**
 * POST /api/missions body - route as [lon,lat] pairs (at least 2, a valid
 * LineString), same coordinate order as every other geometry-bearing DTO
 * in this project.
 */
public record CreateMissionRequest(List<double[]> route, MissionPriority priority) {
}
