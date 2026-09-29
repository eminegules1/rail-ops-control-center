# Feature: Redis resilience

**From build-plan:** feature 14
**Build attempt:** 1
**Status:** verified
**Branch:** feature/redis-resilience

## Goal

The backend keeps ingesting events, accepting status changes, and serving correct
dashboard reads when Redis is down, and automatically resynchronizes its Redis
live state from PostgreSQL once Redis comes back — without ever serving stale or
drifted counters as if they were current.

## In scope

- A Resilience4j circuit breaker named `redis` wraps every Redis call made by
  `LiveStateUpdater` (`apply-event`, `apply-status-change`) and by
  `DashboardQueryService` (summary, services, timeline, recent-events), so a
  down or slow Redis fails fast instead of hanging on the Lettuce timeout.
- `EventIngestionService` and `IncidentStatusService` survive a Redis failure
  (a `DataAccessException` or the breaker's `CallNotPermittedException`): the
  event/status change is still committed to Postgres and still pushed over the
  WebSocket, the exception is logged, and an in-memory reconcile-needed flag is
  set.
- `DashboardQueryService`'s read methods (`summary`, `buildSummary`, `services`,
  `timeline`, `recentEvents`) fall back to PostgreSQL aggregate queries on the
  same failures, returning the same `DashboardSummary` / `ServiceState` /
  `TimelineBucket` / `EventResponse` shapes the Redis path returns, computed
  from stored events instead of live counters.
- A reconciler rebuilds Redis from Postgres: once at startup when the live
  state looks empty, and again whenever the `redis` circuit breaker closes
  after having been open while the reconcile-needed flag is set. It pauses the
  Kafka listener, takes a lock that fully serializes it against
  `IncidentStatusService`, computes the live-state snapshot directly from
  Postgres using the Step 3 aggregate queries, and pipeline-writes the
  resulting absolute values into Redis in one batch (not by replaying
  individual events through the Lua scripts), then resumes the listener,
  releases the lock, and clears the flag.

## Out of scope

- No new response fields signaling "served from Postgres" to API clients; the
  fallback is transparent and the DTO shapes are the locked contracts from
  Features 7-10 and 13.
- No change to the documented limitation that a Redis rebuild does not restore
  `processed:{id}` apply-once guards (project-overview "Known limitations").
- No change to Postgres or Kafka resilience (already handled by Features 3 and
  11); this feature is about Redis only.
- Feature 15 (Observability) owns metrics/logs for these failure paths beyond
  the existing `log.warn` calls; this feature does not add Prometheus counters.

## Build loop

Per `blueprint/config.json`: `workflow.stepReview` is `feature`, so the steps
below are implemented together and reviewed once as a single packet;
`workflow.checkpointCommits` is disabled, so no commit happens between steps.
Each step must still leave `mvn -B -pl backend -am verify` green before moving
to the next.

## Build steps

1. [x] **Circuit breaker around Redis.** Add `resilience4j-spring-boot3` to
   `backend/pom.xml`. Lower `spring.data.redis.timeout` in `application.yml`
   from `2s` to `500ms`: with the existing 2s timeout, the first 5 calls in the
   breaker's sliding window could take up to 10s to fail before the breaker has
   enough samples to trip, and each half-open probe could then stall for another
   2s — too slow for the breaker to protect ingestion/status/dashboard latency
   the way this feature intends. Configure one
   `resilience4j.circuitbreaker.instances.redis` instance in `application.yml`
   (count-based sliding window, `DataAccessException` as the recorded failure
   type, automatic half-open transition). Add a small `@Configuration` class
   exposing the named `CircuitBreaker` bean. Inject it into `LiveStateUpdater`
   and wrap the `redis.execute(...)` calls in `applyEvent` and
   `applyStatusChange` with `circuitBreaker.executeSupplier(...)`. Update the
   existing `LiveStateUpdaterIntegrationTest` constructor call for the new
   dependency. Add a test that forces repeated Redis failures and asserts the
   breaker opens (further calls throw `CallNotPermittedException` without
   touching Redis), then closes again once calls succeed.
   **Done when:** `mvn -B -pl backend -am verify` passes, including the new
   circuit-breaker test.

