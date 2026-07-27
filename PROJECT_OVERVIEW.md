# SwarmHQ — Project Overview

Technical reference for architecture, data model, and the sprint-by-sprint
build plan. For a quick summary and how to run the stack, see
[README.md](README.md) and [HELP.md](HELP.md).

## Goal

A fully simulated drone command-and-control (C2) system, built end-to-end
with free/open-source tooling only. The system must:

1. Simulate a fleet of drones executing missions (patrol, go-to-point, return
   to base).
2. Receive their telemetry (position, battery, status) in real time over a
   real IoT protocol (**MQTT**), not a simplified stand-in.
3. Persist that data in a database with real geospatial capabilities
   (**PostGIS**), supporting queries like "which drones are inside this
   zone" or "how far did this mission travel."
4. Present everything on a live tactical web map ("Google Maps for the
   military"), with automatic alerts (low battery, drone out of zone,
   signal loss).
5. Generate automatic mission reports/statistics: no manual paperwork,
   centralized flight data, visible success/failure rates.

Demonstrable result: *a miniature drone command-and-control system, sharing
the same conceptual architecture as real electronic-warfare/drone systems,
equally applicable to civilian logistics, security, or fleet management.*

**Scope boundary:** this is a software engineering exercise (backend,
geospatial data, real-time systems, coordination algorithms). Nothing related
to real targeting, strike chains, or any capability that could be repurposed
to cause real-world harm is in scope, ever.

## Architecture

```
[Drone simulator] --publishes telemetry--> [Mosquitto (MQTT)]
                                                  |
                                           subscribes
                                                  |
                                       [Spring Boot backend]
                                                  |
                                 persists -->  [PostgreSQL + PostGIS]
                                                  |
                                       pushes via WebSocket (STOMP)
                                                  |
                                       [React + MapLibre GL JS frontend]
```

## Tech stack

| Layer | Technology | Role |
|---|---|---|
| Drone simulator | Python + `paho-mqtt` | Standalone process impersonating N drones moving between waypoints and publishing synthetic telemetry; the only piece standing in for real hardware. Python over Java: it's the ecosystem's de facto choice for lightweight IoT/telemetry simulators, and sets up GPS-noise/Kalman-filter work (a later differentiation layer) with `numpy` instead of hand-rolled math. |
| Message broker | Eclipse Mosquitto (MQTT) | Each simulated drone publishes telemetry to a topic; the backend subscribes. Real IoT/drone protocol, not simplified. |
| Backend | Java + Spring Boot | Subscribes to MQTT, processes telemetry, persists to the database, applies business logic (mission failure, low battery), exposes REST + WebSocket |
| Persistence | Spring Data JPA + Hibernate Spatial | ORM with geometry type support for PostGIS |
| Real-time push | Spring WebSocket (STOMP) | Pushes updates to the map instantly, no polling |
| Database | PostgreSQL + PostGIS | Native geospatial storage and queries: zones, distances, intersections |
| Frontend | React 19 + TypeScript + Vite + Tailwind 4 | Mission list, drone detail, alert panel, KPI dashboard - built and served together with the map, not a separate app |
| Map | MapLibre GL JS | GPU-rendered vector map embedded in a React component; animates moving drone markers smoothly |
| Environment | Docker + Docker Compose | Single-command reproducible local stack |

### Why this stack and not another

- **MQTT** over plain REST: the real protocol used with actual hardware, and
  it demonstrates IoT/telemetry systems knowledge.
- **PostGIS** over plain Postgres: the real standard for any system working
  with positions, zones, and routes — not an arbitrary choice.
- **MapLibre** over Leaflet: better performance with constantly-animating
  markers, and it's modern vector tech used in professional GIS systems.
- **Spring Boot**: solid, already mastered from a previous project (MOLS),
  and no alternative is clearly better suited to this domain to justify the
  learning-curve cost.
- **React + TypeScript + Vite + Tailwind, not Thymeleaf/Bootstrap**: the
  original spec called for Thymeleaf, mirroring MOLS's first version - but
  MOLS itself was later migrated off Thymeleaf to this exact React stack
  because Thymeleaf didn't hold up well in practice for a real dashboard.
  Revisited from scratch for SwarmHQ rather than assumed: a live tactical
  map with WebSocket-driven state and KPI charts is squarely the kind of
  stateful, component-heavy UI React's ecosystem (map wrappers, charting
  libraries, WebSocket hooks) is built for, and it's what recruiters in
  this space actually recognize. Reusing MOLS's validated stack also means
  zero new frontend tooling to debug.
- **OpenFreeMap over MapTiler** for map tiles: both are free, but
  OpenFreeMap needs no account/API key at all, and its built-in `dark`
  style (in the Dark Matter lineage, a de facto standard for dashboard
  basemaps) fits the tactical aesthetic better out of the box than
  MapTiler's tourism/commercial-oriented default styles.

