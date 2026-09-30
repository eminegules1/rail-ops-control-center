# Project Plan

## 1. Problem - What problem are we solving?
An operations center (rail / mobility domain) receives a continuous stream of event, log and incident
messages from many services (ATS, CBTC, SCADA, TMS, PIS). Operators need one live view that shows the
current health of every service, aggregated counts by severity and status, the recent event flow, and a
way to review and move incidents through their lifecycle (OPEN → ACKNOWLEDGED → RESOLVED).

This is also a 7-day technical assignment for Alstom's Full Stack Software Designer role. The real goal
is to show production-minded engineering: clean backend layering, disciplined Kafka processing,
meaningful Redis state design, a quality React dashboard, real-time updates and easy delivery.
It is scored against a 100-point rubric plus 20 bonus points. Every design choice should map to a rubric item.

## 2. Users - Who is this for?
- **Operations-center operators**: watch the dashboard, filter events, acknowledge and resolve incidents.
- **Assignment reviewers (Alstom engineers)**: clone the repo, run one command, read the README and code,
  and score it. They are the real audience. Startup must be trivial, and the design must be easy to explain.

## 3. Features - What does the MVP need?
**Event producer**
- Publishes JSON events to Kafka at a configurable interval (`PRODUCER_INTERVAL_MS`).
- Random but realistic source/service/severity/status values, with weighted severity.
- Auto mode (scheduled) and manual mode (`POST /produce?count=N`).
- A seed burst (~200 events) on startup, so the dashboard is never empty.
- `PRODUCER_INVALID_RATIO` sends malformed messages on purpose, to demo error handling.
- `eventId` = `EVT-` + random UUID, so IDs stay unique across restarts and multiple producer instances.
  The consumer accepts any URL-safe id (letters, digits, `.` `_` `:` `-`, not starting with a dot), so the brief's sample `EVT-10001` message can be published by hand; other ids are skipped as invalid.
- `PRODUCER_DUPLICATE_RATIO` re-sends an earlier event to demo idempotency.

**Kafka consumer / processor**
- Topic `incident-events` (3 partitions, key = service, so each service's events stay in order) plus `incident-events.DLT`.
  Both are declared in code, and names and partition counts come from config.
- Consumer group `incident-processor`, concurrency 3, manual acknowledgement.
- Bean Validation on the payload. `ErrorHandlingDeserializer` handles bad JSON. From the first ingestion
  feature, invalid messages are logged and skipped without stopping the consumer; the DLT feature later routes them to the DLT.
- `DefaultErrorHandler` with exponential backoff, then `DeadLetterPublishingRecoverer` to the DLT.
  Validation and deserialization errors are non-retryable and go straight to the DLT.
- Processing is at-least-once and idempotent, in this order:
  1. Postgres `INSERT ... ON CONFLICT (event_id) DO NOTHING` (Postgres is the source of truth).
  2. Redis Lua script `apply-event`: `SET processed:{id} NX EX 86400`. Only when that succeeds, it
     updates the counters, the service's active-per-severity counts and health, the timeline bucket and the recent list, all in one atomic step.
  3. Ack the offset.
  Each step is safe to repeat, so a crash or retry at any point never loses an event or counts it twice.
- `incident-events` retention is 24h (from config), matching the `processed:{id}` TTL, so a redelivery or
  replay can never outlive its apply-once guard.
- If Redis is unavailable (circuit open), the event is still stored and acked, and the backend sets an
  in-memory "reconcile needed" flag. The reconciler rebuilds Redis from Postgres once Redis recovers.

**Redis live state**
- Service state hash, known-services set, total/severity/status counters, bounded recent-events list,
  a short-TTL summary cache and idempotency keys (see §4).
- Health rule (from active per-severity counts; active = OPEN or ACKNOWLEDGED): active CRITICAL > 0 → DOWN,
  else active MAJOR or WARNING > 0 → DEGRADED, else HEALTHY. It is recalculated inside the Lua scripts on every
  new event and every status change, so health can never drift from the counts.
- Resilience4j circuit breaker around Redis. Dashboard reads fall back to Postgres aggregates.
- Reconciler: runs at startup when the keys are missing, and after Redis recovers when the "reconcile needed"
  flag is set. It pauses the Kafka listener and holds a lock that status updates wait on, rebuilds all
  derived keys from Postgres, then resumes, so no live update is lost or double-counted during the rebuild.

**Incident lifecycle**
- Allowed: OPEN→ACKNOWLEDGED, OPEN→RESOLVED, ACKNOWLEDGED→RESOLVED, RESOLVED→OPEN (reopen).
- Same status again → 200, nothing changes. Any other transition → 409 ProblemDetail
  (`type: /problems/invalid-status-transition`, includes `allowedTransitions`). Unknown status value → 400.
  Unknown eventId → 404. Two overlapping server-side writes to the same event (JPA `@Version`) → 409;
  clients do not send a version.
- A status change commits to Postgres, then runs the Lua script `apply-status-change`. If that Redis step
  fails, the "reconcile needed" flag is set, because a retry of the same status is a no-op and would
  never repair Redis.
- An incoming Kafka event is a new incident with the status given in its payload.

**REST API** (springdoc Swagger UI, RFC 7807 ProblemDetail errors, DTO records)
- `GET /api/events`: filters severity, status, source, service and `q` (search), plus page/size/sort.
- `GET /api/events/{eventId}`: detail.
- `PUT /api/events/{eventId}/status`: updates Postgres, then atomically adjusts the Redis counters and service
  health, evicts the summary cache and broadcasts the change.
- `GET /api/dashboard/summary`: totalEvents, openEvents, acknowledgedEvents, criticalEvents, severityDistribution, services[].
- `GET /api/services`: per-service status, lastEventTime, latestSeverity, openCount, activeCount.
- `GET /api/dashboard/timeline?minutes=60`: event counts per minute by severity, bucketed by the event
  `timestamp` in UTC (Redis buckets, Postgres fallback). `minutes` must be 1-120, otherwise 400.
- `GET /api/dashboard/recent-events?limit=20`: latest events from `recent:events` (Postgres fallback).
  `limit` must be 1-50, otherwise 400. Rows are loaded from Postgres by id, so statuses are never stale.
- Summary field definitions: `totalEvents` = all events; `openEvents` = status OPEN;
  `acknowledgedEvents` = status ACKNOWLEDGED; `criticalEvents` = CRITICAL and not RESOLVED;
  `severityDistribution` = all events by severity; `services[]` = name, status, lastEventTime.
- `openCount` = the service's events with status OPEN (the brief's "open incident count");
  `activeCount` = status OPEN or ACKNOWLEDGED (the same "active" as the health rule).

