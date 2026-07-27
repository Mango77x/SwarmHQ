# HELP

Local setup and troubleshooting notes. For architecture and roadmap, see
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Requirements

- Docker + Docker Compose
- JDK 21+ (the Maven wrapper handles Maven itself — no local Maven install needed)
- Python 3.11+ (for the simulator)
- Node 20+ (for the frontend; not required for `./mvnw package -Pfrontend`,
  which downloads its own pinned Node version automatically)

## Running the infrastructure

```bash
cp .env.example .env
docker compose up -d
```

This starts:

| Service | Port(s) | Notes |
|---|---|---|
| PostgreSQL/PostGIS | `5433` (host, from `.env`) | credentials in `.env`, gitignored; non-default port, see Troubleshooting |
| Mosquitto (MQTT) | `1883` (MQTT), `9001` (MQTT over WebSocket) | anonymous access enabled for now, see below |
| Backend | `8080` | see "Running the backend in Docker" below - optional, only starts if you ask for it |

Stop with:

```bash
docker compose down
```

Add `-v` to also delete the Postgres data volume (destructive — only do
this if you want a clean database).

## Running the backend in Docker

`docker compose up -d` above does **not** start the `backend` service by
default unless you name it explicitly:

```bash
docker compose up -d --build backend
```

This builds `backend/Dockerfile` (multi-stage: Node builds the frontend,
a JDK image builds the backend with that frontend baked into
`static/app`, then a slim JRE image runs the jar) and serves the whole
app - map included - at `http://localhost:8080/app`.

**This is also the recommended way to run the backend at all on a machine
hit by the Tomcat/NIO bug below**: the container runs Linux, where
`WEPollSelectorImpl` (a Windows-only JDK class) doesn't exist, so the bug
simply can't occur - no workaround needed, no `web-application-type=none`
trick required. If `./mvnw spring-boot:run` fails on your machine, reach
for this instead.

Rebuild after backend or frontend changes with
`docker compose build backend` (or add `--build` to `up` as above).

Each instance of the app - a local `./mvnw spring-boot:run`, this Docker
container, another local run - gets its own randomized MQTT client id
suffix (`MqttConfig`) specifically so they can all connect to the same
broker at once without kicking each other off. Don't run two `docker
compose up backend` against the *same* Postgres and expect clean
concurrent writes, though - that's a different problem this doesn't solve.

## Running the backend

With the infrastructure up (previous section), from `backend/`:

```bash
./mvnw spring-boot:run
```

It connects to Postgres/PostGIS (Flyway applies `src/main/resources/db/migration`
on startup, Hibernate only validates against it), connects to Mosquitto as
an MQTT client and subscribes to `drones/+/telemetry` (see
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md), "MQTT contract"), and starts a
WebSocket/STOMP broker at `/ws` (no destinations published yet). There's no
HTTP endpoint of its own beyond Spring Boot Actuator at `/actuator/health`
— that arrives with the first REST controller in a later sprint.

Schema changes go in a new `db/migration/V{n}__description.sql` file (never
edit an already-applied one) - `Drone`/`Mission`/`Event` and their PostGIS
columns are defined in `V1__init_schema.sql` (Sprint 3), `Drone.external_id`
in `V2__add_drone_external_id.sql` (Sprint 4).

`./mvnw test` runs the full test suite against the real infra from
`docker-compose.yml` (context load, the geometry round-trip, and an
end-to-end MQTT publish-and-persist check) - no Testcontainers yet, so the
stack from the previous section must already be running.

### Publishing a test telemetry message by hand

For one-off testing without starting the full simulator:

```bash
docker exec swarmhq-mosquitto mosquitto_pub -h localhost \
  -t drones/manual-test-1/telemetry \
  -m '{"type":"quadcopter","lat":40.4168,"lon":-3.7038,"batteryPercent":90,"status":"PATROLLING"}'
