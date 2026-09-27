# Findings

> **Generated file.** The findings ledger: review findings raised by `/audit`
> against the work in progress, each with a durable ID, severity (P0-P3), and
> status. `/implement` marks repaired findings `fixed`, a later `/audit` pass
> moves them to `closed`, and `/complete` refuses to merge while any P0 or P1
> finding is `open` or `fixed`, then archives resolved findings with the work
> and resets this file.

### F-02 [P3] open - A summary read racing a status change can re-cache the old counts for up to 5 s

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
**Resolution:**

### F-03 [P3] unverified - Event search and page counts scan the whole table as history grows

**File:** backend/src/main/java/com/railops/backend/EventQueryService.java:262
**Found:** 2026-09-27 by /audit (scope: full; lens: performance)
**Why it matters:** `q` becomes `lower(column) LIKE '%...%'` on message,
service and event ID, which no B-tree index can serve. Every list request also
runs a filtered `count(*)` for the page total. The Events page repeats both
every 5 s per open tab. At the producer default of one event every 2 s, the
table grows by about 43k rows a day. The demo scale is probably fine, but
there is no measurement.
**Suggested fix:** Measure first: `EXPLAIN ANALYZE` a `q` search and a
filtered count with a few hundred thousand rows. Act only if slow, for example
with a `pg_trgm` GIN index on `lower(message)` in a new Flyway migration, or by
keeping the default time-window sort without an exact total.
**Resolution:**