**Real-time**
- STOMP over WebSocket at `/ws`: `/topic/events` (created/updated) and `/topic/summary` (throttled to ≤1/s).

**React dashboard**
- Dashboard page: KPI cards (total/open/critical), service health grid, severity distribution chart,
  events-over-time chart, live recent-events list.
- Events page: server-paginated table, severity/status/source filters, debounced search, a detail drawer,
  and a status change that updates the UI immediately and rolls back if it fails.
- Service Status page: status, last event time, latest severity, open incident count (main column) and
  active count (open + acknowledged) per service.
- Until real-time push lands, pages refresh by polling (TanStack Query `refetchInterval`), so the dashboard
  updates automatically from the first page onward.
- Live updates patch the TanStack Query caches directly from WebSocket messages. A connection chip shows
  live/reconnecting/offline, and the app falls back to 10s polling while the socket is disconnected.

**Quality and delivery**
- JSON structured logs with eventId in the MDC. Actuator health and a Prometheus endpoint. Counters for
  processed/invalid/DLT events.
- Tests ship with every feature. The test setup (JUnit 5, Mockito, Testcontainers) arrives with the first backend
  feature, and Vitest + RTL with the first frontend page. A final pass adds end-to-end Testcontainers flows
  (happy path, DLT, Redis-down fallback) and coverage reporting.
- GitHub Actions CI. README with a Mermaid architecture diagram, API docs, the Redis key design, a consumer-group
  section (partitions, key choice, concurrency, what happens with two backend instances), performance notes
  (partitioning, concurrency, indexes, caching, WebSocket throttling), screenshots and a demo video, and known limitations.
- Known limitations include: the Redis rebuild does not restore `processed:{id}` keys, so re-consuming an
  already-stored event after a manual consumer-offset reset (within the 24h retention) would count it twice.
- The README grows with each feature (setup and commands as they appear), not only at the end. It describes
  what was built per assignment area in our own words; it never copies the brief's text or rubric points.

