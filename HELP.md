# HELP

Local setup and troubleshooting notes. For architecture and roadmap, see
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Requirements

- Docker + Docker Compose
- JDK 21+ (the Maven wrapper handles Maven itself, no local Maven install needed)
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
| Mosquitto (MQTT) | `8883` (MQTT+TLS), `9001` (MQTT+TLS over WebSocket) | TLS + per-drone auth since Sprint 11, see "MQTT security" in PROJECT_OVERVIEW.md |
| Backend | `8080` | see "Running the backend in Docker" below - optional, only starts if you ask for it |

A one-shot `mosquitto-setup` service runs first (`depends_on`) and
generates a self-signed CA/server certificate plus the MQTT password file
(`infra/mosquitto/certs/`, `infra/mosquitto/config/passwd` - both
gitignored) before `mosquitto` itself starts. Idempotent - re-running
`docker compose up` never rotates existing certs/secrets, so a `.env`
password change only takes effect after deleting
`infra/mosquitto/config/passwd` (see Troubleshooting if `mosquitto`
crash-loops after changing MQTT credentials).

Stop with:

```bash
docker compose down
```

Add `-v` to also delete the Postgres data volume (destructive, only do
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
(that arrives with the first REST controller in a later sprint).

Schema changes go in a new `db/migration/V{n}__description.sql` file (never
edit an already-applied one) - `Drone`/`Mission`/`Event` and their PostGIS
columns are defined in `V1__init_schema.sql` (Sprint 3), `Drone.external_id`
in `V2__add_drone_external_id.sql` (Sprint 4).

`./mvnw test` runs the full test suite against the real infra from
`docker-compose.yml` (context load, the geometry round-trip, and an
end-to-end MQTT publish-and-persist check) - no Testcontainers yet, so the
stack from the previous section must already be running.

### Publishing a test telemetry message by hand

For one-off testing without starting the full simulator. Since Sprint 11,
this needs TLS + a provisioned identity - the ACL only lets an
authenticated client publish to *its own* `drones/{username}/...` topics
(see "MQTT security" in PROJECT_OVERVIEW.md), so the `-u` username below
must match the topic's drone id exactly. Using `drone-1` here works out of
the box (it's pre-provisioned) but will collide with a real simulator run
also using that identity - fine for a quick one-off check, not while the
simulator is actually running:

```bash
docker exec swarmhq-mosquitto mosquitto_pub -h localhost -p 8883 \
  --cafile /mosquitto/certs/ca.crt -u drone-1 -P changeme \
  -t drones/drone-1/telemetry \
  -m '{"type":"quadcopter","lat":40.4168,"lon":-3.7038,"batteryPercent":90,"status":"PATROLLING"}'
```

Then check it landed: `docker exec swarmhq-postgis psql -U swarmhq -d swarmhq -c "SELECT external_id, battery_percent, status FROM drones;"`

To trigger an alert by hand (Sprint 8) instead of waiting for the
simulator's patrol loop to cross the seeded risk zone: publish a second
message for the same drone with `lat`/`lon` inside `V3__add_risk_zones.sql`'s
"Sector 1 Perimeter Risk Zone" (lon `[-3.7048, -3.7028]`, lat
`[40.4190, 40.4210]` - e.g. `"lat":40.42,"lon":-3.7038`) for
`ENTERED_RISK_ZONE`, or `"batteryPercent":15` for `LOW_BATTERY`. Check
`GET /api/events` or the map's "RECENT ALERTS" panel.

### Creating a mission by hand (Sprint 10)

`V4__seed_demo_missions.sql` already seeds two demo missions near the
simulator's patrol area, so a normal run assigns/flies/completes them
without any manual step. To create another one:

```bash
curl -X POST http://localhost:8080/api/missions \
  -H "Content-Type: application/json" \
  -d '{"route":[[-3.7038,40.4168],[-3.6978,40.4228]],"priority":"HIGH"}'
```

It starts `PENDING`; `MissionAssignmentService`'s scheduled pass (every
5s) picks it up automatically once an eligible drone (`PATROLLING`,
battery above the safety margin) is close enough. Check
`GET /api/missions` for its status, or the map's "RECENT ALERTS" panel
for the `STATUS_CHANGE`/`WAYPOINT_REACHED` events the assignment and
completion raise.

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
small fixed square route, publishing telemetry every 2 seconds - each
drone opens its own MQTT connection, authenticated as its own identity
(Sprint 11), not one shared connection for the whole fleet. Battery
drains while patrolling (or flying an assigned mission); once it drops to
20% the drone heads to base instead of continuing, recharges to 100%, and
resumes. Stop with Ctrl+C (handled gracefully via `SIGINT`).

Connects over TLS by default, trusting the CA `mosquitto-setup` generated
(`../infra/mosquitto/certs/ca.crt`, relative to `simulator/`) - so the
infra must already be up at least once before this will connect (see
"Running the infrastructure"). Tunable via environment variables:
`MQTT_HOST`, `MQTT_PORT` (default `8883`), `MQTT_USE_TLS` (`false` to
disable, not needed normally), `MQTT_CA_CERT_PATH`, `MQTT_DRONE_PASSWORD`,
`DRONE_COUNT`, `PUBLISH_INTERVAL_SECONDS`, `TICKS_PER_SEGMENT`,
`BATTERY_DRAIN_PER_TICK`, `LOW_BATTERY_THRESHOLD`, `GPS_NOISE_STD_METERS`,
`SIGNAL_LOSS_CHANCE_PER_TICK`, `SIGNAL_LOSS_MIN_TICKS`,
`SIGNAL_LOSS_MAX_TICKS` (see `simulator/config.py` for defaults).
`DRONE_COUNT` above `MQTT_MAX_PROVISIONED_DRONES` (see Environment
variables below) will fail to authenticate - provision more identities
first.

### Running in swarm mode (Sprint 14, live-toggleable since Sprint 16)

Centralized by default - a normal run stays in the same "centralized
assignment + fixed waypoint routes" behavior every earlier sprint used.
Switching to swarm mode (boids flocking + auction bidding, both together)
no longer needs any restart or env var - it's a live toggle:

- **From the frontend**: click the mode button in the header (next to the
  KPI bar). It shows the current mode and flips it with one click.
- **From the API**: `curl -X PUT http://localhost:8080/api/mode -H "Content-Type: application/json" -d '{"mode":"auction"}'`
  (or `{"mode":"centralized"}` to switch back). `GET /api/mode` reads the
  current value without changing anything.

Either one flips both halves at once, for every already-running simulator
instance, with nothing to restart: `MissionAssignmentModeHolder`
broadcasts the change on a retained MQTT topic
(`system/mission-assignment-mode`) every drone client is subscribed to, so
patrolling drones start flocking via boids *and* bidding on
`missions/available` from the same signal - see "Live mode toggle" in
PROJECT_OVERVIEW.md for exactly how that wiring works.

The old environment variables still exist, but only as the *initial*
value each side starts with before anything is ever toggled:

- `MISSION_ASSIGNMENT_MODE=auction` in `.env` (backend, before
  `docker compose up -d --build backend`) or
  `swarmhq.mission-assignment.mode=auction` (`./mvnw spring-boot:run
  -Dspring-boot.run.arguments=--swarmhq.mission-assignment.mode=auction`) -
  what the mode holder starts as, not what it's stuck at.
- `SWARM_MODE=true` before `main.py` (simulator) - the fallback used for
  the handful of ticks before the retained MQTT message actually arrives;
  once it does, the backend's live value wins regardless of what this was
  set to.

Boids tuning is unaffected by any of this - still
`BOIDS_NEIGHBOR_RADIUS_DEGREES`, `BOIDS_SEPARATION_WEIGHT`,
`BOIDS_ALIGNMENT_WEIGHT`, `BOIDS_COHESION_WEIGHT`, `BOIDS_CENTER_WEIGHT`,
`BOIDS_MAX_STEP_DEGREES` (see `simulator/config.py` for defaults/rationale).

## Running the frontend

For day-to-day frontend work, from `frontend/`:

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173` with hot reload; its dev server proxies
both `/api/*` and `/ws/*` (STOMP live updates, Sprint 7) to
`http://localhost:8080` (see `vite.config.ts`), so it expects the backend
to already be running there. `npm run build` produces a production bundle
in `frontend/dist/` on its own, without touching the backend.

To produce a single Spring Boot jar with the built frontend baked in
(`static/app`, served at `/app`), from `backend/`:

```bash
./mvnw package -Pfrontend
```

This is a separate Maven profile, not part of the default build, on
purpose: plain `./mvnw test` / `./mvnw spring-boot:run` stay fast and
don't require Node at all. Only reach for `-Pfrontend` when you actually
need the bundled artifact.

## Signing in (Keycloak)

The app requires a login as of the "Hardening & parity layer" in
PROJECT_OVERVIEW.md. `docker compose up -d` also starts Keycloak
(`http://localhost:8081`) and auto-imports a `swarmhq` realm from
`infra/keycloak/import/swarmhq-realm.json` with two demo users:

| Username | Password | Role |
|---|---|---|
| `operator1` | `operator1` | `OPERATOR` - can dispatch/cancel/assign missions, declare zones |
| `observer1` | `observer1` | `OBSERVER` - read-only |

Keycloak's own admin console is `http://localhost:8081` (`admin`/`admin`
by default, `KEYCLOAK_ADMIN_PASSWORD` in `.env` to change it) - only
needed to inspect or edit the realm directly, not for day-to-day use.

## Environment variables

Copy `.env.example` to `.env` before first run; `.env` is gitignored so each
environment (your machine, CI, etc.) keeps its own values.

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_DB` | `swarmhq` | database name |
| `POSTGRES_USER` | `swarmhq` | database user |
| `POSTGRES_PASSWORD` | `changeme` | database password, change it even locally |
| `POSTGRES_PORT` | `5433` | host port mapped to Postgres |
| `MQTT_PORT` | `8883` | host port mapped to Mosquitto MQTT+TLS |
| `MQTT_WS_PORT` | `9001` | host port mapped to Mosquitto MQTT+TLS-over-WebSocket |
| `MQTT_BACKEND_PASSWORD` | `changeme` | the backend's own MQTT identity's password, change it even locally |
| `MQTT_DRONE_PASSWORD` | `changeme` | shared by every simulated drone identity (`drone-1`, `drone-2`, ...), see "MQTT security" in PROJECT_OVERVIEW.md for why one shared password is an acceptable simplification here |
| `MQTT_MAX_PROVISIONED_DRONES` | `20` | how many `drone-N` identities `mosquitto-setup` pre-provisions, raise this (and re-provision, see "Running the infrastructure") if running with `DRONE_COUNT` above 20 |
| `MISSION_ASSIGNMENT_MODE` | `centralized` | the mode holder's *initial* value only (Sprint 16): `centralized` (Sprint 10's engine) or `auction` (drones bid, lowest cost wins). Switch it live afterward via the frontend toggle or `PUT /api/mode` instead of restarting, see "Running in swarm mode" below |

## Known limitations (tracked in the roadmap, not bugs)

- **`hibernate-spatial` pinned to `7.0.2.Final`** against a `hibernate-core`
  managed at `7.4.1.Final` by the Spring Boot 4.1 parent (spatial-specific
  releases tend to lag behind core). Confirmed working end-to-end as of
  Sprint 3: `Drone.position` (a `geometry(Point,4326)` column) round-trips
  correctly through JTS/Hibernate Spatial/PostGIS (see
  `DroneRepositoryTests`). Still worth re-checking after any future
  `hibernate-core` version bump.
- No MQTT listener, REST controllers, or UI yet (Sprint 4+): Sprint 3 only
  adds the `Drone`/`Mission`/`Event` entities and their Flyway-managed
  schema.

## Troubleshooting

- **Port already in use** (5433 / 8883 / 9001): another local service (e.g.
  a native PostgreSQL or Mosquitto install) is likely bound to the same
  port. Either stop it or override the port via the corresponding `.env`
  variable. This is why Postgres defaults to host port `5433` rather than
  the standard `5432`: a machine with a native PostgreSQL install already
  listening on `5432` will silently swallow connections meant for the
  Docker container (the client authenticates against the *native* Postgres
  instead, and fails with a password/auth error that has nothing to do with
  the credentials in `.env`). Docker itself starts fine either way, so
  `docker compose ps` showing "healthy" doesn't rule this out.
- **`./mvnw spring-boot:run` fails with `java.io.IOException: Unable to
  establish loopback connection` / `WEPollSelectorImpl` / `Invalid argument:
  connect`**: this is a local JDK-on-Windows issue, not a SwarmHQ bug. A
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
  own `org.springframework.boot:spring-boot-flyway` module, having
  `flyway-core` (and `flyway-database-postgresql`) on the classpath isn't
  enough by itself, Flyway silently never runs. `pom.xml` already declares
  `spring-boot-flyway` for this reason; if you hit this after adding a new
  dependency elsewhere, check it didn't get excluded.
- **`MqttClient.subscribe(String, int, IMqttMessageListener)` throws a
  `StackOverflowError`**: this is a real bug in Eclipse Paho's mqttv5 client
  `1.2.5`. That overload (and the `String[]/int[]/IMqttMessageListener[]`
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
- **Map renders (canvas, controls, markers, attribution all present) but
  shows only the dark background color, no actual roads/land**: Vite
  doesn't detect/bundle `maplibre-gl`'s internal web worker (used to
  fetch/decode vector tiles) as a separate asset, so `TacticalMap.tsx`
  points MapLibre at a copy served as a plain static file via
  `setWorkerUrl()`. That copy alone isn't enough, though:
  `maplibre-gl-worker.mjs` itself statically imports a sibling chunk,
  `maplibre-gl-shared.mjs` - copying only the worker file makes that
  import 404 *inside the worker's module graph*, which fails silently
  (no console error on the main thread; the worker still constructs
  without throwing, it just never ends up handling tile-load messages).
  Fixed by `frontend/scripts/copy-maplibre-worker.mjs`, which copies both
  files from `node_modules/maplibre-gl/dist/` into `frontend/public/`
  before every `dev`/`build` (wired in via `predev`/`prebuild` in
  `package.json`) - generated vendor files like these are copied at
  build time, not hand-committed, so they can't silently drift from the
  installed `maplibre-gl` version. Confirmed fixed end-to-end: real
  `.pbf` tile requests fire and the map renders actual terrain/roads
  after a full `docker compose up -d --build backend` + browser check.
  - Diagnostic pitfall hit while chasing this: `performance.getEntriesByType`
    only reports the worker's *static* `import` (resolved before the
    worker's realm starts), not `fetch()` calls made from code running
    inside it - so don't conclude "no tile requests" from the main
    thread's resource timing alone. And separately: MapLibre's render
    loop is driven by `requestAnimationFrame`, which doesn't run at all
    while a browser-automation pane is off-screen/not compositing - a
    map can look permanently "stuck" (no tile requests, no `idle` event)
    in that state for reasons that have nothing to do with the app.
    Always confirm a stuck-map symptom with the pane actually visible
    (or a manually-opened browser tab) before trusting it as a real bug.
- **The map/app loads at `/app` but the JS/CSS 404 at `/assets/...` (not
  `/app/assets/...`)**: Vite defaults to root-relative asset paths, which
  is wrong once the SPA is actually served from a subpath. Fixed by
  setting `base: '/app/'` in `vite.config.ts` - re-check this if the
  serving path ever changes.
- **Blank page in the browser - `#root` is empty, no visible error, but the
  page's own `<script>` tag returned 200 and the console shows nothing
  wrong**: this is what a top-level `ReferenceError` inside a bundled ES
  module looks like from the outside - the whole module (including the
  `ReactDOM.createRoot(...).render(...)` call in `main.tsx`) aborts before
  it can mount anything, and depending on how you're inspecting the page
  the actual error can be easy to miss since it's an *uncaught module
  evaluation* error, not one raised from application code. Hit this after
  adding `sockjs-client` (Sprint 7's live-updates dependency): it
  references the Node `global` object, which doesn't exist in a browser -
  fixed by `define: { global: 'globalThis' }` in `vite.config.ts` (the
  standard Vite fix for this exact library). If a similarly silent blank
  page shows up again, don't trust "no console error" - confirm by
  fetching the built bundle and dynamically `import()`-ing it a second
  time in the browser console; a duplicate import re-throws the same
  top-level error the original `<script type="module">` tag swallowed
  from outside view.
- **A test that publishes/consumes MQTT (or the Docker backend) behaves
  as if messages never arrive, with no error anywhere**: check whether
  two instances of the app are running at once (e.g. a local
  `./mvnw spring-boot:run`/test JVM *and* the Docker container) - MQTT
  brokers allow only one active connection per client id, so two
  instances sharing one silently kick each other's subscription off with
  no visible error. `MqttConfig` appends a random suffix to the
  configured client id specifically to prevent this; if it resurfaces,
  something is reusing a fixed id somewhere.
- **A native `@Query` fails with `InvalidDataAccessApiUsage: No parameter
  named ':foo'` even though `:foo` is clearly declared**: check for a `::`
  Postgres cast immediately after the parameter, e.g. `:foo::geography` -
  Spring Data's named-parameter parser swallows the cast as part of the
  parameter name. Use `CAST(:foo AS geography)` instead (see
  `DroneRepository.findBestForMission`, Sprint 10).
- **Running `docker compose` from a git worktree and containers seem to
  vanish, or `docker compose up` tries to recreate `postgis`/`mosquitto`
  and fails with "Conflict: container name already in use"**: Compose's
  default project name is the current directory's basename, so a
  worktree checked out under a differently-named directory (e.g.
  `.claude/worktrees/some-branch/`) gets its *own* project - but this
  repo's `container_name`s are fixed strings (`swarmhq-postgis`, etc.),
  which are globally unique in Docker regardless of project, so a second
  project can't also create them. `docker compose ps` only shows
  containers for the *current directory's* project name, even though
  `docker ps` shows every container - if that's empty despite containers
  clearly running, they belong to a different project. Fix: pass
  `-p <project-name>` explicitly (check the running containers' actual
  project with `docker inspect <container> --format
  '{{index .Config.Labels "com.docker.compose.project"}}'`) to target the
  existing one, rather than letting a new directory silently start a
  second, colliding project.
- **`docker compose up` fails pulling images**: confirm Docker Desktop /
  the Docker daemon is running.
- **Postgres container unhealthy**: check `docker compose logs postgis`,
  usually a credential mismatch between an existing volume and a changed
  `.env`. Recreate the volume with `docker compose down -v` if the data in
  it is disposable.
- **`mosquitto` crash-loops (`docker compose ps` shows `Restarting`) right
  after changing MQTT credentials or on first setup, logging
  `password-file: Error: Unable to open pwfile "/mosquitto/config/passwd"`**:
  `mosquitto_passwd` creates that file mode `0600` (owner-only), but the
  broker runs as the unprivileged `mosquitto` user inside the container -
  not whatever ran `mosquitto-setup` (root) - so it can't read its own
  password file back. `generate.sh` already `chmod 644`s it after
  generating; if you hit this anyway (e.g. after manually deleting/editing
  `infra/mosquitto/config/passwd`), re-run
  `docker compose up -d mosquitto-setup` then `docker compose restart mosquitto`.
- **Backend/simulator/tests fail to connect to MQTT with
  `SSLHandshakeException: No name matching <host> found`**: the server
  certificate's SAN doesn't cover whatever hostname that client used to
  connect (`mosquitto` from inside Docker vs. `localhost` from the host
  side - see "MQTT security" in PROJECT_OVERVIEW.md). `generate.sh`
  already requests SAN entries for both plus `127.0.0.1`; this only
  happens if certs were generated by an older version of that script
  before the SAN was added - delete `infra/mosquitto/certs/*` and
  `docker compose up -d mosquitto-setup` to regenerate.
- **An MQTT-consuming listener (`DroneTelemetryListener`,
  `MissionStatusListener`) seems to process the exact same message
  twice**: this is legitimate QoS1 ("at least once") redelivery, not a
  bug in the publish path - see the "MQTT contract" note in
  PROJECT_OVERVIEW.md on why listeners need to be idempotent, not just
  correct for a single delivery.
- **`MissionStatusListenerTests.marksMissionFailedAndRaisesMissionFailedEvent`
  (or its `COMPLETED` sibling) intermittently fails with "expected 1 event
  but was 2", reproducing even with just that one test class run alone**:
  found and fixed during Sprint 14's own verification pass - this was a
  genuine race in `MissionStatusListener`'s old redelivery guard, not test
  pollution. The guard used to read `mission.getStatus()` and then write
  the new status as two separate steps; two genuinely concurrent QoS1
  redeliveries could both read `ACTIVE` before either write committed, so
  both proceeded and both raised an `Event`. Fixed by
  `MissionRepository.updateStatusIfActive` - a single atomic
  `UPDATE ... WHERE status = 'ACTIVE'` - so only one caller ever gets
  `rowsAffected == 1` for the same mission, no matter how the deliveries
  interleave. If a similar "expected N, got N+1" symptom shows up on a
  future listener with its own "already handled, ignore the repeat"
  guard, check whether that guard is a read-then-write (racy) or a single
  atomic conditional update (safe) before assuming it's cross-test
  pollution (the previous two entries) - a targeted single-class rerun
  ruling out other test classes, exactly like this one, is how to tell
  the two apart.
- **A new MQTT-backed test fails with a Postgres FK violation on cleanup
  (`events_drone_id_fkey`), a duplicate-key violation on
  `uq_drones_external_id`, or - most confusingly - makes an unrelated,
  already-passing test fail intermittently with something like "expected 1
  event but was 2"**: another test class is already using the same drone
  identity (`test-drone-1`, `test-drone-4`, etc.). Every provisioned test
  identity is claimed by exactly one test class - check
  `infra/mosquitto/setup/generate.sh` for the full list before reusing one,
  and add a new `mosquitto_passwd -b` line there (plus regenerate the
  password file: delete `infra/mosquitto/config/passwd`, run
  `docker compose up -d mosquitto-setup`, `docker compose restart
  mosquitto`) if you need a new identity rather than reusing someone else's.
  Isolate a suspected collision by running the affected test class alone
  (passes) vs. the full suite (fails) to confirm it's cross-test pollution
  and not a real bug in the test itself.
- **A `@SpringBootTest` class using its own distinct `@TestPropertySource`
  (a different `swarmhq.mission-assignment.mode`, a different feature flag,
  etc.) makes an unrelated test elsewhere in the suite fail intermittently,
  even after ruling out identity collisions (previous entry)**: Spring Test
  caches `ApplicationContext`s by their exact configuration, so a distinct
  `@TestPropertySource` spins up a *second*, simultaneously-alive context -
  with its own live MQTT connection and its own instance of any
  unconditional `@Component`/listener bean - that is never closed unless
  told to be. That second context's listener can keep consuming messages
  meant for a later, unrelated test's context for the rest of the suite
  run. Fix: add `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)`
  to any test class that introduces a new, distinct `@TestPropertySource`
  configuration (see `AuctionCoordinatorServiceTests`/
  `MissionBidListenerTests`, Sprint 14) so its context is torn down once
  that class finishes rather than lingering for the rest of the run.
