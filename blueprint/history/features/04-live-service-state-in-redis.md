# Feature: Live service state in Redis

**From build-plan:** feature 4
**Build attempt:** 1
**Branch:** feature/live-service-state-in-redis
**Status:** verified

## Goal

After each valid event is stored in Postgres, the backend applies it exactly
once to Redis live state with one atomic Lua script, `apply-event`: the
brief-style counters, per-service active-per-severity counts and health, open
and active counts, the per-minute timeline bucket, and the bounded recent list,
all behind a `processed:{eventId}` apply-once guard. Later features (5-14) read
and adjust this state; this feature only writes it.

## In scope

- Spring Data Redis (Lettuce) in the backend, configured for the host-run
  default (`localhost:${REDIS_PORT:6379}`) and the compose service (`redis:6379`).
- `apply-event.lua` on the classpath, executed through a
  `DefaultRedisScript` from one small backend component.
- Ingestion order: validate -> Postgres insert-if-absent -> `apply-event` ->
  ack. `apply-event` runs for both `STORED` and `DUPLICATE` results, so an event
  whose insert committed but whose Redis step failed is still applied on
  redelivery; the guard prevents double counting.
- Redis keys written (names exactly as the overview and plan):
  - `processed:{eventId}` String, `SET NX EX 86400` (apply-once guard)
  - `services` Set: `SADD` service name
  - `service:{name}` Hash: `active:INFO|WARNING|MAJOR|CRITICAL`, `openCount`,
    `activeCount`, `status`, `lastEventTime`, `latestSeverity`
  - `events:count`, `severity:{SEV}:count`, `status:{STATUS}:count`,
    `active:{SEV}:count` Strings (INCR)
  - `timeline:{yyyyMMddHHmm}` Hash: `HINCRBY <SEV> 1`, UTC minute of the event
    `timestamp`, 2h TTL
  - `recent:events` List: `LPUSH eventId` + `LTRIM 0 49`
- Health recomputed inside the script from the service's active counts:
  active `CRITICAL` > 0 -> `DOWN`; else active `MAJOR` or `WARNING` > 0 ->
  `DEGRADED`; else `HEALTHY`.
- Compose: backend depends on healthy `redis` and gets `SPRING_DATA_REDIS_HOST`.
- Tests (Testcontainers Redis) and README section.

## Out of scope

- Any read API (summary, services, timeline, recent) - features 5 and 7.
- `apply-status-change` and status transitions - feature 6.
- `cache:dashboard:summary` - feature 7.
- Circuit breaker, store-and-ack while Redis is down, reconcile flag,
  reconciler/rebuild from Postgres - feature 14.
- DLT and exponential backoff - feature 11. Metrics and JSON logs - feature 15.
- Backfilling events already stored in Postgres before this feature (no
  reconciler yet; see Notes).

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then one review packet
for the whole feature. `checkpointCommits` is `disabled`: no step commits.
`/complete` creates the final feature commit.

## Build steps

- [x] 1. **Redis wiring.** Add `spring-boot-starter-data-redis` to
  `backend/pom.xml`. In `application.yml` set `spring.data.redis.host: localhost`,
  `port: ${REDIS_PORT:6379}`, and `timeout: 2s` (fail fast like the Hikari
  setting, so the listener retries sooner and health answers within the compose
  timeout). In `docker-compose.yml` add `SPRING_DATA_REDIS_HOST: redis` to the
  backend and `depends_on: redis: condition: service_healthy`. Add a Redis
  Testcontainer (`GenericContainer` of `redis:7.4.11-alpine`, port 6379,
  `@ServiceConnection(name = "redis")`) to `EventIngestionIntegrationTest` so the
  Spring context still starts.
  **Done when:** `mvn -B -pl backend -am verify` passes unchanged in behavior,
  and `docker compose up -d --build --wait` reports the backend healthy with
  `/actuator/health` including a `redis` component `UP`.

- [x] 2. **`apply-event` script and caller.** Add
  `backend/src/main/resources/redis/apply-event.lua` and a component
  `LiveStateUpdater` (package `com.railops.backend`, constructor-injected
  `StringRedisTemplate`) with `boolean applyEvent(String eventId, String service,
  Severity severity, EventStatus status, Instant timestamp)` returning `true`
  when applied and `false` when the guard already existed. Java builds every key
  and passes them all as `KEYS` (no key built inside Lua); `ARGV` carries
  eventId, severity, status, the fixed-format `lastEventTime`, and the timeline
  bucket's epoch start second. Script contract in Data / contracts below.
  Add `LiveStateUpdaterIntegrationTest` against a Redis Testcontainer (no
  Spring Boot context needed beyond a `StringRedisTemplate` on the container).
  **Done when:** the focused tests listed under Testing pass.

