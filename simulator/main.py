import json
import logging
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


def main() -> None:
    signal.signal(signal.SIGINT, _handle_shutdown)
    signal.signal(signal.SIGTERM, _handle_shutdown)

    drones = _build_drones()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, protocol=mqtt.MQTTv5)
    client.connect(MQTT_HOST, MQTT_PORT)
    client.loop_start()
    log.info("Connected to %s:%s - simulating %d drone(s)", MQTT_HOST, MQTT_PORT, len(drones))

    try:
        while _running:
            for drone in drones:
                drone.tick()
                topic = f"drones/{drone.external_id}/telemetry"
                client.publish(topic, json.dumps(drone.to_telemetry_payload()), qos=1)
            time.sleep(PUBLISH_INTERVAL_SECONDS)
    finally:
        client.loop_stop()
        client.disconnect()
        log.info("Disconnected")


if __name__ == "__main__":
    main()
