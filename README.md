# Alstom Rail Operations Control Center

**Real-time railway operations and mobility incident monitoring**

> A technical assignment for Alstom's Full Stack Software Designer role; not an
> official Alstom product.

Real-time incident monitoring for a rail operations center: a producer publishes
service events to Kafka, a Spring Boot backend processes them into PostgreSQL and
Redis live state, and a React dashboard shows service health and incidents live.

> Work in progress. Full setup, architecture, and API docs will land here.

## Repository layout

- `frontend/` - React + TypeScript + Vite dashboard
- `producer/` - Spring Boot app that publishes simulated incident events to Kafka
- `backend/` - Spring Boot processing and API service (planned)
- `pom.xml` - Maven parent for the Java modules
- `docker-compose.yml` - local stack

## Local infrastructure

**Prerequisite:** Docker Desktop running (`docker info` succeeds).

From the repository root:

```bash
docker compose up -d --build --wait   # build app images, start everything, wait until healthy
docker compose ps             # every service should show (healthy)
docker compose logs -f kafka  # follow one service's logs
docker compose down           # stop, keeping data
docker compose down -v        # stop and DELETE all data (Kafka, Redis, Postgres)
```

| Service | From the host | From other containers |
|---|---|---|
| Kafka | `localhost:9092` | `kafka:29092` |
| Kafka UI | http://localhost:8081 | - |
| Producer | http://localhost:8082 | `producer:8080` |
| Redis | `localhost:6379` | `redis:6379` |
| PostgreSQL | `localhost:5432`, database `incidents` | `postgres:5432` |

Kafka runs in KRaft mode (no ZooKeeper) with automatic topic creation turned
off, so topics exist only when the application declares them with their
intended partitions and retention. Redis uses append-only persistence, so its
live counters survive restarts together with the Postgres data.

### Configuration and credentials

Every setting has a default in `docker-compose.yml`, so the stack starts
without a `.env` file. To override ports or credentials, copy `.env.example` to
`.env` and edit it. The database user `rail_ops` / `rail_ops_local_only` is a
local demo default only; applications read credentials from the same
environment variables and never hardcode them. A production deployment would
take credentials from a secrets manager or Docker secrets and would not publish
these ports. All ports are bound to `127.0.0.1`.

## Event producer

`producer/` is a small Spring Boot app that simulates rail control systems
(ATS, CBTC, SCADA, TMS, PIS). It publishes JSON incident events to the Kafka
topic `incident-events`, keyed by service so each service's events stay in
order on one partition. It declares the topic itself (3 partitions, 24h
retention), because the broker does not auto-create topics.

```json
{"eventId":"EVT-3f1c2a9e-8b7d-4e21-9c55-0a6b1d2e3f40","source":"CBTC","service":"signal-service","severity":"CRITICAL","message":"Signal SG-14 failed to clear","status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}
```

It sends events in three ways:

- **Seed burst:** about 200 events at startup, timestamped across the last
  hour, so the dashboard is never empty. Every restart seeds again.
- **Auto mode:** one event every `PRODUCER_INTERVAL_MS`.
- **Manual burst:** `curl -X POST "http://localhost:8082/produce?count=50"`
  returns `{"sent":50,"duplicates":2}` once Kafka confirms every event.
  `count` is 1-1000 (default 1). An invalid value returns a 400 problem
  response, and a Kafka outage returns 503.

Severity and status are weighted towards realistic values (mostly `INFO` and
`OPEN`). A share of sends (`PRODUCER_DUPLICATE_RATIO`) re-sends an earlier event
unchanged, with the same `eventId` and payload, to exercise idempotent
processing downstream.

| Variable | Default | Meaning |
|---|---|---|
| `PRODUCER_INTERVAL_MS` | `2000` | Delay between auto-mode events (min 100) |
| `PRODUCER_DUPLICATE_RATIO` | `0.05` | Share of sends that re-send a recent event (0-1) |
| `PRODUCER_PORT` | `8082` | Host port for `/produce` and `/actuator/health` |

To watch the events, open Kafka UI (http://localhost:8081) and go to
**Topics > incident-events > Messages**.

Build and test the module (JDK 21 and Maven 3.9 from the repository root):

```bash
mvn -pl producer -am verify
```

To run it from an IDE against the compose Kafka, start the stack and run
`ProducerApplication`. It connects to `localhost:9092` by default.

## Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173
npm run build
npm run lint
```
