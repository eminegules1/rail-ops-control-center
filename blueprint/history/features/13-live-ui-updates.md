# Feature: Live UI updates

**From build-plan:** feature 13
**Build attempt:** 1
**Branch:** feature/live-ui-updates
**Status:** verified

## Goal

The dashboard, events and services pages stay current from the Feature 12 STOMP
push instead of polling. Pushed messages patch the TanStack Query cache, a
connection chip in the top bar shows live, reconnecting or offline, the client
reconnects on its own and reconciles over REST, polling runs only while the
socket is not live, and rows that were just created or updated get a brief
highlight.

## In scope

- `@stomp/stompjs` client over same-origin `/ws`, one connection for the app's
  lifetime, subscribed to `/topic/events` and `/topic/summary`.
- Connection chip in the top bar (`AppLayout`), announced to screen readers.
- Automatic reconnect with exponential backoff and REST reconciliation on every
  (re)connect.
- Cache patching for the summary, recent events, event list pages and event
  detail, with a stale-message guard (covers finding F-05 on the client).
- Throttled refetch for data a push cannot patch exactly (filtered/paginated
  event lists, timeline, services).
- Polling (`refetchInterval`) only while the socket is not live.
- Brief highlight on rows in the Events table and the dashboard Recent events
  list that were just created or updated, including the operator's own and other
  operators' status changes.

## Out of scope

- Backend changes (Feature 12 contract is fixed). Redis fallback (14), metrics
  (15), end-to-end tests (16), auth (20).
- Replay of missed messages; reconciliation is a REST refetch.
- Highlighting rows that appear through a polling refetch while offline; the
  existing fade-in on new Recent events rows still applies there.
- Highlighting rows on the Services page.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet. `workflow.checkpointCommits` is `disabled`: no per-step commits.
`/complete` creates the feature commit. Frontend `npm test`, `npm run lint` and
`npm run build` are the gate for every logic-bearing step.

## Build steps

- [x] 1. **Connection and chip.** Add `@stomp/stompjs` (7.x) to
  `frontend/package.json` with npm. Add `src/types/live.ts` (message types) and
  `src/api/stompConnection.ts`, the real connection factory (see Data /
  contracts). Add `src/api/liveUpdates.tsx`: a `LiveUpdatesProvider` that opens
  one connection through an injected `connect` factory, tracks the connection
  state (`connecting | live | reconnecting | offline`, rules below), deactivates
  on unmount, and exposes `useConnectionState()`. Mount it in `AppProviders`
  inside `QueryClientProvider`; `AppProviders` gains an optional `connectLive`
  prop defaulting to the real factory. Add a `FakeLiveConnection` test helper in
  `src/test/` and make `renderApp` pass it; confirm every test render path
  (including `App.test.tsx`) goes through a fake so no test opens a real socket.
  Add `src/components/layout/ConnectionChip.tsx` and render it in the top bar
  before `ThemeSwitch`.
  **Done when:** tests drive the fake connection and see the chip read
  Connecting, then Live after connect, Reconnecting after a drop, Offline after
  10 s without reconnecting (fake timers), and Live again after reconnect; all
  existing frontend tests, lint and build pass.

- [x] 2. **Cache patching and reconciliation.** Add pure functions in
  `src/lib/liveCache.ts` (message parsing and the patch rules below) with
  `liveCache.test.ts`. Wire the provider's message handlers to apply them with
  `queryClient.setQueryData`/`setQueriesData`, run the throttled refetches, and
  invalidate `['dashboard']`, `['events']` and `['services']` on every connect.
  **Done when:** unit tests cover parsing (valid, malformed JSON, wrong shape),
  the `updatedAt` guard (older ignored, equal or newer applied), CREATED
  prepend/dedupe/trim for recent events, UPDATED patch of list pages, recent
  events and detail, the in-flight status-change skip, and the throttle (one
  leading and one trailing refetch within 1000 ms, fake timers); a page test
  shows a pushed summary updating the KPI cards and a pushed UPDATED event
  changing a row's status without a fetch; test, lint and build pass.

- [x] 3. **Polling as fallback only.** Replace the per-hook `refetchInterval`
  in `src/api/dashboard.ts`, `src/api/events.ts` and `src/api/services.ts` with
  a shared rule: `false` while the connection state is `live`, otherwise
  `DASHBOARD_POLL_MS`. Keep the existing "no polling while a status change is in
  flight" rule for event queries.
  **Done when:** tests show no refetch after 5 s (fake timers) while live, and a
  refetch after 5 s while reconnecting or offline; test, lint and build pass.

- [x] 4. **Row highlight.** Add `src/lib/highlights.ts`, a small store of
  recently changed event IDs with a 3 s expiry and a `useRecentlyChanged(eventId)`
  hook. Mark an ID when an applied CREATED/UPDATED push arrives and when the
  operator's own status change succeeds (so it highlights even while the socket
  is down). `EventTable` and `RecentEvents` rows set `data-highlight="true"`
  while highlighted and fade a highlight background out over the window; with
  `prefers-reduced-motion: reduce` the background shows without animation. A
  second change within the window restarts it.
  **Done when:** tests show the attribute appears after a pushed UPDATED event
  and after an own successful status change, and clears after 3 s (fake timers);
  test, lint and build pass; a manual run against `docker compose up -d --build
  --wait` shows the chip go Live, new events and status changes from a second
  browser tab highlight, and stopping the backend turns the chip Reconnecting,
  then Offline, with polling resuming (report as manual evidence only if run).

## Files / areas

