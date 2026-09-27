# Feature: Dashboard data APIs

**From build-plan:** feature 7
**Build attempt:** 1
**Branch:** feature/dashboard-data-apis
**Status:** verified

## Goal

Give the dashboard (Feature 8) and service status page (Feature 10) their read
APIs over the Redis live state built in Features 4 and 6: a cached summary,
per-service health, a per-minute severity timeline, and the recent events list.

## In scope

- `GET /api/dashboard/summary` - read-through cached summary in
  `cache:dashboard:summary` (JSON string, 5s TTL).
- `GET /api/services` - per-service `status`, `lastEventTime`,
  `latestSeverity`, `openCount`, `activeCount`.
- `GET /api/dashboard/timeline?minutes=60` - per-minute counts by severity from
  `timeline:{yyyyMMddHHmm}` (UTC, bucketed by event `timestamp`); `minutes`
  1-120, default 60, else 400.
- `GET /api/dashboard/recent-events?limit=20` - ids from `recent:events`, rows
  loaded from Postgres; `limit` 1-50, default 20, else 400.
- Summary cache eviction on an applied status change (the overview's
  `PUT /api/events/{eventId}/status` "evicts summary cache" contract).
- Swagger descriptions, ProblemDetail errors through the existing
  `ApiExceptionHandler`, README section.

## Out of scope

- Postgres fallback when Redis is down, circuit breaker, reconciler (Feature 14).
  Until then a Redis failure on these reads returns the existing 500
  ProblemDetail ("The request could not be processed"). This is a planned,
  documented gap against the "Redis failures degrade to Postgres" standard, not
  a new decision.
- STOMP broadcasts and throttled `/topic/summary` (Feature 12).
- Evicting the summary cache on ingestion: new events show within the 5s TTL.
- Any frontend work (Features 8 and 10).
- Redis keys other than `cache:dashboard:summary`, or changes to
  `apply-event.lua`.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, running the backend
test command after each logic-bearing step, then present one review packet.
`workflow.checkpointCommits` is `disabled`: no step commits. `/complete` creates
the feature commit.

## Build steps

- [x] **1. Services and summary read model with cache.** Add response records,
  a `ServiceHealth` enum, and `DashboardQueryService` with `services()` and
  `summary()`. Summary reads `cache:dashboard:summary` first; on a miss it
  builds from the counter keys and service hashes and writes the JSON with
  `SET ... EX 5`. Add `cache:dashboard:summary` as `KEYS[5]` of
  `apply-status-change.lua`, deleted after the counters change (not on the
  `return 0` path), and pass it from `LiveStateUpdater.applyStatusChange`.
  **Done when:** Redis-Testcontainers tests show counts matching events applied
  through `LiveStateUpdater`, zero/empty values on an empty Redis, a cache hit
  returning the stored value while counters change, and an applied status
  change deleting the cache key; `mvn -B -pl backend -am verify` is green.
- [x] **2. Timeline and recent events.** Add `timeline(minutes)` and
  `recentEvents(limit)` to `DashboardQueryService`, plus
  `IncidentEventRepository.findByEventIdIn(Collection<String>)`.
  **Done when:** tests show zero-filled, oldest-first buckets covering exactly
  `minutes` minutes ending at the current minute, excluding older buckets; and
  recent events in `recent:events` order, deduplicated, skipping ids missing
  from Postgres, capped at `limit`; backend tests green.
- [x] **3. Controller, validation, docs.** Add `DashboardController` for the
  four GET routes with `@Min`/`@Max` validation and Swagger `@Operation`/
  `@Parameter` text; add a README "Dashboard data APIs" section.
  **Done when:** `@WebMvcTest` tests cover each route's JSON shape and
  defaults, `minutes`/`limit` at 0, 1, max, max+1 and a non-number (400
  ProblemDetail naming the parameter without echoing the value); backend tests
  green; on the rebuilt compose stack all four endpoints answer with seeded
  data, `/v3/api-docs` lists them, and a status change is reflected in the
  summary immediately rather than after 5s.

## Files / areas

New, in `backend/src/main/java/com/railops/backend/`:

- `DashboardController.java`
- `DashboardQueryService.java`
- `DashboardSummary.java`, `ServiceSummary.java` (summary `services[]` item),
  `ServiceState.java` (`/api/services` item), `TimelineBucket.java`
- `ServiceHealth.java` - `HEALTHY`, `DEGRADED`, `DOWN` (the values the Lua
  scripts write)

Changed:

- `backend/src/main/resources/redis/apply-status-change.lua` - `KEYS[5]` DEL
- `LiveStateUpdater.java` - pass the cache key; expose `SUMMARY_CACHE_KEY`
  and `timelineKey(Instant)` for the read side
- `IncidentEventRepository.java` - `findByEventIdIn`
- `README.md` - endpoints, cache, known Redis-down limitation

Tests in `backend/src/test/java/com/railops/backend/`:

- `DashboardQueryServiceIntegrationTest.java` (Redis Testcontainer, like
  `LiveStateUpdaterIntegrationTest`; repository mocked with Mockito for recent
  events)
