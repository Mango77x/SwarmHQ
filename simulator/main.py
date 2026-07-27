import json
import logging
import signal
import time

import paho.mqtt.client as mqtt

from config import (
    BATTERY_DRAIN_PER_TICK,
    DRONE_COUNT,
    GPS_NOISE_STD_DEGREES,
    LOW_BATTERY_THRESHOLD,
    MQTT_CA_CERT_PATH,
    MQTT_DRONE_PASSWORD,
    MQTT_HOST,
    MQTT_PORT,
    MQTT_USE_TLS,
    PUBLISH_INTERVAL_SECONDS,
    TICKS_PER_SEGMENT,
)
from drones import Drone
from routes import build_routes

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("simulator")

_running = True


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
        )
        for index, route in enumerate(build_routes(DRONE_COUNT))
    ]


def _make_mission_assigned_handler(drone: Drone):
    # Runs on this drone's own MQTT client network thread (loop_start()),
    # not the main tick loop - Drone.start_mission() takes its own lock,
    # so this is safe to call concurrently with Drone.tick().
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


def _connect_drone_client(drone: Drone) -> mqtt.Client:
    # One MQTT connection per drone, authenticated as its own identity
    # (Sprint 11) - required for the broker's per-drone ACL patterns
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
    client.loop_start()
    return client


def main() -> None:
    signal.signal(signal.SIGINT, _handle_shutdown)
    signal.signal(signal.SIGTERM, _handle_shutdown)

    drones = _build_drones()
    clients = {drone.external_id: _connect_drone_client(drone) for drone in drones}
    log.info("Connected to %s:%s (TLS=%s) - simulating %d drone(s), each as its own identity",
              MQTT_HOST, MQTT_PORT, MQTT_USE_TLS, len(drones))

    try:
        while _running:
            for drone in drones:
                client = clients[drone.external_id]
                mission_event = drone.tick()
                client.publish(f"drones/{drone.external_id}/telemetry", json.dumps(drone.to_telemetry_payload()), qos=1)
                if mission_event is not None:
                    client.publish(f"drones/{drone.external_id}/mission-status", json.dumps(mission_event), qos=1)
            time.sleep(PUBLISH_INTERVAL_SECONDS)
    finally:
        for client in clients.values():
            client.loop_stop()
            client.disconnect()
        log.info("Disconnected")


if __name__ == "__main__":
    main()
