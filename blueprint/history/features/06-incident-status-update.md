# Feature: Incident status update

**From build-plan:** feature 6
**Build attempt:** 1
**Branch:** feature/incident-status-update
**Status:** verified

## Goal

Operators can move an incident through its lifecycle with
`PUT /api/events/{eventId}/status`. Postgres enforces the lifecycle rules and
detects overlapping writes. After each committed change, the Redis live state
(status counters, active-per-severity counts, open and active counts, service
health) is updated atomically, so it stays consistent with Postgres.

## In scope

- Lifecycle rules, defined once on `EventStatus`:
  - `OPEN` -> `ACKNOWLEDGED`, `RESOLVED`
  - `ACKNOWLEDGED` -> `RESOLVED`
  - `RESOLVED` -> `OPEN` (reopen)
- `PUT /api/events/{eventId}/status` with a JSON body `{"status":"<EventStatus>"}`:
  - Allowed transition: commit to Postgres (`status`, `updated_at`, `@Version`
    bump), then apply the Redis delta. Returns 200 with the updated `EventResponse`.
  - Same status: 200 with the unchanged `EventResponse`. No Postgres write
    (`version` and `updatedAt` stay the same) and no Redis change.
  - Disallowed transition: 409 ProblemDetail with
    `type: /problems/invalid-status-transition` and an `allowedTransitions` array.
  - Unknown `eventId`: 404 (the existing `EventNotFoundException` ProblemDetail).
  - Unknown status value, a missing or null `status`, or an unreadable body:
    400 ProblemDetail that names `status` and its allowed values without echoing
    the rejected input.
  - Overlapping server-side write (JPA `@Version` conflict): 409 ProblemDetail.
    Clients send no version.
- Lua `apply-status-change`: atomically moves one event's contribution between
  statuses and recomputes service health with the same rule `apply-event` uses.
