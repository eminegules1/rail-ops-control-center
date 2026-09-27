# Alstom Rail Operations Control Center - Project Overview

<!-- blueprint:source-hash 5df4ea771cce4986117dffa6cb49849c5e677a4ae91668c8d51969234532542a -->

> Real-time railway operations and mobility incident monitoring: Kafka event
> pipeline, Redis live state, PostgreSQL history, and a live React dashboard.

## Problem

A rail operations center receives a continuous stream of events and incidents
from many services (ATS, CBTC, SCADA, TMS, PIS). Operators need one live view of
every service's health, aggregated counts by severity and status, the recent
event flow, and a way to move incidents through their lifecycle.

It is also a 7-day technical assignment for an Alstom engineering role, scored
on a 100-point rubric plus up to 20 bonus points. Every design choice should map
to an assignment area and show production-minded engineering.

## Users

- **Operators** - watch the dashboard, filter events, acknowledge and resolve
  incidents.
- **Assignment reviewers (Alstom engineers)** - the real audience. They clone,
  run one command, read the README and code, and score it. Startup must be
  trivial and the design easy to explain.

## Usage model

- Local, single-machine demo evaluated by trusted reviewers; not internet-facing.
- No auth in the MVP (lightweight role-based login is stretch feature 20).
- Hard deadline: 7 days. Aim for a runnable end-to-end version (features 1-10)
  by about day 3.
- Mandatory acceptance: working producer and consumer, meaningful Redis use,
  React dashboard, REST API, Docker Compose, sufficient README, event filtering,
  status updates.
- Dev machine is Windows 10 + Docker Desktop; a clean clone must start with one
  command.
- The assignment brief is RESTRICTED: never commit it (it is gitignored) and
  never copy its text or rubric into the README or docs.
- Only build what can be explained in the interview; prefer the simpler design
  when it earns the same score.
- Never start a stretch item while any MVP item is unchecked. The MVP's extras
  already exceed the bonus cap, so stretch adds little score.

## Features

MVP, in build order. Every app feature ships its tests, its own Dockerfile and
compose service, and README updates, so `docker compose up --build` always runs
what exists.

1. **Local infrastructure** - Compose for Kafka (KRaft), Kafka UI, Redis,
   Postgres with healthchecks and `.env.example`.
2. **Event producer** - UUID ids, auto interval, `POST /produce?count=N`,
   ~200-event seed burst, duplicate ratio; Dockerfile and compose service.
3. **Event ingestion** - consumer group validates events, logs and skips invalid
   ones, stores idempotently in Postgres; backend test setup (JUnit 5, Mockito,
   Testcontainers); Dockerfile and compose service.
4. **Live service state in Redis** - Lua `apply-event`: counters, active
   per-severity health, open and active counts, timeline, recent list,
   apply-once guard.
5. **Events API** - filtered, searchable, paginated list and detail,
   ProblemDetail errors, Swagger.
6. **Incident status update** - lifecycle rules, 409 on invalid transitions,
   optimistic locking, Lua `apply-status-change`.
7. **Dashboard data APIs** - summary, services, timeline, recent-events, summary
   cache.
8. **Dashboard page** - app shell and routes, KPI cards, health grid, severity
   and events-over-time charts, recent events, polling refresh; Vitest + RTL;
   nginx frontend compose service.
9. **Events page** - paginated table, URL-synced filters and search,
   deep-linkable detail drawer, optimistic status change with rollback.
10. **Service status page** - health, last event time, latest severity, open and
    active counts.

Features 1-10 cover every mandatory criterion. Improvements follow:

11. **Retry and dead-letter handling** - exponential backoff, non-retryable
    validation errors, DLT publishing, invalid-message demo ratio.
12. **Real-time push** - STOMP broadcasts of created/updated events and throttled
    summary.
13. **Live UI updates** - WebSocket patches TanStack Query caches, connection
    chip, reconnect; polling becomes fallback only; rows that were just
    created or updated (including status changes) get a brief highlight.
14. **Redis resilience** - circuit breaker, Postgres fallback for dashboard reads,
    reconcile-needed flag, pause-and-rebuild reconciler.
15. **Observability** - JSON logs with `eventId` in MDC, Actuator health,
    Prometheus counters for processed/invalid/DLT.
16. **End-to-end test coverage** - Testcontainers flows (happy path, DLT,
    Redis-down fallback) and a coverage report.
17. **Clean-clone startup verification** - harden healthchecks, startup order,
    env defaults; verify one command on a clean clone.
18. **CI pipeline** - GitHub Actions building and testing all three modules.
19. **Delivery documentation** - final README, Mermaid architecture, API docs,
    Redis/Kafka and consumer-group notes, performance notes, screenshots/video,
    known limitations.

Stretch (value order, only after MVP): 20 lightweight JWT login (ADMIN/VIEWER
demo users, credentials in README), 21 OpenTelemetry + Jaeger, 22 CD to GHCR,
23 Kubernetes/Helm, 24 AI incident assistant (off without an API key).

