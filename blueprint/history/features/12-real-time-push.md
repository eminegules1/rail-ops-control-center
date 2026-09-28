# Feature: Real-time push

**From build-plan:** feature 12
**Build attempt:** 1
**Branch:** feature/real-time-push
**Status:** verified

## Goal

The backend pushes changes to browsers as they happen. It uses STOMP over
WebSocket at `/ws`. `/topic/events` carries every newly ingested or
status-changed event. `/topic/summary` carries the dashboard summary at most
once per second while things change. The endpoint is reachable directly on the
backend, through the Vite dev proxy, and through the nginx frontend. The
frontend client (cache patching, connection chip, reconnect, fallback polling)
is Feature 13.

## In scope

- `spring-boot-starter-websocket` in `backend/pom.xml` (Boot-managed version).
- STOMP endpoint `/ws` using native WebSocket, with no SockJS, because
  `@stomp/stompjs` speaks it natively. It has a simple in-memory broker on
  `/topic` and broker heartbeats of 10 s / 10 s. The heartbeats keep sockets
  alive through nginx's 60 s idle proxy timeout and let the Feature 13 client
  detect dead connections.
- Subscribe-only clients: an inbound channel interceptor rejects client `SEND`
  frames. Without it, any socket could publish forged events to every
  dashboard through the simple broker. `/ws` has no application destinations
  (`@MessageMapping`).
- The default same-origin handshake check stays in place. No
  `setAllowedOrigins("*")`.
- `/topic/events` message on:
  - **CREATED**: ingestion applied the event to live state for the first time
    (`LiveStateUpdater.applyEvent` returned `true`). This covers a new row and
    a redelivery whose first delivery stored the row but failed on Redis. A
    true duplicate (`applyEvent` returned `false`) and an invalid event
    broadcast nothing.
  - **UPDATED**: `PUT /api/events/{eventId}/status` committed a real
    transition (`from != to`). The broadcast still happens if the Redis apply
    then failed, because Postgres holds the change. A same-status request, a
    404, a 409 or a 400 broadcasts nothing.
- `/topic/summary`: every CREATED or UPDATED marks the summary dirty. A
  fixed-delay 1 s scheduled task sends one fresh summary when the summary is
  dirty and nothing when it is clean.
- A failed push is logged and swallowed. It never fails ingestion (no Kafka
  retry or DLT because of push) and never fails the `PUT` response.
- A `/ws` proxy in `frontend/nginx.conf` (Upgrade/Connection headers,
  `Host $http_host` so the same-origin check sees the port) and in
  `frontend/vite.config.ts` (`ws: true`).
- A README "Real-time push" section: endpoint, topics, payloads, throttle,
  subscribe-only rule, and a manual check.

## Out of scope

- Any frontend STOMP client, cache patching, connection status chip, reconnect
  or row highlight (Feature 13). Polling stays as it is.
- Pushing timeline, services or recent-events data. Feature 13 patches or
  refetches those from the event messages.
- Redis circuit breaker or reconcile flag (Feature 14), metrics for pushes
  (Feature 15), auth on the socket (stretch Feature 20).
- An external broker relay, SockJS fallback, or per-user destinations.
- A new configuration property for the throttle interval. It is fixed at
  1000 ms.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, running each step's
checks, then present one review packet at the end. `checkpointCommits` is
`disabled`: make no step commits. `/complete` creates the feature commit.

## Build steps

- [x] **1. STOMP endpoint, subscribe-only.** Add the websocket starter. Add
  `WebSocketConfig` (`@EnableWebSocketMessageBroker`): endpoint `/ws`, simple
  broker `/topic` with heartbeats `{10000, 10000}` on the Boot
  `TaskScheduler`, and an inbound `ChannelInterceptor` that rejects
  `StompCommand.SEND` by throwing `MessageDeliveryException`. Leave the
  message converters at Boot's defaults so payloads use the application
  `ObjectMapper` (ISO-8601 `Instant`s, same as REST). Add `@EnableScheduling`
  to `BackendApplication`. It is needed for the step 3 tick and gives the
  broker its heartbeat scheduler.
  **Done when:** a unit test shows the interceptor rejects a SEND and passes
  SUBSCRIBE/CONNECT/DISCONNECT, and `mvn -B -pl backend -am verify` is green.