```

Then check it landed: `docker exec swarmhq-postgis psql -U swarmhq -d swarmhq -c "SELECT external_id, battery_percent, status FROM drones;"`

## Running the simulator

With the infrastructure up (and, if you want persisted data, the backend
running too), from `simulator/`:

```bash
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt   # Windows
# .venv/bin/python -m pip install -r requirements.txt           # macOS/Linux
./.venv/Scripts/python.exe main.py
```

By default it simulates 4 drones (`drone-1`..`drone-4`), each patrolling a
small fixed square route, publishing telemetry every 2 seconds. Battery
drains while patrolling; once it drops to 20% the drone heads to base
instead of continuing its loop, recharges to 100%, and resumes. Stop with
Ctrl+C (handled gracefully via `SIGINT`).

Tunable via environment variables: `MQTT_HOST`, `MQTT_PORT`,
`DRONE_COUNT`, `PUBLISH_INTERVAL_SECONDS`, `TICKS_PER_SEGMENT`,
`BATTERY_DRAIN_PER_TICK`, `LOW_BATTERY_THRESHOLD` (see `simulator/config.py`
for defaults).

## Running the frontend

For day-to-day frontend work, from `frontend/`:

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173` with hot reload; its dev server proxies
`/api/*` to `http://localhost:8080` (see `vite.config.ts`), so it expects
the backend to already be running there. `npm run build` produces a
production bundle in `frontend/dist/` on its own, without touching the
backend.

To produce a single Spring Boot jar with the built frontend baked in
(`static/app`, served at `/app`), from `backend/`:

```bash
./mvnw package -Pfrontend
```

This is a separate Maven profile, not part of the default build, on
purpose: plain `./mvnw test` / `./mvnw spring-boot:run` stay fast and
don't require Node at all. Only reach for `-Pfrontend` when you actually
need the bundled artifact.

## Environment variables

