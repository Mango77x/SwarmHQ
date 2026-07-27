# HELP

Local setup and troubleshooting notes. For architecture and roadmap, see
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Requirements

- Docker + Docker Compose
- JDK 21+ (the Maven wrapper handles Maven itself — no local Maven install needed)

## Running the infrastructure (Sprint 1)

```bash
cp .env.example .env
docker compose up -d
```

This starts:

| Service | Port(s) | Notes |
|---|---|---|
| PostgreSQL/PostGIS | `5433` (host, from `.env`) | credentials in `.env`, gitignored; non-default port, see Troubleshooting |
| Mosquitto (MQTT) | `1883` (MQTT), `9001` (MQTT over WebSocket) | anonymous access enabled for now, see below |

Stop with:

```bash
docker compose down
```

Add `-v` to also delete the Postgres data volume (destructive — only do
this if you want a clean database).

## Running the backend

With the infrastructure up (previous section), from `backend/`:

```bash
./mvnw spring-boot:run
```

It connects to Postgres/PostGIS (Flyway applies `src/main/resources/db/migration`
on startup, Hibernate only validates against it), connects to Mosquitto as
an MQTT client (no subscriptions yet), and starts a WebSocket/STOMP broker
at `/ws` (no destinations published yet). There's no HTTP endpoint of its
own beyond Spring Boot Actuator at `/actuator/health` — that arrives with
the first REST controller in a later sprint.

Schema changes go in a new `db/migration/V{n}__description.sql` file (never
edit an already-applied one) - `Drone`/`Mission`/`Event` and their PostGIS
columns are defined in `V1__init_schema.sql` as of Sprint 3.

`./mvnw test` runs the context-load smoke test (`SwarmHqApplicationTests`);
it uses Spring's default mock web environment, so it doesn't require an
actual HTTP listener to bind.

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
  loopback socket pair the JDK's Windows NIO selector sets up internally.
  `./mvnw test` still works despite this, since the default `@SpringBootTest`
  web environment is mocked and never binds a real socket. Workarounds to
  try: temporarily disable the security software's network filtering, or
  run from a machine/profile without it.
- **Hibernate fails at startup with `SchemaManagementException: Schema
  validation: missing table [...]`, and nothing in the log mentions
  Flyway at all**: Spring Boot 4 split Flyway autoconfiguration into its
  own `org.springframework.boot:spring-boot-flyway` module — having
  `flyway-core` (and `flyway-database-postgresql`) on the classpath isn't
  enough by itself, Flyway silently never runs. `pom.xml` already declares
  `spring-boot-flyway` for this reason; if you hit this after adding a new
  dependency elsewhere, check it didn't get excluded.
- **`docker compose up` fails pulling images**: confirm Docker Desktop /
  the Docker daemon is running.
- **Postgres container unhealthy**: check `docker compose logs postgis` —
  usually a credential mismatch between an existing volume and a changed
  `.env`. Recreate the volume with `docker compose down -v` if the data in
  it is disposable.
