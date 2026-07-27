import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import List, Optional, Tuple

Waypoint = Tuple[float, float]


class DroneStatus(Enum):
    PATROLLING = "PATROLLING"
    RETURNING = "RETURNING"
    ON_MISSION = "ON_MISSION"


@dataclass
class Drone:
    """A simulated drone patrolling a fixed waypoint loop, occasionally
    interrupted by an assigned mission (Sprint 10).

    Battery drains while patrolling or flying a mission; once it hits the
    low-battery threshold the drone breaks off to base (route[0]) instead
    of continuing, aborting any in-progress mission, then resumes
    patrolling once recharged.
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
    active_mission_id: Optional[int] = field(init=False)
    _segment_start: Waypoint = field(init=False)
    _target_index: int = field(init=False)
    _tick_in_segment: int = field(init=False)
    _mission_route: Optional[List[Waypoint]] = field(init=False)
    _patrol_target_index: int = field(init=False)
    _pending_mission_event: Optional[dict] = field(init=False)

    def __post_init__(self) -> None:
        self.status = DroneStatus.PATROLLING
        self.battery_percent = 100.0
        self._segment_start = self.route[0]
        self._target_index = 1 % len(self.route)
        self._tick_in_segment = 0
        self.lat, self.lon = self._segment_start
        self.active_mission_id = None
        self._mission_route = None
        self._patrol_target_index = self._target_index
        self._pending_mission_event = None
        # tick() runs on the main loop thread; start_mission() is called
        # from the MQTT client's own network thread (loop_start()) - both
        # mutate the same state, so they're serialized here.
        self._lock = threading.Lock()

    def start_mission(self, mission_id: int, waypoints: List[Waypoint]) -> bool:
        """Interrupts patrolling to fly an assigned mission route. Returns
        False (assignment rejected) if the drone isn't currently free -
        shouldn't normally happen since the backend only assigns PATROLLING
        drones, but a telemetry/assignment race is possible."""
        with self._lock:
            if self.status is not DroneStatus.PATROLLING or len(waypoints) == 0:
                return False
            self._patrol_target_index = self._target_index
            self.active_mission_id = mission_id
            self._mission_route = waypoints
            self._retarget(DroneStatus.ON_MISSION, target_index=0)
            return True

    def tick(self) -> Optional[dict]:
        """Advances the drone by one publish interval. Returns a
        mission-status event ({"missionId", "status", ["reason"]}) if the
        active mission just completed or was aborted this tick, else None."""
        with self._lock:
            self._pending_mission_event = None
            self._drain_battery_if_active()
            self._advance_towards_target()
            return self._pending_mission_event

    def to_telemetry_payload(self) -> dict:
        return {
            "type": self.type,
            "lat": round(self.lat, 6),
            "lon": round(self.lon, 6),
            "batteryPercent": round(self.battery_percent),
            "status": self.status.value,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    def _active_route(self) -> List[Waypoint]:
        return self._mission_route if self.status is DroneStatus.ON_MISSION else self.route

    def _drain_battery_if_active(self) -> None:
        if self.status not in (DroneStatus.PATROLLING, DroneStatus.ON_MISSION):
            return
        self.battery_percent = max(0.0, self.battery_percent - self.battery_drain_per_tick)
        if self.battery_percent <= self.low_battery_threshold:
            if self.status is DroneStatus.ON_MISSION:
                self._abort_mission("low_battery")
            self._retarget(DroneStatus.RETURNING, target_index=0)

    def _advance_towards_target(self) -> None:
        route = self._active_route()
        target_lat, target_lon = route[self._target_index]
        start_lat, start_lon = self._segment_start

        self._tick_in_segment += 1
        progress = min(1.0, self._tick_in_segment / self.ticks_per_segment)
        self.lat = start_lat + (target_lat - start_lat) * progress
        self.lon = start_lon + (target_lon - start_lon) * progress

        if progress < 1.0:
            return

        if self.status is DroneStatus.ON_MISSION:
            if self._target_index >= len(route) - 1:
                self._complete_mission()
            else:
                self._retarget(DroneStatus.ON_MISSION, target_index=self._target_index + 1)
        elif self.status is DroneStatus.RETURNING and self._target_index == 0:
            self.battery_percent = 100.0
            self._retarget(DroneStatus.PATROLLING, target_index=1 % len(self.route))
        else:
            next_index = (self._target_index + 1) % len(route)
            self._retarget(self.status, target_index=next_index)

    def _complete_mission(self) -> None:
        self._pending_mission_event = {"missionId": self.active_mission_id, "status": "COMPLETED"}
        self.active_mission_id = None
        self._mission_route = None
        self._retarget(DroneStatus.PATROLLING, target_index=self._patrol_target_index)

    def _abort_mission(self, reason: str) -> None:
        self._pending_mission_event = {"missionId": self.active_mission_id, "status": "FAILED", "reason": reason}
        self.active_mission_id = None
        self._mission_route = None

    def _retarget(self, status: DroneStatus, target_index: int) -> None:
        self.status = status
        self._segment_start = (self.lat, self.lon)
        self._target_index = target_index
        self._tick_in_segment = 0