- [x] 3. **Ingestion wiring.** `EventIngestionService.ingest` calls
  `LiveStateUpdater.applyEvent` after the insert: with the incoming values when
  `STORED`; when `DUPLICATE`, with the values of the stored row
  (`findByEventId`), so Redis always reflects the Postgres first-copy-wins row
  even for a conflicting hand-published duplicate. A Redis exception propagates,
  so the existing error handler retries the record every 2 s (insert is a no-op
  on retry, guard keeps the apply once) and the offset is acked only after Redis
  succeeds. Update `EventIngestionServiceTest` and extend
  `EventIngestionIntegrationTest` to assert Redis state end to end.
  **Done when:** unit and integration tests pass, and with the compose stack
  running, `POST /produce?count=20` on the producer yields
  `events:count` equal to `select count(*) from events` (after a
  `docker compose down -v` fresh start), and `redis-cli HGETALL
  service:<name>` shows consistent counts and health.

- [x] 4. **Docs.** README: a "Live service state (Redis)" section with the key
  table, health rule, apply-once behavior, the interim "Redis down -> ingestion
  retries until Redis returns" behavior, the note that events stored before this
  feature are not in Redis (use `docker compose down -v` for a clean slate until
  the feature 14 reconciler), and `redis-cli` inspection commands. Update the
  ingestion step list (validate, insert, apply to Redis, ack) and the
  "Build and test" note (tests also start Redis).
  **Done when:** README commands match the running stack.

- [x] 5. **Repair review findings F-05 and F-04 (tests only).** F-05: add a
  `LiveStateUpdaterIntegrationTest` case where only an active `MAJOR` event
  makes the service `DEGRADED`. F-04: add a record to
  `EventIngestionIntegrationTest` that passes Bean Validation but that Postgres
  rejects (`"message":"a\u0000b"`), placed before the trailing invalid records,
  so the existing row-count, committed-offset, and Redis assertions prove it is
  skipped rather than retried forever.
  **Done when:** `mvn -B -pl backend -am verify` passes with both new cases.

## Files / areas

- `backend/pom.xml` - add `spring-boot-starter-data-redis`
- `backend/src/main/resources/application.yml` - `spring.data.redis.*`
- `backend/src/main/resources/redis/apply-event.lua` - new
- `backend/src/main/java/com/railops/backend/LiveStateUpdater.java` - new
- `backend/src/main/java/com/railops/backend/EventIngestionService.java` - call the updater
- `backend/src/test/java/com/railops/backend/LiveStateUpdaterIntegrationTest.java` - new
- `backend/src/test/java/com/railops/backend/EventIngestionServiceTest.java` - updater mock
- `backend/src/test/java/com/railops/backend/EventIngestionIntegrationTest.java` - Redis container and assertions
- `docker-compose.yml` - backend Redis env and dependency
- `README.md` - Redis section

## Data / contracts

**Keys** (`{SEV}` is `INFO|WARNING|MAJOR|CRITICAL`, `{STATUS}` is
`OPEN|ACKNOWLEDGED|RESOLVED`, enum names exactly):

| Key | Type | Written by `apply-event` |
|---|---|---|
| `processed:{eventId}` | String `"1"` | `SET NX EX 86400`; if it already exists the script returns `0` and writes nothing else |
| `services` | Set | `SADD service` |
| `service:{service}` | Hash | see below |
| `events:count` | String | `INCR` |
| `severity:{SEV}:count` | String | `INCR` |
| `status:{STATUS}:count` | String | `INCR` (initial status from the payload/row) |
| `active:{SEV}:count` | String | `INCR` only when status is `OPEN` or `ACKNOWLEDGED` |
| `timeline:{yyyyMMddHHmm}` | Hash | `HINCRBY {SEV} 1`, see TTL rule |
| `recent:events` | List | `LPUSH eventId`, `LTRIM 0 49` |

**`service:{service}` hash fields** (all written as strings):

- `active:{SEV}`: `HINCRBY 1` when the event is active.
- `openCount`: `HINCRBY 1` when status is `OPEN`. `activeCount`: `HINCRBY 1`
  when active. Fields are initialised to `0` with `HSETNX` so a new service
  always has all six count fields.