## Data model (baseline)

Implemented as of Sprint 3 (`backend/src/main/java/com/swarmhq/model`),
schema owned by Flyway (`backend/src/main/resources/db/migration`):

- **Drone**: id, external id (stable string used in MQTT topics and by the
  simulator to identify a drone - see "MQTT contract" below), type, position
  (`geometry(Point,4326)`, nullable until the first telemetry arrives),
  battery percent, status (`PATROLLING` / `ON_MISSION` / `RETURNING` /
  `SIGNAL_LOST`), last update timestamp.
- **Mission**: id, route (`geometry(LineString,4326)`), an optional
  many-to-one assigned drone (kept single-drone for MVP simplicity - the
  "assign missions to multiple drones" case is deferred to the mission
  assignment engine / swarm differentiation layers, not solved here),
  status (`PENDING` / `ACTIVE` / `COMPLETED` / `FAILED`), priority (`LOW` /
  `MEDIUM` / `HIGH`), created-at timestamp.
- **Event**: append-only audit log entry - many-to-one to `Drone` (required)
  and `Mission` (optional), type (`LOW_BATTERY` / `WAYPOINT_REACHED` /
  `SIGNAL_LOST` / `SIGNAL_RECOVERED` / `STATUS_CHANGE`), free-text detail,
  occurred-at timestamp. Same audit/movement-log pattern as MOLS.

## MQTT contract

Implemented as of Sprint 4
(`backend/src/main/java/com/swarmhq/mqtt/DroneTelemetryListener.java`):

- **Topic**: `drones/{externalId}/telemetry`, subscribed as the wildcard
  `drones/+/telemetry`. `{externalId}` is the drone's stable string
  identifier (assigned by whatever publishes telemetry - the simulator,
  from Sprint 5 onward) and doubles as the upsert key: a telemetry message
  for an unknown `externalId` creates a new `Drone` row, an existing one
  just gets updated. There's no separate "register a drone" step.
- **Payload** (JSON):
  ```json
  {
    "type": "quadcopter",
    "lat": 40.4168,
    "lon": -3.7038,
    "batteryPercent": 87,
    "status": "PATROLLING",
    "timestamp": "2026-07-27T13:00:00Z"
  }
  ```
  `status` must match a `DroneStatus` enum value. `timestamp` is optional -
  if omitted, the server-received time is used instead.
- **Scope**: this listener only ingests and persists. It does not raise
  alerts, detect signal loss, or apply any business rule - that's Sprint 8
  (and the network-resilience differentiation layer) territory.

## Drone simulator

Implemented as of Sprint 5 (`simulator/`, Python, `paho-mqtt`):

- Each simulated drone (`DRONE_COUNT`, default 4) patrols a fixed square
  waypoint loop, offset diagonally per drone so routes don't overlap
  (`routes.py`). Index 0 of a route is that drone's base.
- Battery drains once per publish tick while patrolling
  (`BATTERY_DRAIN_PER_TICK`); once it hits `LOW_BATTERY_THRESHOLD` the
  drone breaks off its patrol loop, heads straight to base instead
  (`status` becomes `RETURNING`), recharges to 100% on arrival, and resumes
  patrolling - the same patrol/go-to-point/return-to-base behavior the
  original spec calls for, driven entirely by the simulator itself with no
  backend involvement (no `Mission` is created or read here - that's the
  centralized assignment engine, a later differentiation layer).
- Publishes to `drones/{externalId}/telemetry` (`drone-1`, `drone-2`, ...)
  on a fixed interval (`PUBLISH_INTERVAL_SECONDS`, default 2s) matching the
  "MQTT contract" above exactly, so the Sprint 4 listener needed no changes
  to consume it.
- Deliberately out of scope for this sprint: GPS noise/Kalman filtering,
  random signal loss, and swarm/auction coordination between drones - each
  is its own later differentiation layer.

## REST API

Implemented as of Sprint 6:

- `GET /api/drones` - every drone's last known state (thin
  `DroneController` → `DroneService` → `DroneRepository`, matching the
  thin-controller/service-layer convention from MOLS). Returns
  `externalId`, `type`, `lat`/`lon` (`null` until the drone's first
  telemetry arrives), `batteryPercent`, `status`, `lastUpdateAt` - the
  internal database id is never exposed, `externalId` is the public
  identifier throughout.

## Frontend

Implemented as of Sprint 6 (`frontend/`, React 19 + TypeScript + Vite +
Tailwind 4 + `maplibre-gl`):

