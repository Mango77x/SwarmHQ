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
  "assign missions to multiple drones" case is deferred to the swarm
  differentiation layer, not solved here), status (`PENDING` / `ACTIVE` /
  `COMPLETED` / `FAILED`), priority (`LOW` / `MEDIUM` / `HIGH`), created-at
  timestamp. Unpopulated (schema only, no writer) until Sprint 10's
  `MissionAssignmentService` - see "Mission assignment" below.
- **Event**: append-only audit log entry - many-to-one to `Drone` (required)
  and `Mission` (optional), type (`LOW_BATTERY` / `WAYPOINT_REACHED` /
  `SIGNAL_LOST` / `SIGNAL_RECOVERED` / `STATUS_CHANGE` / `ENTERED_RISK_ZONE`
  / `EXITED_RISK_ZONE` - the last two added Sprint 8), free-text detail,
  occurred-at timestamp. Same audit/movement-log pattern as MOLS.
- **RiskZone** (Sprint 8): id, name, area (`geometry(Polygon,4326)`) - a
  geofenced danger area `AlertService` checks drone positions against.
  Static for now, seeded by Flyway (`V3__add_risk_zones.sql`); no write
  path exists yet since defining zones isn't a use case this project needs
  today.

## Alerting

Implemented as of Sprint 8
(`backend/src/main/java/com/swarmhq/service/AlertService.java`):

- Called from `DroneService.applyTelemetry` with the drone's state from
  just before that call overwrites it, so every check here is
  transition-based - each fires once when crossing a threshold/boundary,
  not on every telemetry tick spent past it:
  - **`LOW_BATTERY`**: `batteryPercent` crosses from above
    `AlertService.LOW_BATTERY_THRESHOLD` (20) to at-or-below it.
  - **`STATUS_CHANGE`**: `status` differs from the previous reading.
  - **`ENTERED_RISK_ZONE` / `EXITED_RISK_ZONE`**: the drone's position
    transitions across a `RiskZone` boundary, via
    `RiskZoneRepository.findContaining` (native `ST_Contains` - the same
    "real PostGIS query, not hand-rolled math" theme as the rest of the
    project). A brand-new drone (no previous position to compare against)
    is still checked - if its very first reading already lands inside a
    zone, that's a legitimate `ENTERED_RISK_ZONE`, not a no-op.
  - A brand-new drone (no previous battery/status to compare against)
    only gets geofencing checked - "changed" doesn't mean anything yet for
    a first-ever reading, so battery/status checks are skipped rather than
    firing a spurious event every time a drone first appears.
- Every raised `Event` is persisted (audit trail, `GET /api/events`) *and*
  broadcast live to `/topic/events` - the alerting counterpart to
  `DroneService`'s `/topic/drones` push (Sprint 7).
