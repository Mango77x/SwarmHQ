import json
import logging
import math
import signal
import time

import paho.mqtt.client as mqtt

from boids import BoidsParams, compute_step
from config import (
    BATTERY_DRAIN_PER_TICK,
    BOIDS_ALIGNMENT_WEIGHT,
    BOIDS_CENTER_WEIGHT,
    BOIDS_COHESION_WEIGHT,
    BOIDS_MAX_STEP_DEGREES,
    BOIDS_NEIGHBOR_RADIUS_DEGREES,
    BOIDS_SEPARATION_WEIGHT,
    DRONE_COUNT,
    GPS_NOISE_STD_DEGREES,
    LOW_BATTERY_THRESHOLD,
    MQTT_CA_CERT_PATH,
    MQTT_DRONE_PASSWORD,
    MQTT_HOST,
    MQTT_PORT,
    MQTT_USE_TLS,
    PUBLISH_INTERVAL_SECONDS,
    SIGNAL_LOSS_CHANCE_PER_TICK,
    SIGNAL_LOSS_MAX_TICKS,
    SIGNAL_LOSS_MIN_TICKS,
    SWARM_MODE,
    TICKS_PER_SEGMENT,
)
from drones import Drone, DroneStatus
from routes import BASE_LAT, BASE_LON, build_routes

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("simulator")

_running = True
_METERS_PER_DEGREE = 111_320.0
MISSION_ASSIGNMENT_MODE_TOPIC = "system/mission-assignment-mode"

# Used to be the sole, fixed gate for both boids movement and mission
# bidding, read once at startup. The backend can flip it live now, via a
# retained message on MISSION_ASSIGNMENT_MODE_TOPIC published on every
# backend startup as well as on future changes (see
# MissionAssignmentModeHolder), so this is a mutable flag instead.
# SWARM_MODE just becomes the fallback value used before that retained
# message actually arrives, e.g. the first tick or two after connecting.
_swarm_mode_active = SWARM_MODE

BOIDS_PARAMS = BoidsParams(
    neighbor_radius_degrees=BOIDS_NEIGHBOR_RADIUS_DEGREES,
    separation_weight=BOIDS_SEPARATION_WEIGHT,
    alignment_weight=BOIDS_ALIGNMENT_WEIGHT,
    cohesion_weight=BOIDS_COHESION_WEIGHT,
    center_weight=BOIDS_CENTER_WEIGHT,
    max_step_degrees=BOIDS_MAX_STEP_DEGREES,
    center=(BASE_LAT, BASE_LON),
)


def _handle_shutdown(signum, _frame):
    global _running
    log.info("Shutting down (signal %s)", signum)
    _running = False


def _build_drones() -> list[Drone]:
    return [
        Drone(
            external_id=f"drone-{index + 1}",
            type="quadcopter",
            route=route,
            ticks_per_segment=TICKS_PER_SEGMENT,
            battery_drain_per_tick=BATTERY_DRAIN_PER_TICK,
            low_battery_threshold=LOW_BATTERY_THRESHOLD,
            gps_noise_std_degrees=GPS_NOISE_STD_DEGREES,
            signal_loss_chance_per_tick=SIGNAL_LOSS_CHANCE_PER_TICK,
            signal_loss_min_ticks=SIGNAL_LOSS_MIN_TICKS,
            signal_loss_max_ticks=SIGNAL_LOSS_MAX_TICKS,
        )
        for index, route in enumerate(build_routes(DRONE_COUNT))
    ]


def _distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    lon_scale = math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot(lat1 - lat2, (lon1 - lon2) * lon_scale) * _METERS_PER_DEGREE


