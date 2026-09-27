# Feature: Events page

**From build-plan:** feature 9
**Build attempt:** 1
**Branch:** feature/events-page
**Status:** verified

## Goal

Replace the `/events` placeholder with the operator's working page: a
server-paginated events table whose filters, search and page live in the query
string, a detail drawer reachable by deep link at `/events/:eventId`, and an
optimistic status change (acknowledge, resolve, reopen) that rolls back and shows
an error toast when the backend rejects it.

## In scope

- `/events` table backed by `GET /api/events` (Feature 5): columns Time,
  Severity, Service, Source, Status, Message, Event ID; default sort
  `timestamp,desc`; fixed page size 20.
- Filters for `severity`, `status`, `source`, `service` and a text search `q`,
  plus pagination, all synced to the query string, e.g.
  `/events?severity=CRITICAL&status=OPEN&q=signal&page=2`.
- `/events/:eventId` renders the same page with a detail drawer open for that
  event (`GET /api/events/{eventId}`); the table keeps the filters in the query
  string underneath it.
- Status change from the drawer via `PUT /api/events/{eventId}/status`, offering
  only the lifecycle's allowed targets, applied optimistically to the list and
  detail caches, rolled back on failure with an error toast.
- Loading skeletons, empty states, polling refresh and a backend-error toast,
  matching the dashboard's behavior.
- README Frontend section updated for the Events page.

## Out of scope

- Sortable columns and a rows-per-page selector (the API supports `sort` and
  `size`; the plan does not ask for them in the UI).
- WebSocket push and the connection chip (features 12-13); polling stays the
  refresh mechanism here.
- The Services page (feature 10), auth/roles (feature 20), any backend change.
- Bulk status changes and status changes from the table row.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, running the frontend
gates after each, then present one review packet at the end.
`workflow.checkpointCommits` is `disabled`: no commits during `/implement`;
`/complete` creates the single feature commit.

## Build steps

- [x] **1. Events API layer and URL-state logic.**
  Add `src/types/events.ts` (`EventPage` = `{ content: IncidentEvent[]; page;
  size; totalElements; totalPages }`, `SOURCES = ['ATS','CBTC','SCADA','TMS','PIS'] as const`,
  `EVENT_STATUSES` list). Add `src/lib/eventSearch.ts`:
  `parseEventSearch(URLSearchParams)` -> `{ severity?, status?, source?, service?, q, page }`
  and `toEventSearchParams(state)`; unknown enum values are dropped, `q` is
  trimmed and capped at 200 chars, `page` is a 1-based positive integer
  (anything else -> 1), and defaults (`page=1`, empty values) are omitted from the
  URL. `toApiQuery(state)` maps to the API (`page - 1`, `size=20`, omits empties,
  URL-encodes). Add `allowedTransitions(status)` in `src/lib/lifecycle.ts`
  mirroring the backend: OPEN -> ACKNOWLEDGED, RESOLVED; ACKNOWLEDGED -> RESOLVED;
  RESOLVED -> OPEN. Extend `src/api/client.ts` with a `sendJson<T>(url, method, body)`
  that shares `fetchJson`'s ProblemDetail error handling. Add `src/api/events.ts`
  with `eventKeys` (`all: ['events']`, `list(query)`, `detail(id)`),
  `useEvents(state)` (polls every `DASHBOARD_POLL_MS`, `placeholderData:
  keepPreviousData` so paging does not flash a skeleton) and `useEvent(eventId)`
  (path segment `encodeURIComponent`-ed).
  **Done when:** unit tests for `eventSearch.ts`, `lifecycle.ts` and `sendJson`
  pass; `npm test`, `npm run lint`, `npm run build` are green.

