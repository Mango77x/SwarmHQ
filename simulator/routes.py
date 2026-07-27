"""Fixed patrol routes for simulated drones.

Each route is a closed loop of (lat, lon) waypoints; index 0 is the
drone's base, which is also where it heads when returning on low battery.
"""

from typing import List, Tuple

Waypoint = Tuple[float, float]

# Roughly Madrid - matches the coordinates already used in the backend's
# own geometry round-trip test (DroneRepositoryTests).
BASE_LAT = 40.4168
BASE_LON = -3.7038

# Degrees between each drone's base, offset diagonally so routes don't
# overlap (~0.01 deg latitude is about 1.1 km).
_OFFSET_STEP = 0.01
_SIDE = 0.006


def build_routes(drone_count: int) -> List[List[Waypoint]]:
    return [_square_patrol(index) for index in range(drone_count)]


def _square_patrol(index: int) -> List[Waypoint]:
    base_lat = BASE_LAT + index * _OFFSET_STEP
    base_lon = BASE_LON + index * _OFFSET_STEP
    return [
        (base_lat, base_lon),
        (base_lat + _SIDE, base_lon),
        (base_lat + _SIDE, base_lon + _SIDE),
        (base_lat, base_lon + _SIDE),
    ]