- [x] **2. Event broadcasts.** Add a record
  `EventChange(Type type, EventResponse event)` with `enum Type { CREATED,
  UPDATED }`. Add a `LiveUpdatePublisher` component (constructor-injected
  `SimpMessageSendingOperations`) with `eventCreated(EventResponse)` and
  `eventUpdated(EventResponse)`. Each sends to `/topic/events`, marks the
  summary dirty, and catches `MessagingException` with a warn log (event id
  only).
  Wire it up:
  - `EventIngestionService.ingest`: on both paths, load the stored row
    before the Redis apply. The new-row path gains one `findByEventId`, so a
    Postgres read failure is an ordinary retried ingestion failure, not a
    failed push. Apply the row's values and, when `applyEvent` returns
    `true`, call `eventCreated(EventResponse.from(row))`. `IngestionResult`
    return values are unchanged. (Revised during implementation: the spec
    first loaded the row after the apply, which let a push-only read fail
    ingestion.)
  - `IncidentStatusService.changeStatus`: when `change.from() !=
    event.status()`, call `eventUpdated(event)` after the Redis attempt,
    including when that attempt failed or returned `false`.

  **Done when:** unit tests in `EventIngestionServiceTest` cover these cases:
  new event → one CREATED; duplicate whose apply returns `false` → none;
  duplicate whose apply returns `true` → one CREATED; invalid event → none;
  Redis failure → the exception propagates and nothing is published. A
  `LiveUpdatePublisher` unit test shows a throwing template is swallowed.
  `IncidentStatusServiceIntegrationTest` (or a focused test beside it) shows
  one UPDATED for a real transition and none for same-status or invalid
  transitions. Backend verify is green.

- [x] **3. Throttled summary.** Give `LiveUpdatePublisher` an `AtomicBoolean`
  dirty flag and `@Scheduled(fixedDelay = 1000) publishSummary()`. If
  `dirty.getAndSet(false)`, send `DashboardQueryService.buildSummary()` to
  `/topic/summary`. This is built from the Redis counters, not the 5 s summary
  cache, because the cache can lag new events by up to 5 s. A
  `DataAccessException` or `MessagingException` is logged at warn and dropped.
  The next change re-marks the flag, so a Redis outage does not log every
  second.
  **Done when:** unit tests with mocks show: two changes then one tick → one
  send; a tick with no change → no send; a tick after a failed build → no
  exception, and a later change plus tick sends again. Backend verify is green.

- [x] **4. End-to-end over a real socket, proxies, docs.** Add
  `LiveUpdatesIntegrationTest`. It uses `@SpringBootTest(webEnvironment =
  RANDOM_PORT)` with the same Kafka/Postgres/Redis containers as
  `EventIngestionIntegrationTest`, and a `WebSocketStompClient` with
  `StandardWebSocketClient` and a Jackson converter, subscribed to both
  topics. Publish one valid event to Kafka and assert a CREATED message with
  that `eventId`, `status` `OPEN` and a string `timestamp`. Then
  `PUT .../status` `ACKNOWLEDGED` over HTTP and assert an UPDATED message with
  status `ACKNOWLEDGED`. Assert that at least one `/topic/summary` message
  arrives whose `totalEvents` is ≥ 1. Add a test that a client `SEND` to
  `/topic/events` never reaches a subscriber (a short bounded wait). Add the
  nginx `/ws` location and Vite `/ws` proxy, then the README section.
  **Done when:** the integration test passes in backend verify. `npm run build`
  passes in `frontend/`. With `docker compose up -d --build --wait`, a
  WebSocket upgrade request to `http://localhost:3000/ws` carrying
  `Origin: http://localhost:3000` returns `101 Switching Protocols`, while the
  same request with `Origin: http://evil.example` is refused (403). The
  request is a `curl -i` with `Connection: Upgrade`, `Upgrade: websocket`,
  `Sec-WebSocket-Version: 13` and a `Sec-WebSocket-Key`. Record the observed
  status lines. If the same-origin check rejects the proxied handshake,
  inspect the Host/Origin the backend sees and fix it in the nginx headers.
  Do not widen allowed origins.