Copy `.env.example` to `.env` before first run; `.env` is gitignored so each
environment (your machine, CI, etc.) keeps its own values.

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_DB` | `swarmhq` | database name |
| `POSTGRES_USER` | `swarmhq` | database user |
| `POSTGRES_PASSWORD` | `changeme` | database password — change it, even locally |
| `POSTGRES_PORT` | `5433` | host port mapped to Postgres |
| `MQTT_PORT` | `1883` | host port mapped to Mosquitto MQTT |
| `MQTT_WS_PORT` | `9001` | host port mapped to Mosquitto MQTT-over-WebSocket |

## Known limitations (tracked in the roadmap, not bugs)

- **Mosquitto allows anonymous connections.** `infra/mosquitto/config/mosquitto.conf`
  has `allow_anonymous true` — intentional for this early sprint, replaced by
  TLS + per-client authentication in a later sprint (see
  [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md), "Security hardening").
- **`hibernate-spatial` pinned to `7.0.2.Final`** against a `hibernate-core`
  managed at `7.4.1.Final` by the Spring Boot 4.1 parent — spatial-specific
  releases tend to lag behind core. Confirmed working end-to-end as of
  Sprint 3: `Drone.position` (a `geometry(Point,4326)` column) round-trips
  correctly through JTS/Hibernate Spatial/PostGIS (see
  `DroneRepositoryTests`). Still worth re-checking after any future
  `hibernate-core` version bump.
- No MQTT listener, REST controllers, or UI yet (Sprint 4+) — Sprint 3 only
  adds the `Drone`/`Mission`/`Event` entities and their Flyway-managed
  schema.

## Troubleshooting

- **Port already in use** (5433 / 1883 / 9001): another local service (e.g.
  a native PostgreSQL or Mosquitto install) is likely bound to the same
  port. Either stop it or override the port via the corresponding `.env`
  variable. This is why Postgres defaults to host port `5433` rather than
  the standard `5432`: a machine with a native PostgreSQL install already
  listening on `5432` will silently swallow connections meant for the
  Docker container (the client authenticates against the *native* Postgres
  instead, and fails with a password/auth error that has nothing to do with
  the credentials in `.env`) — Docker itself starts fine either way, so
  `docker compose ps` showing "healthy" doesn't rule this out.
- **`./mvnw spring-boot:run` fails with `java.io.IOException: Unable to
  establish loopback connection` / `WEPollSelectorImpl` / `Invalid argument:
  connect`**: this is a local JDK-on-Windows issue, not a SwarmHQ bug — a
  bare `Selector.open()` with no Spring involved fails the same way on an
  affected machine. It shows up when something on the host (commonly
  endpoint-security/AV network-filter software) interferes with the
  loopback socket pair the JDK's Windows NIO selector sets up internally,
  and it isn't specific to the default selector provider either (forcing
  the legacy `WindowsSelectorProvider` fails identically - both eventually
  hit the same broken `Pipe`/Unix-domain-socket bootstrap).
  **Fix: run the backend via Docker instead** (see "Running the backend
  in Docker" above) - a Linux container doesn't have this class at all, so
  the bug can't occur there, no workaround needed. `./mvnw test` also
  works despite the bug, since the default `@SpringBootTest` web
  environment is mocked and never binds a real socket - so day-to-day
  backend test iteration is unaffected either way.
- **Hibernate fails at startup with `SchemaManagementException: Schema
  validation: missing table [...]`, and nothing in the log mentions
  Flyway at all**: Spring Boot 4 split Flyway autoconfiguration into its
  own `org.springframework.boot:spring-boot-flyway` module — having
  `flyway-core` (and `flyway-database-postgresql`) on the classpath isn't
  enough by itself, Flyway silently never runs. `pom.xml` already declares
  `spring-boot-flyway` for this reason; if you hit this after adding a new
  dependency elsewhere, check it didn't get excluded.
- **`MqttClient.subscribe(String, int, IMqttMessageListener)` throws a
  `StackOverflowError`**: this is a real bug in Eclipse Paho's mqttv5 client
  `1.2.5` — that overload (and the `String[]/int[]/IMqttMessageListener[]`
  one it delegates to) call themselves instead of the next overload down,
  confirmed by disassembling the jar. `DroneTelemetryListener.subscribe()`
  uses the `MqttSubscription[]/IMqttMessageListener[]` overload instead,
  which is implemented correctly. Re-check this if the Paho version ever
  changes.
- **A `Awaitility.await().untilAsserted(...)` block fails immediately
  instead of retrying for the full timeout**: only `AssertionError` (what
  JUnit/AssertJ assertions throw) gets retried - a plain exception like
  `Optional.orElseThrow()`'s `NoSuchElementException` propagates on the
  first failing poll instead. Use `assertTrue(optional.isPresent())` before
  unwrapping, not `.orElseThrow()`, inside an `untilAsserted` block (see
  `DroneTelemetryListenerTests` - this exact bug made that test flaky until
  fixed).
- **`npm run dev` logs `The file does not exist at
  ".../node_modules/.vite/deps/maplibre-gl-worker.mjs"`**: a known
  Vite dependency-pre-bundling quirk with `maplibre-gl` (it ships a web
  worker entry point Vite's optimizer doesn't handle perfectly). Harmless
  in practice - the map still initializes and renders correctly despite
  the warning.
- **The map/app loads at `/app` but the JS/CSS 404 at `/assets/...` (not
  `/app/assets/...`)**: Vite defaults to root-relative asset paths, which
  is wrong once the SPA is actually served from a subpath. Fixed by
  setting `base: '/app/'` in `vite.config.ts` - re-check this if the
  serving path ever changes.
- **A test that publishes/consumes MQTT (or the Docker backend) behaves
  as if messages never arrive, with no error anywhere**: check whether
  two instances of the app are running at once (e.g. a local
  `./mvnw spring-boot:run`/test JVM *and* the Docker container) - MQTT
  brokers allow only one active connection per client id, so two
  instances sharing one silently kick each other's subscription off with
  no visible error. `MqttConfig` appends a random suffix to the
  configured client id specifically to prevent this; if it resurfaces,
  something is reusing a fixed id somewhere.
- **`docker compose up` fails pulling images**: confirm Docker Desktop /
  the Docker daemon is running.
- **Postgres container unhealthy**: check `docker compose logs postgis` —
  usually a credential mismatch between an existing volume and a changed
  `.env`. Recreate the volume with `docker compose down -v` if the data in
  it is disposable.
