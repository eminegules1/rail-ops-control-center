# Feature: Service status page

**From build-plan:** feature 10
**Build attempt:** 1
**Branch:** feature/service-status-page
**Status:** verified

## Goal

Replace the `/services` placeholder with a per-service status table backed by
`GET /api/services` (Feature 7): each service's health, last event time, latest
severity, open incident count as the main column, and active incident count,
refreshed by polling like the rest of the app.

## In scope

- `/services` table, one row per service in the API's order (sorted by name):
  Service, Health, Open, Active, Latest severity, Last event.
- The Open column is the visually primary column (bold, larger figures).
- Each service name links to the Events page filtered to that service
  (`/events?service=<name>`), reusing Feature 9's URL contract.
- Health and latest severity use the shared chip colors (`HealthChip`,
  `SeverityChip`); last event shows local `HH:mm:ss` with the full date-time as
  its tooltip.
- Loading skeleton, empty state, error state, and the shared "Can't reach the
  backend - retrying" toast; polling every `DASHBOARD_POLL_MS` (5 s).
- One shared toast component for that message, replacing the two inline copies
  (dashboard and events page), so the three pages cannot drift.
- README Frontend section updated.

## Out of scope

- Sorting, filtering, or searching services; per-service detail pages or charts.
- WebSocket push and the connection chip (features 12-13).
- Any backend change; `GET /api/services` already returns every field.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, running the frontend
gates after each, then present one review packet at the end.
`workflow.checkpointCommits` is `disabled`: no commits during `/implement`;
`/complete` creates the single feature commit.

## Build steps

- [x] **1. Services API hook, type and shared backend toast.**
  Add `src/types/services.ts` with `ServiceState` mirroring the backend record
  (see Data / contracts). Add `src/api/services.ts` with `serviceKeys` and
  `useServices()` (`fetchJson<ServiceState[]>('/api/services')`,
  `refetchInterval: DASHBOARD_POLL_MS`, `retry: retryUnlessClientError`). Add
  `src/components/layout/BackendErrorToast.tsx` taking `open: boolean` and
  rendering the existing Snackbar + filled error Alert with "Can't reach the
  backend - retrying"; make `components/dashboard/BackendErrorToast.tsx` and
  `pages/EventsPage.tsx` use it, with unchanged text and behavior.
  **Done when:** `npm test` (existing dashboard and events toast tests still
  pass), `npm run lint` and `npm run build` are green.

- [x] **2. Service status table.**
  Replace `src/pages/ServicesPage.tsx` (h1 "Services") with
  `src/components/services/ServiceStatusTable.tsx` inside the existing `Panel`
  titled "Service status". Columns: Service (a router `Link` to
  `/events?${toEventSearchParams({ service: name, q: '', page: 1 })}`, so the
  name is URL-encoded; plain text, ellipsis with full name as `title`), Health (`HealthChip`), Open (bold, larger, right
  aligned), Active (right aligned), Latest severity (`SeverityChip`), Last event
  (`formatTime`, `formatDateTime` tooltip). Counts use `formatCount`. A null
  `status`, `latestSeverity` or `lastEventTime` renders an em dash instead of a
  chip or time. Table is `size="small"` inside a `TableContainer` so it scrolls
  on narrow screens instead of widening the page (same `minmax(0, 1fr)` grid as
  the other pages). States: first-load skeleton; "No services have reported
  events yet." when the list is empty; Panel error when the first load fails; a
  failing poll with data keeps the rows and opens the shared toast.
  **Done when:** `src/pages/ServicesPage.test.tsx` (mocked `fetch`, `renderApp`)
  shows: skeleton while loading; rows with name, health, open and active counts,
  the service link's `href` (including a name that needs encoding), clicking it
  lands on the Events page with the Service filter set,
  latest severity and last event; em dashes for null fields; the empty state;
  the error state plus toast when the API fails. Gates green.

- [x] **3. Live check and README.**
  Rebuild with `docker compose up -d --build --wait` and open
  http://localhost:3000/services: rows match `GET /api/services`; a service
  link opens the Events page filtered to that service; acknowledging
  or resolving an event on the Events page changes that service's Open/Active
  counts within a poll; stopping the backend shows the toast and restarting
  clears it; no console errors besides the browser's own failed-request lines.
  Replace the README's `/services` placeholder row and describe the page.
  **Done when:** the live behaviors above are observed and the README describes
  the page.