## Data model

PostgreSQL is the source of truth. Redis holds derived live state that the
reconciler can always rebuild from Postgres.

### Enums and derived terms

- `Severity` - `INFO`, `WARNING`, `MAJOR`, `CRITICAL` (producer weights severity)
- `EventStatus` - `OPEN`, `ACKNOWLEDGED`, `RESOLVED`
- **Open** - status `OPEN`. **Active** - status `OPEN` or `ACKNOWLEDGED`.
- `ServiceHealth` - active `CRITICAL` > 0 -> `DOWN`; else active `MAJOR` or
  `WARNING` > 0 -> `DEGRADED`; else `HEALTHY`. Recomputed inside both Lua
  scripts, so it never drifts from the counts.
- Sources - `ATS`, `CBTC`, `SCADA`, `TMS`, `PIS`

### Event (Postgres `events`, Flyway migration)

- `eventId` (varchar, unique; column `event_id`) - producer uses `EVT-` + UUID;
  the consumer accepts any URL-safe id - letters, digits, `.` `_` `:` `-`, not
  starting with a dot (e.g. a hand-published `EVT-10001`); others are skipped as invalid
- `source` (varchar)
- `service` (varchar) - e.g. `route-service`, `signal-service`, `train-tracking`
- `severity` (enum `Severity`)
- `message` (text)
- `status` (enum `EventStatus`) - initial value from the Kafka payload; changed
  only via the status endpoint
- `timestamp` (timestamptz) - event creation time, ISO 8601 on the wire
- `receivedAt`, `updatedAt` (timestamptz) - processing audit
- `version` - JPA `@Version` for overlapping-write detection
- Indexes: `severity`, `status`, `source`, `service`, `timestamp`

> Locked by features 2-7: the Kafka payload, JPA entity, and API DTOs share
> these field names.

### Kafka

- `incident-events` - 3 partitions, key = `service` (per-service ordering), 24h
  retention (matches the apply-once TTL)
- `incident-events.DLT` - invalid and retry-exhausted messages (feature 11;
  before that, invalid messages are logged and skipped)
- Payload: `eventId`, `source`, `service`, `severity`, `message`, `status`,
  `timestamp`; Bean Validation
- Consumer group `incident-processor`, concurrency 3, manual ack
- Names, partitions, retention from config; topics declared in code

### Ingestion contract (at-least-once, idempotent)

1. `INSERT ... ON CONFLICT (event_id) DO NOTHING` in Postgres.
2. Lua `apply-event`: `SET processed:{id} NX EX 86400`; only on success, update
   counters, open/active counts, health, timeline, recent list atomically.
3. Ack the offset.

Every step is repeatable. If Redis is down (circuit open), the event is still
stored and acked and an in-memory "reconcile needed" flag is set.

### Redis keys

Counter naming follows the brief's suggested keys.

| Key | Type | Purpose |
|---|---|---|
| `service:{name}` | Hash | `status`, `lastEventTime`, `latestSeverity`, `active:{SEV}` x4, `openCount`, `activeCount` |
| `services` | Set | known service names |
| `events:count` | String (INCR) | total events |
| `severity:{SEV}:count` | String (INCR) | all events per severity |
| `status:{STATUS}:count` | String (INCR) | events per status |
| `active:{SEV}:count` | String (INCR) | active events per severity |
| `timeline:{yyyyMMddHHmm}` | Hash | per-minute counts by severity (HINCRBY), UTC, 2h TTL |
| `recent:events` | List | eventIds only; LPUSH + LTRIM 50 |
| `cache:dashboard:summary` | String (JSON) | 5s TTL read-through cache |
| `processed:{eventId}` | String | apply-once guard, 24h TTL |

All multi-key updates run as Lua scripts (`apply-event`,
`apply-status-change`).

### Incident lifecycle

| From | Allowed to |
|---|---|
| `OPEN` | `ACKNOWLEDGED`, `RESOLVED` |
| `ACKNOWLEDGED` | `RESOLVED` |
| `RESOLVED` | `OPEN` (reopen) |

- Same status -> 200, no change. Other transitions -> 409 ProblemDetail
  (`type: /problems/invalid-status-transition`, with `allowedTransitions`).
- Unknown status value -> 400. Unknown `eventId` -> 404. Overlapping
  server-side writes (`@Version`) -> 409; clients send no version.
- Commit to Postgres, then Lua `apply-status-change`. If Redis fails, set the
  reconcile flag (a same-status retry is a no-op and would never repair Redis).

### Reconciler

Runs at startup when keys are missing, and after Redis recovers when the flag is
set. It pauses the Kafka listener, holds a lock that status updates wait on,
rebuilds all derived keys from Postgres, then resumes.

## API and real-time

