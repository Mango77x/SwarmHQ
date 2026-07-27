# SwarmHQ

A fully simulated drone command-and-control (C2) system, conceptually inspired
by real battlefield tools used in Ukraine (DELTA, Kropyva, Mission Control),
built as a software engineering portfolio project.

**No real hardware. No real data. No targeting or weapons capability of any
kind.** Every drone, mission, and telemetry stream in this system is
synthetic. The project exists to demonstrate distributed systems, IoT
messaging, geospatial data, real-time web, and multi-agent coordination
engineering — not to build anything that could cause real-world harm.

## What it does

- Simulates a fleet of drones executing missions (patrol, go-to-point, return
  to base).
- Streams telemetry (position, battery, status) over **MQTT**, the same
  publish/subscribe protocol used by real IoT and drone hardware — not a
  simplified REST polling stand-in.
- Persists telemetry in **PostgreSQL + PostGIS**, so spatial queries ("which
  drones are inside this zone", "how far did this mission travel") are native
  database operations, not hand-rolled geometry in application code.
- Renders a live tactical map (MapLibre GL JS) with automatic alerting
  (low battery, geofence violation, signal loss).
- Produces mission statistics/reports automatically (active missions, success
  rate, failures).
- (Planned) Exhibits genuine **swarm behavior** between simulated drones —
  boids-style local coordination and/or auction-based distributed task
  allocation — alternable against a centralized assignment engine, which is
  what the project's name refers to.

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
| Drone simulator | Java or Python (TBD, see roadmap) | Standalone process impersonating N drones; the only piece standing in for real hardware |
| Message broker | Eclipse Mosquitto (MQTT) | Drones publish telemetry to a topic; backend subscribes |
| Backend | Java + Spring Boot | Subscribes to MQTT, applies business logic, exposes REST + WebSocket |
| Persistence | Spring Data JPA + Hibernate Spatial | ORM with PostGIS geometry support |
| Real-time push | Spring WebSocket (STOMP) | Live map updates without polling |
| Database | PostgreSQL + PostGIS | Geospatial storage: zones, distances, intersections |
| Map frontend | MapLibre GL JS | GPU-rendered vector map, animated drone markers |
| Admin frontend | Thymeleaf + Bootstrap 5.3 | Mission list, drone detail, KPI dashboard |
| Environment | Docker Compose | Single-command local stack, zero paid dependencies |

Every component is free/open-source; the project runs entirely on a €0
budget with no paid API keys.

## Status

Currently: **Sprint 1 — local infrastructure** (Mosquitto + PostgreSQL/PostGIS
via Docker Compose). See [Roadmap](#roadmap) below for what's next.

## Running the stack

```bash
cp .env.example .env
docker compose up -d
```

This brings up:
- PostgreSQL/PostGIS on `localhost:5432` (credentials from `.env`)
- Mosquitto MQTT on `localhost:1883`, MQTT-over-WebSocket on `localhost:9001`

`docker compose down` to stop; add `-v` to also drop the Postgres volume.

> **Dev-mode note:** Mosquitto currently allows anonymous connections
> (`infra/mosquitto/config/mosquitto.conf`). This is intentional for this
> early sprint and is replaced by per-client TLS + authentication in a later
> sprint (see Roadmap, "Security hardening") before the project is considered
> complete.

## Roadmap

### Core build (MVP, in order)
1. ~~Local infra: Docker Compose with Mosquitto + PostgreSQL/PostGIS~~ (Sprint 1)
2. Spring Boot project skeleton (Web, Data JPA, WebSocket, Hibernate Spatial, MQTT client)
3. `Drone` / `Mission` / `Event` entities with PostGIS `Point` geometry
4. MQTT listener persisting incoming telemetry
5. Basic simulator: 3-5 drones moving between waypoints over MQTT
6. Static tactical map (MapLibre) via REST, last known position
7. Live updates over WebSocket/STOMP
8. Business logic: battery/status alerts, geofenced risk zones
9. KPI dashboard (active missions, success rate, recent alerts, critical battery)

### Differentiation layers (post-MVP, in priority order)
1. **Security hardening** — MQTT over TLS (self-signed certs) with
   per-drone/unit authentication instead of an open channel.
2. **Kalman filtering** — simulator injects realistic GPS noise; backend
   applies a Kalman filter to smooth trajectories before persisting/displaying.
3. **Constrained mission assignment** — greedy algorithm assigning missions
   by battery, distance, and priority instead of fixed waypoint routes.
4. **Network resilience** — simulator randomly drops a drone's connection;
   system marks it "signal lost," retains last known position, and
   reconnects automatically when it returns.
5. **Swarm behavior** — boids (separation/alignment/cohesion) and/or
   auction-based distributed task allocation between simulated drones,
   switchable against the centralized assignment engine from item 3.

## Scope and ethical boundaries

This is a software engineering exercise in backend architecture, geospatial
data, real-time systems, and multi-agent coordination algorithms. It
deliberately does **not** implement, and will not implement, anything related
to real targeting, weapons/strike chains, or any capability that could be
repurposed to cause real-world harm. If a proposed feature starts drifting in
that direction, it gets rejected or redesigned before being built.

## Author

Built and maintained by [Mango77x](https://github.com/Mango77x) as a
portfolio project.