- Deliberately out of scope for this sprint: automatic `SIGNAL_LOST`
  detection (the simulator never actually drops a connection yet - that's
  the "Network resilience" differentiation layer) and mission
  success/failure logic (`Mission` isn't touched by any listener yet).

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
- **Mission assignment** (Sprint 10, `MissionAssignmentService`/
  `MissionStatusListener`): two more topics, the reply pair to the
  telemetry one above.
  - `drones/{externalId}/mission` (backend → simulator, published once per
    assignment): `{"missionId": 5, "route": [[lon,lat], ...], "priority":
    "HIGH"}` - the same `[lon,lat]` order as every REST geometry DTO.
  - `drones/{externalId}/mission-status` (simulator → backend, published
    once the drone finishes or aborts the route):
    `{"missionId": 5, "status": "COMPLETED"}` or
    `{"missionId": 5, "status": "FAILED", "reason": "low_battery"}`.
- **MQTT is QoS1 ("at least once") in both directions - listeners must be
  idempotent, not just correct on a single delivery.** A message can be
  legitimately redelivered by the broker if it doesn't get a timely PUBACK,
  regardless of whether the actual bug is on the publisher or subscriber
  side. `MissionStatusListener` learned this the hard way while testing
  Sprint 11 (TLS's slower handshake made a redelivery race easy to hit
  that plaintext rarely surfaced) - it now no-ops on a mission-status
  report for a mission that isn't `ACTIVE` anymore, since `COMPLETED`/
  `FAILED` are terminal and a repeat of either is a duplicate delivery,
  not a new fact. `DroneTelemetryListener` doesn't need an equivalent
  guard - reapplying the same telemetry reading twice is naturally a
  no-op (it just upserts the same lat/lon/battery/status again).
  A first attempt at this guard (read `mission.getStatus()`, then write)
  was itself racy under genuinely concurrent redeliveries - both could
  read `ACTIVE` before either write committed, so both proceeded and
  raised a duplicate `Event` (found and fixed during Sprint 14's own
  verification pass, see `MissionRepository.updateStatusIfActive` and
  HELP.md's Troubleshooting). The fix makes the check-and-transition one
  atomic `UPDATE ... WHERE status = 'ACTIVE'` instead of two separate
  steps, so Postgres' own row lock - not application code - is what
  guarantees only one redelivery ever wins.

## MQTT security

Implemented as of Sprint 11
(`infra/mosquitto/setup/generate.sh`, `backend/src/main/java/com/swarmhq/config/MqttConfig.java`,
`simulator/main.py`) - the first differentiation layer, replacing the
anonymous plaintext channel every earlier sprint used:

- **TLS**: a self-signed CA + server certificate, generated once by the
  `mosquitto-setup` one-shot Compose service (idempotent - re-running
  `docker compose up` never rotates existing certs/secrets) and never
  committed (`infra/mosquitto/certs/` is gitignored). The server cert's
  SAN covers both `mosquitto` (container-to-container, e.g. the backend
  inside Docker) and `localhost`/`127.0.0.1` (host-side - a locally-run
  backend/simulator/test suite via the host-mapped port) - a CN alone
  isn't enough, modern TLS clients check the SAN list. The backend trusts
  that CA by building a one-off `SSLContext` from the cert file
  (`MqttConfig.trustingOnly`) rather than requiring a full Java keystore
  to be provisioned; the simulator does the same via `paho`'s
  `tls_set(ca_certs=...)`.
- **Per-drone authentication**: every simulated drone connects as its own
  MQTT identity (`drone-1`, `drone-2`, ...), not a single connection
  shared across the whole fleet - required for the next point to mean
  anything. All drones share one password (`MQTT_DRONE_PASSWORD` -
  they're the same trust tier, a fleet of field units; genuinely distinct
  per-unit secrets would need a provisioning workflow out of scope here),
  while the backend gets its own separate identity/password
  (`swarmhq-backend`/`MQTT_BACKEND_PASSWORD`). A fixed range of drone
  identities is pre-provisioned (`MQTT_MAX_PROVISIONED_DRONES`, default
  20, comfortably above `DRONE_COUNT`'s default of 4) rather than dynamic
  self-enrollment - this is a demo fleet of a known rough size, not an
  open registration system.
- **ACLs** (`infra/mosquitto/config/acl.conf`): a `pattern` rule scopes
  every drone identity to only its own topics
  (`drones/%u/telemetry`/`mission-status` write, `drones/%u/mission`
  read) - `%u` substitutes the authenticated username, so this is one
  rule covering every drone rather than one block per drone, and it's
  what makes per-drone auth actually mean something: a compromised
  `drone-3` credential can publish fake telemetry for `drone-3`, but
  gets denied (silently - MQTT ACL rejection has no error feedback to
  the publisher, confirmed by testing it directly with `mosquitto_pub`)
  if it tries to touch `drones/drone-7/telemetry`. The backend's own
  identity gets a broader explicit grant (read every drone's
  telemetry/mission-status, write every drone's mission topic), since it
  legitimately needs fleet-wide access.
- **Known limitation, left as-is**: Mosquitto 2.1.2 warns that the
  password file and ACL file aren't owned by the `mosquitto` user and
  logs "future versions will refuse to load this file." Bind-mounting a
  Windows host directory into a Linux container doesn't support setting
  real Unix ownership in a way that persists (`chown` inside the
  container doesn't stick), so satisfying that check isn't achievable
  here without a fundamentally different volume strategy (a named Docker
  volume instead of a host bind mount) - out of scope for this sprint.
  Purely cosmetic today (mosquitto 2.1.2 still loads the files fine); a
  future Mosquitto major version enforcing it would need revisiting.

## Kalman filtering

Implemented as of Sprint 12
(`backend/src/main/java/com/swarmhq/service/KalmanFilter2D.java`,
`backend/src/main/java/com/swarmhq/service/KalmanFilterService.java`,
`simulator/drones.py`, `simulator/config.py`) - the third differentiation
layer. A real drone's GPS reading is never its exact position; the
simulator now injects that realism and the backend now compensates for it,
demonstrating sensor fusion rather than "store what arrives."

- **Simulator side**: each published telemetry reading has independent
  Gaussian noise (`numpy.random.normal`, std dev `GPS_NOISE_STD_METERS`,
  default 5m - typical civilian GPS accuracy) added to lat/lon separately.
  Only the *published* reading is noisy - the drone's own internal route
  progress, waypoint arrival, and mission logic all still use its true
  simulated position, exactly like a real drone's flight computer knows
  where it commanded itself to go even though its GPS receiver reports
  something slightly different.
- **Backend side**: `KalmanFilterService` keeps one `KalmanFilter2D`
  instance per drone (`ConcurrentHashMap` keyed by `externalId`), created
  lazily on that drone's first-ever reading. `KalmanFilter2D` is a
  hand-rolled 4-state constant-velocity filter (`[lon, lat, vLon, vLat]`)
  - small, fixed-size matrix math, so no linear-algebra dependency was
  worth adding on the Java side (unlike the simulator, where `numpy` was
  already the natural choice for noise generation). `DroneService.applyTelemetry`
  runs every incoming reading through `KalmanFilterService.smooth()` before
  anything else touches it.
- **Deliberately transparent**: only the *smoothed* estimate is ever
  persisted, broadcast over STOMP, or geofence-checked - the raw noisy
  reading is used for exactly one thing (updating the filter) and then
  discarded. This was a scope decision: the roadmap's own wording ("the
  backend applies a Kalman filter to smooth trajectories before
  persisting/displaying them") reads as full transparency, so there's no
  raw-vs-filtered toggle, no second column, no frontend change - the
  filter is invisible infrastructure, the same way a real ground station
  never shows you the GPS chipset's unfiltered NMEA output.
- **Side effect worth knowing about**: geofence entry/exit
  (`AlertService.evaluateRiskZones`) now reacts to the filtered position,
  which blends toward a new reading instead of snapping to it instantly.
  A drone that jumps directly into a small risk zone in one raw tick won't
  be recorded as "entered" until the filter's estimate actually converges
  into the zone over a few ticks - correct behavior for a smoothing filter,
  but worth remembering if a future zone is small relative to
  `PUBLISH_INTERVAL_SECONDS` × patrol speed.

## Network resilience

Implemented as of Sprint 13
(`backend/src/main/java/com/swarmhq/service/SignalMonitorService.java`,
`simulator/drones.py`, `simulator/config.py`) - the fourth differentiation
layer. Field systems in this domain have to work offline-first; this
replicates the "a unit drops off the network mid-mission and comes back
later" constraint instead of assuming every drone is always reachable.

- **Simulator side**: each drone has an independent per-tick chance
  (`SIGNAL_LOSS_CHANCE_PER_TICK`, default 0.01) of losing its connection
  for a random duration (`SIGNAL_LOSS_MIN_TICKS`/`SIGNAL_LOSS_MAX_TICKS`,
  default 5-15 ticks, i.e. 10-30s at the default publish interval). While
  "out of contact" the drone keeps flying, draining battery, and finishing
  missions exactly as normal - only publishing stops, the same way a real
  drone's flight computer doesn't pause just because its radio link to the
  ground station drops. Any mission-status event that would have been
  reported during the outage is queued in `main.py` and flushed once the
  connection is back, rather than silently lost (which would otherwise
  strand a mission `ACTIVE` forever with no completion/failure ever
  reported).
- **Backend side**: `SignalMonitorService` is a `@Scheduled` watchdog (the
  mirror image of `MissionAssignmentService`'s scheduled pass - reacting to
  messages that *stopped* arriving instead of ones that did). Every 5s it
  marks any drone whose `lastUpdateAt` is older than
  `swarmhq.signal-monitor.timeout-seconds` (default 15) as `SIGNAL_LOST`
  (an existing `DroneStatus`/`EventType` pair from Sprint 3, unused until
  now) and raises a `SIGNAL_LOST` event. Its last known position is left
  untouched - "lost signal" means exactly that, the last thing heard from
  it is still the best guess of where it is.
- **Recovery needs no watchdog counterpart**: the moment a `SIGNAL_LOST`
  drone's telemetry reaches `DroneService.applyTelemetry` again,
  `AlertService.evaluate` sees `previousStatus == SIGNAL_LOST` and the
  current status something else, and raises `SIGNAL_RECOVERED` for free -
  the same "ordinary telemetry does the work" pattern Sprint 10 already
  established for a mission ending and a drone resuming patrol.
- **`SIGNAL_LOST` drones are automatically excluded from new mission
  assignment** - no code change was needed for this:
  `DroneRepository.findBestForMission` already filters `status = 'PATROLLING'`
  (Sprint 10), so a drone the backend can't currently reach was never a
  candidate to begin with.
- Both the frontend's status-color map (`TacticalMap.tsx`) and the
  `EventType` union it renders already had `SIGNAL_LOST`/`SIGNAL_RECOVERED`
  wired in from earlier sprints (scaffolded ahead of this one) - so this
  sprint needed zero frontend changes, same as Sprint 12.

## Drone simulator

Implemented as of Sprint 5 (`simulator/`, Python, `paho-mqtt`):

- Each simulated drone (`DRONE_COUNT`, default 4) patrols a fixed square
  waypoint loop, offset diagonally per drone so routes don't overlap
  (`routes.py`). Index 0 of a route is that drone's base.
- Battery drains once per publish tick while patrolling or flying a
  mission (`BATTERY_DRAIN_PER_TICK`); once it hits `LOW_BATTERY_THRESHOLD`
  the drone breaks off (aborting any in-progress mission first, see
  below), heads straight to base instead (`status` becomes `RETURNING`),
  recharges to 100% on arrival, and resumes patrolling - the same
  patrol/go-to-point/return-to-base behavior the original spec calls for.
- Publishes to `drones/{externalId}/telemetry` (`drone-1`, `drone-2`, ...)
  on a fixed interval (`PUBLISH_INTERVAL_SECONDS`, default 2s) matching the
  "MQTT contract" above exactly, so the Sprint 4 listener needed no changes
  to consume it.
- **One MQTT connection per drone, each authenticated as its own identity
  (Sprint 11, see "MQTT security" above)** - not one shared connection
  publishing/subscribing for the whole fleet. Required for the broker's
  per-drone ACL patterns to mean anything; a single shared connection
  would make "per-drone authentication" hollow, since every drone's
  traffic would carry the same credential.
- **Flying an assigned mission (Sprint 10, `drones.py`'s `Drone.start_mission`)**:
  subscribing to its own `drones/{externalId}/mission` topic, a `PATROLLING`
  drone breaks off its fixed patrol loop to fly the assigned route instead
  (`status` becomes `ON_MISSION`), publishing `mission-status: COMPLETED`
  on reaching the final waypoint (then resuming patrol from wherever it
  physically ended up) or `FAILED` if battery forces an abort mid-route.
  `Drone.tick()` (main loop thread) and `Drone.start_mission()` (MQTT
  client's own network thread, from `loop_start()`) mutate the same
  state, so each `Drone` instance guards both behind its own lock.
- **Injects Gaussian GPS noise into every published reading (Sprint 12,
  see "Kalman filtering" above)** - `GPS_NOISE_STD_METERS`, only affects
  what's published over MQTT, never the drone's own internal route state.
- **Randomly drops its own connection (Sprint 13, see "Network resilience"
  above)** - `SIGNAL_LOSS_CHANCE_PER_TICK`/`_MIN_TICKS`/`_MAX_TICKS`, only
  suppresses publishing (telemetry and any queued mission-status), never
  the drone's own internal route/mission/battery state.
- Deliberately out of scope for this sprint: swarm/auction coordination
  between drones - its own later differentiation layer.

## Mission assignment

Implemented as of Sprint 10
(`backend/src/main/java/com/swarmhq/service/MissionAssignmentService.java`) -
the "constrained mission assignment" differentiation layer, moved ahead of
security hardening in the roadmap because it closes a gap Sprint 9 shipped
knowingly incomplete (see Roadmap below):

- A `@Scheduled` pass (every 5s) re-evaluates every `PENDING` mission,
  priority order first (`HIGH`/`MEDIUM`/`LOW`, sorted in Java - sorting by
  `MissionPriority` in a derived query would sort alphabetically by the
  stored enum string instead, "HIGH" < "LOW" < "MEDIUM", not by actual
  priority), oldest-within-priority second. Re-evaluating every PENDING
  mission each tick (not just newly created ones) means a mission left
  unassigned because no drone qualified last time gets picked up
  automatically once one does.
- For each mission, `DroneRepository.findBestForMission` (native query,
  `ORDER BY ST_Distance(position::geography, mission_start::geography) /
  (battery_percent / 100.0) LIMIT 1`) picks the closest eligible
  (`PATROLLING`, battery above `AlertService.LOW_BATTERY_THRESHOLD + 10`)
  drone - real PostGIS distance in meters (the `::geography` casts), not
  hand-rolled haversine math, same theme as `RiskZoneRepository`. Queried
  fresh per mission, not from one snapshot up front, so a drone claimed
  earlier in the same pass is already excluded from the next mission's
  query.
- Assignment flips the drone to `ON_MISSION` immediately
  (`DroneService.markOnMission`) rather than waiting for the simulator's
  next telemetry tick to confirm it - the backend is the one deciding the
  assignment and needs the drone to read as unavailable right away, not
  after up to one telemetry interval's delay (which could otherwise let
  the same assignment pass hand it a second mission). This also means the
  `STATUS_CHANGE` alert (Sprint 8) fires from the assignment itself, for
  free.
- On completion/failure feedback (`MissionStatusListener`), `Mission.status`
  updates and an `Event` is raised against the now-used `Event.mission`
  field and `EventType.WAYPOINT_REACHED` (completed) or the new
  `MISSION_FAILED` (aborted) - both persisted and broadcast to
  `/topic/events` (`AlertService.raiseMissionEvent`), same as every other
  alert.
- `GET /api/missions` / `POST /api/missions` (see REST API below) exist so
  the loop is testable/demonstrable without a UI: `V4__seed_demo_missions.sql`
  seeds two demo missions placed near the simulator's actual patrol area,
  so a normal `docker compose up` run assigns, flies, and completes them
  automatically.
- Test-only note: `swarmhq.mission-assignment.scheduler-enabled` (default
  `true`) disables the `@Scheduled` tick without touching
  `assignPendingMissions()` itself - set to `false` via `@TestPropertySource`
  in `MissionAssignmentServiceTests`/`KpiServiceTests` so a background tick
  can't race their own assertions.

## Swarm behavior

Implemented as of Sprint 14
(`backend/src/main/java/com/swarmhq/service/AuctionCoordinatorService.java`,
`backend/src/main/java/com/swarmhq/mqtt/MissionBidListener.java`,
`simulator/boids.py`), made live-toggleable in Sprint 16 (see "Live mode
toggle" below) - the differentiation layer that actually justifies the
project's name. Two halves, centralized by default so existing behavior
from Sprints 5-13 is unaffected unless explicitly switched:

- **Boids flocking** (`SWARM_MODE=true` in the simulator): patrolling
  drones stop following their fixed waypoint square and instead move by
  Craig Reynolds' three classic rules - separation (steer away from
  neighbors that are too close), alignment (match the average heading of
  nearby drones), cohesion (steer toward the average position of nearby
  drones) - plus a gentle pull back toward the patrol area's center so the
  flock doesn't wander off indefinitely. `simulator/boids.py` is pure,
  dependency-free math operating directly in lat/lon degree space (with a
  `cos(latitude)` correction so a degree of longitude and a degree of
  latitude aren't treated as equal distances) and is unit-tested in
  isolation (`simulator/test_boids.py`, 5 cases) rather than only exercised
  indirectly through the full simulator loop. `main.py`'s tick loop derives
  each patrolling drone's velocity from its last two positions (there's no
  other source of "heading" for a waypoint-less drone), computes one
  `boids.compute_step` per tick over every currently-patrolling drone at
  once, and passes the result into `Drone.tick(position_override=...)` -
  applied only if the drone is still `PATROLLING` after its own
  battery/mission checks run, so a drone that just broke off to `RETURNING`
  or is flying an assigned mission always ignores the flock and follows its
  own route, same priority order as before this sprint.
- **Auction-based distributed assignment**: the decentralized counterpart
  to Sprint 10's centralized engine. Both `MissionAssignmentService` and
  `AuctionCoordinatorService` are always registered beans (Sprint 16
  dropped the `@ConditionalOnProperty` this used to lean on) - each checks
  `MissionAssignmentModeHolder` on its own tick and no-ops on whichever one
  isn't currently active, so exactly one strategy ever actually assigns a
  mission at a time without either bean's existence being fixed for the
  app's whole lifetime. `AuctionCoordinatorService` is a `@Scheduled`
  watchdog (1s) that opens an
  auction (broadcasts the mission on `missions/available`) for every
  `PENDING` mission with no auction already open for it, and closes any
  auction older than `swarmhq.mission-assignment.auction-window-seconds`
  (default 3s) by picking the lowest bid received - or leaving the mission
  `PENDING` for the next tick to retry if none arrived. The winning bid is
  handed to the same `MissionAssigner` (extracted this sprint from what was
  previously private logic inside `MissionAssignmentService`) that
  centralized mode uses, so both strategies assign missions through
  identical code - flip the drone `ON_MISSION`, publish
  `drones/{externalId}/mission`, raise the `STATUS_CHANGE` alert - and only
  differ in *how a drone is chosen*, not in what happens once one is.
  Every drone (simulator side, subscribed unconditionally since Sprint 16 -
  see "Live mode toggle") bids on anything it hears on `missions/available`
  while the live mode is auction, cost-per-mission
  `distance / (battery_percent / 100.0)`, deliberately
  mirroring the same distance-over-battery shape as
  `DroneRepository.findBestForMission`'s own `ORDER BY`, so a drone bids on
  roughly the criteria the centralized engine would have judged it by, and
  the lowest bid winning is doing the same job that engine's `ORDER BY
  ... LIMIT 1` does, just without a single component that can see every
  drone at once.
- **New MQTT topics** (see "MQTT contract" above for the existing pair):
  `missions/available` (backend → simulator, one broadcast per opened
  auction: `{"missionId": 5, "lat": 40.42, "lon": -3.70, "priority":
  "HIGH"}`) and `missions/{missionId}/bids` (simulator → backend, one
  publish per bidding drone: `{"droneId": "drone-2", "cost": 143.7}`),
  subscribed by the backend as the wildcard `missions/+/bids`
  (`MissionBidListener`, missionId parsed out of the topic itself rather
  than carried in the payload). Both are QoS1, same "listeners must be
  idempotent" rule as the telemetry/mission-status pair - a bid arriving
  for a mission whose auction already closed, or from a drone no longer
  `PATROLLING`, is silently ignored rather than treated as an error.
- Verified live end-to-end (Sprint 14): an auction opened for a real
  `PENDING` mission, a bid was received and was the lowest of those that
  arrived, the mission was assigned to that drone via the exact same
  `drones/{externalId}/mission` contract centralized mode uses, and the
  drone accepted and flew it; separately, repeated position polls of
  patrolling drones showed organic drift consistent with flocking rather
  than the old fixed-square pattern.

### Live mode toggle (Sprint 16)

Sprint 14 shipped both halves as environment variables a developer had to
set and *keep paired* before restarting everything - `mode=auction` on the
backend did nothing useful without also remembering `SWARM_MODE=true` on
the simulator, and neither could be flipped without a restart. Sprint 16
replaces that with one live switch:

- **`MissionAssignmentModeHolder`** (`backend/src/main/java/com/swarmhq/service/MissionAssignmentModeHolder.java`)
  holds the current mode as a mutable `AtomicReference`, seeded from
  `swarmhq.mission-assignment.mode` (still the *initial* value at startup,
  same property as before) but changeable at runtime from here on.
  `MissionAssignmentService` and `AuctionCoordinatorService` both read it
  fresh on every tick instead of either one being permanently wired in or
  out at boot.
- **`GET`/`PUT /api/mode`** (`MissionAssignmentModeController`) lets
  anything - the frontend, `curl`, a script - read or switch the mode.
  `PUT` body: `{"mode": "auction"}` or `{"mode": "centralized"}`; an
  unrecognized value is a 400.
- **Every change is broadcast, not just held in memory**: a retained MQTT
  message on `system/mission-assignment-mode` (`{"mode": "auction"}`,
  published on backend startup too, not only on future changes, so a
  simulator connecting after the fact still learns the real mode
  immediately) and a STOMP broadcast on `/topic/mode` for the frontend.
- **The simulator follows the backend live**, not just at its own startup:
  every drone client unconditionally subscribes to both `missions/available`
  and `system/mission-assignment-mode` now (Sprint 14's `SWARM_MODE`-gated
  subscription is gone). `SWARM_MODE` is still read, but only as the
  *fallback* value used for the handful of ticks before that retained
  message actually arrives - the moment it does, it wins, for both halves
  at once: the same `_swarm_mode_active` flag gates boids movement in the
  tick loop and whether a drone bids in `_on_mission_available`. This is
  what actually eliminates the old "you had to remember to pair the two
  flags" caveat - one broadcast now drives both.
- **The frontend** (`ModeToggle.tsx`) shows the live mode and switches it
  with one click - `fetchMode`/`updateMode` (`GET`/`PUT /api/mode`) plus
  `connectLiveMode` (subscribes `/topic/mode`) so a change from anywhere
  else (another tab, a `curl` call) still updates the button.
- Verified live end-to-end, no restarts anywhere: flipped to auction via
  the REST endpoint, the backend opened an auction for a real mission and
  assigned it to the drone that bid, and *separately* patrolling drones'
  positions started drifting via boids in the same run - both from a
  single switch. Flipped back and both stopped. Repeated the same round
  trip by clicking the actual button in the running frontend.

## Dashboard KPIs

Implemented as of Sprint 9
(`backend/src/main/java/com/swarmhq/service/KpiService.java`):

- Four aggregates, `GET /api/kpis` (`KpiSummary`): active mission count,
  mission success rate (`COMPLETED / (COMPLETED + FAILED)` as a
  percentage), alerts raised in the last hour, and drones currently at or
  below the low-battery threshold (reuses `AlertService.LOW_BATTERY_THRESHOLD`
  rather than a second magic number).
- **Polled, not pushed**: unlike `DroneService`/`AlertService`, `KpiService`
  has no STOMP topic - the frontend's `KpiBar` calls `GET /api/kpis` every
  5s instead. These are aggregate counts a dashboard reasonably refreshes
  every few seconds, not per-event state a marker/alert list needs
  instantly - broadcasting a full recompute on every single
  drone/event/mission write would cost real complexity for no meaningful
  benefit here.
- **Mission KPIs reflect real data as of Sprint 10** - `MissionAssignmentService`/
  `MissionStatusListener` (see "Mission assignment" above) are the write
  path `Mission` never had before. `missionSuccessRatePercent` stays
  `null` (not `0`) specifically when no mission has completed or failed
  *yet* - "no data" and "0% succeeded" are different facts - which is
  still the case on a totally fresh install for the few seconds before
  the seeded demo missions get assigned and flown.

## REST API

Implemented as of Sprint 6:

- `GET /api/drones` - every drone's last known state (thin
  `DroneController` → `DroneService` → `DroneRepository`, matching the
  thin-controller/service-layer convention from MOLS). Returns
  `externalId`, `type`, `lat`/`lon` (`null` until the drone's first
  telemetry arrives), `batteryPercent`, `status`, `lastUpdateAt` - the
  internal database id is never exposed, `externalId` is the public
  identifier throughout.
- `GET /api/events` (Sprint 8) - the 50 most recent alerts, newest first
  (`EventService.listRecent`). Returns `droneExternalId`, `type`,
  `detail`, `occurredAt` - same "no internal ids" convention.
- `GET /api/zones` (Sprint 8) - every `RiskZone`'s exterior ring as
  `[lon, lat]` pairs (`ZoneResponse` - the same coordinate order as a
  GeoJSON `Polygon` ring, so the frontend drops it straight into one with
  no reordering).
- `GET /api/kpis` (Sprint 9) - dashboard aggregates (`KpiService.summarize`,
  see "Dashboard KPIs" below). The one REST endpoint that's polled rather
  than pushed - see that section for why.
- `GET /api/missions` / `POST /api/missions` (Sprint 10, `MissionController`
  → `MissionService`) - list every mission (`MissionResponse`: `id`,
  `route` as `[lon,lat]` pairs, `assignedDroneExternalId` - `null` until
  `MissionAssignmentService` picks one, `status`, `priority`, `createdAt`)
  or create one (`CreateMissionRequest`: `route`, `priority` - always
  starts `PENDING`, same "not this controller's job" reasoning as
  everywhere else in this project - `MissionAssignmentService`'s scheduled
  pass, not the request itself, decides who flies it).
- `GET`/`PUT /api/mode` (Sprint 16, `MissionAssignmentModeController`) -
  read or switch the live mission-assignment mode (`"centralized"` or
  `"auction"`, see "Live mode toggle" under "Swarm behavior" above).
  `PUT` with anything else is a 400.

## WebSocket contract

Implemented as of Sprint 7
(`backend/src/main/java/com/swarmhq/config/WebSocketConfig.java`,
`DroneService.applyTelemetry`):

- STOMP over SockJS at `/ws` (`registry.addEndpoint("/ws").withSockJS()`) -
  SockJS gives a WebSocket-compatible connection with automatic fallback
  transports, which the frontend's dev proxy and any intermediary in front
  of the Docker deployment don't need special-casing for.
- A single broadcast topic, `/topic/drones` (`DroneService.DRONE_UPDATES_TOPIC`):
  every time `applyTelemetry` upserts a drone (called from
  `DroneTelemetryListener`, i.e. once per MQTT telemetry message), it
  publishes that drone's updated state - the same `DroneResponse` shape
  `GET /api/drones` returns for one drone - to every subscriber. One shared
  topic rather than per-drone destinations (`/topic/drones/{externalId}`)
  is deliberate: the expected fleet size (single-digit to low-tens of
  drones) doesn't need the fan-out savings a per-drone topic would give,
  and clients upsert by `externalId` either way.
- A second topic, `/topic/events` (`AlertService.EVENT_UPDATES_TOPIC`,
  Sprint 8) - every `Event` `AlertService` raises is broadcast here,
  the same `EventResponse` shape `GET /api/events` returns.
- A third topic, `/topic/mode` (`MissionAssignmentModeHolder.MODE_UPDATES_TOPIC`,
  Sprint 16) - `{"mode": "centralized" | "auction"}`, broadcast whenever
  the live mission-assignment mode changes, so every connected frontend
  tab's toggle stays in sync regardless of which tab (or `curl` call)
  actually changed it.
- No `/app`-prefixed client-to-server destinations exist yet - drones only
  broadcast telemetry they generate themselves; nothing today has a
  server-side handler needed for a client to *send* something over this
  connection.

## Frontend

Implemented as of Sprint 6/7/8/9 (`frontend/`, React 19 + TypeScript +
Vite + Tailwind 4 + `maplibre-gl` + `@stomp/stompjs` + `sockjs-client`):

- Single page for now: a header bar and a full-height `TacticalMap`
  component. `TacticalMap` renders a MapLibre map on OpenFreeMap's `dark`
  style centered on the simulator's patrol area, and keeps one MapLibre
  `Marker` per drone in sync, colored by status (green patrolling, blue on
  mission, amber returning, red signal lost).
- **Live updates (Sprint 7)**: `GET /api/drones` is now only called once,
  on mount, for the baseline (drones that already reported before the page
  loaded) - `frontend/src/api/liveDrones.ts` then opens a STOMP/SockJS
  connection to `/ws` and subscribes to `/topic/drones`, upserting markers
  as updates arrive instead of polling. A small "live"/"connecting…"
  indicator (bottom-left) reflects the WebSocket connection state. Markers
  are never removed on their own in this model - a drone that stops
  reporting doesn't disappear, it should eventually show as `SIGNAL_LOST`
  (a later differentiation layer), not vanish.
- **Alerting (Sprint 8)**: risk zones (`GET /api/zones`) are drawn once,
  on map load, as a translucent red fill + outline layer from a GeoJSON
  source built out of each zone's ring. `AlertsPanel`
  (`frontend/src/components/AlertsPanel.tsx`) is the same
  REST-baseline-then-STOMP-push pattern as the map itself, just for
  `Event`s instead of `Drone`s (`GET /api/events` once, then
  `/topic/events`) - a small top-right panel listing the most recent
  alerts, newest first, capped at 8 visible.
- **KPI dashboard (Sprint 9)**: `KpiBar` (`frontend/src/components/KpiBar.tsx`)
  sits in the header, polling `GET /api/kpis` every 5s (the one place this
  frontend polls rather than subscribes - see "Dashboard KPIs" above for
  why) and rendering four tiles: active missions, mission success rate,
  alerts in the last hour, and drones at critical battery. The last two
  highlight red when non-zero.
- **Live mode toggle (Sprint 16)**: `ModeToggle`
  (`frontend/src/components/ModeToggle.tsx`), also in the header - the
  same REST-baseline-then-STOMP-push pattern as the map and alerts panel
  (`GET /api/mode` once, then `/topic/mode`), except this one can also
  *write*: clicking it calls `PUT /api/mode` to flip the live strategy.
  Styled to match the project's own orange/slate branding when swarm
  (auction) mode is active, slate when centralized.
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
| 7 | ✅ | Live updates over WebSocket/STOMP |
| 8 | ✅ | Business logic: battery/status alerts, geofenced risk zones |
| 9 | ✅ | KPI dashboard (active missions, success rate, recent alerts, critical battery) |

Core MVP complete as of Sprint 9: the full pipeline (simulator → MQTT →
persistence → live map/alerts/KPIs) runs end to end. What's *not* real yet
- and won't be faked to make a KPI tile look busier - is anything that
needs a mission-assignment engine (active mission count, success rate)
or the network-resilience layer (`SIGNAL_LOST`/`SIGNAL_RECOVERED`); both
are differentiation layers below, not bugs in Sprint 9. Remaining work is
the differentiation layers.

### Differentiation layers (post-MVP, in order)

| # | Sprint | Deliverable |
|---|---|---|
| 10 | ✅ | Constrained mission assignment engine |
| 11 | ✅ | Security hardening: MQTT over TLS + per-drone auth |
| 12 | ✅ | Kalman filtering: simulated GPS noise + backend smoothing |
| 13 | ✅ | Network resilience: simulated signal loss/reconnect |
| 14 | ✅ | Swarm behavior: boids + auction-based assignment |

Re-sequenced from the original priority order (security first) agreed
2026-07-27: mission assignment moved to Sprint 10, ahead of security,
specifically because it closes a gap Sprint 9 shipped knowingly incomplete
- `activeMissions`/`missionSuccessRatePercent` on the KPI dashboard are
`0`/`null` today because nothing creates a `Mission` row yet. Landing the
assignment engine next makes that dashboard reflect real data instead of
staying an admittedly-empty tile for another four sprints. Security
hardening is still next right after it - cheap, and the original
"communications security is the central concern in this domain" rationale
below still holds.

1. **Sprint 10 - Constrained mission assignment.** ✅ Done - see "Mission
   assignment" and the new MQTT topics in "MQTT contract" above for the
   implementation. Deliberately left out: a mission-creation UI
   (`POST /api/missions` is enough for this sprint, same as risk zones
   having no creation UI since Sprint 8) and a live `/topic/missions`
   push (the KPI bar's existing 5s poll is enough for aggregate counts,
   per Sprint 9's own "polled, not pushed" reasoning).
2. **Sprint 11 - Security hardening.** ✅ Done - see "MQTT security" above
   for the implementation (TLS, per-drone auth, ACLs actually verified to
   block cross-drone impersonation, not just assumed to). In defense
   contexts, communications security is the central concern, not an
   afterthought, and it turned out cheap relative to the other layers -
   the real cost of this sprint was debugging environment quirks (a
   Windows-bind-mount file permission mismatch, a missing cert SAN, an
   MQTT QoS1 redelivery race), not the security design itself.
3. **Sprint 12 - Kalman filtering.** ✅ Done - see "Kalman filtering" below
   for the implementation. The simulator injects realistic GPS noise; the
   backend applies a Kalman filter to smooth trajectories before
   persisting/displaying them. The heaviest algorithmic piece -
   demonstrates sensor fusion, not just "store what arrives."
4. **Sprint 13 - Network resilience.** ✅ Done - see "Network resilience"
   above for the implementation. The simulator can randomly drop a drone's
   connection; the system marks it `SIGNAL_LOST` (an existing
   `DroneStatus`/`EventType` value, unused until now), retains its last
   known position, and reconnects automatically (`SIGNAL_RECOVERED`) once
   it returns. Field systems in this domain have to work offline-first;
   this replicates that constraint.
5. **Sprint 14 - Swarm behavior.** ✅ Done - see "Swarm behavior" above for
   the implementation. Two complementary approaches, both toggleable
   against Sprint 10's centralized mode:
   - **Boids (local coordination):** each simulated drone decides its
     movement from simple rules relative to its neighbors - separation,
     alignment, cohesion (Craig Reynolds' classic model).
   - **Auction-based distributed assignment:** instead of the backend
     centrally assigning missions (Sprint 10), simulated drones "bid" on
     available missions based on their own battery/distance, and the
     lowest-cost bidder wins.
   This is what actually justifies the project's name - it's possible to
   toggle between "centralized mode" (Sprint 10) and "swarm mode"
   (boids/auction) as a demonstrable feature, since comparing both
   approaches is a strong portfolio argument on its own. Deliberately
   last: needed Sprint 10's centralized mode to exist first, to have
   something to toggle against/compare with.

## Continuous integration

Implemented as of Sprint 15 (`.github/workflows/ci.yml`) - three independent
jobs, run in parallel on every push to `main` and every pull request:

- **Backend** (`./mvnw test`): starts the same Postgres/PostGIS + Mosquitto
  infra `docker compose up -d` gives a local developer (`.env.example`
  copied to `.env`, defaults unchanged), then runs the full test suite
  directly on the runner against it - the same way a developer runs tests
  locally, rather than reimplementing the stack as separate CI service
  containers. Mosquitto has no Docker healthcheck of its own, so a short
  TCP-readiness loop against port 8883 runs first - `MqttConfig`'s
  `connect()` call has no retry of its own, so a Spring context that
  starts before the broker's TLS listener is actually bound fails every
  test in the suite, not just the MQTT-specific ones.
- **Simulator** (`pytest`): only `boids.py`'s pure math is unit-tested
  (`test_boids.py`) - no broker or database needed for this job.
- **Frontend** (`npm run lint` + `npm run build`): no frontend unit tests
  exist yet, so lint (`oxlint`) plus the build's own `tsc -b` typecheck is
  the real signal available today - either catches a broken build/type
  error before merge instead of only when someone happens to run it
  locally.
- `backend/mvnw`'s executable bit wasn't actually tracked in git (`100644`
  instead of `100755` - a Windows checkout doesn't preserve it), which a
  Windows dev machine never notices since `./mvnw` still resolves via the
  Git Bash/PowerShell wrapper either way; a Linux CI runner does notice,
  failing the very first `./mvnw test` with a plain permission error.
  Fixed via `git update-index --chmod=+x backend/mvnw` alongside adding
  this workflow.

## Working conventions

- Work proceeds sprint by sprint, in the order above; later phases are not
  started before earlier ones are closed.
- Design decisions not already fixed here (simulator language, package
  layout, MQTT topic naming, etc.) are confirmed before writing the code for
  that sprint, not assumed silently.
- Controllers stay thin; business logic lives in the service layer (same
  convention as MOLS).
