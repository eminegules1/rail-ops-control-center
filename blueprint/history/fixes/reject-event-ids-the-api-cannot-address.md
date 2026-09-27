# Fix: Reject event IDs the API cannot address

**Type:** Fix
**Status:** verified
**Branch:** fix/reject-event-ids-the-api-cannot-address
**Fixes:** F-01

## The problem

Ingestion accepts any non-blank `eventId` of up to 64 characters
(`IncidentEventMessage`, `@NotBlank @Size(max = 64)`). The Events list shows
every stored event, but `GET` and `PUT /api/events/{eventId}` cannot address
some IDs. Checked live on 2026-09-27 through nginx:

| Request | Result |
|---|---|
| `/api/events/EVT-nope` (unknown ID) | 404, as expected |
| `/api/events/EVT%2F1` (ID `EVT/1`) | 400, rejected by Tomcat's encoded-slash handling before the app |
| `/api/events/..` (ID `..`) | 404, because the path is normalized to a different URL |

A hand-published event with such an ID (containing `/` or `\`, or consisting
of dots only) shows in the table, but its detail drawer says "Couldn't load
this event" and its status can never change. Producer IDs (`EVT-` + UUID) and
the brief's sample `EVT-10001` are unaffected.

## The fix

The root cause is missing input validation at the ingestion trust boundary.
Accept only IDs that are safe as a single URL path segment, and treat anything
else as an invalid message, like a blank ID today.

- In `IncidentEventMessage`, add to `eventId`:
  `@Pattern(regexp = "[A-Za-z0-9_:-][A-Za-z0-9._:-]*")`. That means letters,
  digits, `_`, `:` and `-`, plus `.` anywhere except the first character. A
  leading dot is excluded because `.` and `..` are path segments servers
  normalize. Keep `@NotBlank` and `@Size(max = 64)`.
- Such messages then fail validation. The existing path handles them: they are
  logged as `invalid fields: eventId`, skipped, and their offset committed. No
  new handling is needed.
- Rows already stored are not touched. Validation only applies to new
  messages.
- README: add the rule to the "A message is **invalid** when" list.

Must not break:

- Producer IDs (`EVT-` + UUID), `EVT-10001`, and the 64-character limit.
- The Events API, which already handles every ID that can now be stored.
- Nothing changes in the frontend. `eventPath` keeps encoding the ID, which is
  harmless for the allowed characters.

The user-owned plan said the consumer accepts "any non-blank string"
(`blueprint/project-plan.md:27`). At the owner's request, that line now
describes the URL-safe rule. The generated `project-overview.md:119` still
has the old wording until `/overview` refreshes it.

## Build steps

- [x] **1. Validate the event ID.** Add the `@Pattern` to
  `IncidentEventMessage.eventId`. In `IncidentEventMessageValidationTest`,
  replace the `"any non-blank id"` case: accept IDs such as `EVT-10001`,
  `EVT-` + UUID, `a.b`, `svc:1_x` and a 64-character ID; reject `EVT/1`,
  `EVT\1`, `.`, `..`, `.hidden`, `has space` and `EVT;1`, each with exactly
  the `eventId` violation. Add the rule to the README's invalid-message list.
  _Done when:_ `mvn -B -pl backend -am verify` passes, including the existing
  ingestion integration test.
- [x] **2. Live check.** Rebuild the backend. In Kafka UI, publish one message
  with `eventId` `EVT/1` and otherwise valid fields, and one with
  `EVT-10001`.
  _Done when:_ the backend log shows `EVT/1` skipped as `invalid fields:
  eventId`, it never appears in `/api/events`, and `EVT-10001` is stored and
  opens in the Events drawer.

## Verify

- `mvn -B -pl backend -am verify` passes.
- Run `docker compose up -d --build --wait`, then open Kafka UI
  (http://localhost:8081) and go to topic `incident-events`, then Produce
  Message. Publish a valid payload with `"eventId": "EVT/1"`. Run
  `docker compose logs backend` and look for "Skipping invalid event ... invalid
  fields: eventId". The event should not appear on the Events page.
- Publish the same payload with `"eventId": "EVT-10001"`. It should appear on
  the Events page and open in the detail drawer.

## Implementation notes

- `IncidentEventMessage.eventId` has the `@Pattern`. The Javadoc says why: the
  ID must work as one URL path segment.
- `EventIngestionService` now names each invalid field once (`.distinct()`).
  A blank ID breaks both `@NotBlank` and the new pattern, and would otherwise
  log `invalid fields: eventId, eventId, source`. The existing
  `EventIngestionServiceTest` caught this.
- The validation tests cover 5 accepted IDs (`EVT-10001`, a producer UUID ID,
  `a.b`, `svc:1_x`, `EVT.`) and 10 rejected ones (`EVT/1`, `EVT\1`, `.`,
  `..`, `.hidden`, `has space`, `EVT;1`, `EVT%2F1`, `EVT?1`, `ÉVT-1`), plus
  the existing 64-character limit test.
- `mvn -B -pl backend -am verify`: 188 tests, 0 failures (previously 175).
- Live check (backend rebuilt; both messages published to `incident-events`
  with `kafka-console-producer.sh` in the Kafka container, the same topic
  Kafka UI writes to). The backend logged `Skipping invalid event at
  incident-events-2@11725: invalid fields: eventId`. Searching for `EVT/1`
  returned 0 events. `EVT-10001` was stored,
  `GET /api/events/EVT-10001` returned 200, and `/events/EVT-10001` opened the
  drawer with Acknowledge and Resolve.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":5075,"specSha256":"810156f62ac039b9b8db98efafeadbbaf8315564a24c3d7f935283b0f767756a","branch":"refs/heads/fix/reject-event-ids-the-api-cannot-address","head":"19ecf31957d22da81c6d30804744fe71ab49d53f","baseRef":"refs/heads/master","baseCommit":"19ecf31957d22da81c6d30804744fe71ab49d53f","sourceTree":"c6f1e2fd4deff541e34d9556496c6f88267b0374","absentOptional":[]} -->

## Findings

### reject-event-ids-the-api-cannot-address/F-01 [P3] closed - Event IDs with a slash or only dots are stored but unreachable through the API

**File:** backend/src/main/java/com/railops/backend/IncidentEventMessage.java:12
**Found:** 2026-09-27 by /audit (scope: full; lens: all)
**Why it matters:** Ingestion accepts any non-blank `eventId` of up to 64
characters, and the Events list shows it. But `GET` and
`PUT /api/events/{eventId}` cannot address some IDs. Checked live through
nginx: `/api/events/EVT%2F1` returns 400 from Tomcat's encoded-slash rejection
before reaching the app (an unknown ID returns 404), and `/api/events/..` is
normalized to another path and returns 404. A hand-published event such as
`EVT/1` (or one containing `\`, `.` or `..`) therefore shows in the table, but
its drawer says "Couldn't load this event" and its status can never change.
Producer IDs (`EVT-` + UUID) are unaffected.
**Suggested fix:** Pick one contract. Either narrow `eventId` validation to a
URL-path-safe pattern, for example `@Pattern(regexp = "[A-Za-z0-9_:-][A-Za-z0-9._:-]*")`,
so such messages are skipped as invalid at ingestion (this changes the
documented "any non-blank string" contract and needs a user decision). Or keep
the contract and document the limitation in the README. Loosening Tomcat's
encoded-slash handling is not recommended.
**Resolution:** Fixed by fix/reject-event-ids-the-api-cannot-address: `eventId` now has `@Pattern(regexp = "[A-Za-z0-9_:-][A-Za-z0-9._:-]*")`, so such messages are skipped as `invalid fields: eventId`. Live: `EVT/1` was skipped and never stored; `EVT-10001` was stored and opens in the drawer. Re-reviewed 2026-09-27 by /audit (scope: current; lens: all): Hibernate Validator matches `@Pattern` against the whole value, and the pattern cannot backtrack badly. Every character class it allows reaches the app intact through nginx: `EVT.`, `a..b`, `svc:1_x`, `-x`, `_x` and `:x` each got the app's own 404 naming the exact ID, and `EVT-10001` returned 200. `.distinct()` in `EventIngestionService` only removes repeated field names from the log reason. 188 backend tests pass. Closed.