- Single page for now: a header bar and a full-height `TacticalMap`
  component. `TacticalMap` renders a MapLibre map on OpenFreeMap's `dark`
  style centered on the simulator's patrol area, polls `GET /api/drones`
  every few seconds, and keeps one MapLibre `Marker` per drone in sync
  (added/moved/removed), colored by status (green patrolling, blue on
  mission, amber returning, red signal lost).
- Polling rather than the WebSocket push mentioned in the architecture
  diagram is deliberate for this sprint - "via REST, last known position"
  is exactly what the roadmap calls for here. Sprint 7 replaces/complements
  it with real WebSocket-driven updates.
- **Build/serve integration**: `frontend/` is a self-contained Vite
  project (its own `npm run dev`/`npm run build`, proxying `/api` to
  `localhost:8080` in dev). It is *not* part of the backend's default
  Maven build - `./mvnw test` and `./mvnw spring-boot:run` stay fast and
  Java-only. A dedicated `frontend` Maven profile
  (`./mvnw package -Pfrontend`) installs Node via `frontend-maven-plugin`,
  runs `npm ci && npm run build`, and copies `frontend/dist` into
  `src/main/resources/static/app`, so the bundled jar serves the SPA at
  `/app` - same end result as MOLS, reached deliberately (a profile gate)
  rather than copied wholesale, since backend iteration is far more
  frequent here than producing a bundled jar.
- **`backend/Dockerfile`** (multi-stage: Node builds the frontend, a JDK
  image builds the backend with that frontend copied straight into
  `src/main/resources/static/app`, a slim JRE image runs the result) gives
  the same bundled `/app` outcome as `-Pfrontend`, reached a different way
  because a container build has Node and a JDK in separate stages
  already - no need to invoke `frontend-maven-plugin` inside it. Also the
  only way to run a live backend at all on a machine hit by the
  Tomcat/NIO issue in HELP.md, since it runs on Linux inside the
  container.

## Roadmap

### Core build (MVP, in order)

| # | Sprint | Deliverable |
|---|---|---|
| 1 | ✅ | Local infra: Docker Compose with Mosquitto + PostgreSQL/PostGIS |
| 2 | ✅ | Spring Boot project skeleton (Web, Data JPA, WebSocket, Hibernate Spatial, MQTT client) |
| 3 | ✅ | `Drone` / `Mission` / `Event` entities with PostGIS `Point` geometry |
| 4 | ✅ | MQTT listener persisting incoming telemetry (`drones/+/telemetry`) |
| 5 | ✅ | Basic simulator: 3-5 drones moving between waypoints over MQTT |
| 6 | ✅ | Static tactical map (MapLibre) via REST, last known position |
| 7 |  | Live updates over WebSocket/STOMP |
| 8 |  | Business logic: battery/status alerts, geofenced risk zones |
| 9 |  | KPI dashboard (active missions, success rate, recent alerts, critical battery) |

### Differentiation layers (post-MVP, in priority order)

1. **Security hardening** — MQTT over TLS (self-signed certs) with
   per-drone/unit authentication instead of an open channel. Highest
   priority: in defense contexts, communications security is the central
   concern, not an afterthought, and it's cheap to implement.
2. **Kalman filtering** — the simulator injects realistic GPS noise; the
   backend applies a Kalman filter to smooth trajectories before
   persisting/displaying them. The heaviest algorithmic piece — demonstrates
   sensor fusion, not just "store what arrives."
3. **Constrained mission assignment** — a greedy algorithm assigns missions
   by remaining battery, distance, and priority, instead of moving drones
   along fixed waypoints. Turns the project from "a panel that shows data"
   into "a system that makes decisions."
4. **Network resilience** — the simulator can randomly drop a drone's
   connection; the system marks it "signal lost," retains its last known
   position, and reconnects automatically once it returns. Field systems
   in this domain have to work offline-first; this replicates that
   constraint.
5. **Swarm behavior** — two complementary approaches:
   - **Boids (local coordination):** each simulated drone decides its
     movement from simple rules relative to its neighbors — separation,
     alignment, cohesion (Craig Reynolds' classic model).
   - **Auction-based distributed assignment:** instead of the backend
     centrally assigning missions, simulated drones "bid" on available
     missions based on their own battery/distance, and the lowest-cost
     bidder wins.
   This is what actually justifies the project's name — it should be
   possible to toggle between "centralized mode" (item 3) and "swarm mode"
   (boids/auction) as a demonstrable feature, since comparing both approaches
   is a strong portfolio argument on its own.

## Working conventions

- Work proceeds sprint by sprint, in the order above; later phases are not
  started before earlier ones are closed.
- Design decisions not already fixed here (simulator language, package
  layout, MQTT topic naming, etc.) are confirmed before writing the code for
  that sprint, not assumed silently.
- Controllers stay thin; business logic lives in the service layer (same
  convention as MOLS).
