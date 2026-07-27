# SwarmHQ

<p align="center">
  <img alt="STATUS" src="https://img.shields.io/badge/status-sprint%206%20of%209-informational?style=for-the-badge">
  <a href="HELP.md">
    <img alt="HELP" src="https://img.shields.io/badge/help-HELP.md-informational?style=for-the-badge">
  </a>
  <a href="PROJECT_OVERVIEW.md">
    <img alt="PROJECT OVERVIEW" src="https://img.shields.io/badge/docs-PROJECT_OVERVIEW-informational?style=for-the-badge">
  </a>
</p>

SwarmHQ is a fully simulated drone command-and-control (C2) system, built as
a software engineering portfolio project.

**No real hardware. No real data. No targeting or weapons capability of any
kind.** Every drone, mission, and telemetry stream is synthetic — the goal is
to demonstrate IoT messaging, geospatial data, real-time web, and multi-agent
coordination engineering, applicable equally to defense, logistics, or fleet
management domains.

---

## Highlights (target capabilities)

- **Real IoT telemetry**: drones publish position/battery/status over
  **MQTT** (pub/sub), the actual protocol used by real drone hardware — not
  simplified REST polling
- **Real geospatial storage**: positions persisted as **PostGIS** geometries,
  enabling native "which drones are in this zone" / "distance traveled"
  queries instead of hand-rolled math
- **Live tactical map**: MapLibre GL JS map with drones animating in real
  time over WebSocket, automatic alerts (low battery, out-of-zone, signal
  loss)
- **Automatic mission reporting**: active missions, success/failure rate, no
  manual paperwork
- **Genuine swarm behavior** *(planned)*: boids-style local coordination
  and/or auction-based distributed task allocation between drones,
  switchable against a centralized assignment engine — what the project's
  name refers to

<details>
<summary>Resumen en español (🇪🇸)</summary>

SwarmHQ es un sistema de mando y control (C2) de drones completamente
simulado, sin hardware ni datos reales. Es un proyecto de portfolio de
ingeniería de software: mensajería IoT (MQTT), datos geoespaciales
(PostGIS), tiempo real (WebSocket) y coordinación multi-agente (swarming).
No implementa ni implementará capacidades reales de targeting o armamento.

Para detalles técnicos y la hoja de ruta completa, consulta
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

</details>

---

## Tech Stack

- Java + Spring Boot (Spring Web, Spring Data JPA, Spring WebSocket/STOMP)
- PostgreSQL + PostGIS, Hibernate Spatial
- Eclipse Mosquitto (MQTT broker)
- Python + paho-mqtt (drone simulator)
- React 19 + TypeScript + Vite + Tailwind 4, MapLibre GL JS (frontend)
- Docker + Docker Compose

Full rationale for each choice in [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

---

## Status

**Sprint 6 of 9** — there's now something to look at in a browser: a React
+ MapLibre tactical map (`frontend/`) polling a new `GET /api/drones`
endpoint, showing every drone's last known position and status. See
[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) for the full sprint plan and
[HELP.md](HELP.md) for how to run it and known limitations.

---

## Docs

- Technical details, data model, full roadmap: [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md)
- Local run / troubleshooting: [HELP.md](HELP.md)

---

## Run (quick)

```bash
cp .env.example .env
docker compose up -d
```

That's infra only (Mosquitto + PostgreSQL/PostGIS). To also run the full
app (backend + map) in a container - no local JDK/Node required, and the
recommended way to run it at all if `./mvnw spring-boot:run` fails on your
machine:

```bash
docker compose up -d --build backend
```

Then open `http://localhost:8080/app`. See [HELP.md](HELP.md) for ports,
environment variables, and troubleshooting.

---

## Scope and ethical boundaries

This is a software engineering exercise in backend architecture, geospatial
data, real-time systems, and coordination algorithms. It deliberately does
**not** implement, and will not implement, anything related to real
targeting, weapons/strike chains, or any capability that could be repurposed
to cause real-world harm.

---

## License

This project is for educational and portfolio purposes.

---

## Contributing

- Keep changes small and focused, one sprint at a time
- Prefer service-layer rules (controllers stay thin)
- Add/adjust tests when behavior changes

---

## Author

Built and maintained by [Mango77x](https://github.com/Mango77x) as a
portfolio project.
