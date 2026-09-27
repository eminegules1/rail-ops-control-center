# Fix: Summary cache ignores status changes during a rebuild

**Type:** Fix
**Status:** verified
**Branch:** fix/summary-cache-ignores-status-changes-during-a-rebuild
**Fixes:** F-02

## The problem

`DashboardQueryService.summary()` handles a cache miss in three steps. It reads
the Redis counters, reads each service hash, then `SET`s
`cache:dashboard:summary` for 5 s. `apply-status-change.lua` deletes that key
in the same atomic step that changes the counters. If a status change lands
between the reads and the `SET`, its `DEL` runs first, and the summary written
afterwards holds the pre-change counts. The KPIs then show the old open and
critical numbers for up to 5 s.

That breaks two promises:

- The README says "A status change deletes it ... so the next read shows the
  change".
- The summary endpoint's OpenAPI text says "a status change refreshes it at
  once".

It is reachable. After every status change the Events page invalidates and
refetches the summary (`onSettled` in `useChangeEventStatus`), and it also
polls the summary every 5 s through `EventFilters`.

## The fix

Only cache a summary if no status change happened while it was being built. A
version counter records status changes, and the write becomes a
compare-and-set:

- **Status script bumps a version.** `apply-status-change.lua` gets a sixth
  key, `cache:dashboard:summary:version`, and `INCR`s it next to the existing
  `DEL` (only when it applies, same as the `DEL`). `LiveStateUpdater` adds the
  key constant and passes the key. The key has no TTL; it is one small
  counter. If Redis is wiped it simply starts again.
- **`summary()` reads the version first.** One `MGET` of the cache key and the
  version key replaces today's single `GET`. A readable cached value is
  returned as before. On a miss, the summary is built as today.
- **Conditional write.** A new `redis/cache-summary.lua` sets the cache (with
  the same 5 s TTL, as `PX` milliseconds) only if the version still equals the
  one read before building. A missing version compares as an empty string.
  Otherwise it leaves the cache empty. The freshly built summary is returned to
  the caller either way. It can only be stale for that one response, which is
  the same as any read that raced the change.
- To test the race deterministically, split `summary()` into package-private
  steps (read version, build, cache if unchanged) and keep `summary()` as the
  one public entry point.

Must not break:

- The 5 s cache and its hit path, including rebuilding after an unreadable
  entry.
- New events still wait for the TTL. `apply-event.lua` is not changed, so
  ingestion doesn't bump the version.
- `applyStatusChange` still returns false, and touches nothing, when the
  service has no live state.
- Ingestion, the other dashboard endpoints and the frontend don't change.

No new dependency. Redis scripting and `RedisScript` are already used by
`LiveStateUpdater`.

## Build steps

- [x] **1. Version-checked summary cache.** Add the version key to
  `apply-status-change.lua` and `LiveStateUpdater`. Add `cache-summary.lua`,
  and make `DashboardQueryService.summary()` read the version, build, and
  cache through the script. Tests in
  `DashboardQueryServiceIntegrationTest`:
  - The race: read the version, build a summary, apply a status change, then
    cache. The cache must stay empty, and the next `summary()` must show the
    changed counts.
  - The normal path still caches for 5 s.
  - A status change bumps the version. When the service has no live state it
    doesn't.

  Update `LiveStateUpdaterIntegrationTest` for the new key. In the README's
  summary cache paragraph, note that a summary built while a status change
  lands is not cached.
  _Done when:_ `mvn -B -pl backend -am verify` passes, and the race test fails
  if the version check is removed (the plain `SET` is restored).

## Verify

- `mvn -B -pl backend -am verify` passes.
- Rebuild with `docker compose up -d --build --wait`. On
  http://localhost:3000/events, open an OPEN event and resolve it, then open
  the Dashboard. The Open KPI already shows the lower count, and
  `redis-cli GET cache:dashboard:summary:version` in the redis container has
  gone up by one.

## Implementation notes

- `LiveStateUpdater.SUMMARY_VERSION_KEY` is `cache:dashboard:summary:version`.
  `apply-status-change.lua` takes it as KEYS[6] and `INCR`s it right after the
  `DEL`, so a status change for a service with no live state still touches
  nothing.
