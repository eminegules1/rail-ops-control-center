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
- `backend/` - Spring Boot service that consumes events from Kafka, stores them in PostgreSQL, keeps live state in Redis and serves the REST API
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
| Backend | http://localhost:8080 | `backend:8080` |
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

## Event ingestion (backend)

`backend/` is a Spring Boot service that consumes `incident-events` in the
consumer group `incident-processor` (3 listener threads, one per partition) and
stores every valid event in the PostgreSQL table `events`. Flyway creates the
schema on startup.

Processing is at-least-once and idempotent. For each record the backend:

1. validates the payload,
2. runs `INSERT ... ON CONFLICT (event_id) DO NOTHING`,
3. applies the event once to the Redis live state (see
   [Live service state](#live-service-state-redis)),
4. commits the Kafka offset only after both have succeeded.

A redelivered or duplicated event (the producer re-sends some on purpose) is
therefore stored once. The first stored copy wins, and the backend logs
`Duplicate event <id> skipped`.

A message is **invalid** when any of these apply:

- it is not readable JSON, or `severity`/`status`/`timestamp` has an unknown
  value (enum values are case-sensitive names, never numbers; `timestamp` is an
  ISO-8601 string, never an epoch number)
- `timestamp` is before 2000-01-01 or in year 10000 or later
- `eventId`, `service` or `message` is blank, or `eventId`/`service` is longer
  than 64 characters
- `source` is not exactly one of `ATS`, `CBTC`, `SCADA`, `TMS`, `PIS`
- PostgreSQL rejects the data anyway

Invalid messages are logged with their partition and offset and skipped, and
their offset is committed. For now they are only logged; a later feature sends
them to a dead-letter topic. Any other failure, such as PostgreSQL being down,
is retried every 2 seconds until it succeeds, so valid events are never
dropped. The same applies while Redis is down: the event is already stored in
PostgreSQL, and the Redis step is retried until Redis is back.

Inspect stored events:

```bash
docker compose exec postgres psql -U rail_ops -d incidents   -c "select event_id, source, service, severity, status, timestamp from events order by timestamp desc limit 10;"
```

Publish an invalid message by hand and watch the backend skip it (in Git Bash,
prefix the command with `MSYS_NO_PATHCONV=1`):

```bash
echo '{not json' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh   --bootstrap-server kafka:29092 --topic incident-events
docker compose logs backend | grep "Skipping invalid"
```

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_PORT` | `8080` | Host port for the backend (`/actuator/health`) |

If port 8080 is already taken on your machine, set `BACKEND_PORT` in `.env`.

Build and test the module from the repository root with JDK 21 and Maven 3.9.
The tests start Kafka, PostgreSQL and Redis with Testcontainers, so Docker
Desktop must be running:

```bash
mvn -pl backend -am verify
```

To run it from an IDE against the compose stack, start the stack and run
`BackendApplication`. It connects to `localhost:9092` and
`localhost:5432/incidents` and Redis on `localhost:6379` by default.

### Live service state (Redis)

After storing an event, the backend runs one Lua script, `apply-event`
(`backend/src/main/resources/redis/apply-event.lua`), that updates all live
state atomically. It first sets `processed:{eventId}` (`SET NX`, 24h TTL, the
same as the topic retention); if that key already exists the event was already
applied and nothing else changes, so redelivered and duplicate events are
counted once.

| Key | Type | Content |
|---|---|---|
| `events:count` | String | all events |
| `severity:{SEV}:count` | String | all events per severity |
| `status:{STATUS}:count` | String | events per initial status |
| `active:{SEV}:count` | String | active (`OPEN` or `ACKNOWLEDGED`) events per severity |
| `services` | Set | known service names |
| `service:{name}` | Hash | `active:INFO`/`WARNING`/`MAJOR`/`CRITICAL`, `openCount`, `activeCount`, `status`, `lastEventTime`, `latestSeverity` |
| `timeline:{yyyyMMddHHmm}` | Hash | events per severity in that UTC minute of the event `timestamp`; expires 2h after the minute |
| `recent:events` | List | the 50 most recently applied event ids, newest first |
| `processed:{eventId}` | String | apply-once guard, 24h TTL |

A service's `status` is recalculated from its active counts on every event:
any active `CRITICAL` makes it `DOWN`, otherwise any active `MAJOR` or
`WARNING` makes it `DEGRADED`, otherwise it is `HEALTHY`. `lastEventTime` and
`latestSeverity` follow the newest event `timestamp`, not arrival order.
Events older than the 2-hour timeline window do not create a timeline bucket.

Events stored before Redis state existed (for example from an older run of the
stack) are not in Redis yet; a later feature rebuilds Redis from PostgreSQL.
Until then, start from a clean slate with `docker compose down -v`.

Inspect the live state:

```bash
docker compose exec redis redis-cli GET events:count
docker compose exec redis redis-cli SMEMBERS services
docker compose exec redis redis-cli HGETALL service:signal-service
docker compose exec redis redis-cli LRANGE recent:events 0 9
```

## Events API

The backend serves stored events from PostgreSQL. Interactive docs are at
http://localhost:8080/swagger-ui/index.html (OpenAPI JSON at `/v3/api-docs`).

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/events` | a page of events, newest first by default |
| `GET` | `/api/events/{eventId}` | one event, or 404 |

Query parameters for `/api/events` (all optional, combined with AND):

| Parameter | Meaning |
|---|---|
| `severity` | `INFO`, `WARNING`, `MAJOR` or `CRITICAL` |
| `status` | `OPEN`, `ACKNOWLEDGED` or `RESOLVED` |
| `source`, `service` | exact, case-sensitive match |
| `q` | case-insensitive text in `message`, `service` or `eventId` (at most 200 characters) |
| `page` | zero-based page number, default 0 |
| `size` | 1 to 100, default 20 |
| `sort` | `field` or `field,asc\|desc` with `field` one of `timestamp`, `receivedAt`, `service`, `source`, `eventId`; default `timestamp,desc` |

Severity and status are not sortable because they are stored as text and would
sort alphabetically rather than by rank.

A list response looks like this:

```json
{
  "content": [
    {
      "eventId": "EVT-3f1c...",
      "source": "CBTC",
      "service": "signal-service",
      "severity": "CRITICAL",
      "message": "Signal failure at ...",
      "status": "OPEN",
      "timestamp": "2026-09-26T14:30:05.123Z",
      "receivedAt": "2026-09-26T14:30:05.456Z",
      "updatedAt": "2026-09-26T14:30:05.456Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7
}
```

Errors use RFC 7807 problem details (`application/problem+json`): 404 for an
unknown event id, 400 for an invalid parameter value, and 500 with a generic
message for anything unexpected (details stay in the backend log).

```bash
curl "http://localhost:8080/api/events?severity=CRITICAL&status=OPEN&q=signal&size=5"
curl "http://localhost:8080/api/events/EVT-10001"
```

## Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173
npm run build
npm run lint
```
