# HELP

Local setup and troubleshooting notes. For architecture and roadmap, see
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Requirements

- Docker + Docker Compose
- (From Sprint 2 onward) JDK 21+ and Maven, for the Spring Boot backend

## Running the infrastructure (Sprint 1)

```bash
cp .env.example .env
docker compose up -d
```

This starts:

| Service | Port(s) | Notes |
|---|---|---|
| PostgreSQL/PostGIS | `5432` (host, from `.env`) | credentials in `.env`, gitignored |
| Mosquitto (MQTT) | `1883` (MQTT), `9001` (MQTT over WebSocket) | anonymous access enabled for now, see below |

Stop with:

```bash
docker compose down
```

Add `-v` to also delete the Postgres data volume (destructive — only do
this if you want a clean database).

## Environment variables

Copy `.env.example` to `.env` before first run; `.env` is gitignored so each
environment (your machine, CI, etc.) keeps its own values.

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_DB` | `swarmhq` | database name |
| `POSTGRES_USER` | `swarmhq` | database user |
| `POSTGRES_PASSWORD` | `changeme` | database password — change it, even locally |
| `POSTGRES_PORT` | `5432` | host port mapped to Postgres |
| `MQTT_PORT` | `1883` | host port mapped to Mosquitto MQTT |
| `MQTT_WS_PORT` | `9001` | host port mapped to Mosquitto MQTT-over-WebSocket |

## Known limitations (tracked in the roadmap, not bugs)

- **Mosquitto allows anonymous connections.** `infra/mosquitto/config/mosquitto.conf`
  has `allow_anonymous true` — intentional for this early sprint, replaced by
  TLS + per-client authentication in a later sprint (see
  [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md), "Security hardening").
- No Spring Boot backend yet (Sprint 2+) — this sprint only stands up the
  broker and database.

## Troubleshooting

- **Port already in use** (5432 / 1883 / 9001): another local service (e.g.
  a native PostgreSQL or Mosquitto install) is likely bound to the same
  port. Either stop it or override the port via the corresponding `.env`
  variable.
- **`docker compose up` fails pulling images**: confirm Docker Desktop /
  the Docker daemon is running.
- **Postgres container unhealthy**: check `docker compose logs postgis` —
  usually a credential mismatch between an existing volume and a changed
  `.env`. Recreate the volume with `docker compose down -v` if the data in
  it is disposable.