def _make_mission_assigned_handler(drone: Drone):
    # Runs on this drone's own MQTT client network thread (loop_start()),
    # separate from the main tick loop. Drone.start_mission() takes its
    # own lock, so calling it concurrently with Drone.tick() is safe.
    def _on_mission_assigned(_client, _userdata, message):
        try:
            payload = json.loads(message.payload)
            # Backend sends [lon,lat] pairs (GeoJSON/PostGIS order); the
            # simulator's own routes are (lat,lon), matching routes.py.
            waypoints = [(point[1], point[0]) for point in payload["route"]]
            accepted = drone.start_mission(payload["missionId"], waypoints)
            if accepted:
                log.info("%s accepted mission %s (%s priority, %d waypoints)",
                         drone.external_id, payload["missionId"], payload.get("priority"), len(waypoints))
            else:
                log.warning("%s rejected mission %s - not currently PATROLLING",
                             drone.external_id, payload["missionId"])
        except Exception:
            log.exception("Failed to process mission assignment for %s", drone.external_id)

    return _on_mission_assigned


def _make_mission_cancel_handler(drone: Drone):
    # Same thread-safety reasoning as _on_mission_assigned above -
    # Drone.cancel_mission() takes its own lock too.
    def _on_mission_cancel(_client, _userdata, message):
        try:
            payload = json.loads(message.payload)
            accepted = drone.cancel_mission(payload["missionId"])
            if accepted:
                log.info("%s recalled to base, cancelling mission %s",
                         drone.external_id, payload["missionId"])
            else:
                log.warning("%s ignored cancel for mission %s - not currently flying it",
                             drone.external_id, payload["missionId"])
        except Exception:
            log.exception("Failed to process mission cancel for %s", drone.external_id)

    return _on_mission_cancel


def _make_mission_available_handler(drone: Drone, client: mqtt.Client):
    # The auction-mode counterpart to _on_mission_assigned: instead of
    # waiting to be told, an idle drone bids on what it hears about. Cost
    # mirrors DroneRepository.findBestForMission's own distance/battery
    # formula, so a drone bids on roughly the criteria the centralized
    # engine would've judged it by, and the lowest bid winning
    # (AuctionCoordinatorService picks it) does the same job that
    # engine's ORDER BY does.
    #
    # Every drone stays subscribed to missions/available unconditionally.
    # The backend simply never publishes to it outside auction mode, but
    # the _swarm_mode_active check here is a cheap second guard against a
    # stray message right at a mode-switch boundary.
    def _on_mission_available(_client, _userdata, message):
        try:
            if not _swarm_mode_active or drone.status is not DroneStatus.PATROLLING:
                return
            payload = json.loads(message.payload)
            distance = _distance_meters(drone.lat, drone.lon, payload["lat"], payload["lon"])
            cost = distance / (drone.battery_percent / 100.0)
            bid = json.dumps({"droneId": drone.external_id, "cost": cost})
            client.publish(f"missions/{payload['missionId']}/bids", bid, qos=1)
        except Exception:
            log.exception("%s failed to process available mission", drone.external_id)

    return _on_mission_available


def _on_mode_message(_client, _userdata, message):
    # Every drone client subscribes to this and gets the same retained
    # message independently, so this fires once per drone for the same
    # real-world event. Only log when the value actually changes, to
    # avoid a redundant line per client on every delivery.
    global _swarm_mode_active
    try:
        payload = json.loads(message.payload)
        new_value = payload.get("mode") == "auction"
        if new_value != _swarm_mode_active:
            log.info("Mission-assignment mode is now %s", "auction (swarm)" if new_value else "centralized")
        _swarm_mode_active = new_value
    except Exception:
        log.exception("Failed to process mission-assignment mode update")