- Redis failure after the Postgres commit: the request still returns 200
  (Postgres is the source of truth; see coding standards, "Every Redis failure
  path must degrade to Postgres, not break the API"). The failure is logged at
  WARN with the `eventId`, without values from the request.
- OpenAPI docs for the endpoint, and a README section on the endpoint.

## Out of scope

- Setting the reconcile-needed flag and the reconciler, including the lock that
  status updates wait on (feature 14). This feature leaves one clearly marked
  catch block where feature 14 will set the flag.
- Summary-cache eviction (feature 7 introduces the cache) and WebSocket
  broadcast (feature 12).
- Any frontend work (features 9 and 13), authorization (stretch feature 20),
  and Redis circuit breaking (feature 14).
- Changing `timeline:*`, `recent:events`, `events:count`, `severity:*:count`,
  `lastEventTime`, or `latestSeverity`. A status change is not a new event.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one
review packet at the end. `workflow.checkpointCommits` is `disabled`, so no
per-step commits are made. `/complete` creates the final feature commit.
Backend tests (`mvn -B -pl backend -am verify`) gate every logic-bearing step.

## Build steps

- [x] **1. Lifecycle and Postgres write.** Add `EventStatus.allowedTransitions()`
  (an unmodifiable, ordered set) and `canTransitionTo(EventStatus)`. Add
  `IncidentEvent.changeStatus(EventStatus, Instant)`, which sets `status` and
  `updatedAt`. Add `IncidentStatusService.changeStatus(eventId, target)`. Inside
  a `TransactionTemplate` transaction it loads the row by `eventId`, throws
  `EventNotFoundException` when the row is missing, returns the unchanged row
  for the same status, throws `InvalidStatusTransitionException` (carrying
  `from`, `to`, and `allowedTransitions`) for a disallowed target, and otherwise
  calls `changeStatus(target, Instant.now())`. The commit happens when the
  template returns, so a version conflict surfaces there as Spring's
  `ObjectOptimisticLockingFailureException`. It returns `EventResponse`.
  **Done when** backend tests pass, including:
  - An `EventStatus` unit test covering every from/to pair.
  - A Postgres Testcontainers test covering each allowed transition (status,
    newer `updatedAt`, `version` + 1), same status (unchanged `version` and
    `updatedAt`), a disallowed transition, and an unknown id.
  - An `@Version` conflict test: two detached copies of the same row are saved
    in turn, and the second save throws `ObjectOptimisticLockingFailureException`.
- [x] **2. Redis delta.** Add `redis/apply-status-change.lua` and
  `LiveStateUpdater.applyStatusChange(service, severity, from, to)`. After a
  real change commits, the service calls it with the committed row's values.
  It never calls it for a same-status request or a failed commit. A Redis
  exception is caught, logged at WARN with the `eventId`, and the 200 result is
  kept. **Done when** backend tests pass, including:
  - A Redis Testcontainers test that applies an event with `applyEvent` and
    then each allowed transition, and asserts `status:*:count`,
    `active:{SEV}:count`, the service hash's `active:{SEV}`, `openCount`,
    `activeCount`, and `status` (health). This covers DOWN -> HEALTHY when the
    last active CRITICAL is resolved, and a reopen returning it to DOWN.
  - A missing `service:{name}` hash, which leaves every key untouched and
    returns 0.
  - A Mockito test showing that a throwing `LiveStateUpdater` still returns the
    updated response, and that a same-status request never calls Redis.
- [x] **3. API endpoint and docs.** Add `StatusChangeRequest(@NotNull
  EventStatus status)` and `PUT /api/events/{eventId}/status` on
  `EventController`, taking `@Valid @RequestBody`. In `ApiExceptionHandler`:
  - Map `InvalidStatusTransitionException` to 409 with title
    `Invalid status transition`, type `/problems/invalid-status-transition`,
    detail `Cannot change status from X to Y`, and property
    `allowedTransitions`.
  - Map `ObjectOptimisticLockingFailureException` to 409 with title
    `Concurrent update` and detail `The event was changed by another request;
    reload it and try again`.
  - Override `handleMethodArgumentNotValid` (missing or null `status`) and
    `handleHttpMessageNotReadable` (unknown enum value, malformed JSON, or an
    empty body) to return 400 with title `Invalid request`. An enum format error
    names `status` and lists `OPEN, ACKNOWLEDGED, RESOLVED`. Other unreadable
    bodies get a fixed message. The rejected text is never echoed.
    The existing query 400s keep the title `Invalid query`.

  Add `@Parameter` or `@Operation` docs and a README "Incident status update"
  section with the lifecycle table, example request and response, and the error
  cases. **Done when:**
  - `@WebMvcTest` cases pass for 200, same-status 200, 404, both 409 shapes, and
    the 400 cases (with non-echo assertions using unique marker values).
  - `mvn -B -pl backend -am verify` is green.
  - A live check against the rebuilt compose stack (backend host port from
    `BACKEND_PORT`, default 8080) shows OPEN -> ACKNOWLEDGED -> RESOLVED -> OPEN
    on a real event with the matching Redis counter and health changes read via
    `redis-cli`, a 409 for RESOLVED -> ACKNOWLEDGED, and a 400 for an unknown
    status.

## Files / areas

- `backend/src/main/java/com/railops/backend/EventStatus.java` - transition rules
- `backend/src/main/java/com/railops/backend/IncidentEvent.java` - `changeStatus`
- `backend/src/main/java/com/railops/backend/IncidentStatusService.java` - new
- `backend/src/main/java/com/railops/backend/InvalidStatusTransitionException.java` - new
- `backend/src/main/java/com/railops/backend/StatusChangeRequest.java` - new record
- `backend/src/main/java/com/railops/backend/LiveStateUpdater.java` - `applyStatusChange`
- `backend/src/main/resources/redis/apply-status-change.lua` - new
- `backend/src/main/java/com/railops/backend/EventController.java` - PUT endpoint
- `backend/src/main/java/com/railops/backend/ApiExceptionHandler.java` - 409 and body 400 handlers
- `backend/src/test/java/com/railops/backend/` - `EventStatusTest`,
  `IncidentStatusServiceIntegrationTest` (Postgres, with a `@MockitoBean`
  `LiveStateUpdater` for the Redis calls and the Redis-failure case), additions
  to `LiveStateUpdaterIntegrationTest` and `EventControllerTest`
- `backend/src/main/resources/application.yml` - `fail-on-numbers-for-enums`, so
  `{"status":1}` is a 400 instead of an enum ordinal
- `README.md` - new section after "Events API"

No Flyway migration is needed: `status`, `updated_at`, and `version` already
exist in `V1__create_events.sql`.

## Data / contracts

**Request:** `PUT /api/events/{eventId}/status`, `Content-Type: application/json`,
body `{"status":"ACKNOWLEDGED"}`. Other body fields are ignored (Jackson default).

**200:** the `EventResponse` record already returned by `GET /api/events/{eventId}`.

**409 invalid transition:**
```json
{"type":"/problems/invalid-status-transition","title":"Invalid status transition",
 "status":409,"detail":"Cannot change status from RESOLVED to ACKNOWLEDGED",
 "instance":"/api/events/EVT-1/status","allowedTransitions":["OPEN"]}
```
`allowedTransitions` lists values in `EventStatus` declaration order.

**409 concurrent update:** `type` is `about:blank`, the title is `Concurrent
update`, and the detail is fixed (see step 3).

**400:** the title is `Invalid request`. The detail is either `status must be one
of OPEN, ACKNOWLEDGED, RESOLVED`, `status is required`, or a fixed "request body
must be JSON like {"status":"ACKNOWLEDGED"}" message.

**Postgres:** the update goes through JPA on the loaded entity: `status`,
`updated_at = Instant.now()`, and `version` incremented by Hibernate. The
`WHERE version = ?` check is what detects overlapping writes.

**Redis `apply-status-change`:**
- KEYS: 1 `service:{name}`, 2 `status:{FROM}:count`, 3 `status:{TO}:count`,
  4 `active:{SEV}:count`
- ARGV: 1 severity, 2 from, 3 to
- If KEYS[1] does not exist, return 0 and change nothing. The event was never
  applied, or Redis was wiped; the reconciler in feature 14 rebuilds this.
- Otherwise:
  - `DECR` from-count and `INCR` to-count.
  - Active means `OPEN` or `ACKNOWLEDGED`. On active -> inactive, decrement
    `active:{SEV}:count`, hash `active:{SEV}`, and `activeCount`. On inactive
    -> active, increment them.
  - Hash `openCount` gets -1 when from is `OPEN` and +1 when to is `OPEN`.
  - Recompute hash `status` with the `apply-event` health rule.
  - Return 1.
- The deltas are additive, so two committed changes applied in either order end
  in the same state.

## Testing

Backend tests (`mvn -B -pl backend -am verify`, Docker Desktop running) are the
gate. The coverage is listed per step above: lifecycle unit test, Postgres
integration for the write rules and `@Version`, Redis integration for the Lua
deltas and health, a Mockito test for Redis-failure degradation, and
`@WebMvcTest` for the HTTP contract. The step 3 live check covers the full
stack. There is no frontend and no browser coverage in this feature.

## Notes for the AI

- Reuse `EventQueryService`'s `findByEventId` pattern and `EventResponse.from`.
  Do not return the entity from the controller.
- `TransactionTemplate` is auto-configured by Spring Boot. Use it instead of
  `@Transactional`, so the Redis call runs after the commit without a
  self-invocation proxy trap.
- Compute the Redis arguments from the committed row (service, severity) and
  from the `from` status read inside the transaction, never from request input.
- Known limitation, documented in the README and not fixed here: a status
  change can commit after ingestion's Postgres insert but before its
  `apply-event`. Because the deltas are additive, the final counts are still
  correct when the service hash already exists. They go stale in two cases:
  - The service has no live state yet, so the delta is skipped and
    `apply-event` then counts the old status.
  - Ingestion is retrying after a Redis outage and re-reads the stored row,
    which already has the new status.

  The feature 14 reconciler repairs both.
- Keep the health rule identical in both Lua scripts. Keep each script
  self-contained: Redis scripts cannot share functions without Redis Functions,
  and adding those is not warranted here.
- Do not bump `updatedAt` or `version` on a same-status request.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":12491,"specSha256":"f0fd3cf0e5deada74f4573dff9c2d5fd6cfd0f2fddaef8d16c901a450ec2fb65","branch":"refs/heads/feature/incident-status-update","head":"a3116b75649445092444d3c7de67e201ea9f1d37","baseRef":"refs/heads/master","baseCommit":"a3116b75649445092444d3c7de67e201ea9f1d37","sourceTree":"365162d88a38af8a56702082104ba0144607b321","absentOptional":[]} -->
