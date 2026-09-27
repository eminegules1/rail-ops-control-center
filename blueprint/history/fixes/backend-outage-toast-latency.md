# Fix: Backend outage toast latency

**Type:** Fix
**Status:** verified
**Branch:** fix/backend-outage-toast-latency

## The problem

When the backend goes down, the "Can't reach the backend - retrying" toast
takes roughly 30-50 s to appear on the dashboard, events and services pages.
In an operations control room that is too long to notice a dead backend.

Measured on 2026-09-27, with the backend container stopped and the rest of
the stack running:

- `curl http://localhost:3000/api/services` returned 502 after ~3.5 s, three
  times in a row.
- `nslookup backend 127.0.0.11` inside the frontend container took 3.3 s to
  answer NXDOMAIN. So nearly all of the wait is Docker's embedded DNS, which
  forwards the unknown name to the host resolver.
- nginx logged `backend could not be resolved (3: Host not found)` for each
  request. `frontend/nginx.conf` sets `resolver 127.0.0.11 valid=10s;` with
  no `resolver_timeout`, so nginx waits for that slow answer. The default
  timeout is 30 s.

The frontend multiplies that wait. Every page opens the toast from `isError`,
which only turns true after TanStack Query has used up its retries.
`retryUnlessClientError` in `frontend/src/api/client.ts` retries a 5xx up to 3
times, with the default backoff of 1 s, 2 s and 4 s. So each poll makes 4 slow
attempts plus 7 s of backoff, and the poll only starts on the next 5 s tick.

## The fix

Fix both halves at the source. No new dependency or configuration surface.

1. **nginx fails fast.** Add `resolver_timeout 1s;` next to the existing
   `resolver` line in `frontend/nginx.conf`. A healthy lookup of a running
   container through Docker DNS takes well under 1 s, and answers are cached
   for 10 s (`valid=10s`). A failed lookup now becomes a 502 after about 1 s
   instead of 3-5 s. Keep the variable `proxy_pass` and the resolver, because
   they let the proxy recover after the backend container is recreated.
2. **The toast opens on the first failed attempt.** The message already says
   "retrying", so it should show while TanStack Query is still retrying, not
   only after it gives up. Open it when the query has failed at least once:
   `isError || failureCount > 0`. Checked against the installed
   `@tanstack/query-core` 5.104: `failureCount` goes up on each failed attempt
   (the `failed` action during retries), and the start of each new fetch
   resets it to 0. `isError` stays true across later polls while cached data
   exists, so the toast stays open between polls. Apply this in the three
   places that open the toast:
   - `ServicesPage`: `open={isError || failureCount > 0}`.
   - `DashboardErrorToast`: the same check for any of the three queries.
   - `EventsPage`: keep today's rule that the toast shows only when there is
     table data to keep (the table's own error state covers the no-data
     case), so `events.data !== undefined && (events.isError ||
     events.failureCount > 0)`.

Must not break:

- Retries stay as they are, including never retrying a 4xx. Only the moment
  the toast opens changes.
- The toast still closes as soon as a request succeeds. A success resets
  `failureCount` and clears `isError`.
- Section and table error states (`failed`, "Couldn't load ...") still wait
  for the retries to finish. They keep using `isError && !data`.
- A 409 or other 4xx from a status change is still reported by
  `StatusChangeToast`, not the backend toast.
- The Vite dev server proxy is not changed. It points at `localhost` and has
  no Docker DNS lookup.

## Build steps

- [x] **1. nginx resolver timeout.** Add `resolver_timeout 1s;` to
   `frontend/nginx.conf`, with a short comment saying why. Rebuild the
   frontend image.
   _Done when:_ with the backend stopped, `curl -w "%{time_total}"
   http://localhost:3000/api/services` returns 502 in about 1 s or less. With
   the backend running again, the same URL returns 200 without delay, and
   still works after `docker compose up -d --force-recreate backend`.
- [x] **2. Open the toast on the first failure.** Update `ServicesPage`,
   `DashboardErrorToast` and `EventsPage` as described above. Add or extend
   page tests: with retries enabled and a failing API, the toast shows before
   the retries finish. Include a test that the toast closes once the API
   answers again. Update the outage sentence in `README.md` (around line 403)
   if it no longer matches.
   _Done when:_ `npm test`, `npm run lint` and `npm run build` pass in
   `frontend/`, and the new tests fail if the old `isError`-only condition is
   put back.

## Verify

- Run `docker compose up -d --build --wait`, then open
  http://localhost:3000/services.
- Run `docker compose stop backend`. The toast should appear within about 6 s
  (up to one 5 s poll tick plus a ~1 s failed request), not 30-50 s. Repeat
  on `/dashboard` and `/events`.
- Leave the backend down for 30 s. The toast should stay open and not
  flicker while a page still shows data.
- Run `docker compose start backend`. The toast should close on the next
  successful poll, and the data should refresh.

## Implementation notes

- Step 1 measured: with the backend stopped, a proxied request now returns 502
  in about 1.2 s, down from 3.5 s. nginx logs `backend could not be resolved
  (110: Operation timed out)`. With the backend running, and after
  `docker compose up -d --force-recreate backend`, requests answer 200 in
  about 0.2 s.
- `renderApp` takes an optional `QueryClient`, so page tests can run with
  retries on (`retry: 3, retryDelay: 0`). The new tests hold the retry open on
  a pending response. The services and dashboard tests fail with the old
  `isError`-only condition. The events test guards the rule that a first load
  with no data leaves the failure to the table, so it passes either way.
- Live check (rebuilt frontend, `/services` open, backend stopped): the toast
  appeared 2.1 s after `docker compose stop backend` returned, down from
  30-50 s. It then stayed open steadily for 20 s on `/dashboard`, sampled
  every 200 ms. After the backend was healthy again, the toast closed 5.8 s
  later, on the next poll.
- Known and unchanged: a page that has never loaded data resets its query to
  `pending` at each new poll. So on a page opened during an outage, the toast
  can close for up to ~1 s until that poll's first attempt fails.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":6325,"specSha256":"cfcaaf7c0013ee41b5632a410b932c3bd110abc3ed044677ea976206a941e1cc","branch":"refs/heads/fix/backend-outage-toast-latency","head":"106b776a10c569b19b321eb70bd25ce9e58429e7","baseRef":"refs/heads/master","baseCommit":"106b776a10c569b19b321eb70bd25ce9e58429e7","sourceTree":"4d09339483bcbb249b11aef5dc450fc68a334b2f","absentOptional":[]} -->
