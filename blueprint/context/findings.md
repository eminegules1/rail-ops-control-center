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

### F-04 [P3] fixed - Cross-origin handshake rejection on /ws has no automated test

**File:** backend/src/main/java/com/railops/backend/WebSocketConfig.java:40
**Found:** 2026-09-28 by /audit independent (scope: current; lens: tests, security)
**Why it matters:** The spec relies on Spring's default same-origin handshake
check as the only browser-facing guard on `/ws` (no auth in the MVP). No
backend test sends a handshake with a foreign `Origin`; `LiveUpdatesIntegrationTest`
connects without any `Origin`, which Spring always accepts. A later
`setAllowedOrigins("*")` or `setAllowedOriginPatterns("*")` would keep every
test green. The only evidence is the manual curl check in step 4.
**Suggested fix:** In `LiveUpdatesIntegrationTest`, connect once with
`WebSocketHttpHeaders` carrying `Origin: http://evil.example` and assert the
handshake fails (403), and optionally once with `Origin: http://localhost:<port>`
and assert it succeeds. No production change.
**Resolution:** Fixed by fix `test-cross-origin-rejection-on-the-websocket-endpoint`. `LiveUpdatesIntegrationTest.refusesHandshakeFromAnotherOrigin` connects with `Origin: http://evil.example` and requires the handshake to fail with 403; `acceptsHandshakeFromTheServersOwnOrigin` connects with the server's own origin. Widening the endpoint to `setAllowedOriginPatterns("*")` makes the first test fail. Awaiting re-review.

### F-05 [P3] unverified - A CREATED push can arrive after an UPDATED push for the same event with a stale status

**File:** backend/src/main/java/com/railops/backend/EventIngestionService.java:58
**Found:** 2026-09-28 by /audit independent (scope: current; lens: quality)
**Why it matters:** Ingestion reads the row (line 58), applies it to Redis,
then pushes CREATED (line 62). The row is already committed and visible to
`PUT /api/events/{id}/status` during that window. A status change committed in
between pushes UPDATED (for example ACKNOWLEDGED) first, and the later CREATED
still carries `OPEN`. A Feature 13 client that patches its cache from pushes in
arrival order would show the stale status until its next REST refetch. The
window is milliseconds and needs a client that acts on the event before its
push, so this is a lead, not a confirmed defect. The spec already declares
delivery best-effort and unordered.
**Suggested fix:** No backend change needed now. In Feature 13, have the client
ignore a push whose `event.updatedAt` is older than the cached row's, or treat
CREATED for a known row as a no-op.
**Resolution:**