| Method | Path | Contract |
|---|---|---|
| `GET` | `/api/events` | filters `severity`, `status`, `source`, `service`, `q`; `page`/`size`/`sort` |
| `GET` | `/api/events/{eventId}` | detail; 404 if unknown |
| `PUT` | `/api/events/{eventId}/status` | lifecycle above; evicts summary cache, broadcasts |
| `GET` | `/api/dashboard/summary` | cached summary (fields below) |
| `GET` | `/api/services` | per-service `status`, `lastEventTime`, `latestSeverity`, `openCount`, `activeCount` |
| `GET` | `/api/dashboard/timeline?minutes=60` | per-minute counts by severity, bucketed by event `timestamp` (UTC); `minutes` 1-120 else 400 |
| `GET` | `/api/dashboard/recent-events?limit=20` | ids from `recent:events`, rows loaded from Postgres; `limit` 1-50 else 400 |
| `POST` | `/produce?count=N` | producer app: manual burst |

Summary fields (a superset of the brief's example): `totalEvents` (all),
`openEvents` (OPEN), `acknowledgedEvents` (ACKNOWLEDGED), `criticalEvents`
(CRITICAL, not RESOLVED), `severityDistribution` (all events by severity),
`services[]` (name, status, lastEventTime). Dashboard reads fall back to
Postgres when Redis is down.

- Errors: RFC 7807 `ProblemDetail`; DTOs are Java records
- STOMP over WebSocket at `/ws`: `/topic/events` (created/updated),
  `/topic/summary` (at most 1/s). Polling (`refetchInterval`) keeps pages live
  before this lands and whenever the socket is down.
- Swagger UI via springdoc; Actuator health and Prometheus endpoint

## Tech stack

- **Java 21, Spring Boot 3** - backend and producer, Maven multi-module
- **spring-kafka** - `ErrorHandlingDeserializer`, `DefaultErrorHandler` +
  `DeadLetterPublishingRecoverer`
- **Spring Data JPA + Flyway, PostgreSQL 16** - source of truth
- **Spring Data Redis (Lettuce), Redis 7** - live state via Lua scripts
- **Resilience4j** - circuit breaker around Redis
- **Spring WebSocket (STOMP)** - real-time push
- **springdoc-openapi, Micrometer + Actuator, logstash-logback-encoder** - API
  docs, metrics, JSON logs
- **React 19 + TypeScript + Vite** (`frontend/`) with React Router, TanStack
  Query, MUI, Recharts, @stomp/stompjs
- **Kafka (KRaft, single broker), Kafka UI, nginx** - nginx serves the frontend
  and proxies `/api` and `/ws`
- **JUnit 5, Mockito, Testcontainers, Vitest, React Testing Library** - tests

Repo layout: `backend/`, `producer/`, `frontend/`, `docs/`, `blueprint/`,
`docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`. Only
`frontend/` exists today.

## Monetization

Not applicable: a hiring assignment. Success is passing every mandatory
acceptance criterion and scoring as high as possible.

## UI/UX

Control-room style: dense but calm, scannable in seconds. Left nav (Dashboard /
Events / Services), top bar with the short label "Rail Ops Control Center" and a
live/reconnecting/offline connection chip.

| Route | Page |
|---|---|
| `/` | redirects to `/dashboard` |
| `/dashboard` | KPI cards (total/open/critical), service health grid, severity distribution and events-over-time charts, live recent events |
| `/events` | server-paginated table; filters, search, page in the query string (`?severity=CRITICAL&status=OPEN&q=signal&page=2`); optimistic status change with rollback |
| `/events/:eventId` | Events page with the detail drawer open (deep link) |
| `/services` | health, last event time, latest severity, open count (main column), active count |
| `*` | not-found page |

Severity colors: INFO blue, WARNING amber, MAJOR orange, CRITICAL red. Health:
HEALTHY green, DEGRADED amber, DOWN red. New events animate in gently, no
full-page reloads. Loading skeletons, empty states, error toasts. Dark mode is
nice to have.

## Deployment

- **Target:** local only, `docker compose up --build` on a clean clone
- **Services and ports:** kafka, kafka-ui (:8081), redis, postgres, backend
  (:8080), producer, frontend via nginx (:3000)
- **Incremental:** each app feature adds its Dockerfile and compose service;
  feature 17 hardens and verifies
- **Startup:** healthchecks with `depends_on: service_healthy`
- **Config:** env vars in `.env.example`, including `PRODUCER_INTERVAL_MS`,
  `PRODUCER_INVALID_RATIO`, `PRODUCER_DUPLICATE_RATIO`
- **Delivery:** GitHub repo link with meaningful commits across the week;
  public vs private with reviewer access to confirm with the recruiter
- **CI (GitHub Actions):** `mvn verify` (backend, producer),
  `npm ci && npm test && npm run build` (frontend), `docker compose build`
- **README:** grows with each feature; final version has setup, architecture +
  Mermaid diagram, API docs (Swagger + `docs/api.md`), Redis key design,
  consumer-group and performance notes, screenshots or demo video, known
  limitations. Written in our own words, never copying the brief.
- **Known limitations (documented):** the Redis rebuild does not restore
  `processed:{id}` keys, so re-consuming a stored event after a manual offset
  reset within 24h would count it twice.
