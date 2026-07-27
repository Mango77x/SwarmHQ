import os

MQTT_HOST = os.environ.get("MQTT_HOST", "localhost")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))

DRONE_COUNT = int(os.environ.get("DRONE_COUNT", "4"))
PUBLISH_INTERVAL_SECONDS = float(os.environ.get("PUBLISH_INTERVAL_SECONDS", "2"))

# How many publish ticks it takes a drone to cross one leg of its patrol
# route - lower is faster/choppier movement, higher is slower/smoother.
TICKS_PER_SEGMENT = int(os.environ.get("TICKS_PER_SEGMENT", "10"))

BATTERY_DRAIN_PER_TICK = float(os.environ.get("BATTERY_DRAIN_PER_TICK", "0.5"))
LOW_BATTERY_THRESHOLD = float(os.environ.get("LOW_BATTERY_THRESHOLD", "20"))
