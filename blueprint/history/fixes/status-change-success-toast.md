# Fix: Status change success toast

**Type:** Fix
**Status:** verified
**Branch:** fix/status-change-success-toast

## The problem

When an operator acknowledges, resolves or reopens an incident from the Events
detail drawer, a successful change is shown only by the drawer's status text
changing. Once the drawer closes, and with events arriving continuously, there
is no confirmation that the change went through. Only failures get a toast.

A second, related gap: the failure toast lives inside `StatusActions`, which is
inside the drawer. If the operator closes the drawer before the backend
answers, the component unmounts and a rejection's rollback happens silently.

## The fix

Move status-change feedback from the drawer up to `EventsPage`, which stays
mounted while the drawer opens and closes, and add a success message:

- `useChangeEventStatus()` in `frontend/src/api/events.ts` takes optional
  `onSuccess(event: IncidentEvent)` and `onError(error: Error)` callbacks and
  passes them to `useMutation`'s own options. Mutation-level callbacks still
  run after the calling component unmounts, while per-`mutate()` callbacks
  do not. The existing optimistic update, rollback and invalidation stay as
  they are.
- `EventsPage` owns one feedback state, `{ severity: 'success' | 'error';
  message }`, rendered in one `Snackbar` (bottom right, same as the other
  toasts):
  - success: `"<eventId> status changed to <STATUS>"` (for example
    `EVT-10001 status changed to RESOLVED`), taken from the server's response,
    filled green `Alert` with `role="status"` so screen readers announce it
    politely, auto-hides after 4 s;
  - error: the current behavior and text (ProblemDetail detail, falling back to
    "Couldn't change the status"), filled red `Alert`, `role="alert"`,
    auto-hides after 8 s.
  A newer message replaces the one showing. Both have a close button.
- `EventDetailDrawer` and `StatusActions` receive the callbacks from
  `EventsPage`; `StatusActions` loses its own `Snackbar`.

Must not break: the optimistic update and rollback, the pause of polling while
a change is in flight, the buttons disabling while pending, the "Can't reach
the backend" toast (separate component, unchanged), and the existing 409
rollback test.

No new dependency, component library or global notification system: one
`Snackbar` on the one page that changes statuses.

## Build steps

- [x] **1. Page-level status-change toasts.**
  Implement the fix above. Update `frontend/src/pages/EventsPage.test.tsx`:
  - a successful Acknowledge shows `EVT-1 status changed to ACKNOWLEDGED` in a
    `role="status"` element after the PUT resolves;
  - that message is still shown after the drawer is closed;
  - the existing rejected-change test still finds its message, now at page
    level, and the row and drawer still roll back;
  - closing the drawer while a PUT is pending and then rejecting it still shows
    the error toast.
  **Done when:** `npm test`, `npm run lint` and `npm run build` in `frontend/`
  are green with those tests included.

- [x] **2. Live check.**
  Rebuild the frontend with `docker compose up -d --build --wait frontend`. On
  http://localhost:3000/events, open an OPEN event, click Acknowledge, and see
  the green message naming the event and `ACKNOWLEDGED`; close the drawer and
  see the message stay until it times out. Trigger a rejection, for example
  by resolving an event in a second tab and then clicking Resolve on the stale
  drawer, and see the red message and rollback.
  **Done when:** both messages are observed in the running app with no console
  errors besides the browser's own failed-request lines.

## Verify

- Automated: the four `EventsPage.test.tsx` cases above plus the full frontend
  test suite, lint and build.
- Manual: Acknowledge, Resolve or Reopen any event on `/events` and read the
  green confirmation; it stays visible after closing the drawer.

## Implementation notes

- The toast renders through MUI `Portal` into `<body>`. While the detail drawer
  (a modal) is open, MUI marks the rest of the page `aria-hidden`, so a
  page-level toast would render but never reach a screen reader; the first
  test run caught this. Snackbar only mounts its element on opening, after the
  drawer has hidden its siblings, so a message raised while the drawer is open
  stays exposed.
- The toast keeps the last message while closing, with open/closed tracked
  separately, so the exit animation doesn't blank or recolor it. Clicking
  elsewhere on the page does not dismiss it.
- Live check: acknowledging showed the green message, which stayed after the
  drawer closed and hid after about 4 s. A real 409 (resolving the event in
  the background, then clicking Acknowledge on the stale drawer) answered in
  about 0.2 s, rolled the drawer back to OPEN, showed "Cannot change status
  from RESOLVED to ACKNOWLEDGED" in red, then refetched to RESOLVED; the
  message hid after about 8 s. The only console entry was the browser's 409
  line.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":5008,"specSha256":"cbd9ff997a1f4de235df005ff13920076f55fa872a7d4d7387fd99cfcd8ef0b7","branch":"refs/heads/fix/status-change-success-toast","head":"2df72f4dcb3ed0130bfb5fcf8564ddea6d960035","baseRef":"refs/heads/master","baseCommit":"2df72f4dcb3ed0130bfb5fcf8564ddea6d960035","sourceTree":"f1b6e229a86012ccf73e3ca7721a7e9624e40b64","absentOptional":[]} -->
