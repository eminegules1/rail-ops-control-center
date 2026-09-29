# Fix: Redis-outage log flooding in DashboardQueryService

**Type:** Fix
**Status:** verified
**Branch:** fix/redis-outage-log-flooding-in-dashboardqueryservice

## The problem

While Redis is down, every dashboard read that falls back to Postgres logs a
`WARN` with the full exception attached. `DashboardQueryService` has six such
sites (`summary` x2, `buildSummary`, `services`, `timeline`, `recentEvents`),
each passing `e` to `log.warn`. With the polling dashboard and the circuit
breaker's `CallNotPermittedException`, every request emits a multi-line stack
trace that says nothing new. Logs fill up and the first real cause (the
original Redis failure) is buried. Found as a residual risk in Feature 14's
review.

## The fix

- Log one line per fallback at `WARN`, with no stack trace: the existing message
  plus the exception class and message.
- Keep the full stack trace available at `DEBUG` for diagnosis.
- Route all six sites through one small private helper in `DashboardQueryService`
  so the format stays consistent.
- Do not change fallback behavior, the circuit breaker, return values, or the
  caught exception types. No rate limiter, new config, or dependency: a
  one-line message per request is proportionate.

## Build steps

1. [x] Add the private helper and switch the six `log.warn(..., e)` calls to it.
   **Done when:** no `log.warn` in `DashboardQueryService` passes a throwable,
   and a fallback test asserts the emitted `WARN` event carries no throwable
   while the response still comes from Postgres.

## Verify

- `mvn -B -pl backend -am verify` passes (Docker Desktop running); the new
  assertion lives in `DashboardQueryServiceFallbackIntegrationTest`.
- Manual: `docker compose stop redis`, call `GET /api/...` dashboard endpoints a
  few times, then `docker compose logs backend`: single-line warnings, no stack
  traces, responses still served. `docker compose start redis` afterwards.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":1928,"specSha256":"a2fa2f9173993303b9408a8234ad69bd97a8c71c2accbbe5da02329a4625e3f5","branch":"refs/heads/fix/redis-outage-log-flooding-in-dashboardqueryservice","head":"930aef03857e1422afa9bd3dbb872d986d2e8deb","baseRef":"refs/heads/master","baseCommit":"930aef03857e1422afa9bd3dbb872d986d2e8deb","sourceTree":"baaf9cb78d2d960d6caee6e0e7314ddd82912205","absentOptional":[]} -->