## Files / areas

- New: `frontend/src/types/services.ts`, `frontend/src/api/services.ts`,
  `frontend/src/components/layout/BackendErrorToast.tsx`,
  `frontend/src/components/services/ServiceStatusTable.tsx`,
  `frontend/src/pages/ServicesPage.test.tsx`
- Changed: `frontend/src/pages/ServicesPage.tsx`,
  `frontend/src/components/dashboard/BackendErrorToast.tsx`,
  `frontend/src/pages/EventsPage.tsx`, `README.md`
- Reused: `components/dashboard/Panel.tsx`, `StatusChips.tsx`, `lib/format.ts`,
  `lib/eventSearch.ts` (`toEventSearchParams`),
  `api/client.ts` (`fetchJson`, `retryUnlessClientError`),
  `api/dashboard.ts` (`DASHBOARD_POLL_MS`), `test/renderApp.tsx`
- No route, backend, nginx or compose change: `/services` already routes to
  `ServicesPage` and nginx proxies `/api/`.

## Data / contracts

Consumed, unchanged: `GET /api/services` -> JSON array of `ServiceState`,
sorted by name; empty array when no service has reported yet.

```ts
type ServiceState = {
  name: string
  status: ServiceHealth | null        // HEALTHY | DEGRADED | DOWN
  lastEventTime: string | null        // ISO 8601
  latestSeverity: Severity | null     // INFO | WARNING | MAJOR | CRITICAL
  openCount: number                   // status OPEN
  activeCount: number                 // status OPEN or ACKNOWLEDGED
}
```

The backend parses each field from the Redis hash and returns `null` when one is
missing, so the UI must tolerate nulls even though `apply-event` normally sets
them all.

## Testing

- Page tests (Vitest + RTL, mocked `fetch`, `renderApp('/services')`): loading,
  populated, null fields, empty, error + toast.
- The shared toast refactor is covered by the existing dashboard and events
  page tests, which assert the toast text.
- No new pure logic, so no new unit tests.
- Live: step 3 against the compose stack; no browser-test harness exists.
- Gates per step: `npm test`, `npm run lint`, `npm run build` in `frontend/`.
  No `Verify` command is declared. Baseline: 57 tests in 8 files passing
  (Feature 9 completion, same tree as `master`).

## Notes for the AI

- Service names come from producer or hand-published events; render them as
  plain React text only.
- Keep the Open column visually primary without relying on color alone.
- Do not duplicate the health or severity color mapping; use the chips.
- Planning note approved during spec review and carried in this feature's
  commit: Feature 13's build-plan line and overview entry now include a brief
  highlight on newly created or updated rows. The overview source hash was
  recomputed for it. No code in this feature implements it.

## Implementation notes

- The dashboard's wrapper became `components/dashboard/DashboardErrorToast.tsx`
  (renamed from `BackendErrorToast.tsx`) so it does not share a name with the
  new shared `components/layout/BackendErrorToast.tsx`.
- The 4xx retry rule moved from per-query options into the app's default
  `QueryClient` in `AppProviders.tsx`. Per-query `retry` overrode the tests'
  retry-off client, so a 500 in a page test waited out three retries. The app
  behaves the same, and dashboard queries now also skip retries on 4xx.
- Live check: with the backend stopped, nginx takes ~5 s to fail each proxied
  request (Docker DNS lookup for the missing container), so with three retries
  the "Can't reach the backend" toast appears ~30-50 s into an outage on every
  page. Pre-existing and shared; not changed here.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":8185,"specSha256":"6ad83ccdceb1771ba39325f379b1018e5a18da28e3d65a10f8fb8352d7695325","branch":"refs/heads/feature/service-status-page","head":"bf605f02d9105e9e68237eba3475b22f1310cbe0","baseRef":"refs/heads/master","baseCommit":"bf605f02d9105e9e68237eba3475b22f1310cbe0","sourceTree":"862646a61000aa251dd9ffd9cead71deae4f5e32","absentOptional":[]} -->