- [x] **2. Events table with URL-synced filters, search and pagination.**
  Replace `src/pages/EventsPage.tsx`. Components under `src/components/events/`:
  `EventFilters.tsx` (labelled MUI selects for Severity, Status, Source, Service
  with an "All" option; Service options come from `useDashboardSummary()`'s
  `services[].name`, plus the current URL value if it is not in that list; a
  labelled "Search events" text field, `maxLength` 200, debounced 300 ms; a
  "Clear filters" button when any filter is set) and `EventTable.tsx` (reuses
  `Panel`, `EmptyState`, `SeverityChip`, `formatTime`/`formatDateTime`; message
  and other user text rendered as plain React text only; the Event ID cell is a
  router `Link` to `/events/:eventId` keeping the current query string, and the
  whole row is also clickable). MUI `TablePagination` (no rows-per-page options)
  drives `page`. Any filter or search change resets `page` to 1. Filter and page
  changes push history entries; debounced search typing replaces the entry.
  States: first-load skeleton; "No events match these filters." with a Clear
  filters action when filters are set, "No events yet." otherwise; a page past
  `totalPages` shows an empty state with a "Go to first page" action; list error
  without data shows the Panel error; a failing poll while data exists keeps the
  data and shows a "Can't reach the backend - retrying" toast (same wording as
  the dashboard). A 400 from the API (e.g. a hand-edited `q`) shows the
  ProblemDetail detail in the Panel error.
  **Done when:** page tests (mocked `fetch`, following `DashboardPage.test.tsx`)
  show: rows render from a page response; loading, empty and filtered-empty
  states; a URL like `/events?severity=CRITICAL&status=OPEN&q=signal&page=2`
  sends `severity=CRITICAL&status=OPEN&q=signal&page=1&size=20` and pre-fills the
  controls; changing a filter updates the URL and resets the page; search is
  debounced (fake timers). Gates green.

- [x] **3. Deep-linkable detail drawer.**
  Add the `events/:eventId` route in `src/App.tsx` rendering `EventsPage`.
  `EventDetailDrawer.tsx` (right-anchored MUI `Drawer`, heading labelled by the
  event ID) shows every `IncidentEvent` field: eventId, severity chip, status,
  service, source, message (plain text, wrapping), timestamp, receivedAt,
  updatedAt as local date-times. States: skeleton while loading; "Event not
  found" for 404 with the eventId shown as text; generic error with a Retry
  button otherwise. Closing (close button, Escape, backdrop) navigates to
  `/events` with the current query string; focus returns via MUI's focus
  handling. The detail query polls like the list.
  **Done when:** tests show opening `/events/EVT-1?status=OPEN` requests
  `/api/events/EVT-1` and the list with `status=OPEN`, renders the fields, the
  404 state renders, and closing lands on `/events?status=OPEN`; clicking a row's
  event ID opens the drawer. Gates green.

- [x] **4. Optimistic status change with rollback.**
  `useChangeEventStatus()` in `src/api/events.ts` (`useMutation`): `onMutate`
  cancels `eventKeys.all` queries, snapshots every cached list page and the
  detail, and sets the new status (and nothing else) on the matching event;
  `onError` restores the snapshots and exposes the error; `onSettled`
  invalidates `eventKeys.all` and the `['dashboard']` queries. While a status
  mutation is pending, list and detail polling is paused (`useIsMutating`) so a
  stale poll cannot flicker the old status back. The drawer shows one button per
  `allowedTransitions(status)` target (labels "Acknowledge", "Resolve",
  "Reopen"), disabled while the mutation is pending. A failure opens an error
  toast (`role="alert"`) with the ProblemDetail detail, e.g. the 409 "Cannot
  change status from ... to ..." or the concurrent-update message, falling back
  to "Couldn't change the status". A row that no longer matches the active
  status filter drops out on the next refetch, which is expected.
  **Done when:** tests show a successful change updates the drawer and table row
  before the PUT resolves and sends `{"status":"ACKNOWLEDGED"}`; a 409 rolls the
  row and drawer back and shows the detail in a toast; only allowed targets
  render per status. Gates green.

- [x] **5. Live check and README.**
  Rebuild with `docker compose up -d --build --wait`, then in a browser at
  http://localhost:3000: filter, search and page and see the URL follow; reload a
  filtered URL and a `/events/<real eventId>` URL; acknowledge and resolve an
  event and see the dashboard counts follow within a poll; open an unknown event
  ID; stop the backend container and confirm the toast, then restart. Update the
  README Frontend section with the Events page routes and behavior.
  **Done when:** the live behaviors above are observed with no console errors, and
  the README describes the page.

- [x] **6. Event ID row in the detail drawer.** (Added during completion review.)
  Add a labelled "Event ID" row as the first entry in the drawer's field list,
  so the ID reads and copies like every other field. The heading stays the
  event ID, since it names the dialog.
  **Done when:** the deep-link drawer test finds the "Event ID" term with the
  event's ID as its definition. Gates green.

## Files / areas

