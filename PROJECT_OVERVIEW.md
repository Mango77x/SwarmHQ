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
                                    [MapLibre GL JS tactical map]
                                    [Thymeleaf/Bootstrap admin panels]
```

## Tech stack

| Layer | Technology | Role |
|---|---|---|
| Drone simulator | Java or Python (TBD) | Standalone process impersonating N drones moving between waypoints and publishing synthetic telemetry; the only piece standing in for real hardware |
| Message broker | Eclipse Mosquitto (MQTT) | Each simulated drone publishes telemetry to a topic; the backend subscribes. Real IoT/drone protocol, not simplified. |
| Backend | Java + Spring Boot | Subscribes to MQTT, processes telemetry, persists to the database, applies business logic (mission failure, low battery), exposes REST + WebSocket |
| Persistence | Spring Data JPA + Hibernate Spatial | ORM with geometry type support for PostGIS |
| Real-time push | Spring WebSocket (STOMP) | Pushes updates to the map instantly, no polling |
| Database | PostgreSQL + PostGIS | Native geospatial storage and queries: zones, distances, intersections |
| Map frontend | MapLibre GL JS | GPU-rendered vector map in the browser; animates moving drone markers smoothly |
| Admin frontend | Thymeleaf + Bootstrap 5.3 | Mission list, drone detail, alert panel, KPI dashboard |
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

## Roadmap

### Core build (MVP, in order)

| # | Sprint | Deliverable |
|---|---|---|
| 1 | ✅ | Local infra: Docker Compose with Mosquitto + PostgreSQL/PostGIS |
| 2 | ✅ | Spring Boot project skeleton (Web, Data JPA, WebSocket, Hibernate Spatial, MQTT client) |
| 3 | ✅ | `Drone` / `Mission` / `Event` entities with PostGIS `Point` geometry |
| 4 | ✅ | MQTT listener persisting incoming telemetry (`drones/+/telemetry`) |
| 5 |  | Basic simulator: 3-5 drones moving between waypoints over MQTT |
| 6 |  | Static tactical map (MapLibre) via REST, last known position |
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
