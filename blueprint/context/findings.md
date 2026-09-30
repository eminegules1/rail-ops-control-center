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

### F-09 [P3] open - Spec-required test evidence for the reconciler and controller fallback is missing

**File:** backend/src/test/java/com/railops/backend/LiveStateReconcilerIntegrationTest.java:72
**Found:** 2026-09-29 by /audit independent (scope: current; lens: tests)
**Why it matters:** Step 4 requires asserting that the listener is paused and
resumed around the rebuild, and that the rebuild takes a bounded number of Redis
round trips. The test only infers resume from a later ingest and asserts
neither. Step 3 requires `DashboardController` 200s for all four endpoints with
Redis down; `DashboardControllerTest` is unchanged. `buildSummary()`'s own
fallback branch is never exercised, because `summary()` falls back at
`multiGet` first. The race test covers only the "status change waits for the
rebuild" ordering and does not cover in-flight ingestion (see F-06).
**Suggested fix:** Add a focused reconciler test with a mocked
`KafkaListenerEndpointRegistry` or container verifying `pause()` before the
Postgres reads and `resume()` after. Add a controller test with the service's
fallback path (or a broken Redis) for the four endpoints. Add one direct
`buildSummary()` fallback test.
**Resolution:** Re-examined 2026-09-29 by /audit independent (fresh subagent,
claude-opus-5-5) at 94754b5. Still open. The in-flight ingestion gap is now
covered by `EventIngestionServiceTest.ingestWaitsForAConcurrentRebuildToReleaseTheWriteLockThenAppliesAfterIt`.
The rest is unchanged: `DashboardControllerTest` is not in the
`8a71b5b..94754b5` delta, the reconciler test asserts neither pause/resume nor
a bounded round-trip count, and no test calls `buildSummary()` with a failing
Redis.

### F-10 [P3] open - ServiceHealth derivation is duplicated in two Java classes

**File:** backend/src/main/java/com/railops/backend/LiveStateReconciler.java:223
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality)
**Why it matters:** `LiveStateReconciler.healthOf(ServiceCounts)` and
`DashboardQueryService.healthOf(long, long, long)` implement the same precedence
rule, which must also match both Lua scripts. Two Java copies make drift likelier
if the rule changes.
**Suggested fix:** Keep one static method, for example on `ServiceCounts`
(`health()`), and call it from both classes. No current requirement is lost.
**Resolution:** Re-examined 2026-09-29 by /audit independent (fresh subagent,
claude-opus-5-5) at 94754b5. Still open. The duplicate is now at
`LiveStateReconciler.java:223` and `DashboardQueryService.java:275`.

### F-11 [P3] open - Spec contract and reconciler comments still describe the pre-repair design

**File:** blueprint/context/current-feature.md:264
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality)
**Why it matters:** The Data / contracts section still says
"`EventIngestionService` needs no such lock: ingestion is already halted during
a rebuild because the Kafka listener is paused first". Step 5 and the code
(`EventIngestionService.java:65`) say the opposite. This spec is archived as the
feature record, so the history will contain the rationale F-06 proved wrong.
The code has the same drift. The `LiveStateReconciler.java:29-31` class doc
lists only the startup and breaker-close triggers and omits the 30 s retry. The
`:96` comment says "the state-transition listener retries once it recovers",
but the startup `hasKey` call is not breaker-guarded, so that failure never
opens the breaker and only the scheduled retry recovers it. `:114` makes the
same claim for a failed rebuild. `IncidentStatusService.java:44` also contains
em dashes, which the coding standards' Writing section forbids in comments.
**Suggested fix:** Correct the spec paragraph to match Step 5 (a local spec
revision needs a new review request). Update the two reconciler comments and
the class doc to name the scheduled retry. Replace the em dashes with commas or
parentheses. No behavior change, and no current requirement is lost.
**Resolution:**

### F-12 [P3] unverified - Redis data loss with no failed write is never flagged for reconciliation

**File:** backend/src/main/java/com/railops/backend/IncidentStatusService.java:75
**Found:** 2026-09-29 by /audit independent (scope: current; lens: quality)
**Why it matters:** `applyStatusChange` returns `false` exactly when the
service hash is missing. `apply-status-change.lua` documents this as "never
applied, or Redis was wiped". Lines 75-78 log that and move on without
`reconcileState.markNeeded()`. The dashboard read fallbacks
(`DashboardQueryService.java:63,107,162,212,235`) never mark the flag either.
The empty-state check (`LiveStateReconciler.java:92`) runs only at boot, outside
the lock and pause. So if Redis loses its data while no ingestion or status
apply fails during the gap (reads alone can trip and close the breaker), the
flag stays clear. New events then rebuild counters from zero. The dashboard
indefinitely serves understated totals as current, which contradicts the
feature goal. This is unverified because compose runs Redis with
`--appendonly yes` on a named volume, so a plain restart keeps the data. It
needs volume loss, a recreated container, or a lost AOF tail.
**Suggested fix:** Call `reconcileState.markNeeded()` in the
`applyStatusChange` returns-false branch, since that is a direct signal of a
wiped live state. Optionally, move the startup emptiness check inside
`reconcile()`'s lock, or repeat it from `retryIfStillNeeded()`. Add one test.
**Resolution:**

