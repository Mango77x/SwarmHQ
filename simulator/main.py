import json
import logging
import re
import signal
import time

import paho.mqtt.client as mqtt

from config import (
    BATTERY_DRAIN_PER_TICK,
    DRONE_COUNT,
    LOW_BATTERY_THRESHOLD,
    MQTT_HOST,
    MQTT_PORT,
    PUBLISH_INTERVAL_SECONDS,
    TICKS_PER_SEGMENT,
)
from drones import Drone
from routes import build_routes

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("simulator")

_running = True

_MISSION_TOPIC_PATTERN = re.compile(r"^drones/([^/]+)/mission$")


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
        )
        for index, route in enumerate(build_routes(DRONE_COUNT))
    ]


def _make_mission_assigned_handler(drones_by_id: dict[str, Drone]):
    # Runs on the MQTT client's own network thread (client.loop_start()),
    # not the main tick loop - Drone.start_mission() takes its own lock,
    # so this is safe to call concurrently with Drone.tick().
    def _on_mission_assigned(_client, _userdata, message):
        match = _MISSION_TOPIC_PATTERN.match(message.topic)
        if not match:
            return
        external_id = match.group(1)
        drone = drones_by_id.get(external_id)
        if drone is None:
            log.warning("Mission assignment for unknown drone '%s'", external_id)
            return

        try:
            payload = json.loads(message.payload)
            # Backend sends [lon,lat] pairs (GeoJSON/PostGIS order); the
            # simulator's own routes are (lat,lon), matching routes.py.
            waypoints = [(point[1], point[0]) for point in payload["route"]]
            accepted = drone.start_mission(payload["missionId"], waypoints)
            if accepted:
                log.info("%s accepted mission %s (%s priority, %d waypoints)",
                         external_id, payload["missionId"], payload.get("priority"), len(waypoints))
            else:
                log.warning("%s rejected mission %s - not currently PATROLLING",
                             external_id, payload["missionId"])
        except Exception:
            log.exception("Failed to process mission assignment for %s", external_id)

    return _on_mission_assigned


def main() -> None:
    signal.signal(signal.SIGINT, _handle_shutdown)
    signal.signal(signal.SIGTERM, _handle_shutdown)

    drones = _build_drones()
    drones_by_id = {drone.external_id: drone for drone in drones}

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, protocol=mqtt.MQTTv5)
    client.connect(MQTT_HOST, MQTT_PORT)
    client.subscribe("drones/+/mission")
    client.message_callback_add("drones/+/mission", _make_mission_assigned_handler(drones_by_id))
    client.loop_start()
    log.info("Connected to %s:%s - simulating %d drone(s)", MQTT_HOST, MQTT_PORT, len(drones))

    try:
        while _running:
            for drone in drones:
                mission_event = drone.tick()
                client.publish(f"drones/{drone.external_id}/telemetry", json.dumps(drone.to_telemetry_payload()), qos=1)
                if mission_event is not None:
                    client.publish(f"drones/{drone.external_id}/mission-status", json.dumps(mission_event), qos=1)
            time.sleep(PUBLISH_INTERVAL_SECONDS)
    finally:
        client.loop_stop()
        client.disconnect()
        log.info("Disconnected")


if __name__ == "__main__":
    main()
