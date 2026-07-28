"""Sprint 14: boids flocking for patrolling drones in swarm mode.

Classic separation/alignment/cohesion (Craig Reynolds) plus a "return to
center" term so the flock doesn't wander off - unbounded boids has no
notion of a patrol area to stay near, and this simulation only has a
small finite map worth demonstrating on.

Longitude is scaled by cos(latitude) so distances/vectors aren't visibly
stretched east-west at this latitude (~40 deg N, where a degree of
longitude is only ~77% the length of a degree of latitude) - the same
approximation config.py already uses for GPS noise, just applied per-axis
here instead of uniformly.

Pure functions, no I/O - kept separate from Drone so it's cheap to unit
test in isolation (see test_boids.py).
"""

import math
from dataclasses import dataclass
from typing import Dict, Tuple

Position = Tuple[float, float]  # (lat, lon)
Velocity = Tuple[float, float]  # (dlat, dlon) since the previous step


@dataclass(frozen=True)
class BoidsParams:
    neighbor_radius_degrees: float
    separation_weight: float
    alignment_weight: float
    cohesion_weight: float
    center_weight: float
    max_step_degrees: float
    center: Position


def compute_step(positions: Dict[str, Position], velocities: Dict[str, Velocity],
                  params: BoidsParams) -> Dict[str, Position]:
    """Given every patrolling drone's current position and its heading
    since the previous step (for alignment), returns each drone's next
    position. Drones not present in `positions` are neither read nor
    affected - callers only pass the currently-patrolling subset."""
    lon_scale = _lon_scale(params.center[0])
    ids = list(positions.keys())
    new_positions: Dict[str, Position] = {}

    for drone_id in ids:
        lat, lon = positions[drone_id]
        neighbor_ids = [
            other_id for other_id in ids
            if other_id != drone_id
            and _distance(lat, lon, *positions[other_id], lon_scale) <= params.neighbor_radius_degrees
        ]

        sep_lat, sep_lon = 0.0, 0.0
        align_lat, align_lon = 0.0, 0.0
        coh_lat, coh_lon = 0.0, 0.0

        if neighbor_ids:
            for other_id in neighbor_ids:
                o_lat, o_lon = positions[other_id]
                d = max(_distance(lat, lon, o_lat, o_lon, lon_scale), 1e-9)
                # Separation: steer away from neighbors, more strongly the closer they are.
                sep_lat += (lat - o_lat) / d
                sep_lon += (lon - o_lon) / d

            align_lat = sum(velocities.get(oid, (0.0, 0.0))[0] for oid in neighbor_ids) / len(neighbor_ids)
            align_lon = sum(velocities.get(oid, (0.0, 0.0))[1] for oid in neighbor_ids) / len(neighbor_ids)

            avg_lat = sum(positions[oid][0] for oid in neighbor_ids) / len(neighbor_ids)
            avg_lon = sum(positions[oid][1] for oid in neighbor_ids) / len(neighbor_ids)
            coh_lat = avg_lat - lat
            coh_lon = avg_lon - lon

        center_lat = params.center[0] - lat
        center_lon = params.center[1] - lon

        dlat = (params.separation_weight * sep_lat
                + params.alignment_weight * align_lat
                + params.cohesion_weight * coh_lat
                + params.center_weight * center_lat)
        dlon = (params.separation_weight * sep_lon
                + params.alignment_weight * align_lon
                + params.cohesion_weight * coh_lon
                + params.center_weight * center_lon)

        # Clamp step size so movement stays smooth and bounded per tick,
        # regardless of how strong the combined rule forces happen to be.
        step_len = math.hypot(dlat, dlon * lon_scale)
        if step_len > params.max_step_degrees:
            scale = params.max_step_degrees / step_len
            dlat *= scale
            dlon *= scale

        new_positions[drone_id] = (lat + dlat, lon + dlon)

    return new_positions


def _lon_scale(lat_degrees: float) -> float:
    return math.cos(math.radians(lat_degrees))


def _distance(lat1: float, lon1: float, lat2: float, lon2: float, lon_scale: float) -> float:
    return math.hypot(lat1 - lat2, (lon1 - lon2) * lon_scale)
