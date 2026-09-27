# Fix: Round audit timestamps to milliseconds

**Type:** Fix
**Status:** verified
**Branch:** fix/round-audit-timestamps-to-milliseconds

## The problem

The Events API returns `receivedAt` and `updatedAt` with microseconds
(`2026-09-27T00:26:01.965392Z`) because Postgres `now()` stores microsecond
precision, while the event `timestamp` from the producer has milliseconds
(`2026-09-27T00:26:01.948Z`). All are valid ISO-8601, but the mixed precision
looks inconsistent in the API and Swagger.

## The fix

Truncate `receivedAt` and `updatedAt` to milliseconds when mapping the entity to
the API record in `EventResponse.from`
(`backend/src/main/java/com/railops/backend/EventResponse.java`), using
`Instant.truncatedTo(ChronoUnit.MILLIS)`.

- Storage keeps full precision; Postgres stays the untouched source of truth, so
  no Flyway migration.
- The API mapping is the single path for both endpoints and for feature 6's
  later `updatedAt` changes, so they get the same treatment.
- `timestamp` is left as received: it is the event's own creation time from the
  payload, and the producer already sends milliseconds.
- Must not break: field names, ISO-8601 UTC format, the page envelope, or
  existing tests. Jackson's `ISO_INSTANT` output omits a zero fraction and
  prints milliseconds as three digits, so values look like `...:01.965Z` or
  `...:01Z`.

## Build steps

- [x] **1. Truncate audit times in `EventResponse.from`.** Add a focused unit
  test `EventResponseTest` (mocked `IncidentEvent`, as in
  `EventIngestionServiceTest`) proving `receivedAt` / `updatedAt` lose sub-
  millisecond digits, an exact-millisecond value is unchanged, and `timestamp`
  and the other fields pass through unchanged. Done when: the test passes and
  `mvn -B -pl backend -am verify` is green.

## Verify

- `mvn -B -pl backend -am verify` green.
- Rebuild the backend (`docker compose up -d --build --wait backend`) and open
  http://localhost:8083/api/events?size=3 (port from your `.env`):
  `receivedAt` and `updatedAt` show at most three fractional digits, for
  example `2026-09-27T00:26:01.965Z`.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":2098,"specSha256":"d19a95e3ffbcbf33fcc1566c4ae83b544f91aabae1d1f5bafc17a9b9e96c8b26","branch":"refs/heads/fix/round-audit-timestamps-to-milliseconds","head":"1abefd85ba954bd64cab08061464a129b9c2edc1","baseRef":"refs/heads/master","baseCommit":"1abefd85ba954bd64cab08061464a129b9c2edc1","sourceTree":"8247fa744e294a96ee18834254b3417533e1e152","absentOptional":[]} -->