**Stretch bonuses (only if time allows, after the MVP is solid):** JWT role-based login (ADMIN changes
status, VIEWER is read-only; demo credentials in the README so it never slows a reviewer's first run),
OpenTelemetry tracing with Jaeger, CD (image push to GHCR), Kubernetes/Helm manifests, and an AI incident
assistant (optional, off without an API key).
Rule: never start a stretch item while any MVP item is still unchecked. The MVP's extras already exceed the
20-point bonus cap, so stretch items add little score; a polished core scores more than half-finished bonuses.

## 4. Data - What are we storing?
**Event** (Postgres `events` table, source of truth; Flyway migration)
| Field | Type | Notes |
|---|---|---|
| eventId | varchar PK/unique | `EVT-` + UUID |
| source | varchar | ATS, CBTC, SCADA, TMS, PIS |
| service | varchar | route-service, signal-service, train-tracking, ... |
| severity | enum | INFO, WARNING, MAJOR, CRITICAL |
| message | text | human-readable |
| status | enum | OPEN, ACKNOWLEDGED, RESOLVED |
| timestamp | timestamptz | event creation time (ISO 8601 on the wire) |
| receivedAt / updatedAt | timestamptz | processing audit |
Indexes on severity, status, source, service and timestamp.

**Redis keys**
| Key | Type | Purpose |
|---|---|---|
| `service:{name}` | Hash | status, lastEventTime, latestSeverity, `active:INFO`, `active:WARNING`, `active:MAJOR`, `active:CRITICAL`, openCount, activeCount |
| `services` | Set | known service names |
| `events:count`, `severity:{SEV}:count`, `status:{STATUS}:count`, `active:{SEV}:count` | String (INCR) | aggregated counters (naming follows the brief's suggested keys) |
| `timeline:{yyyyMMddHHmm}` | Hash | per-minute counts by severity (HINCRBY), UTC, 2h TTL |
| `recent:events` | List | eventIds only; LPUSH + LTRIM 50 (bounded cleanup) |
| `cache:dashboard:summary` | String (JSON) | 5s TTL read-through cache |
| `processed:{eventId}` | String | Redis-side apply-once guard, 24h TTL |

All multi-key updates run as Lua scripts (`apply-event`, `apply-status-change`), so they are atomic and can be retried safely.

**Kafka topics:** `incident-events` (3 partitions, 24h retention), `incident-events.DLT`.

## 5. Tech - What stack are we using?
- **Backend:** Java 21, Spring Boot 3, spring-kafka, Spring Data JPA + Flyway (PostgreSQL 16), Spring Data Redis
  (Lettuce), Resilience4j, Spring WebSocket (STOMP), springdoc-openapi, Micrometer + Actuator, logstash-logback-encoder.
- **Producer:** a separate small Spring Boot app, in the same Maven multi-module repo.
- **Frontend:** React + TypeScript + Vite, React Router, TanStack Query, MUI, Recharts, @stomp/stompjs.
- **Infra:** Kafka (KRaft, single broker), Kafka UI, Redis 7, PostgreSQL 16, nginx serving the frontend and proxying `/api` and `/ws`.
- **Testing:** JUnit 5, Mockito, Testcontainers, Vitest, React Testing Library.
- **Why:** the job ad names Java, JS frameworks and relational DBs. spring-kafka gives first-class retry and DLT support.

Repo layout: `backend/`, `producer/`, `frontend/`, `docs/`, `blueprint/`, `docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`.

## 6. Monetize - How will this make money?
Not applicable: this is a hiring assignment. Success means passing all the mandatory acceptance
criteria and scoring as high as possible on the rubric (target: 100 + most of the bonus).

## 7. UI/UX - How should this look and feel?
A control-room style: dense but calm, easy to scan in a few seconds. Left nav (Dashboard / Events / Services),
a top bar with the live-connection chip. Severity colors are consistent everywhere (INFO blue, WARNING amber,
MAJOR orange, CRITICAL red), and health badges are HEALTHY green, DEGRADED amber, DOWN red. New events
animate in gently, with no full-page reloads. Loading skeletons, empty states and error toasts. Dark mode is nice to have.

Routes: `/` redirects to `/dashboard`; `/dashboard`; `/events` (filters, search and page live in the query string,
e.g. `?severity=CRITICAL&status=OPEN&q=signal&page=2`, so they survive refresh and can be shared);
`/events/:eventId` (Events page with the detail drawer open, deep-linkable); `/services`; `*` shows a not-found page.

## 8. Deployment - Where and how will this ship?
- Runs locally with `docker compose up --build`: kafka, kafka-ui (:8081), redis, postgres, backend (:8080),
  producer, frontend (:3000). Healthchecks and `depends_on: service_healthy`, with config through env vars (`.env.example`).
- Every app feature adds its own Dockerfile and compose service, so `docker compose up --build` always runs
  everything built so far. The later startup feature only hardens and verifies it on a clean clone.
- Build order aims for a runnable end-to-end version (producer, consumer, Postgres, Redis counters, the four
  required endpoints, basic pages with polling) by about day 3; everything after that is improvement, not risk.
- Delivered as a GitHub repo link, with meaningful commits spread across the week. Public vs private with
  reviewer access is to be confirmed with the recruiter.
- GitHub Actions: `mvn verify` (backend and producer), `npm ci && npm test && npm run build`, `docker compose build`.
- Deliverables: README, architecture explanation plus diagram, API docs (Swagger + docs/api.md), screenshots or a
  short demo video, known limitations and improvement areas.

## 9. Usage model and constraints (optional)
- Local, single-machine demo. It's evaluated by trusted reviewers, not exposed to the internet.
- Hard deadline: 7 days from receiving the assignment.
- Mandatory acceptance: working producer and consumer, meaningful Redis use, React dashboard, REST API,
  Docker Compose, a sufficient README, event filtering, status updates.
- The dev machine is Windows 10 with Docker Desktop. Everything must also start on a clean clone with one command.
- Auth is not required for the MVP.
- The assignment brief is confidential: it stays out of the repo, and its text and rubric are never copied into
  the README or docs.
- Interview readiness: only build what can be explained in the interview; prefer the simpler design when it
  earns the same score.