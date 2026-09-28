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
| Dashboard (nginx) | http://localhost:3000 | `frontend:80` |
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
  returns `{"sent":50,"duplicates":2,"invalid":1}` once Kafka confirms every
  event.
  `count` is 1-1000 (default 1). An invalid value returns a 400 problem
  response, and a Kafka outage returns 503.

Severity and status are weighted towards realistic values (mostly `INFO` and
`OPEN`). A share of sends (`PRODUCER_DUPLICATE_RATIO`) re-sends an earlier event
unchanged, with the same `eventId` and payload, to exercise idempotent
processing downstream.

Another share (`PRODUCER_INVALID_RATIO`) is broken on purpose, to show the
backend's [dead-letter handling](#retries-and-the-dead-letter-topic). Each one
is truncated JSON, an unknown `severity`, a blank `service`, or a missing
`eventId`. Invalid messages count towards `sent` and are never re-sent as
duplicates.

| Variable | Default | Meaning |
|---|---|---|
| `PRODUCER_INTERVAL_MS` | `2000` | Delay between auto-mode events (min 100) |
| `PRODUCER_DUPLICATE_RATIO` | `0.05` | Share of sends that re-send a recent event (0-1) |
| `PRODUCER_INVALID_RATIO` | `0.02` | Share of sends that are intentionally invalid (0-1) |
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
- `eventId` has a character other than letters, digits, `.`, `_`, `:` and `-`,
  or starts with `.`, so every stored event can be opened at
  `/api/events/{eventId}` (for example `EVT/1` or `..` is invalid)
- `source` is not exactly one of `ATS`, `CBTC`, `SCADA`, `TMS`, `PIS`
- PostgreSQL rejects the data anyway

### Retries and the dead-letter topic

The backend declares `incident-events.DLT` (3 partitions, 7-day retention).
Failed records end up there instead of being dropped or blocking their
partition:

- **Invalid messages** go to the DLT straight away, without retrying.
- **Any other failure**, such as PostgreSQL or Redis being down, is retried
  with exponential back-off: 1s, 2s, 4s, 8s, 16s, then 30s. After 8 retries
  (about 2 minutes) the record goes to the DLT, and the partition moves on.

Each dead-lettered record keeps its key and partition. A message that was not
readable JSON keeps its original bytes; any other message is written as event
JSON. Spring's `kafka_dlt-*` headers record the original topic, partition,
offset and the exception. The offset is committed only once Kafka confirms the
DLT write. If that write fails, the record is attempted again.

Every failed attempt is logged with a short reason, never with payload values,
and so is every record sent to the DLT (`Sent event at <topic-partition@offset>
to incident-events.DLT: <reason>`).

Known limitations:

- A valid event that is dead-lettered after an outage longer than the retry
  window is not in the dashboard. It is kept in the DLT, and because ingestion
  is idempotent it can be republished to `incident-events` safely. There is no
  replay tool yet.
- Until Redis resilience (feature 14), a Redis outage longer than the retry
  window can leave an event stored in PostgreSQL but sent to the DLT, so the
  Redis counters miss it until they are rebuilt.

Inspect stored events:

```bash
docker compose exec postgres psql -U rail_ops -d incidents   -c "select event_id, source, service, severity, status, timestamp from events order by timestamp desc limit 10;"
```

Publish an invalid message by hand and watch it reach the DLT (in Git Bash,
prefix each command with `MSYS_NO_PATHCONV=1`):

```bash
echo '{not json' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh   --bootstrap-server kafka:29092 --topic incident-events
docker compose logs backend | grep "incident-events.DLT"
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh   --bootstrap-server kafka:29092 --topic incident-events.DLT --from-beginning   --property print.headers=true --timeout-ms 5000
```