- New: `frontend/src/types/events.ts`, `frontend/src/lib/eventSearch.ts` (+ test),
  `frontend/src/lib/lifecycle.ts` (+ test), `frontend/src/api/events.ts`,
  `frontend/src/components/events/EventFilters.tsx`, `EventTable.tsx`,
  `EventDetailDrawer.tsx`, `StatusActions.tsx`, `frontend/src/pages/EventsPage.test.tsx`
- Changed: `frontend/src/pages/EventsPage.tsx`, `frontend/src/App.tsx` (route),
  `frontend/src/api/client.ts` (+ `client.test.ts`), `README.md`
- Reused as-is: `components/dashboard/Panel.tsx`, `StatusChips.tsx`,
  `lib/format.ts`, `lib/colors.ts`, `api/dashboard.ts` (`useDashboardSummary`,
  `DASHBOARD_POLL_MS`), `test/renderApp.tsx`
- No backend, nginx or compose changes: nginx already proxies `/api/` (including
  `PUT`) and falls back to `index.html` for `/events/...`.

## Data / contracts

Consumed, unchanged (Features 5-6):

- `GET /api/events?severity&status&source&service&q&page&size&sort` ->
  `{ content: EventResponse[], page, size, totalElements, totalPages }`; `page`
  zero-based, `size` 1-100, `q` <= 200 chars, case-insensitive over message,
  service and eventId; `source` and `service` match exactly. Invalid params -> 400
  ProblemDetail.
- `GET /api/events/{eventId}` -> `EventResponse`; unknown -> 404 ProblemDetail.
- `PUT /api/events/{eventId}/status` body `{ "status": "OPEN|ACKNOWLEDGED|RESOLVED" }`
  -> `EventResponse`; same status -> 200 unchanged; invalid transition -> 409
  (`type /problems/invalid-status-transition`, `allowedTransitions`); overlapping
  write -> 409 "The event was changed by another request; reload it and try
  again"; clients send no version.

Frontend URL contract (new): `/events` query keys `severity`, `status`, `source`,
`service`, `q`, `page` (1-based; the API's `page` is this minus 1). Defaults are
omitted. `/events/:eventId` carries the same query string.

## Testing

- Unit (Vitest): `eventSearch.ts` parse/serialize round-trips, invalid enum and
  page values, `q` trimming/cap, API mapping; `lifecycle.ts` targets per status;
  `sendJson` success, ProblemDetail detail, non-JSON error body.
- Page tests (Vitest + RTL, mocked `fetch`, `renderApp(path)`): list states, URL
  sync, debounce, deep link and 404 drawer, optimistic change and 409 rollback.
- Live: step 5 against the compose stack. No browser-test harness exists, so no
  automated browser tests.
- Gates per step: `npm test`, `npm run lint`, `npm run build` in `frontend/`.
  No `Verify` command is declared. Baseline before this feature: 23 tests in 5
  files passing.

## Notes for the AI

- Keep `IncidentEvent` in `types/dashboard.ts`; import it rather than duplicating.
- Never render event text with `dangerouslySetInnerHTML`; message, service,
  source and eventId are producer- or hand-published input.
- Encode `eventId` with `encodeURIComponent` in API paths and router links.
- Filter controls need visible labels; the status buttons need text labels, not
  color alone; toasts use `Alert` inside `Snackbar` like `BackendErrorToast`.
- The optimistic patch updates only `status`; `updatedAt` comes from the server
  on refetch.
- The 500 kB chunk warning from Feature 8 is known; do not add dependencies.

## Implementation notes

- Live check found an unknown event ID took ~7 s to show "Event not found"
  because TanStack Query retried the 404 three times. Added
  `retryUnlessClientError` in `api/client.ts` (no retry on 4xx, up to 3 retries
  otherwise) for the events list and detail queries, with unit tests.
- `Panel` gained an optional `errorText` prop so the list can show the API's
  ProblemDetail detail.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":12436,"specSha256":"99d5298c7714f1b1d4515af17190edd53fa32a2bfbbd5524a9c6c4ffadb6a780","branch":"refs/heads/feature/events-page","head":"b6dfe7d466ce2704a1cdf22df508a87a0a19380a","baseRef":"refs/heads/master","baseCommit":"b6dfe7d466ce2704a1cdf22df508a87a0a19380a","sourceTree":"8648cc0059b478c16e3eee636197c32e36304d4c","absentOptional":[]} -->
