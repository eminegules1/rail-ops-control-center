# Alstom Rail Operations Control Center - Project Overview

<!-- blueprint:source-hash 5d0599304851c2ffde9d41b06ebbe5566e553d664ff94abfd3d58116ddd86a90 -->

> Real-time railway operations and mobility incident monitoring: Kafka event
> pipeline, Redis live state, PostgreSQL history, and a live React dashboard.

## Problem

A rail operations center receives a continuous stream of events and incidents
from many services (ATS, CBTC, SCADA, TMS, PIS). Operators need one live view of
every service's health, aggregated counts by severity and status, the recent
event flow, and a way to move incidents through OPEN -> ACKNOWLEDGED -> RESOLVED.

It is also a 7-day technical assignment for Alstom's Full Stack Software
Designer role, scored on a 100-point rubric plus 20 bonus points. Every design
choice should map to a rubric item and show production-minded engineering.

## Users

- **Operators** - watch the dashboard, filter events, acknowledge and resolve
  incidents.
- **Assignment reviewers (Alstom engineers)** - the real audience. They clone,
  run one command, read the README and code, and score it. Startup must be
  trivial and the design easy to explain.

No auth in the MVP; role-based login (ADMIN changes status, VIEWER read-only) is
stretch feature 18.

## Usage model

- Local, single-machine demo evaluated by trusted reviewers; not internet-facing.
- Hard deadline: 7 days from receiving the assignment.
- Mandatory acceptance: working producer and consumer, meaningful Redis use,
  React dashboard, REST API, Docker Compose, sufficient README, event filtering,
  status updates.
- Dev machine is Windows 10 + Docker Desktop; a clean clone must start with one
  command.
- Rule: never start a stretch item while any MVP item is unchecked.

## Features

MVP, in build order. The headline is the end-to-end live pipeline: producer ->
Kafka -> processor -> Postgres/Redis -> REST + WebSocket -> dashboard.

1. **Event producer** - Spring Boot app publishing JSON events (auto interval,
   `POST /produce?count=N`, ~200-event startup seed), plus the Kafka, Kafka UI,
   Redis, and Postgres compose stack.
2. **Event ingestion** - consumer group `incident-processor` validates events
   and stores them idempotently in Postgres.
3. **Retry and dead-letter handling** - exponential backoff, non-retryable
   validation/deserialization errors, DLT publishing, `PRODUCER_INVALID_RATIO`
   demo.
4. **Live service state in Redis** - service hashes, counters, bounded
   recent-events list, idempotency keys.
5. **Events API** - filtered, searchable, paginated list and detail, ProblemDetail
   errors, Swagger.
6. **Incident status update** - `PUT` status that updates Postgres and atomically
   adjusts Redis counters and service health.
7. **Dashboard summary and services API** - cached summary, services endpoint,
   Redis circuit breaker with Postgres fallback, startup reconciler.
8. **Real-time push** - STOMP broadcasts of created/updated events and throttled
   summary.
9. **Dashboard page** - KPI cards, service health grid, severity and
   events-over-time charts, live recent events.
10. **Events page** - paginated table, filters, debounced search, detail drawer,
    optimistic status change with rollback.
11. **Service status page** - per-service health, last event time, latest
    severity, open count.
12. **Live UI updates** - WebSocket patches TanStack Query caches, connection
    chip, reconnect, 10s polling fallback.
13. **Observability** - JSON logs with `eventId` in MDC, Actuator health,
    Prometheus counters for processed/invalid/DLT.
14. **Automated tests** - JUnit 5 + Mockito, Testcontainers happy-path and DLT,
    Vitest + RTL.
15. **One-command local startup** - containerized backend/producer/frontend,
    healthchecks, env vars, clean-clone check.
16. **CI pipeline** - GitHub Actions building and testing all three modules.
17. **Delivery documentation** - README, Mermaid architecture, API docs,
    Redis/Kafka design, screenshots/video, limitations, rubric mapping table.

Stretch (value order, only after MVP): 18 role-based JWT login, 19 OpenTelemetry
+ Jaeger, 20 CD to GHCR, 21 Kubernetes/Helm, 22 performance notes with a load
test, 23 AI incident assistant (off without an API key).

