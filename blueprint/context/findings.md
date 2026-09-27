# Findings

> **Generated file.** The findings ledger: review findings raised by `/audit`
> against the work in progress, each with a durable ID, severity (P0-P3), and
> status. `/implement` marks repaired findings `fixed`, a later `/audit` pass
> moves them to `closed`, and `/complete` refuses to merge while any P0 or P1
> finding is `open` or `fixed`, then archives resolved findings with the work
> and resets this file.

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