### F-15 [P3] open - The 3.3 MB login video is still mounted, and so fetched, for reduced-motion visitors

**File:** frontend/src/components/login/BrandPanel.tsx:43
**Found:** 2026-09-30 by /audit independent (scope: current; lens: performance)
**Why it matters:** From `md` up, `LoginPage.tsx:91` always renders
`BrandPanel`, which always mounts a `<video autoPlay src="/Alstom_History_Innovation.mp4">`.
Reduced motion only adds `display: none` through CSS (line 43). CSS display does
not stop a media element's resource selection, and `autoplay` asks the browser to
buffer, so a reduced-motion visitor on a desktop still starts downloading the
3.3 MB file they never see. The small-screen case is correct: below `md` the
panel is not rendered at all. No test covers the reduced-motion path (jsdom
cannot evaluate the CSS media query). Not observed in a network trace in this
pass; the conclusion rests on the code path and standard media-element behavior.
**Suggested fix:** Decide in JS, like the breakpoint: render the `<video>` only
when `useMediaQuery('(prefers-reduced-motion: no-preference)', { noSsr: true })`
also matches, and keep the poster background otherwise. Add a test with the
`matchMedia` stub that reduced motion renders no `video`. No current requirement
is lost.
**Resolution:**

### F-16 [P3] open - The looping background video has no way to pause it

**File:** frontend/src/components/login/BrandPanel.tsx:33
**Found:** 2026-09-30 by /audit independent (scope: current; lens: quality)
**Why it matters:** The video autoplays and loops indefinitely (about 10.8 s per
loop) beside the sign-in form, with `aria-hidden`, `tabIndex={-1}` and no
controls. WCAG 2.2.2 (Pause, Stop, Hide, level A) requires a pause or stop
mechanism for automatically started moving content that lasts more than 5 s and
sits next to other content. Hiding it for `prefers-reduced-motion` helps only
users who set that OS preference. AGENTS.md says simplicity never removes
accessibility.
**Suggested fix:** Add one small visible pause/play toggle button over the panel
that calls `video.pause()` and `play()` (keeps the look), or stop the motion
within 5 s (for example, play once without `loop` using a clip trimmed to under
5 s). The toggle loses no current requirement; the second option changes the
approved step-6 "looping" design and needs the user's decision.
**Resolution:**

### F-17 [P3] open - Unreferenced duplicate logo and orphaned favicon ship in frontend/public

**File:** frontend/public/ALSTOM_CORPORATE LOGO_2 COLOURS_RGB.svg:1
**Found:** 2026-09-30 by /audit independent (scope: current; lens: quality)
**Why it matters:** `ALSTOM_CORPORATE LOGO_2 COLOURS_RGB.svg` is byte-identical to
`alstom-logo.svg` (`cmp` reports no difference) and nothing in `src/`,
`index.html` or the docs references it; its file name also contains spaces. After
`index.html` switched the icon to `/Alstom_logo.svg`, the old
`frontend/public/favicon.svg` is referenced nowhere either. Vite copies both into
every build, so they are dead files that invite confusion about which logo is
canonical.
**Suggested fix:** Delete `ALSTOM_CORPORATE LOGO_2 COLOURS_RGB.svg` and
`favicon.svg`. Nothing replaces them (`alstom-logo.svg` and `Alstom_logo.svg` stay
in use), and no current requirement is lost.
**Resolution:**

### F-18 [P3] open - IncidentStatusServiceIntegrationTest fails intermittently on a 1 ms rounding difference

**File:** backend/src/test/java/com/railops/backend/IncidentStatusServiceIntegrationTest.java:92
**Found:** 2026-09-30 by /audit independent (scope: current; lens: tests)
**Why it matters:** Not part of this feature's delta, but it made the full
`mvn -B -pl backend -am verify` fail once in this pass (285 run, 1 failure:
`allowedTransitionIsStored[1]`, expected `...57.572Z` but was `...57.571Z`); a
rerun of the class passed 10/10. `IncidentStatusService.java:69` stamps
`Instant.now()` (sub-microsecond on this JVM), PostgreSQL rounds it to
microseconds, and `EventResponse.java:23` truncates the in-memory value to
milliseconds. A time such as `.5719996` is returned as `.571` but stored as
`.572000`, so line 92's equality fails whenever the rounding carries into the
next millisecond. A flaky gate can hide or be mistaken for a real regression.
**Suggested fix:** Truncate the timestamp to microseconds where it is created
(`Instant.now().truncatedTo(ChronoUnit.MICROS)` in `IncidentStatusService`), so
the returned and stored values agree; or compare with a 1 ms tolerance in the
test. No current requirement is lost.
**Resolution:**