## Data model

PostgreSQL is the source of truth; Redis holds derived live state that can be
rebuilt from Postgres.

### Enums

- `Severity` - `INFO`, `WARNING`, `MAJOR`, `CRITICAL` (producer weights severity)
- `EventStatus` - `OPEN`, `ACKNOWLEDGED`, `RESOLVED`
- `ServiceHealth` (derived) - `HEALTHY`, `DEGRADED`, `DOWN`
- Sources - `ATS`, `CBTC`, `SCADA`, `TMS`, `PIS`

### Event (Postgres `events`, Flyway migration)

- `eventId` (varchar, unique) - e.g. `EVT-10001`; idempotency key
- `source` (varchar) - one of the sources above
- `service` (varchar) - e.g. `route-service`, `signal-service`, `train-tracking`
- `severity` (enum `Severity`)
- `message` (text) - human-readable
- `status` (enum `EventStatus`) - changed only via the status endpoint
- `timestamp` (timestamptz) - event creation time, ISO 8601 on the wire
- `receivedAt` (timestamptz) - when the processor stored it
- `updatedAt` (timestamptz) - last status change
- Indexes: `severity`, `status`, `source`, `service`, `timestamp`

> Locked by features 2-7: the Kafka payload, JPA entity, and API DTOs all share
> these field names.

### Kafka message (`incident-events`)

- JSON with `eventId`, `source`, `service`, `severity`, `message`, `status`,
  `timestamp`; validated with Bean Validation
- Key = `service` so each service's events stay ordered; 3 partitions
- `incident-events.DLT` receives invalid and exhausted-retry messages
- Topic names and partition counts come from config and are declared in code

### Redis keys

| Key | Type | Purpose |
|---|---|---|
| `service:{name}:state` | Hash | `status`, `lastEventTime`, `latestSeverity`, `openCount` |
| `services` | Set | known service names |
| `count:total` | String (INCR) | total events |
| `count:severity:{SEV}` | String (INCR) | per-severity counts |
| `count:status:{STATUS}` | String (INCR) | per-status counts |
| `recent:events` | List | `LPUSH` + `LTRIM` to 50 |
| `cache:dashboard:summary` | String (JSON) | 5s TTL read-through cache |
| `processed:{eventId}` | String | `SETNX`, 24h TTL idempotency guard |

Health rule: any open `CRITICAL` -> `DOWN`; any open `MAJOR`/`WARNING` ->
`DEGRADED`; otherwise `HEALTHY`.

### API shapes (DTO records)

- **Summary** - `totalEvents`, `openEvents`, `criticalEvents`, severity
  distribution, `services[]`
- **Service** - name, status (health), `lastEventTime`, `latestSeverity`,
  `openCount`
- **Status update request** - target `status`
- **Errors** - RFC 7807 `ProblemDetail`

## API and real-time

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/events` | filters `severity`, `status`, `source`, `service`, `q`; `page`/`size`/`sort` |
| `GET` | `/api/events/{eventId}` | detail |
| `PUT` | `/api/events/{eventId}/status` | Postgres update, atomic Redis adjust, evict summary cache, broadcast |
| `GET` | `/api/dashboard/summary` | cached summary |
| `GET` | `/api/services` | per-service state |
| `POST` | `/produce?count=N` | producer app: manual burst |

- STOMP over WebSocket at `/ws`: `/topic/events` (created/updated),
  `/topic/summary` (throttled to at most 1/s)
- Swagger UI via springdoc; Actuator health and Prometheus endpoint

## Tech stack

- **Java 21, Spring Boot 3** - backend and producer, Maven multi-module
- **spring-kafka** - consumer (concurrency 3, manual ack), `ErrorHandlingDeserializer`,
  `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
- **Spring Data JPA + Flyway, PostgreSQL 16** - source of truth
- **Spring Data Redis (Lettuce), Redis 7** - live state, counters, cache, idempotency
- **Resilience4j** - circuit breaker around Redis
- **Spring WebSocket (STOMP)** - real-time push
- **springdoc-openapi** - Swagger UI
- **Micrometer + Actuator, logstash-logback-encoder** - metrics and JSON logs
- **React 19 + TypeScript + Vite** (`frontend/`) - SPA
- **React Router, TanStack Query, MUI, Recharts, @stomp/stompjs** - routing,
  server state, UI, charts, WebSocket client
