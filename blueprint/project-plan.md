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

**Kafka consumer / processor**
- Topic `incident-events` (3 partitions, key = service, so each service's events stay in order) plus `incident-events.DLT`.
  Both are declared in code, and names and partition counts come from config.
- Consumer group `incident-processor`, concurrency 3, manual acknowledgement.
- Bean Validation on the payload. `ErrorHandlingDeserializer` handles bad JSON.
- `DefaultErrorHandler` with exponential backoff, then `DeadLetterPublishingRecoverer` to the DLT.
  Validation and deserialization errors are non-retryable and go straight to the DLT.
- Idempotent: `eventId` is unique in Postgres, and `SETNX processed:{id}` has a 24h TTL. Duplicates never double-count.

**Redis live state**
- Service state hash, known-services set, total/severity/status counters, bounded recent-events list,
  a short-TTL summary cache and idempotency keys (see §4).
- Health rule: an open CRITICAL means DOWN, an open MAJOR/WARNING means DEGRADED, otherwise HEALTHY.
- Resilience4j circuit breaker around Redis. The summary falls back to Postgres aggregates.
- A startup reconciler rebuilds the counters from Postgres when the keys are missing.

**REST API** (springdoc Swagger UI, RFC 7807 ProblemDetail errors, DTO records)
- `GET /api/events`: filters severity, status, source, service and `q` (search), plus page/size/sort.
- `GET /api/events/{eventId}`: detail.
- `PUT /api/events/{eventId}/status`: updates Postgres, then atomically adjusts the Redis counters and service
  health, evicts the summary cache and broadcasts the change.
- `GET /api/dashboard/summary`: totalEvents, openEvents, criticalEvents, severity distribution, services[].
- `GET /api/services`: per-service status, lastEventTime, latestSeverity, openCount.

**Real-time**
- STOMP over WebSocket at `/ws`: `/topic/events` (created/updated) and `/topic/summary` (throttled to ≤1/s).

**React dashboard**
- Dashboard page: KPI cards (total/open/critical), service health grid, severity distribution chart,
  events-over-time chart, live recent-events list.
- Events page: server-paginated table, severity/status/source filters, debounced search, a detail drawer,
  and a status change that updates the UI immediately and rolls back if it fails.
- Service Status page: status, last event time, latest severity and open incident count per service.
- Live updates patch the TanStack Query caches directly from WebSocket messages. A connection chip shows
  live/reconnecting/offline, and the app falls back to 10s polling while the socket is disconnected.

**Quality and delivery**
- JSON structured logs with eventId in the MDC. Actuator health and a Prometheus endpoint. Counters for
  processed/invalid/DLT events.
- JUnit 5 + Mockito unit tests. Testcontainers integration tests for the happy path and the DLT path. Vitest + RTL.
- GitHub Actions CI. README with a Mermaid architecture diagram, API docs, the Redis key design, screenshots and
  a demo video, known limitations, and a table mapping the rubric to what was built.

**Stretch bonuses (only if time allows, after the MVP is solid):** JWT role-based login (ADMIN changes
status, VIEWER is read-only), OpenTelemetry tracing with Jaeger, CD (image push to GHCR), Kubernetes/Helm
manifests, a performance notes section with a simple load test, and an AI incident assistant (optional, off without an API key).
Rule: never start a stretch item while any MVP item is still unchecked. A polished core scores more than
half-finished bonuses.

## 4. Data - What are we storing?
**Event** (Postgres `events` table, source of truth; Flyway migration)
| Field | Type | Notes |
|---|---|---|
| eventId | varchar PK/unique | e.g. EVT-10001 |
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
| `service:{name}:state` | Hash | status, lastEventTime, latestSeverity, openCount |
| `services` | Set | known service names |
| `count:total`, `count:severity:{SEV}`, `count:status:{STATUS}` | String (INCR) | aggregated counters |
| `recent:events` | List | LPUSH + LTRIM 50 (bounded cleanup) |
| `cache:dashboard:summary` | String (JSON) | 5s TTL read-through cache |
| `processed:{eventId}` | String | idempotency guard, 24h TTL |

**Kafka topics:** `incident-events` (3 partitions), `incident-events.DLT`.

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

## 8. Deployment - Where and how will this ship?
- Runs locally with `docker compose up --build`: kafka, kafka-ui (:8081), redis, postgres, backend (:8080),
  producer, frontend (:3000). Healthchecks and `depends_on: service_healthy`, with config through env vars (`.env.example`).
- Delivered as a public GitHub repo link, with meaningful commits spread across the week.
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