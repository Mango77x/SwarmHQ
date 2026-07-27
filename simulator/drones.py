from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import List, Tuple

Waypoint = Tuple[float, float]


class DroneStatus(Enum):
    PATROLLING = "PATROLLING"
    RETURNING = "RETURNING"


@dataclass
class Drone:
    """A simulated drone patrolling a fixed waypoint loop.

    Battery drains while patrolling; once it hits the low-battery
    threshold the drone breaks off to base (route[0]) instead of
    continuing the loop, then resumes patrolling once recharged.
    """

    external_id: str
    type: str
    route: List[Waypoint]
    ticks_per_segment: int
    battery_drain_per_tick: float
    low_battery_threshold: float

    status: DroneStatus = field(init=False)
    battery_percent: float = field(init=False)
    lat: float = field(init=False)
    lon: float = field(init=False)
    _segment_start: Waypoint = field(init=False)
    _target_index: int = field(init=False)
    _tick_in_segment: int = field(init=False)

    def __post_init__(self) -> None:
        self.status = DroneStatus.PATROLLING
        self.battery_percent = 100.0
        self._segment_start = self.route[0]
        self._target_index = 1 % len(self.route)
        self._tick_in_segment = 0
        self.lat, self.lon = self._segment_start

    def tick(self) -> None:
        self._drain_battery_if_patrolling()
        self._advance_towards_target()

    def to_telemetry_payload(self) -> dict:
        return {
            "type": self.type,
            "lat": round(self.lat, 6),
            "lon": round(self.lon, 6),
            "batteryPercent": round(self.battery_percent),
            "status": self.status.value,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    def _drain_battery_if_patrolling(self) -> None:
        if self.status is not DroneStatus.PATROLLING:
            return
        self.battery_percent = max(0.0, self.battery_percent - self.battery_drain_per_tick)
        if self.battery_percent <= self.low_battery_threshold:
            self._retarget(DroneStatus.RETURNING, target_index=0)

    def _advance_towards_target(self) -> None:
        target_lat, target_lon = self.route[self._target_index]
        start_lat, start_lon = self._segment_start

        self._tick_in_segment += 1
        progress = min(1.0, self._tick_in_segment / self.ticks_per_segment)
        self.lat = start_lat + (target_lat - start_lat) * progress
        self.lon = start_lon + (target_lon - start_lon) * progress

        if progress < 1.0:
            return

        if self.status is DroneStatus.RETURNING and self._target_index == 0:
            self.battery_percent = 100.0
            self._retarget(DroneStatus.PATROLLING, target_index=1 % len(self.route))
        else:
            next_index = (self._target_index + 1) % len(self.route)
            self._retarget(self.status, target_index=next_index)

    def _retarget(self, status: DroneStatus, target_index: int) -> None:
        self.status = status
        self._segment_start = (self.lat, self.lon)
        self._target_index = target_index
        self._tick_in_segment = 0