- **Kafka (KRaft, single broker), Kafka UI, nginx** - infra; nginx serves the
  frontend and proxies `/api` and `/ws`
- **JUnit 5, Mockito, Testcontainers, Vitest, React Testing Library** - tests

Repo layout: `backend/`, `producer/`, `frontend/`, `docs/`, `blueprint/`,
`docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`. Only
`frontend/` exists today.

## Monetization

Not applicable: a hiring assignment. Success is passing every mandatory
acceptance criterion and scoring as high as possible (target 100 + most bonus).

## UI/UX

Control-room style: dense but calm, scannable in seconds. Left nav (Dashboard /
Events / Services), top bar with the short label "Rail Ops Control Center" and a
live/reconnecting/offline connection chip.

- **Dashboard** - KPI cards (total/open/critical), service health grid, severity
  distribution chart, events-over-time chart, live recent events
- **Events** - server-paginated table, severity/status/source filters, debounced
  search, detail drawer, optimistic status change
- **Services** - status, last event time, latest severity, open count per service

Colors are consistent everywhere: severity INFO blue, WARNING amber, MAJOR
orange, CRITICAL red; health HEALTHY green, DEGRADED amber, DOWN red. New events
animate in gently, no full-page reloads. Loading skeletons, empty states, error
toasts. Dark mode is nice to have.

> TODO: route paths for the three pages are not specified.

## Deployment

- **Target:** local only, `docker compose up --build` on a clean clone
- **Services and ports:** kafka, kafka-ui (:8081), redis, postgres, backend
  (:8080), producer, frontend via nginx (:3000)
- **Startup:** healthchecks with `depends_on: service_healthy`
- **Config:** env vars documented in `.env.example`, including
  `PRODUCER_INTERVAL_MS` and `PRODUCER_INVALID_RATIO`
- **Delivery:** public GitHub repo with meaningful commits across the week
- **CI (GitHub Actions):** `mvn verify` (backend, producer),
  `npm ci && npm test && npm run build` (frontend), `docker compose build`
- **Deliverables:** README, architecture explanation + diagram, API docs
  (Swagger + `docs/api.md`), screenshots or demo video, known limitations

## Open questions

> Gaps found in the plans. Resolve them in the plans, then re-run `/overview`.

1. **Events-over-time chart has no data source.** No endpoint or Redis key
   returns time-bucketed counts. Decide between a summary field, a new endpoint,
   or client-side bucketing of fetched events.
2. **Dashboard recent-events list has no endpoint.** `recent:events` exists in
   Redis, but no API exposes it; `GET /api/events` sorted by `timestamp` may be
   the intended source.
3. **Service health cannot be recomputed from the hash alone.** After a CRITICAL
   is resolved, deciding DOWN vs DEGRADED needs per-service open counts by
   severity, but `service:{name}:state` stores only `openCount`.
4. **`eventId` uniqueness across producer restarts.** A counter-style
   `EVT-10001` that resets on restart would collide, and the collision would be
   dropped as a duplicate. Specify how ids are generated.
5. **Idempotency ordering.** If `SETNX processed:{id}` succeeds but the Postgres
   write fails, the retry is skipped as a duplicate and the event is lost. Define
   whether Postgres uniqueness is the authority and when the Redis key is set.
6. **Status transitions.** Allowed moves are not defined (backward moves,
   reopening, same-status updates) nor the error returned for invalid ones.
7. **`criticalEvents` meaning.** All CRITICAL events, or only open ones.
8. **Tests arrive late.** Feature 14 adds the test runners, so features 1-13 run
   without a test gate. Consider running `/tests` (frontend) and adding the
   Maven test setup with the first backend feature, leaving feature 14 for the
   Testcontainers and component suites.
9. **Feature 1 bundles two concerns.** The producer app and the whole
   infrastructure compose stack (plus the Maven parent POM) share one item; a
   larger first step than the rest.
