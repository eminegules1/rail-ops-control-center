# Feature: Dashboard page

**From build-plan:** feature 8
**Build attempt:** 1
**Branch:** feature/dashboard-page
**Status:** verified

## Goal

Replace the Vite scaffold with the first real UI: an app shell with routes and a
`/dashboard` page that shows KPI cards, a service health grid, severity
distribution and events-over-time charts, and the recent events list, kept
fresh by polling the Feature 7 APIs. Add the frontend test setup (Vitest + React
Testing Library) and an nginx `frontend` compose service on port 3000, so
`docker compose up --build` serves the dashboard.

## In scope

- Dependencies: React Router, TanStack Query, MUI (+ Emotion), Recharts;
  dev: Vitest, React Testing Library, jest-dom, jsdom. `npm test` script.
- App shell: left nav (Dashboard / Events / Services), top bar with the short
  label "Rail Ops Control Center"; tab title stays "Rail Ops Control Center".
- Routes: `/` redirects to `/dashboard`; `/dashboard`; `/events` and `/services`
  as minimal placeholder pages (one line saying the page arrives in a later
  feature) so the nav never lands on not-found; `*` not-found page with a link
  back to the dashboard.
- Dashboard widgets:
  - KPI cards: Total events, Open, Critical (from `totalEvents`, `openEvents`,
    `criticalEvents`; Critical means CRITICAL and not RESOLVED, say so in the
    card's caption).
  - Service health grid from `summary.services`: name, health chip
    (HEALTHY / DEGRADED / DOWN, text label plus color), last event time.
  - Severity distribution chart from `summary.severityDistribution` (bar chart,
    one bar per severity in INFO, WARNING, MAJOR, CRITICAL order).
  - Events-over-time chart from `GET /api/dashboard/timeline?minutes=60`:
    stacked bars per minute by severity, local `HH:mm` axis labels.
  - Recent events from `GET /api/dashboard/recent-events?limit=20`: time,
    severity chip, service, source, status, message (single line, ellipsis,
    full text in `title`). Rows appearing for the first time fade in gently;
    existing rows do not re-animate on refetch.
- States per widget: loading skeleton; empty state (no events / no services
  yet); error state when a query fails with no data yet. When a refetch fails
  after data loaded, keep showing the last data.
- Error toast: one MUI Snackbar reading "Can't reach the backend - retrying"
  that stays open while any dashboard query is in error and closes when all
  recover (state-derived, so polling failures never stack toasts).
- Polling: TanStack Query `refetchInterval` of 5 s for summary, timeline and
  recent events (matches the 5 s summary cache); TanStack's default pauses
  polling in a hidden tab.
- Shared severity and health color mapping in one module (overview colors:
  INFO blue, WARNING amber, MAJOR orange, CRITICAL red; HEALTHY green,
  DEGRADED amber, DOWN red), used by every chip and chart.
- MUI theme with light and dark color schemes (`colorSchemes` light + dark),
  `CssBaseline`; the dense, calm control-room look.
- Theme switch in the top bar: the user picks Light, Dark or System (System is
  the default and follows the OS setting). The choice is remembered in this
  browser through MUI's built-in `useColorScheme` storage (localStorage), so a
  reload keeps it; if storage is unavailable it falls back to System. The
  control has an accessible name ("Theme") and shows the current choice.
- API access through relative `/api` URLs: Vite dev server proxies `/api` to
  `http://localhost:${BACKEND_PORT:-8080}`; nginx proxies `/api` to
  `http://backend:8080` in compose. No CORS change in the backend.
- `frontend/Dockerfile` (Node build stage, nginx serve stage),
  `frontend/nginx.conf` (SPA fallback `try_files $uri /index.html`, `/api`
  proxy), `frontend/.dockerignore`, compose `frontend` service on
  `127.0.0.1:${FRONTEND_PORT:-3000}` depending on a healthy backend, with a
  healthcheck; `FRONTEND_PORT` in `.env.example`.
- Docs: README Frontend section (dev proxy, tests, compose URL); AGENTS.md
  Commands gain the frontend test command and the compose frontend URL.

## Out of scope

- Events page table, filters, detail drawer, status change (feature 9).
- Service status page content (feature 10).
- `/events/:eventId` deep link (feature 9).
- WebSocket, `/ws` nginx proxy, connection status chip (features 12-13); polling
  is the only refresh mechanism here.
- Redis-down fallback (feature 14); the dashboard just shows its error states.
- Storing the theme choice anywhere but the browser (no backend setting).
- CI, a `Verify` command, browser test harness.
- Any backend change.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet. `workflow.checkpointCommits` is `disabled`: no checkpoint commits;
`/complete` creates the feature commit.

## Build steps

- [x] 1. **Dependencies, test setup, API layer, shared logic.** Install the
  runtime and dev dependencies (confirm current major versions and React 19
  compatibility via Context7 docs before pinning). Add `vitest` config
  (jsdom environment, setup file importing jest-dom), `"test": "vitest run"`.
  Add DTO types, a `fetchJson` helper that throws an `ApiError` carrying the
  ProblemDetail `detail` (or status text) on non-2xx, query hooks for summary,
  timeline and recent events, the shared color mapping, and pure helpers
  (timeline buckets -> chart rows with missing severities as 0; severity
  distribution -> ordered chart rows; time formatting).
  **Done when:** `npm test` passes with tests for the helpers and `fetchJson`
  (ok, ProblemDetail error, non-JSON error), and `npm run lint` and
  `npm run build` pass.
- [x] 2. **App shell and routes.** Remove the scaffold (`App.css`, `assets/`,
  `public/icons.svg`, scaffold `App.tsx` body; reduce `index.css` to nothing
  or delete it in favor of `CssBaseline`). Add theme, `QueryClientProvider`,
  router, layout with left nav and top bar (with the Light / Dark / System
  theme switch), placeholder Events and Services pages, not-found page, `/`
  redirect. Add the Vite `/api` proxy.
  **Done when:** lint and build pass; an RTL test proves `/` renders the
  dashboard route, the nav links exist, an unknown path shows not-found, and
  choosing Dark in the theme switch applies the dark scheme; the dev server
  shows the shell with working navigation, and a chosen theme survives a
  reload.
- [x] 3. **Dashboard page.** KPI cards, health grid, two charts, recent events,
  per-widget loading / empty / error states, 5 s polling, backend error toast,
  new-row fade-in. User text (message, service, source, event id) renders only
  as React text, never as HTML.
  **Done when:** RTL tests with the API module mocked cover: loading skeletons,
  populated KPIs and service health labels and recent event rows, empty states,
  error state plus toast on failure; `npm test`, lint and build pass; against
  the running backend (`npm run dev` + compose stack) the dashboard shows live
  data that changes within ~5 s while the producer runs.
- [x] 4. **nginx image and compose service.** Add `frontend/Dockerfile`,
  `frontend/nginx.conf`, `frontend/.dockerignore`, the compose `frontend`
  service and `FRONTEND_PORT`; update README and AGENTS.md Commands.
  **Done when:** `docker compose up -d --build --wait` reports all services
  healthy; http://localhost:3000/dashboard shows live data; reloading
  http://localhost:3000/dashboard and http://localhost:3000/nope returns the
  SPA (dashboard and not-found page respectively); `curl
  http://localhost:3000/api/dashboard/summary` returns the backend JSON.

## Files / areas

- `frontend/package.json`, `frontend/package-lock.json`, `frontend/vite.config.ts`
  (proxy + Vitest `test` block), `frontend/tsconfig.app.json` (Vitest globals
  types only if used), `frontend/src/test/setup.ts`
- `frontend/src/main.tsx`, `frontend/src/App.tsx` (routes),
  `frontend/src/AppProviders.tsx` (theme, CssBaseline, QueryClient),
  `frontend/src/theme.ts`, `frontend/src/test/renderApp.tsx`, `frontend/src/App.test.tsx`
- `frontend/src/components/layout/AppLayout.tsx`,
  `frontend/src/components/layout/ThemeSwitch.tsx`
- `frontend/src/pages/DashboardPage.tsx`, `EventsPage.tsx`, `ServicesPage.tsx`,
  `NotFoundPage.tsx`
- `frontend/src/components/dashboard/` - `KpiCards.tsx`, `ServiceHealthGrid.tsx`,
  `SeverityChart.tsx`, `TimelineChart.tsx`, `RecentEvents.tsx`,
  `BackendErrorToast.tsx`, `Panel.tsx` (title + loading/error/empty states),
  `StatusChips.tsx`; `frontend/src/pages/DashboardPage.test.tsx`
- `frontend/src/api/client.ts` (`fetchJson`, `ApiError`),
  `frontend/src/api/dashboard.ts` (query hooks)
- `frontend/src/types/dashboard.ts`, `frontend/src/lib/colors.ts`,
  `frontend/src/lib/chartData.ts`, `frontend/src/lib/format.ts`, with
  `*.test.ts(x)` next to them
- Remove: `frontend/src/App.css`, `frontend/src/assets/`,
  `frontend/public/icons.svg`, `frontend/src/index.css` (or reduce it)
- `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`
- `docker-compose.yml`, `.env.example`, `README.md`, `AGENTS.md` (Commands)

## Data / contracts

Consumed as-is (Feature 7, no backend change):

- `GET /api/dashboard/summary` -> `{ totalEvents, openEvents,
  acknowledgedEvents, criticalEvents, severityDistribution: Record<Severity,
  number>, services: { name, status: ServiceHealth, lastEventTime: string |
  null }[] }`
- `GET /api/dashboard/timeline?minutes=60` -> `{ minute: string (ISO UTC),
  counts: Record<Severity, number> }[]`, oldest first
- `GET /api/dashboard/recent-events?limit=20` -> `EventResponse[]`:
  `{ eventId, source, service, severity, message, status, timestamp,
  receivedAt, updatedAt }`, newest first
- Errors: RFC 7807 ProblemDetail JSON (`title`, `status`, `detail`)

Frontend types: `Severity = 'INFO' | 'WARNING' | 'MAJOR' | 'CRITICAL'`,
`EventStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'`,
`ServiceHealth = 'HEALTHY' | 'DEGRADED' | 'DOWN'` (union types, no TS enums).
Treat `lastEventTime` as nullable and a missing severity key as 0. Times
display in the browser's local time; the ISO value goes in the `title`.

Query keys: `['dashboard','summary']`, `['dashboard','timeline',60]`,
`['dashboard','recent-events',20]` (feature 13 patches these caches).

## Testing

- Test command added: `npm test` (`vitest run`) in `frontend/`. Vitest fails
  on an empty suite by default; keep that.
- Unit: `chartData` (missing severities -> 0, ordering, local minute labels
  with a fixed timezone-independent assertion), `format`, `fetchJson` with a
  stubbed `fetch`.
- Component (RTL, API hooks' fetch layer mocked with `vi.mock`): routing
  (redirect, nav, not-found) and dashboard states. Recharts'
  `ResponsiveContainer` needs `ResizeObserver`; stub it in the test setup
  rather than asserting chart internals. Use `findBy*` for async data;
  disable query retries in the test `QueryClient`.
- No browser harness is configured; live evidence comes from the dev server,
  compose stack, curl and screenshots in `/check`.
- Backend tests are untouched; no backend command is needed for this feature.

## Notes for the AI

- Look up React Router, TanStack Query, MUI and Recharts setup through
  Context7 before writing code; use whichever React Router package the current
  major documents (`react-router` for v7).
- No `any`; no TS `enum`; `verbatimModuleSyntax` means `import type` for types.
- Colors come only from `lib/colors.ts`; health and severity are always shown
  with a text label, not color alone. Charts get an `aria-label`; the severity
  chart's counts are also visible as text (axis/labels or legend).
- Accessibility: nav is a `<nav>` landmark with the active link marked
  (`aria-current`); page has one `<h1>`; the toast uses MUI Snackbar's alert
  role.
- Keep the query hooks' polling interval in one constant.
- Theme switch: use MUI's `useColorScheme` (`mode` / `setMode`) rather than a
  custom context or hand-written localStorage code; it handles persistence and
  the System option. Guard the first render where `mode` is still undefined.
  Severity and health colors must stay readable on both schemes.
- Pin the nginx and Node base images to specific versions like the other
  compose images; nginx listens on 80 in the container, published on
  `127.0.0.1:${FRONTEND_PORT:-3000}`. Healthcheck with `wget` against
  `http://localhost/` (use `127.0.0.1` if alpine resolves `localhost` to IPv6
  and nginx is IPv4-only).
- `.dockerignore` must exclude `node_modules` and `dist` so the Windows host
  modules never enter the image; the build stage runs `npm ci`.
- Do not add a `/ws` location yet (feature 12 adds the endpoint and proxy).


<!-- blueprint:completion {"schemaVersion":1,"specBytes":12651,"specSha256":"addba1d7524e0bb0b735e60b2042436f12abf10287957415f3ce01257b15b60e","branch":"refs/heads/feature/dashboard-page","head":"85bce41b47cef69466708f3a5b16d00160763a86","baseRef":"refs/heads/master","baseCommit":"85bce41b47cef69466708f3a5b16d00160763a86","sourceTree":"581e09275acb6a7c01e772ca14075f98bdc2c25b","absentOptional":[]} -->
