#!/bin/sh
# Provisions everything MQTT TLS + per-drone auth needs before
# mosquitto starts - a self-signed CA/server certificate and the
# password file (backend + a fixed range of pre-provisioned drone
# identities, since this is a demo fleet of a known rough size, not an
# open enrollment system). Idempotent: re-running `docker compose up`
# never rotates existing secrets/certs, it only fills in what's missing.
set -eu

CERTS_DIR="/mosquitto/certs"
CONFIG_DIR="/mosquitto/config"

if ! command -v openssl >/dev/null 2>&1; then
  apk add --no-cache openssl >/dev/null
fi

if [ ! -f "$CERTS_DIR/server.crt" ]; then
  echo "Generating self-signed CA + server certificate..."
  openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
    -keyout "$CERTS_DIR/ca.key" -out "$CERTS_DIR/ca.crt" \
    -subj "/CN=SwarmHQ Dev CA" >/dev/null 2>&1
  # SAN covers both names clients actually connect with: "mosquitto"
  # (container-to-container, e.g. the backend inside docker-compose) and
  # "localhost"/127.0.0.1 (host-side - a locally-run backend/simulator/test
  # suite, all via the host-mapped port). A CN alone isn't enough - modern
  # TLS clients (including Paho's default hostname verification) ignore
  # CN and only check the SAN list.
  openssl req -newkey rsa:2048 -nodes \
    -keyout "$CERTS_DIR/server.key" -out "$CERTS_DIR/server.csr" \
    -subj "/CN=mosquitto" \
    -addext "subjectAltName=DNS:mosquitto,DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
  openssl x509 -req -in "$CERTS_DIR/server.csr" \
    -CA "$CERTS_DIR/ca.crt" -CAkey "$CERTS_DIR/ca.key" -CAcreateserial \
    -out "$CERTS_DIR/server.crt" -days 3650 \
    -copy_extensions copy >/dev/null 2>&1
  rm -f "$CERTS_DIR/server.csr" "$CERTS_DIR/ca.srl"
  chmod 644 "$CERTS_DIR"/*.crt "$CERTS_DIR"/*.key
else
  echo "Certificates already present, skipping generation."
fi

: "${MQTT_BACKEND_PASSWORD:?MQTT_BACKEND_PASSWORD must be set (see .env)}"
: "${MQTT_DRONE_PASSWORD:?MQTT_DRONE_PASSWORD must be set (see .env)}"
: "${MQTT_MAX_PROVISIONED_DRONES:=20}"

if [ ! -f "$CONFIG_DIR/passwd" ]; then
  echo "Generating MQTT password file ($MQTT_MAX_PROVISIONED_DRONES drone identities pre-provisioned)..."
  mosquitto_passwd -b -c "$CONFIG_DIR/passwd" swarmhq-backend "$MQTT_BACKEND_PASSWORD"
  i=1
  while [ "$i" -le "$MQTT_MAX_PROVISIONED_DRONES" ]; do
    mosquitto_passwd -b "$CONFIG_DIR/passwd" "drone-$i" "$MQTT_DRONE_PASSWORD"
    i=$((i + 1))
  done
  # Dedicated identities for backend tests (DroneTelemetryListenerTests /
  # MissionStatusListenerTests / MissionBidListenerTests) that
  # publish MQTT messages directly - kept separate from drone-1..N so a
  # real simulator run's data can never collide with a test run's, and
  # each test class gets its own so *test* runs can't collide with each
  # other either (test-drone-1 already existing, unnoticed, is exactly
  # what made MissionBidListenerTests corrupt DroneTelemetryListenerTests'
  # data the first time this was written).
  mosquitto_passwd -b "$CONFIG_DIR/passwd" test-drone-1 "$MQTT_DRONE_PASSWORD"
  mosquitto_passwd -b "$CONFIG_DIR/passwd" test-drone-2 "$MQTT_DRONE_PASSWORD"
  mosquitto_passwd -b "$CONFIG_DIR/passwd" test-drone-3 "$MQTT_DRONE_PASSWORD"
  mosquitto_passwd -b "$CONFIG_DIR/passwd" test-drone-4 "$MQTT_DRONE_PASSWORD"
  mosquitto_passwd -b "$CONFIG_DIR/passwd" test-drone-5 "$MQTT_DRONE_PASSWORD"
  # mosquitto_passwd creates the file mode 0600 (owner-only); the broker
  # itself runs as the unprivileged "mosquitto" user, not whatever ran
  # this setup script (root, in the mosquitto-setup container), so it
  # can't read that file back without this.
  chmod 644 "$CONFIG_DIR/passwd"
else
  echo "Password file already present, skipping generation."
fi

echo "Mosquitto TLS/auth setup complete."