## Files / areas

- `backend/pom.xml`: websocket starter
- `backend/src/main/java/com/railops/backend/BackendApplication.java`:
  `@EnableScheduling`
- New in `backend/src/main/java/com/railops/backend/`: `WebSocketConfig.java`,
  `EventChange.java`, `LiveUpdatePublisher.java`
- `EventIngestionService.java`, `IncidentStatusService.java`: publisher calls
- `DashboardQueryService.java`: reuse `buildSummary()` (package-private, same
  package, so no visibility change expected)
- Tests in `backend/src/test/java/com/railops/backend/`:
  `EventIngestionServiceTest` (new constructor argument),
  `IncidentStatusServiceIntegrationTest`, new `WebSocketConfigTest` /
  `LiveUpdatePublisherTest` / `LiveUpdatesIntegrationTest`. Any other test
  that constructs these services by hand gets the extra argument.
- `frontend/nginx.conf`, `frontend/vite.config.ts`
- `README.md`: new "Real-time push" section after "Dashboard data APIs"

## Data / contracts

- Handshake: `GET /ws` WebSocket upgrade, STOMP 1.2 frames. Browser origin
  must match the host (same-origin). Non-browser clients without an `Origin`
  are accepted (Spring default). No auth, as in the MVP.
- Clients may `CONNECT`, `SUBSCRIBE`, `UNSUBSCRIBE`, `DISCONNECT`. A `SEND` is
  rejected with a STOMP `ERROR` frame and the broker never delivers it.
- `/topic/events` body (JSON). `event` has exactly the `EventResponse` shape of
  `GET /api/events/{eventId}`:

  ```json
  { "type": "CREATED" | "UPDATED",
    "event": { "eventId", "source", "service", "severity", "message",
               "status", "timestamp", "receivedAt", "updatedAt" } }
  ```

- `/topic/summary` body: exactly the `DashboardSummary` shape of
  `GET /api/dashboard/summary`. It is sent at most once per 1000 ms of fixed
  delay, only after a change. No retained or initial message on subscribe:
  clients load initial state over REST.
- Delivery is best-effort and at-most-once. There is no replay after a
  disconnect, and order across Kafka partitions is not guaranteed. A client
  reconciles by refetching over REST (Feature 13).
- User-controlled text (`message`, `source`, `service`) travels as JSON
  strings. Rendering safety stays with the existing React text rendering. No
  HTML is built server-side.

## Testing

- Backend: `mvn -B -pl backend -am verify` (JUnit 5, Mockito, Testcontainers;
  Docker Desktop required). Unit tests for the interceptor, publisher,
  throttle, and ingestion/status wiring. One Testcontainers + real STOMP client
  integration test.
- Frontend: `npm run build` in `frontend/` for the Vite config change (no
  frontend logic changes, so no new Vitest tests).
- Compose: a manual curl upgrade check through nginx on port 3000, with the
  observed status lines recorded. No browser harness exists, and none is added.
- No Verify command exists yet. The module commands above are the gates.

## Notes for the AI

- Broadcast only after the Postgres commit (ingestion's native insert and the
  status `TransactionTemplate` both commit before returning). For ingestion,
  broadcast after the Redis apply succeeds, so a client that refetches the
  summary sees updated counters.
- Keep the duplicate-path comment and behaviour in `EventIngestionService`
  intact. Only add the capture of the apply result.
- Log event ids only, never message text (matches existing logging).
- Do not change `summary()` or the summary cache. The push uses
  `buildSummary()`.
- The `fixedDelay` tick runs in every `@SpringBootTest` context. It publishes
  nothing unless something is dirty, so existing integration tests are
  unaffected. If one is not, report it rather than disabling scheduling
  globally.