2. [x] **Reconcile-needed flag; ingestion and status changes survive an outage.**
   Add a `ReconcileState` component (in-memory flag: `markNeeded()`, `isNeeded()`,
   `clear()`). In `EventIngestionService`, catch `DataAccessException` and
   `CallNotPermittedException` around the `liveState.applyEvent(...)` call, log a
   warning, mark the flag, and push the created event when the apply succeeded
   *or* the Postgres insert was new (`inserted == 1`) — so a genuinely new event
   is still broadcast even when Redis could not run the apply-once guard, while a
   redelivered duplicate during the same outage is not double-pushed. In
   `IncidentStatusService`, widen its existing `catch (DataAccessException e)`
   around `liveState.applyStatusChange(...)` to also catch
   `CallNotPermittedException` and call `reconcileState.markNeeded()` there
   (replacing the `// Feature 14 sets the reconcile-needed flag here.` marker
   comment); the unconditional push after the try/catch is unchanged. Add tests
   covering: a Redis failure during ingestion still stores and pushes the event
   and marks the flag; a duplicate delivered during the same outage is not
   pushed twice; a Redis failure during a status change still commits, still
   pushes, and marks the flag.
   **Done when:** `mvn -B -pl backend -am verify` passes; ingestion and status
   changes both succeed, push, and mark `ReconcileState` while Redis is
   unreachable.

3. [x] **Postgres fallback for dashboard reads.** Add to
   `IncidentEventRepository`: derived count queries for total/open/acknowledged
   events and for active-critical events (`severity = CRITICAL AND status <>
   RESOLVED`); a native grouped query returning, per `service`, the row with the
   latest `timestamp` (for `lastEventTime`/`latestSeverity`) plus open/active
   counts and active counts per severity (`CRITICAL`/`MAJOR`/`WARNING`) needed to
   derive `ServiceHealth` with the same precedence the Lua script uses (active
   CRITICAL > 0 -> DOWN; else active MAJOR or WARNING > 0 -> DEGRADED; else
   HEALTHY); a native grouped query for per-minute, per-severity counts over a
   `[start, now]` window for the timeline; and `findByOrderByReceivedAtDesc(Pageable)`
   for the recent-events fallback (replacing the `recent:events` Redis list).
   Wire `DashboardQueryService.summary()`, `buildSummary()`, `services()`,
   `timeline()`, and `recentEvents()` to catch `DataAccessException` /
   `CallNotPermittedException` from their Redis calls and build the same return
   shape from these queries instead. Add tests (stopping or disconnecting the
   Redis container) proving each of the five methods returns the correct,
   Postgres-derived result while Redis is down, and that `DashboardController`
   still answers 200 for all four dashboard endpoints.
   **Done when:** `mvn -B -pl backend -am verify` passes; with the Redis
   container stopped, `/api/dashboard/summary`, `/api/services`,
   `/api/dashboard/timeline`, and `/api/dashboard/recent-events` all return
   correct 200 responses sourced from Postgres.