- `status`: recomputed from the four `active:{SEV}` fields after the update:
  `DOWN` / `DEGRADED` / `HEALTHY` (rule in Goal). Written on every apply.
- `lastEventTime`, `latestSeverity`: set only when the event's `lastEventTime`
  is `>=` the stored value (or none is stored), so "latest" means newest by
  event `timestamp`, not arrival order; this matches what a feature 14 rebuild
  from Postgres (`max(timestamp)`) would produce. `lastEventTime` is UTC ISO-8601
  with exactly millisecond precision, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (fixed
  width; validation limits years to 2000-9999, so string comparison orders
  correctly). Ties go to the later-applied event.

**Timeline TTL:** bucket key uses the event `timestamp` truncated to the UTC
minute. The script reads Redis `TIME`; with `end = bucketStart + 7260` (2 h +
one minute, so a 120-minute window ending now is fully readable): if
`end <= now` the timeline step is skipped (no stale bucket is created for old
events); otherwise `EXPIRE` is set to `min(end - now, 7260)` seconds, so no
bucket (including far-future timestamps) lives longer than about 2 h.

**Script result:** integer `1` applied, `0` already applied. Script errors or
connection failures surface as Spring `DataAccessException` subclasses; the
error handler logs only their class name (existing `reason()` behavior, no
payload values).

**Atomicity:** one `EVALSHA`/`EVAL` per event; all writes above happen in that
single script, or none do (guard checked first).

## Testing

Backend test command: `mvn -B -pl backend -am verify` (Docker required).

- `LiveStateUpdaterIntegrationTest` (Redis Testcontainer, `FLUSHALL` between
  tests):
  - OPEN CRITICAL event: all counters, `active:CRITICAL`, `openCount`,
    `activeCount` = 1, service `status` `DOWN`, `services` contains it,
    `recent:events` head is the id, `processed:{id}` TTL in (0, 86400].
  - Same event applied twice: second call returns `false`, every count still 1.
  - ACKNOWLEDGED WARNING -> `DEGRADED`, `openCount` 0, `activeCount` 1.
  - RESOLVED CRITICAL -> `status:RESOLVED:count` 1, no active counts, `HEALTHY`.
  - INFO only -> `HEALTHY` with `active:INFO` 1.
  - Newer event updates `lastEventTime`/`latestSeverity`; an older one applied
    afterwards does not.
  - Timeline: event at now -> bucket field incremented, TTL in (0, 7260];
    event 3 h old -> no timeline key.
  - 55 events -> `recent:events` length 50, newest first.
- `EventIngestionServiceTest` (Mockito): updater called with incoming values on
  `STORED`; with the stored row's values on `DUPLICATE`; not called for invalid
  events; a Redis exception propagates.
- `EventIngestionIntegrationTest`: after the existing flow, `events:count` = 2,
  `processed:EVT-1` exists, the duplicate `EVT-1` counted once,
  `service:signal-service` `status` `DOWN`.

No frontend work; no browser tests.

## Notes for the AI

- Interim failure behavior (until feature 14): Redis failure is treated like a
  Postgres outage - retried by the existing `FixedBackOff` error handler, the
  event is already durable in Postgres, and no count is lost or doubled. Do not
  add a circuit breaker, flag, or fallback here. Backend `/actuator/health`
  will include the auto-configured Redis indicator; that is expected.
- Only `processed:{eventId}` guards against double counting; do not add a
  second guard in Java. Do not skip `apply-event` for `DUPLICATE` results.
- Lettuce/Spring Boot auto-configuration supplies `StringRedisTemplate`; add no
  custom Redis configuration class or `@ConfigurationProperties` unless a
  current value needs one (key names, 50, 86400, 7260 are contract constants in
  the script or updater).
- Keep log lines free of payload values beyond `eventId`.
- Pre-existing rows in a long-running local Postgres are not in Redis. That is a
  documented interim gap, not a bug to fix here.

## Verification evidence

- `mvn -B verify` (reactor): producer 19 tests, backend 61 tests, 0 failures,
  including `LiveStateUpdaterIntegrationTest` (10) and the extended
  `EventIngestionIntegrationTest`.
- Compose stack (`docker compose up -d --build --wait`, backend on
  `BACKEND_PORT=8083`): all services healthy, `/actuator/health` `UP`.
  `POST /produce?count=20`, producer stopped: `events:count` 354 = Postgres rows
  received since the new backend started (354); a sampled `service:{name}` hash
  matched Postgres per severity/status (active 28/15/11/4, open 38, active 58,
  `DOWN`).