- Keep the class count small. The publisher owns both topics and the dirty
  flag. Add no interfaces or properties classes.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":12065,"specSha256":"8aaa322b42fa1d5c3671093794204156912122e9ab0fe27fbaf34c4a36ec1d82","branch":"refs/heads/feature/real-time-push","head":"21951d99c130c6f85d09025b799c610c4bdb71ea","baseRef":"refs/heads/master","baseCommit":"6690fdc7b11b04c614ef666f60d0e8855700e5ab","sourceTree":"1f2425f724dee03b07e0ebcdf4b2ca0453d13f59","absentOptional":[]} -->

## Independent review

# Independent Review

**Status:** passed
**Target commit:** 21951d99c130c6f85d09025b799c610c4bdb71ea
**Base commit:** 6690fdc7b11b04c614ef666f60d0e8855700e5ab
**Base ref:** master
**Spec hash:** 8aaa322b42fa1d5c3671093794204156912122e9ab0fe27fbaf34c4a36ec1d82
**Prepared by:** claude
**Builder model:** claude-opus-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-28T10:25:33Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-28T10:30:21Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Handoff

Review the active spec and the complete `6690fdc7b11b04c614ef666f60d0e8855700e5ab..21951d99c130c6f85d09025b799c610c4bdb71ea` delta in a fresh
session or isolated subagent without the builder conversation. Run all Audit lenses from scratch.
Run Check when required above. Do not edit product code, accept findings, or
reuse the existing findings as the review scope.

## Commands

- `git rev-parse HEAD` / `git merge-base master <target>` / `sha256sum blueprint/context/current-feature.md` / `git status --porcelain --untracked-files=all`: pass (HEAD, merge base and spec hash match the request; only `blueprint/context/review.md` dirty; spec is tracked)
- `mvn -B -pl backend -am verify`: pass (210 tests, 0 failures, 0 errors, 0 skipped; includes LiveUpdatesIntegrationTest 2, WebSocketConfigTest 6, LiveUpdatePublisherTest 5, EventIngestionServiceTest 7, IncidentStatusServiceIntegrationTest 9)
- `npm --prefix frontend run build`: pass (existing >500 kB chunk warning only)
- `npm --prefix frontend run lint`: pass
- `npm --prefix frontend test -- --run`: pass (68 tests)

## Evidence

- Reviewed all 16 files in the delta: WebSocketConfig, LiveUpdatePublisher, EventChange, EventIngestionService, IncidentStatusService, BackendApplication, backend/pom.xml, the five backend test files, frontend/nginx.conf, frontend/vite.config.ts, README.md and the spec.
- Security: SEND frames are rejected on the client inbound channel (unit test plus a real-socket test proving a forged SEND never reaches a subscriber); no `@MessageMapping` or application destination prefix; no widened allowed origins, so Spring's default same-origin handshake check applies; nginx passes `Host $http_host` so the port-bearing Origin matches; logs carry event ids only.
- Correctness: pushes happen after the Postgres commit and after the Redis apply for ingestion; CREATED only when `applyEvent` returns true; UPDATED only on a real transition, including after a Redis failure; push failures (`MessagingException`) are swallowed; the summary tick sends only when dirty and drops Redis/messaging failures.
- Performance: one extra `findByEventId` per new event (accepted by the spec); summary built at most once per second and only after a change; simple broker send is non-blocking to the outbound channel.
- Check was not required by the request and was not run; the step 4 curl upgrade check through nginx was not re-run by this reviewer.

## Findings

- F-04 [P3] open: cross-origin handshake rejection on `/ws` has no automated test (WebSocketConfig.java:40)
- F-05 [P3] unverified: a CREATED push can follow an UPDATED push for the same event with a stale status (EventIngestionService.java:58)
- No P0 or P1 findings.

## Remaining risk

- The nginx `/ws` proxy and the 101/403 origin behaviour through port 3000 were not re-verified by this reviewer (Check not required; docker compose stack not started).
- Same-origin rejection is guarded only by manual evidence until F-04 is addressed.
- Vite dev proxy `/ws` (`ws: true`) verified by build only, not by a live dev-server handshake.
- Push ordering across Kafka and the status API is best effort (F-05); Feature 13 must reconcile.