Or open Kafka UI (http://localhost:8081) and go to
**Topics > incident-events.DLT > Messages**.

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
| `cache:dashboard:summary` | String | the dashboard summary JSON, 5s TTL (see [Dashboard data APIs](#dashboard-data-apis)) |
| `cache:dashboard:summary:version` | String | counter bumped by every applied status change; a summary is cached only if it is unchanged |

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

## Incident status update

`PUT /api/events/{eventId}/status` moves an incident through its lifecycle:

| From | Allowed to |
|---|---|
| `OPEN` | `ACKNOWLEDGED`, `RESOLVED` |
| `ACKNOWLEDGED` | `RESOLVED` |
| `RESOLVED` | `OPEN` (reopen) |

```bash
curl -X PUT "http://localhost:8080/api/events/EVT-10001/status" \
  -H "Content-Type: application/json" -d '{"status":"ACKNOWLEDGED"}'
```

A successful change returns 200 with the updated event (same shape as
`GET /api/events/{eventId}`, with a new `updatedAt`). Sending the current
status also returns 200 and changes nothing.

| Case | Response |
|---|---|
| Transition not in the table | 409, `type: /problems/invalid-status-transition`, with `allowedTransitions` |
| Another request changed the event at the same time | 409, title `Concurrent update`; reload and retry |
| Unknown `eventId` | 404 |
| Missing, unknown or numeric `status`, or a body that is not JSON | 400 naming `status` and its allowed values |

```json
{
  "type": "/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "detail": "Cannot change status from RESOLVED to ACKNOWLEDGED",
  "instance": "/api/events/EVT-10001/status",
  "allowedTransitions": ["OPEN"]
}
```

The change is committed to PostgreSQL first. Clients send no version: JPA
optimistic locking (`@Version`) detects overlapping writes on the server. After
the commit, the Lua script `apply-status-change` updates the Redis live state
in one step: the `status:*:count` and `active:{SEV}:count` counters, and the
service's `active:{SEV}`, `openCount`, `activeCount` and health. Event totals,
the timeline, the recent list and `lastEventTime` are not touched, because a
status change is not a new event.

Known limitations until the reconciler (feature 14) lands:

- If Redis is unavailable after the commit, the request still succeeds and
  the backend logs a warning. Redis stays stale for that event until it is
  rebuilt.
- A status change to an event that ingestion has stored but not yet applied
  to Redis can leave that event's counts stale. This happens when it is the
  service's first event in Redis, or while ingestion is retrying after a Redis
  outage. Normally the window is milliseconds.

## Dashboard data APIs

Read endpoints for the dashboard and service status pages. They read the Redis
live state, and the recent events also load their rows from PostgreSQL.

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/dashboard/summary` | totals, severity distribution and service health; cached up to 5s |
| `GET` | `/api/services` | each service's live state, sorted by name |
| `GET` | `/api/dashboard/timeline?minutes=60` | events per UTC minute by severity; `minutes` 1-120, default 60 |
| `GET` | `/api/dashboard/recent-events?limit=20` | the most recently processed events, newest first; `limit` 1-50, default 20 |

```bash
curl "http://localhost:8080/api/dashboard/summary"
curl "http://localhost:8080/api/services"
curl "http://localhost:8080/api/dashboard/timeline?minutes=15"
curl "http://localhost:8080/api/dashboard/recent-events?limit=5"
```

Summary:

```json
{
  "totalEvents": 214,
  "openEvents": 120,
  "acknowledgedEvents": 30,
  "criticalEvents": 12,
  "severityDistribution": {"INFO": 90, "WARNING": 70, "MAJOR": 40, "CRITICAL": 14},
  "services": [
    {"name": "signal-service", "status": "DOWN", "lastEventTime": "2026-09-27T12:30:05.123Z"}
  ]
}
```

`criticalEvents` counts CRITICAL events that are not RESOLVED;
`severityDistribution` counts all events. Service `status` is `HEALTHY`,
`DEGRADED` (an active MAJOR or WARNING incident) or `DOWN` (an active CRITICAL
incident).

`/api/services` items add `latestSeverity`, `openCount` and `activeCount` to
the summary's service fields. Timeline items look like
`{"minute":"2026-09-27T12:30:00Z","counts":{"INFO":0,"WARNING":2,"MAJOR":0,"CRITICAL":1}}`:
one per minute, oldest first, ending with the current minute, with zeros for
minutes without events. Recent events use the event shape of
`GET /api/events/{eventId}`.

The summary is cached in `cache:dashboard:summary` for 5 seconds. A status
change deletes it in the same Lua script that updates the counters, so the next
read shows the change. The script also bumps `cache:dashboard:summary:version`,
and a summary is only cached (by the `cache-summary` Lua script) when that
version is unchanged since the summary's counters were read, so a summary built
while a status change lands is never cached. New events appear once the cache
expires. An
out-of-range or non-numeric `minutes` or `limit` returns 400 naming the
parameter.

Known limitation until Redis resilience (feature 14): if Redis is unavailable,
these endpoints return 500 instead of falling back to PostgreSQL.

## Real-time push

The backend pushes changes over STOMP on a native WebSocket at `/ws`
(`ws://localhost:8080/ws`, or `/ws` through the frontend on port 3000 and the
Vite dev server, which both proxy it). Clients only subscribe; a `SEND` frame
gets an `ERROR` frame and the connection is closed. Browsers must connect from
the same origin as the page. The broker sends heart-beats every 10 seconds.

| Topic | When | Body |
|---|---|---|
| `/topic/events` | an event is ingested for the first time, or its status changes | `{"type":"CREATED"\|"UPDATED","event":{...}}` |
| `/topic/summary` | at most once a second, only after a change | the `GET /api/dashboard/summary` body |

`event` has the shape of `GET /api/events/{eventId}`. A duplicate or invalid
event, and a status request that changes nothing or fails, push nothing. The
pushed summary is built from the live counters rather than the 5-second cache,
so it includes new events straight away.

Delivery is best effort: nothing is replayed after a reconnect, there is no
message on subscribe, and events from different Kafka partitions can arrive
in any order. Clients load state over the REST API and use pushes to stay
current. A failed push is logged and never fails ingestion or a status update.

To see the handshake through nginx with the stack running:

```bash
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket"   -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="   -H "Origin: http://localhost:3000" http://localhost:3000/ws
```

It answers `101 Switching Protocols`; with another `Origin` it answers `403`.

## Frontend

The dashboard is a React + TypeScript + Vite app (React Router, TanStack
Query, MUI, Recharts). With the compose stack running, open
http://localhost:3000: nginx serves the built app and proxies `/api` to the
backend, and any other path loads the app, so routes such as `/dashboard` can
be reloaded or linked directly.

| Route | Page |
|---|---|
| `/` | redirects to `/dashboard` |
| `/dashboard` | KPI cards, service health, severity and events-over-time charts, recent events |
| `/events` | server-paginated events table with filters and search in the query string |
| `/events/:eventId` | the events page with that event's detail drawer open |
| `/services` | per-service health, open and active incident counts, latest severity, last event |

The dashboard polls the dashboard APIs every 5 seconds (the summary cache
lifetime); polling pauses while the browser tab is hidden. If the backend is
unreachable, each section keeps its last data or shows its error state, and a
single "Can't reach the backend - retrying" message appears on the first failed
request (within a few seconds) and stays open until the API answers again. The top bar has a Light / Dark / System theme switch; the
choice is remembered in the browser.

The events page keeps its state in the URL, so any view can be reloaded,
bookmarked or shared: severity, status, source and service filters, the search
text `q` (matched case-insensitively against message, service and event ID,
applied 300 ms after typing stops) and a 1-based `page`, for example
`/events?severity=CRITICAL&status=OPEN&q=signal&page=2`. Changing a filter goes
back to page 1. Clicking a row or its event ID opens the detail drawer at
`/events/<eventId>` with the same query string; closing it returns to the
filtered table, and an unknown ID shows "Event not found". The drawer offers
only the status changes the lifecycle allows (Acknowledge, Resolve, Reopen).
The new status shows at once; if the backend rejects the change (for example a
409 for an invalid transition or an overlapping update) it rolls back and the
reason appears in an error message. The table and drawer poll every 5 seconds
like the dashboard, pausing while a status change is in flight.

The services page lists every service that has reported, sorted by name, with
its health, open incidents (the main column), active incidents (open or
acknowledged), latest severity and last event time, refreshed every 5 seconds.
Each service name opens the events page filtered to that service. Before any
event arrives it says so instead of showing an empty table.

For development, run the Vite dev server against the backend on the host
(`BACKEND_PORT`, default 8080):

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173, proxies /api to the backend
npm test        # Vitest + React Testing Library
npm run lint
npm run build   # typecheck + production bundle
```