- `DashboardControllerTest.java` (`@WebMvcTest`, service mocked)
- `LiveStateUpdaterIntegrationTest.java` - status change deletes the cache key;
  the no-live-state path leaves it

## Data / contracts

All times are ISO-8601 UTC `Instant`s serialized by the existing Jackson
config. Severity keys are always all four, in `Severity` order, with 0 for a
missing key. Missing Redis counters read as 0. Lists of services are sorted by
name. Records are Java records.

**Summary** (`GET /api/dashboard/summary`, 200):

```json
{
  "totalEvents": 214,            // events:count
  "openEvents": 120,             // status:OPEN:count
  "acknowledgedEvents": 30,      // status:ACKNOWLEDGED:count
  "criticalEvents": 12,          // active:CRITICAL:count (CRITICAL, not RESOLVED)
  "severityDistribution": {"INFO": 90, "WARNING": 70, "MAJOR": 40, "CRITICAL": 14},
  "services": [{"name": "signal-service", "status": "DOWN",
                "lastEventTime": "2026-09-27T12:30:05.123Z"}]
}
```

**Services** (`GET /api/services`, 200): JSON array of
`{"name","status","lastEventTime","latestSeverity","openCount","activeCount"}`
from `SMEMBERS services` plus each `service:{name}` hash. A member whose hash is
missing is skipped. Empty Redis returns `[]`.

**Timeline** (`GET /api/dashboard/timeline?minutes=N`, 200): JSON array of
`minutes` items, oldest first, the last item being the current UTC minute:
`{"minute":"2026-09-27T12:30:00Z","counts":{"INFO":0,"WARNING":2,"MAJOR":0,"CRITICAL":1}}`.
Missing buckets are zero-filled. Buckets for future minutes (event timestamps
ahead of the server clock) are not included. All bucket hashes are read in one
pipelined round trip. The service takes the current instant through a
package-private overload so tests control the minute boundary.

**Recent events** (`GET /api/dashboard/recent-events?limit=N`, 200): JSON
array of the existing `EventResponse`, newest first, from `LRANGE recent:events
0 limit-1`, deduplicated keeping the first occurrence (a redelivery after the
24h apply guard expires can push an id twice), and dropping ids with no
Postgres row. Rows come from one `findByEventIdIn` query.

**Summary cache:** key `cache:dashboard:summary`, value is the summary JSON,
`EX 5`. A hit is returned as stored. A miss builds, stores, and returns. An
applied status change deletes the key inside `apply-status-change.lua`, so the
next read rebuilds. A read that races the change can re-cache pre-change
counts for at most 5s; this is accepted. The summary's counter reads are not a
single atomic snapshot; concurrent ingestion can make fields differ by the
in-flight events, which the next refresh corrects.

**Errors:** `minutes` or `limit` out of range returns 400 ProblemDetail, title
"Invalid query", detail `minutes must be between 1 and 120` / `limit must be
between 1 and 50` (existing `handleHandlerMethodValidationException`). A
non-integer returns `... must be a whole number` (existing
`handleTypeMismatch`). Redis unavailable returns the catch-all 500 until
Feature 14.

## Testing

Backend test command: `mvn -B -pl backend -am verify` (JUnit 5, Mockito,
Testcontainers; Docker Desktop required). No Verify command is declared.
Master was last green at 144 backend tests when Feature 6 completed; this spec
did not re-run it.

- Integration (Redis container): summary and services values against events
  applied with `LiveStateUpdater.applyEvent` and `applyStatusChange`; empty
  Redis; cache hit, miss, TTL set to at most 5s, eviction on status change;
  timeline zero-fill, window edges, and future-bucket exclusion with a fixed
  instant; recent-events order, dedupe, missing rows, limit.
- Web layer (`@WebMvcTest`): shapes, defaults, and all 400 cases above.
- Live check in step 3 on the compose stack (backend host port from
  `BACKEND_PORT`, 8083 in the current local setup).

## Notes for the AI

- Follow the existing style: package `com.railops.backend`, records for DTOs,
  constructor injection, `StringRedisTemplate`, messages like
  `EventController`'s `@Min`/`@Max`.
- Parse `lastEventTime` with `Instant.parse` (the fixed `EVENT_TIME` output is
  ISO-8601); a service hash without it yields `null`. Parse `status` into
  `ServiceHealth`.
- Use the Spring-managed `ObjectMapper` for the cache JSON so `Instant`s match
  the API's serialization.
- Keep controllers thin; all Redis and repository access goes through
  `DashboardQueryService`.
- Do not add a `Clock` bean or configuration properties for the TTL or limits;
  they are fixed contracts from the overview.
- Update `LiveStateUpdater`'s and the script's header comments for the new key.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":9775,"specSha256":"caed6f9dba4eb3b04ec3caac3736d7b0668d80a4b85a435cf2e06c86bc0de769","branch":"refs/heads/feature/dashboard-data-apis","head":"9b148630303df2c8b438b3202d02285b9393c916","baseRef":"refs/heads/master","baseCommit":"9b148630303df2c8b438b3202d02285b9393c916","sourceTree":"6499df539bd3bf05e5ef577b8effafaca530f2b0","absentOptional":[]} -->