- Redis outage: `docker compose stop redis` -> health 503 and records retried
  every 2 s; after `start redis`, `events:count` 553 = Postgres 553 (no loss, no
  double count). Existing data kept (no `down -v`); comparison used rows received
  since the backend start.
- Step 5 (F-05, F-04 repair): `mvn -B -pl backend -am verify` passes, backend
  62 tests, 0 failures (`LiveStateUpdaterIntegrationTest` 11). The integration
  log shows `EVT-7` skipped with `database error (SQLState 22021)`.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":13463,"specSha256":"b817cc32a8d875d34610de9b3b70ef8197e43556b30dd94c81e9bbdf90dffd61","branch":"refs/heads/feature/live-service-state-in-redis","head":"1e5d96c1df64acc19155560461675a95aa8ce2b2","baseRef":"refs/heads/master","baseCommit":"a56e25b8fd785e56ef1375e8cecab4a033595a21","sourceTree":"c2667d417be02b4a8023e5c69bfeb45e700d990e","absentOptional":[]} -->

## Findings

### 4/F-04 [P3] closed - Skipping of database-rejected records is not exercised through the error handler

**File:** backend/src/main/java/com/railops/backend/KafkaConsumerConfig.java:34
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security, performance, tests)
**Why it matters:** The spec's invalid-message contract includes "a `DataIntegrityViolationException` from Postgres", and that path is reachable: a payload with a NUL byte in `message` or `eventId` passes Bean Validation but Postgres rejects it. A reviewer probe (outside the repository, against the target's `IncidentEventRepository` with Testcontainers Postgres) confirmed both surface as `DataIntegrityViolationException`, so current behavior is correct. But no test drives such a record through the listener and `DefaultErrorHandler`: every invalid record in `EventIngestionIntegrationTest` is stopped by deserialization or validation, `IncidentEventRepositoryTest` only proves the exception type, and `KafkaConsumerConfigTest` only covers `reason`. Removing `DataIntegrityViolationException` from `addNotRetryableExceptions` would keep every test green while one such record blocks its partition forever under the unlimited `FixedBackOff`.
**Suggested fix:** Add one record to `EventIngestionIntegrationTest` that passes validation but violates the database (for example `"message":"a\u0000b"`) before the trailing invalid records, so the existing row-count and committed-offset assertions cover it. Alternatively, unit-test that `kafkaErrorHandler()` classifies a `ListenerExecutionFailedException` wrapping `DataIntegrityViolationException` as non-retryable. Current requirement lost: none.
**Resolution:** Fixed in feature 4 step 5: `EventIngestionIntegrationTest` publishes `EVT-7` with `"message":"a\u0000b"` before the trailing invalid records; the run logs `Skipping invalid event ... database error (SQLState 22021)` and the row-count, committed-offset, and Redis assertions pass. Closed 2026-09-26 by /audit independent current (fresh subagent, target 1e5d96c): `EventIngestionIntegrationTest.java:72` publishes the literal JSON escape `\u0000` (Java source `\"a\\u0000b\"`, not a Java Unicode escape), the reviewer run logged `incident-events-2@8 (attempt 1): database error (SQLState 22021)` followed by one skip, and EVT-5 after it was stored with the committed offset reaching the end offset; removing `DataIntegrityViolationException` from `addNotRetryableExceptions` would now stall the partition and time out the await. No new defect introduced.

### 4/F-05 [P3] closed - MAJOR-only DEGRADED health branch is not asserted

**File:** backend/src/test/java/com/railops/backend/LiveStateUpdaterIntegrationTest.java:137
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security, performance, tests)
**Why it matters:** The health rule in `apply-event.lua:39` treats active `MAJOR` or `WARNING` as `DEGRADED`, but only the `WARNING` half is asserted (`acknowledgedWarningDegradesServiceButIsNotOpen`). Tests that apply an active `MAJOR` event (`timelineBucketsRecentEventsWithBoundedTtl`, `latestFieldsFollowEventTimeNotArrivalOrder`) never assert `DEGRADED` from it alone, so dropping `counts[2]` from the `elseif` would keep every test green while MAJOR incidents leave a service `HEALTHY`.
**Suggested fix:** Assert `status` `DEGRADED` after the active `MAJOR` apply in `timelineBucketsRecentEventsWithBoundedTtl`, or add a one-line OPEN MAJOR case. Current requirement lost: none.
**Resolution:** Fixed in feature 4 step 5: `LiveStateUpdaterIntegrationTest.activeMajorAloneDegradesService` asserts an OPEN MAJOR event alone makes the service `DEGRADED`. Closed 2026-09-26 by /audit independent current (fresh subagent, target 1e5d96c): `LiveStateUpdaterIntegrationTest.java:117-121` applies only an OPEN MAJOR event and asserts `status` `DEGRADED` with `active:MAJOR` 1 (WARNING and CRITICAL stay 0 from `HSETNX`), so dropping `counts[2]` from `apply-event.lua:39` would fail it. No new defect introduced.