- New `redis/cache-summary.lua` compares `GET version or ''` with the version
  read before building, then `SET ... PX 5000`. `summary()` reads the cache and
  the version in one `MGET`. `buildSummary()` and `cacheSummary(summary,
  version)` are package-private, so the race can be tested step by step.
- Tests: `summaryBuiltWhileAStatusChangeLandsIsNotCached` (the race) and
  `summaryIsCachedWhenTheVersionIsUnchanged` in
  `DashboardQueryServiceIntegrationTest`. The live-state tests now check that
  the version goes 1 then 2 across two status changes, and is never created
  without live state. Mutation check: with `cache-summary.lua` reduced to a
  plain `SET`, only the race test failed.
- README: the summary cache paragraph describes the version check, and the
  table of Redis keys lists the version key.
- `mvn -B -pl backend -am verify`: 190 tests, 0 failures (previously 188).
- Live (backend rebuilt): the version key was unset. Resolving an OPEN event
  through `PUT /api/events/{id}/status` set it to `1`, and the summary read
  right after showed `openEvents` 14476, equal to Redis `status:OPEN:count`.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":5607,"specSha256":"5860aacdd687fbe88ae15538edf3ce7e46554222e89463adfc4057d2bbe4db31","branch":"refs/heads/fix/summary-cache-ignores-status-changes-during-a-rebuild","head":"827969cad546aedd94fe468b4221618d9f6ade7d","baseRef":"refs/heads/master","baseCommit":"827969cad546aedd94fe468b4221618d9f6ade7d","sourceTree":"6b12e8a7fa82130b0c17da273abe0d8bbbe3ce5d","absentOptional":[]} -->

## Findings

### summary-cache-ignores-status-changes-during-a-rebuild/F-02 [P3] closed - A summary read racing a status change can re-cache the old counts for up to 5 s

**File:** backend/src/main/java/com/railops/backend/DashboardQueryService.java:48
**Found:** 2026-09-27 by /audit (scope: full; lens: all)
**Why it matters:** `summary()` builds on a cache miss from several Redis
reads (the counters, then each service hash), then `SET`s the result with a
5 s TTL. If `apply-status-change.lua` runs between those reads and the `SET`,
its `DEL cache:dashboard:summary` (line 47) happens first, and the rebuilt
summary written afterwards holds the pre-change counts. KPIs then show the old
open and critical numbers for up to 5 s. That contradicts the README ("A
status change deletes it ... so the next read shows the change") and the
summary endpoint's OpenAPI text ("a status change refreshes it at once"). It is
reachable because the Events page polls the summary every 5 s (via
`EventFilters`) while operators change statuses. It is timing-dependent and
self-heals within one TTL.
**Suggested fix:** Smallest option: soften the README and OpenAPI wording to
"within 5 seconds". Or make the write conditional: have the status script also
`INCR` a `cache:dashboard:summary:version` key, have `summary()` read the
version before building, and write the cache through a tiny Lua script that
sets it only if the version is unchanged. No current requirement is lost
either way.
**Resolution:** Fixed by fix/summary-cache-ignores-status-changes-during-a-rebuild: `apply-status-change.lua` bumps `cache:dashboard:summary:version`, and `summary()` caches only through `cache-summary.lua`, which sets the cache only if the version is unchanged since the read. The new race test fails if the script is changed back to a plain SET. Live: a summary read right after resolving an event matched `status:OPEN:count`. Re-reviewed 2026-09-27 by /audit (scope: current; lens: all). Every ordering is covered: a status change after the `MGET` changes the version, so the compare-and-set skips the write; a change before it is already in the counters the build reads; a change after the write deletes the entry itself. `cache-summary.lua` is now the only writer of `cache:dashboard:summary` (checked with grep). A missing version compares as an empty string on both sides, so a Redis wipe only skips one write. The extra EVALSHA runs only on a cache miss. 190 backend tests pass, and the mutation check showed the race test fails without the version check. Closed.