4. [x] **Pause-and-rebuild reconciler.** This step does not replay events
   through the `apply-event` Lua script. That script's `processed:{id}` `SET ...
   NX` guard makes it a no-op for any event Redis still holds a guard key for —
   including one whose *counted* state is stale because its status changed
   during the outage but the Redis apply for that change failed. A replay-based
   rebuild would silently leave that drift uncorrected, and would also mean up
   to tens of thousands of individual round trips to Redis while the Kafka
   listener is paused and status updates are blocked. Instead:
   - Add a `LiveStateLock` (`ReentrantReadWriteLock`-backed). Wrap
     `IncidentStatusService.changeStatus`'s **entire body** — the Postgres
     transaction commit *and* the `liveState.applyStatusChange(...)` attempt —
     in the read lock, not just the Redis call. This is what makes the lock
     actually exclude the reconciler: a status change either fully commits to
     Postgres and (attempts to) apply to Redis before the reconciler's write
     lock can be granted, so the reconciler's Postgres snapshot reflects it and
     no further Redis apply is pending for it; or the status change's read-lock
     acquisition blocks until the reconciler finishes and releases the write
     lock, so its Postgres commit — and therefore its Redis apply — happens
     strictly after the rebuild, applying its delta on top of the fresh state
     instead of on top of a snapshot that already includes it. (Wrapping only
     the Redis call, as originally planned, leaves the Postgres commit
     unguarded and open to exactly the double-apply race described above.)
   - Give `IncidentEventListener`'s `@KafkaListener` an explicit `id`.
   - Add a `LiveStateReconciler` that runs once on `ApplicationReadyEvent` when
     the live state looks empty (no `services` key), and again whenever the
     `redis` circuit breaker closes after having been open while
     `ReconcileState.isNeeded()` is true. To reconcile, it pauses the Kafka
     listener container via `KafkaListenerEndpointRegistry`, takes the write
     lock, computes the full live-state snapshot from Postgres by reusing the
     same aggregate queries added in Step 3 (per-service health and counts,
     severity/status totals, the top-50 recent events, and timeline buckets for
     the current ~2h window), and writes those computed absolute values into
     Redis as one pipelined batch — replacing each derived key's value outright
     (`SET`/`HSET` with the computed value, not `INCR`/`HINCRBY`), clearing and
     rewriting the recent-events list in newest-first order, and evicting the
     cached summary key/version — rather than issuing one Lua call per stored
     event. It deliberately does not touch or restore `processed:{id}` guards,
     consistent with the already-documented "Known limitations" note. It then
     releases the write lock, resumes the listener, and clears the flag. Runs
     on its own background thread so it never blocks the Kafka listener thread
     or the circuit breaker's event publisher.
   - Add an integration test that ingests events, stops the Redis container to
     force the flag, restarts it, and asserts the live state converges to the
     Postgres-derived state (services, counters, recent list) in one bounded
     rebuild rather than one round trip per event; that the listener is paused
     and resumed around the rebuild; and — the race from above — that a status
     change whose Postgres commit lands *after* the rebuild's snapshot is taken
     is applied on top of the fresh Redis state exactly once, never double
     counted, whichever side of the lock it falls on.
   **Done when:** `mvn -B -pl backend -am verify` passes; after a forced Redis
   outage and recovery, `/api/services` and `/api/dashboard/summary` match the
   state derivable from Postgres, with no missed or duplicated status, and the
   rebuild completes in a small bounded number of Redis round trips regardless
   of table size.

5. [x] **Repair independent-review findings F-06, F-07, F-08.** F-06 [P1]:
   pausing the Kafka listener only requests a pause; the consumer finishes its
   in-flight record (and by default the rest of its poll batch) before actually
   stopping, and `EventIngestionService` took no lock, so an in-flight ingest
   could straddle the rebuild's snapshot-then-write and be silently erased or
   double-counted. Fix: rename `LiveStateLock.forStatusChange()` to
   `forLiveStateWrite()` and wrap `EventIngestionService.ingest`'s Postgres
   insert *and* Redis apply in that same read lock, exactly like
   `IncidentStatusService.changeStatus`, so the reconciler's write lock excludes
   ingestion regardless of pause timing. F-07 [P2]: the reconcile-needed flag was
   only acted on by the breaker's HALF_OPEN-to-CLOSED transition, so a failed
   startup check, a failed rebuild (its own Redis pipeline isn't breaker-guarded),
   or too few failures to trip the breaker could leave the flag set with Redis
   reachable and closed. Fix: a `@Scheduled(fixedDelay = 30_000)` retry on
   `LiveStateReconciler` that reconciles whenever the flag is still needed and
   the breaker is closed. F-08 [P3]: the rebuild's pipelined write had no
   `MULTI`/`EXEC`, so a concurrent reader could see a transiently half-rebuilt
   state (for example `services` deleted but not yet re-added). Fix: wrap the
   `SessionCallback` body in `operations.multi()` / `operations.exec()`; still
   one pipelined round trip, now atomic to readers.
   **Done when:** `mvn -B -pl backend -am verify` passes, including a new test
   proving an in-flight ingestion blocks on a concurrent rebuild's write lock
   and applies correctly once it releases (the same guarantee
   `IncidentStatusServiceIntegrationTest` already proves for status changes).

## Files / areas

- `backend/pom.xml` - add `resilience4j-spring-boot3`
- `backend/src/main/resources/application.yml` - `resilience4j.circuitbreaker.instances.redis` config
- `backend/src/main/java/com/railops/backend/LiveStateUpdater.java` - wrap Redis calls with the circuit breaker
- `backend/src/main/java/com/railops/backend/RedisResilienceConfig.java` - new, exposes the named `CircuitBreaker` bean
- `backend/src/main/java/com/railops/backend/ReconcileState.java` - new
- `backend/src/main/java/com/railops/backend/EventIngestionService.java` - catch/flag/push-on-insert
- `backend/src/main/java/com/railops/backend/IncidentStatusService.java` - widen catch, take the read lock, mark flag
- `backend/src/main/java/com/railops/backend/DashboardQueryService.java` - Postgres fallback wiring
- `backend/src/main/java/com/railops/backend/IncidentEventRepository.java` - new aggregate queries
- `backend/src/main/java/com/railops/backend/LiveStateLock.java` - new, wraps the `ReentrantReadWriteLock`
- `backend/src/main/java/com/railops/backend/LiveStateReconciler.java` - new
- `backend/src/main/java/com/railops/backend/IncidentEventListener.java` - explicit listener `id`
- Matching test files under `backend/src/test/java/com/railops/backend/`:
  `LiveStateUpdaterIntegrationTest.java` (constructor update), `EventIngestionServiceTest.java`
  (constructor update plus the F-06 lock test), `IncidentStatusServiceIntegrationTest.java`,
  `DashboardQueryServiceIntegrationTest.java` (constructor update plus fallback tests),
  `DashboardControllerTest.java`, and a new `LiveStateReconcilerIntegrationTest.java`

## Data / contracts

- Circuit breaker instance name: `redis`. Recorded failure type:
  `org.springframework.dao.DataAccessException` (the type Spring Data Redis
  already translates connection/timeout failures into). Count-based sliding
  window, automatic open-to-half-open transition — exact thresholds are an
  internal tuning detail, not a product contract.
- `ReconcileState` is a single process-local in-memory flag (not persisted, not
  shared across instances); acceptable because this is a single-backend-instance
  deployment (compose stack runs one `backend` container).
- No changes to `DashboardSummary`, `ServiceState`, `ServiceSummary`,
  `TimelineBucket`, or `EventResponse` field shapes — the Postgres fallback
  produces the exact same DTOs.
- `ServiceHealth` derivation from the Postgres fallback must match
  `apply-event.lua` exactly: active `CRITICAL` count > 0 -> `DOWN`; else active
  `MAJOR` or `WARNING` count > 0 -> `DEGRADED`; else `HEALTHY`. "Active" means
  status `OPEN` or `ACKNOWLEDGED`, same as everywhere else in the codebase.
- Reconciliation computes the live-state snapshot directly from Postgres (the
  same Step 3 aggregate queries) and writes absolute values into Redis in one
  pipelined batch. It does not replay `apply-event.lua`/`apply-status-change.lua`
  per stored event: their `processed:{id}` `SET ... NX` guard would make the
  script a no-op for any event whose guard key Redis still holds, which is
  exactly the case for an event whose *current* Redis-counted state is stale
  (its status changed during the outage but the Redis apply failed) — a replay
  cannot correct that drift, and one Lua round trip per stored event does not
  scale to a large table. The batch write deliberately never touches
  `processed:{id}` guards at all, which is why the existing documented
  limitation (re-consuming after a manual offset reset within 24h double-counts)
  still applies unchanged.
- The lock guards `IncidentStatusService`'s *entire* `changeStatus` body — the
  Postgres commit and the Redis apply attempt together, not just the Redis call
  — so a status change and a reconciler rebuild are fully serialized: whichever
  acquires the lock first completes its whole commit-then-apply (or
  snapshot-then-write) sequence before the other proceeds. Guarding only the
  Redis call would let a status change commit to Postgres, get included in a
  concurrent rebuild's snapshot, and then still apply its now-already-reflected
  delta to the freshly rebuilt Redis state, double-counting it.
  `EventIngestionService` needs no such lock: ingestion is already halted
  during a rebuild because the Kafka listener is paused first (matches the
  project overview's "pauses the Kafka listener, holds a lock that status
  updates wait on").

## Testing

Backend: JUnit 5 + Mockito for unit-level catch/flag/push logic (ingestion,
status service), Testcontainers (Redis, Postgres, Kafka) for the fallback and
reconciler integration tests, run through `mvn -B -pl backend -am verify`. A
Redis outage is simulated by stopping/restarting the `redis` `GenericContainer`
(as already used for connection-factory-based tests) or by pointing a test-only
connection factory at a closed port; both patterns already exist in this test
suite. No frontend or browser changes are in this feature, so no frontend test
or screenshot evidence applies.

## Notes for the AI

- Reuse the existing pattern from `IncidentStatusService.changeStatus`: Postgres
  commits first and is always the source of truth; a Redis failure is logged
  and flagged, never thrown back to the caller, and the live push still happens
  because Postgres already has the change. Feature 14 extends this same
  philosophy to ingestion (push based on the Postgres insert result, not the
  Redis apply-once guard, when Redis fails) and to reads (compute from Postgres
  when Redis calls fail).
- Do not implement the reconciler as a per-event replay through
  `LiveStateUpdater.applyEvent`/`applyStatusChange`. It looks like the obvious
  reuse of already-tested Lua, but the `processed:{id}` apply-once guard makes
  it silently skip correcting any event Redis already has a guard key for,
  which is precisely the drift a reconciler exists to fix, and it does not
  scale to a large events table (one Lua round trip per row). Reuse the Step 3
  Postgres aggregate queries instead — compute the snapshot once, then write it
  into Redis as a single pipelined batch of absolute values. This is why Step 3
  is ordered before Step 4: the reconciler depends on its queries.
- `CallNotPermittedException` is `io.github.resilience4j.circuitbreaker.CallNotPermittedException`;
  it does not extend `DataAccessException`, so every catch site added or widened
  in this feature must name both exception types explicitly.
- Keep constructor injection: `LiveStateUpdater` and `DashboardQueryService`
  both gain a `CircuitBreaker` constructor parameter, so every existing direct
  `new LiveStateUpdater(...)` / `new DashboardQueryService(...)` call in tests
  needs updating, not just production wiring.
- `@EnableScheduling` is already on `BackendApplication`; do not add
  `@EnableAsync` for the reconciler's background thread. A small, explicitly
  managed single-thread `ExecutorService` (shut down like
  `KafkaConsumerConfig`'s `DisposableBean` pattern) fits the codebase's existing
  style better than a new cross-cutting annotation for one caller.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":20413,"specSha256":"86f00e718dd4e7aacdc74b77e12b7d23a4e28fd2987350b1e38b80f6c0d0038f","branch":"refs/heads/feature/redis-resilience","head":"94754b5e3f9f8f2ef3fa373de03b95cdf1dc48d1","baseRef":"refs/heads/master","baseCommit":"8a71b5b624d8d8f0458f24e90ad08a3dd5405c41","sourceTree":"a6f0d14c2f177b95adf5f27a8c78f61930791df2","absentOptional":[]} -->

## Findings

### 14/F-06 [P1] closed - Rebuild snapshots Postgres before the Kafka listener has actually paused, so in-flight ingestion races it

**File:** backend/src/main/java/com/railops/backend/LiveStateReconciler.java:89
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality, performance)
**Why it matters:** The spec's contract is that `EventIngestionService` needs no
lock because "ingestion is already halted during a rebuild because the Kafka
listener is paused first". `reconcile()` calls `listener.pause()` and goes
straight to the write lock and `rebuild()`. In spring-kafka 3.3.16,
`KafkaMessageListenerContainer.pause()` only sets the pause request and wakes
the consumer (bytecode: `super.pause(); consumerWakeIfNecessary()`); the
consumer finishes the record it is on, and by default the rest of the current
poll batch, before it pauses. Ingestion takes no lock. A record whose insert
commits after the rebuild's count queries and whose Lua apply runs before the
pipelined `SET`s is erased; one whose insert lands before the snapshot and whose
apply lands after the pipeline is counted twice. The Postgres reads are
also separate statements, so an in-flight insert can make the snapshot itself
internally inconsistent. The flag is then cleared, so the drift is silent and
permanent until the next outage, which is the exact outcome the feature exists
to prevent. It is most reachable at startup (listener already consuming the
seed backlog while `reconcileAtStartupIfEmpty` runs) and under producer bursts.
**Suggested fix:** Smallest: have `EventIngestionService` take
`LiveStateLock.forStatusChange()` around its insert and apply, the same as
`changeStatus`, so the write lock excludes ingestion regardless of pause
timing. Alternatively, after `pause()` wait (bounded) until
`listener.isContainerPaused()` before taking the lock. Add a test that proves
an ingest in flight during a rebuild is counted exactly once.
**Resolution:** Fixed by renaming `LiveStateLock.forStatusChange()` to
`forLiveStateWrite()` and wrapping `EventIngestionService.ingest`'s Postgres
insert and Redis apply in that same read lock, matching
`IncidentStatusService.changeStatus`. `EventIngestionServiceTest.ingestWaitsForAConcurrentRebuildToReleaseTheWriteLockThenAppliesAfterIt`
proves an in-flight ingestion blocks while a rebuild holds the write lock and
applies correctly once released. Awaiting re-review.
Closed 2026-09-29 by /audit independent (fresh subagent, claude-opus-5-5) at
94754b5. Re-examined `EventIngestionService.java:65-90` and
`IncidentStatusService.java:53-87`: the only two writers of `events` (grep of
`backend/src/main/java` finds no other save/delete/insert) both hold
`LiveStateLock.forLiveStateWrite()` across the Postgres write and the Redis
apply. `insertIfAbsent` commits in its own `@Transactional` before the lock is
released (the listener is not transactional, no Kafka transaction manager), and
open-in-view is off, so no thread blocked on the read lock holds a pooled DB
connection. `LiveStateReconciler.reconcile()` (`:103-122`) takes the write lock
for the whole snapshot-then-write, so the snapshot's separate count queries now
see a quiescent table. With `listener.concurrency: 3`, the write lock waits for
every in-flight consumer thread. `EventIngestionServiceTest.ingestWaitsForAConcurrentRebuildToReleaseTheWriteLockThenAppliesAfterIt`
passes against the real `LiveStateLock`. No new defect found in the repair.

### 14/F-07 [P2] closed - The reconcile-needed flag is acted on only on a HALF_OPEN to CLOSED transition

**File:** backend/src/main/java/com/railops/backend/LiveStateReconciler.java:63
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality)
**Why it matters:** Nothing reads `ReconcileState.isNeeded()` except the
breaker's `HALF_OPEN_TO_CLOSED` listener. Any path that sets the flag without
the breaker tripping leaves Redis drifted indefinitely while dashboards serve it
as current: a single failed `applyStatusChange` (fewer than
`minimum-number-of-calls: 5` failures), a startup `hasKey` failure (that call
is not routed through the breaker), and a failed `rebuild()` (its pipeline also
bypasses the breaker; after a partial write `services` may already be
deleted). This matches the spec's trigger wording, but it contradicts the
feature goal of never serving drifted counters as current.
**Suggested fix:** Add a small retry that uses the existing single-thread
executor or the already-enabled `@Scheduled` support: if
`reconcileState.isNeeded()` and the breaker is `CLOSED`, run `reconcile()`
(for example every 30 s). Keep the transition trigger for fast recovery. No
current requirement is lost.
**Resolution:** Fixed by adding `LiveStateReconciler.retryIfStillNeeded()`, a
`@Scheduled(fixedDelay = 30_000)` method that reconciles whenever the flag is
still needed and the breaker is closed, alongside the existing transition
trigger. Verified by inspection and the existing reconciler convergence test
(`mvn -B -pl backend -am verify` passes); no new test targets the 30s retry
path specifically, since it only supplements the already-tested transition
trigger. Awaiting re-review.
Closed 2026-09-29 by /audit independent (fresh subagent, claude-opus-5-5) at
94754b5. Re-examined `LiveStateReconciler.java:81-86`: `@EnableScheduling` is on
`BackendApplication`, the method is a void, no-arg bean method, and it only
enqueues onto the same single-thread executor, so it cannot run concurrently
with another rebuild or block the scheduler. It now recovers every path the
finding listed: a sub-threshold `applyStatusChange`/`applyEvent` failure, a
failed startup `hasKey` (`:95-99`), and a failed rebuild (`:113-115`), since
each marks the flag while the breaker can stay `CLOSED`. A duplicate enqueue
only causes one redundant, correct rebuild. No new defect found. Remaining
gap, not a defect: no test exercises the 30 s path (the reconciler test log
shows the rebuild fired from the breaker transition about 0.5 s after recovery).
The comment at `:96` still credits the transition listener; see F-11.

### 14/F-08 [P3] closed - Rebuild pipeline is not atomic, so readers can observe a half-rebuilt live state

**File:** backend/src/main/java/com/railops/backend/LiveStateReconciler.java:137
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality)
**Why it matters:** `executePipelined` batches the round trip but does not
wrap it in `MULTI/EXEC`. Other clients' commands interleave, so a dashboard read
between `DEL services` and `SADD`, or between `DEL recent:events` and the push,
sees no services or no recent events. When no summary version key existed yet,
a summary built from partly written counters can pass `cache-summary.lua`'s
version check and be cached for 5 s. The effect is transient.
**Suggested fix:** Call `operations.multi()` at the start of the
`SessionCallback` and `operations.exec()` at the end. It stays one pipelined
round trip and becomes atomic to readers.
**Resolution:** Fixed by wrapping the `SessionCallback` body in
`operations.multi()` / `operations.exec()`. Verified by the existing reconciler
convergence test passing unchanged (`mvn -B -pl backend -am verify`); no new
test asserts atomicity directly, since observing a mid-pipeline state from a
concurrent reader is not practically assertable without instrumenting Redis
itself. Awaiting re-review.
Closed 2026-09-29 by /audit independent (fresh subagent, claude-opus-5-5) at
94754b5. Re-examined `LiveStateReconciler.java:154-220`: `multi()` is the first
and `exec()` the last command in the `SessionCallback`, both on the pipeline's
dedicated connection, so Redis applies the batch atomically with respect to
other clients. `LiveStateReconcilerIntegrationTest` passed in this pass and its
post-recovery asserts (`status:OPEN:count` = 0, `status:ACKNOWLEDGED:count` =
2) can only hold if the queued transaction actually executed, because the Lua
path left `OPEN` = 1 after the failed status change. A command error at `EXEC`
still surfaces from `executePipelined`, is caught at `:113`, and re-marks the
flag. No new defect found.

## Independent review

# Independent Review

**Status:** passed
**Target commit:** 94754b5e3f9f8f2ef3fa373de03b95cdf1dc48d1
**Base commit:** 8a71b5b624d8d8f0458f24e90ad08a3dd5405c41
**Base ref:** master
**Spec hash:** 86f00e718dd4e7aacdc74b77e12b7d23a4e28fd2987350b1e38b80f6c0d0038f
**Prepared by:** claude
**Builder model:** claude-sonnet-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-29T13:05:00+03:00
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-29T16:36:00+03:00
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Handoff

Review the active spec and the complete `<base>..<target>` delta in a fresh
session or isolated subagent without the builder conversation. Run all Audit lenses from scratch.
Run Check when required above. Do not edit product code, accept findings, or
reuse the existing findings as the review scope.

## Commands

- `git rev-parse HEAD`: pass (equals Target commit)
- `git merge-base master 94754b5e3f9f8f2ef3fa373de03b95cdf1dc48d1`: pass (equals Base commit)
- `sha256sum blueprint/context/current-feature.md`: pass (equals Spec hash; spec is tracked, no snapshot)
- `git status --porcelain=v1 --untracked-files=all`: pass (only `blueprint/context/review.md` differed before this review)
- `mvn -B -pl backend -am verify`: pass (224 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS)

## Evidence

- Reviewed the full `8a71b5b..94754b5` delta: 3 commits, 22 files (backend main and test sources, `pom.xml`, `application.yml`, `.gitignore`, spec, findings).
- F-06: `events` has only two writers, and both hold `LiveStateLock.forLiveStateWrite()` across the Postgres write and Redis apply. The reconciler holds the write lock for the whole snapshot-then-write. `EventIngestionServiceTest` proves an ingest blocks on a held rebuild lock.
- F-07: `@Scheduled` retry at `LiveStateReconciler.java:81` is wired (`@EnableScheduling` present) and covers the sub-threshold, startup-check, and failed-rebuild paths.
- F-08: `MULTI`/`EXEC` wraps the rebuild batch. The reconciler integration test's post-recovery counters are only reachable if the transaction executed.
- Test log: in `LiveStateReconcilerIntegrationTest`, the rebuild ran from the breaker's HALF_OPEN to CLOSED transition about 0.5 s after Redis resumed, not from the 30 s retry.
- Security: no new endpoints or untrusted inputs. Actuator exposure is still `health` only, so resilience4j endpoints are not published. No secrets in the delta.
- Rebuilt Redis keys cover every key written by `apply-event.lua`/`apply-status-change.lua` except the intentionally excluded `processed:{id}` guards.

## Findings

- F-06 [P1] closed, F-07 [P2] closed, F-08 [P3] closed
- F-09 [P3] open (re-examined, partially addressed), F-10 [P3] open (re-examined, line updated)
- F-11 [P3] open (new): spec contract and reconciler comments contradict the repaired design, plus em dashes in `IncidentStatusService.java:44`
- F-12 [P3] unverified (new): Redis data loss with no failed write is never flagged for reconciliation
- No P0 or P1 finding is open or fixed

## Remaining risk

- Check was not required and was not run: no running-stack verification of the four dashboard endpoints with Redis stopped.
- No test exercises the 30 s scheduled reconcile retry (F-07) or `buildSummary()`'s own fallback branch (F-09).
- Postgres fallback cost is unmeasured: each uncached summary request runs about 10 aggregate queries, including `DISTINCT ON (service)` with no `(service, timestamp)` index. This only applies during a Redis outage.
- No dependency vulnerability scan is available offline for the new `resilience4j-spring-boot3` 2.4.0 dependency.
- Frontend and producer commands were not run because the delta touches neither module.