- `frontend/package.json`, `frontend/package-lock.json` - `@stomp/stompjs`
- `frontend/src/types/live.ts` - new
- `frontend/src/api/stompConnection.ts`, `frontend/src/api/liveUpdates.tsx` - new
- `frontend/src/lib/liveCache.ts`, `frontend/src/lib/highlights.ts` (+ tests) - new
- `frontend/src/AppProviders.tsx` - mount provider, `connectLive` prop
- `frontend/src/components/layout/ConnectionChip.tsx` - new;
  `AppLayout.tsx` - render it
- `frontend/src/api/dashboard.ts`, `events.ts`, `services.ts` - poll rule;
  `events.ts` mutation success marks a highlight
- `frontend/src/components/events/EventTable.tsx`,
  `frontend/src/components/dashboard/RecentEvents.tsx` - highlight
- `frontend/src/test/renderApp.tsx`, new fake connection helper, page tests
- No nginx or Vite change: both already proxy `/ws` (Feature 12).

## Data / contracts

- **Server contract (Feature 12, unchanged):** `/topic/events` body
  `{ "type": "CREATED" | "UPDATED", "event": IncidentEvent }`;
  `/topic/summary` body is a `DashboardSummary`, at most 1/s, only after a
  change, no initial message. Delivery is at-most-once, unordered across
  partitions, no replay. Clients must reconcile over REST.
- **Types:** `LiveEventMessage = { type: 'CREATED' | 'UPDATED'; event: IncidentEvent }`.
  `ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline'`.
- **Connection factory seam:** `connect(handlers) => { close(): void }` with
  handlers `onConnect`, `onDisconnect`, `onEventMessage(body: string)`,
  `onSummaryMessage(body: string)`. The real factory builds a stompjs `Client`
  with `brokerURL` = `ws[s]://${location.host}/ws` (`wss` when the page is
  `https:`), `reconnectDelay` 1000 ms, `ReconnectionTimeMode.EXPONENTIAL`,
  `maxReconnectDelay` 30 000 ms, heartbeats 10 000 ms both ways (matches the
  backend), subscribes both topics in `onConnect`, maps `onWebSocketClose` to
  `onDisconnect`, and never sends. `close()` calls `deactivate()`.
- **Connection state rules:** start `connecting`; any connect → `live`; a
  disconnect from `live` → `reconnecting`; not `live` again within 10 000 ms of
  leaving `live` (or of start) → `offline`; retries continue forever with
  backoff; the next connect → `live`.
- **Chip:** labels "Connecting", "Live", "Reconnecting", "Offline"; colors from
  the theme palette (live success, connecting/reconnecting warning, offline
  error). Wrapped in `role="status"` so changes are announced politely. Visible
  text carries the meaning, not color alone.
- **Parsing:** `JSON.parse` in try/catch; drop a message whose `type` is not
  CREATED/UPDATED or whose `event.eventId`/`event.updatedAt` is not a string,
  and a summary whose `totalEvents` is not a number. Dropped messages change
  nothing.
- **Stale guard:** an event patch replaces a cached event only when the incoming
  `updatedAt` is equal or newer (`Date.parse` compare) than the cached one.
  This makes a late CREATED after an UPDATED (F-05) a no-op.
- **In-flight skip:** while a status-change mutation for the same `eventId` is
  pending, event patches for that ID are skipped; the mutation's existing
  `onSettled` refetch reconciles.
- **Patch rules:**
  - Summary → `setQueryData(dashboardKeys.summary, summary)`.
  - CREATED → prepend to each cached recent-events list if absent, trim to that
    key's `limit`; throttled refetch of event lists, timeline and services.
  - UPDATED → patch the event in place in cached event list pages, recent-events
    lists and the cached detail (guarded); throttled refetch of event lists and
    services.
  - Throttle: per group (lists, timeline, services), leading + trailing,
    1000 ms window, using `invalidateQueries` so only active queries refetch.
  - On every connect: invalidate `['dashboard']`, `['events']`, `['services']`.
- **Rendering safety:** pushed text renders only through React text nodes, as
  today; no HTML is built.

## Testing

- `npm test` (Vitest + RTL, jsdom) with the fake connection and fake timers:
  `liveCache.test.ts`, `highlights` tests, chip states, polling on/off, page
  tests for summary and row patch and highlight.
- `npm run lint`, `npm run build` (typecheck + bundle).
- No backend changes, so backend tests are not required for this feature.
- No Browser tests command exists; live-stack behavior is manual evidence in
  step 4 only if actually run.

## Notes for the AI

- Server state stays in TanStack Query; pushes patch the cache. The highlight
  and connection state are UI state, not a parallel copy of server data.
- Keep the connection out of tests except through the fake; stompjs must never
  open a jsdom WebSocket in the suite.
- Do not insert pushed CREATED events into filtered/paginated list pages; the
  server owns filtering, search, sort and totals, so lists refetch (throttled).
- Mutation `onSettled` invalidation stays as is; it also covers a push that is
  lost for the operator's own change.
- Decided without asking (reversible, UI-only): the "Connecting" label before the
  first connection, the 10 s offline threshold, the 3 s highlight window, and
  the 1 s refetch throttle.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":10950,"specSha256":"38bcc0f47ce9d1d821124f0f6f43c410fd61f1b3f01c34481568a1507d442bb1","branch":"refs/heads/feature/live-ui-updates","head":"95a6425d832db1f0d6ba97111977a9b11be65f98","baseRef":"refs/heads/main","baseCommit":"95a6425d832db1f0d6ba97111977a9b11be65f98","sourceTree":"97b1757787e3ab7b43b1e8831a232c8b247de721","absentOptional":[]} -->