def _connect_drone_client(drone: Drone) -> mqtt.Client:
    # One MQTT connection per drone, authenticated as its own identity.
    # This is required for the broker's per-drone ACL patterns
    # (drones/%u/...) to actually mean anything; a single shared
    # connection for every drone would make "per-drone auth" hollow.
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, protocol=mqtt.MQTTv5, client_id=drone.external_id)
    client.username_pw_set(drone.external_id, MQTT_DRONE_PASSWORD)
    if MQTT_USE_TLS:
        client.tls_set(ca_certs=MQTT_CA_CERT_PATH)
    client.connect(MQTT_HOST, MQTT_PORT)
    mission_topic = f"drones/{drone.external_id}/mission"
    client.subscribe(mission_topic)
    client.message_callback_add(mission_topic, _make_mission_assigned_handler(drone))
    cancel_topic = f"drones/{drone.external_id}/mission/cancel"
    client.subscribe(cancel_topic)
    client.message_callback_add(cancel_topic, _make_mission_cancel_handler(drone))
    # Always subscribed, rather than gated behind SWARM_MODE at startup -
    # both bidding and the mode itself follow the backend live instead.
    client.subscribe("missions/available")
    client.message_callback_add("missions/available", _make_mission_available_handler(drone, client))
    client.subscribe(MISSION_ASSIGNMENT_MODE_TOPIC, qos=1)
    client.message_callback_add(MISSION_ASSIGNMENT_MODE_TOPIC, _on_mode_message)
    client.loop_start()
    return client


def main() -> None:
    signal.signal(signal.SIGINT, _handle_shutdown)
    signal.signal(signal.SIGTERM, _handle_shutdown)

    drones = _build_drones()
    clients = {drone.external_id: _connect_drone_client(drone) for drone in drones}
    # Mission events that occur while a drone is signal_lost can't get
    # through a dropped connection either, same as telemetry - so they're
    # held here and flushed together the moment it's back, instead of
    # being silently lost (which would strand a mission ACTIVE forever
    # with no completion/failure ever reported).
    pending_mission_events: dict[str, list] = {drone.external_id: [] for drone in drones}
    # Each currently-patrolling drone's position as of the previous tick,
    # so this tick's boids step can compute a velocity (this tick's
    # position minus last tick's, per drone) for the alignment rule. A
    # lone Drone has no notion of its neighbors' headings, so this lives
    # at the loop level instead. Replaced wholesale each tick rather than
    # merged: a drone missing from it (just resumed patrolling after a
    # mission/RTB) gets zero velocity for one tick instead of a stale,
    # misleadingly large one left over from whenever it last patrolled.
    last_patrol_positions: dict[str, tuple[float, float]] = {}
    log.info("Connected to %s:%s (TLS=%s) - simulating %d drone(s), each as its own identity "
              "(initial mode: %s, follows the backend live from here)",
              MQTT_HOST, MQTT_PORT, MQTT_USE_TLS, len(drones),
              "auction/swarm" if _swarm_mode_active else "centralized")

    try:
        while _running:
            boids_positions: dict[str, tuple[float, float]] = {}
            if _swarm_mode_active:
                current_positions = {
                    drone.external_id: (drone.lat, drone.lon)
                    for drone in drones if drone.status is DroneStatus.PATROLLING
                }
                velocities = {
                    external_id: (lat - last_patrol_positions.get(external_id, (lat, lon))[0],
                                  lon - last_patrol_positions.get(external_id, (lat, lon))[1])
                    for external_id, (lat, lon) in current_positions.items()
                }
                boids_positions = compute_step(current_positions, velocities, BOIDS_PARAMS)
                last_patrol_positions = current_positions

            for drone in drones:
                client = clients[drone.external_id]
                mission_event = drone.tick(position_override=boids_positions.get(drone.external_id))
                if mission_event is not None:
                    pending_mission_events[drone.external_id].append(mission_event)
                if drone.signal_lost:
                    continue
                client.publish(f"drones/{drone.external_id}/telemetry", json.dumps(drone.to_telemetry_payload()), qos=1)
                queued = pending_mission_events[drone.external_id]
                for event in queued:
                    client.publish(f"drones/{drone.external_id}/mission-status", json.dumps(event), qos=1)
                queued.clear()
            time.sleep(PUBLISH_INTERVAL_SECONDS)
    finally:
        for client in clients.values():
            client.loop_stop()
            client.disconnect()
        log.info("Disconnected")


if __name__ == "__main__":
    main()