## Independent review

# Independent Review

**Status:** passed
**Target commit:** 1e5d96c1df64acc19155560461675a95aa8ce2b2
**Base commit:** a56e25b8fd785e56ef1375e8cecab4a033595a21
**Base ref:** master
**Spec hash:** b817cc32a8d875d34610de9b3b70ef8197e43556b30dd94c81e9bbdf90dffd61
**Prepared by:** claude
**Builder model:** claude-opus-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-26T23:19:15Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-26T23:23:00Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Handoff

Review the active spec and the complete `a56e25b8fd785e56ef1375e8cecab4a033595a21..1e5d96c1df64acc19155560461675a95aa8ce2b2` delta in a fresh
session or isolated subagent without the builder conversation. Run all Audit lenses from scratch.
Run Check when required above. Do not edit product code, accept findings, or
reuse the existing findings as the review scope.

## Commands

- `git rev-parse HEAD`, `git merge-base master HEAD`, `sha256sum blueprint/context/current-feature.md`, `git status --porcelain --untracked-files=all`: pass (HEAD, merge base, and spec hash match the request; only review.md and findings.md differ; spec is tracked, no snapshot needed)
- `mvn -B -pl backend -am verify`: pass (backend 62 tests, 0 failures, 0 errors, 0 skipped; LiveStateUpdaterIntegrationTest 11, EventIngestionIntegrationTest 1, EventIngestionServiceTest 4)

## Evidence

- Reviewed the full delta: `backend/pom.xml`, `application.yml`, `redis/apply-event.lua`, `LiveStateUpdater.java`, `EventIngestionService.java`, the three changed test classes, `docker-compose.yml`, `README.md`, plus callers and contracts (`IncidentEventListener`, `KafkaConsumerConfig`, `IncidentEventRepository`, `IncidentEventMessage`, producer duplicate generator).
- `apply-event.lua` matches the spec contract: guard `SET NX EX 86400` first, all keys passed from Java, `HSETNX` initialises six count fields, health derived from active counts, newest-by-event-time `lastEventTime`/`latestSeverity`, timeline skipped when `bucketStart + 7260 <= now` and TTL capped at 7260, recent list trimmed to 50.
- Ingestion order validate -> insert -> apply -> ack holds; DUPLICATE applies the stored row's values; Redis exceptions propagate to the retrying handler; logs carry no payload values beyond ids and SQLState.
- Test run log: EVT-7 (`"a\u0000b"`) logged `database error (SQLState 22021)` on attempt 1 and was skipped; committed offset reached the end offset; Redis assertions (events:count 2, duplicate counted once, service DOWN) passed.
- No `@Disabled`, focused, or assumption-gated tests in `backend/src/test`.

## Findings

- F-04 [P3] closed: repair verified at `EventIngestionIntegrationTest.java:72`.
- F-05 [P3] closed: repair verified at `LiveStateUpdaterIntegrationTest.java:117-121`.
- No new findings.

## Remaining risk

- Compose-stack behaviour (backend healthy with Redis `UP`, `POST /produce` counts matching Postgres, Redis outage recovery) was not re-run by the reviewer; it relies on the builder's recorded evidence in the spec. Check was not required.
- The DUPLICATE path re-applies whenever `processed:{eventId}` has expired, so a duplicate of an event first applied more than 24h earlier (hand-published, or replayed after a manual offset reset from a not-yet-deleted segment, since Kafka retention is segment-granular) is counted twice. This is the plan's documented, accepted design (24h guard matching 24h retention) until the feature 14 reconciler; the producer's auto-duplicates come from a 100-event recent buffer refreshed every interval, so they stay well inside the window.
- No frontend, browser, or security-scanner command applies to this backend-only feature.
