# Feature: Role-based login

**From build-plan:** feature 20
**Build attempt:** 1
**Status:** verified
**Branch:** feature/role-based-login

## Goal

Lightweight JWT login with two demo users: **ADMIN** (may change incident status) and **VIEWER** (read-only).
Every dashboard page is behind a login page, the API and the live WebSocket feed reject anonymous callers, and a
VIEWER sees a read-only Events page instead of the status buttons. Demo credentials live in the README so a
reviewer's first run stays a one-step sign-in.

This is a stretch feature and the MVP (1-19) is complete, so the plan's "no stretch while MVP is unchecked" rule is
satisfied.

## In scope

Backend (`backend/`, package `com.railops.backend`):

- `POST /api/auth/login` checks a username and password against two in-memory demo users (BCrypt-hashed at
  startup) and returns a signed JWT with the user's role.
- Standard Spring Security (`spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`), no
  hand-written JWT code: `NimbusJwtEncoder` issues tokens, `NimbusJwtDecoder` validates them, both HS256.
- Stateless bearer-token authorization for `/api/**` (matrix under Data / contracts), with 401 and 403 answered as
  ProblemDetail.
- STOMP `CONNECT` on `/ws` must carry a valid token; without one the connection gets an ERROR frame.
- OpenAPI bearer scheme so Swagger UI's Authorize button works (otherwise `PUT .../status` can no longer be tried
  there).

Frontend (`frontend/`):

- `/login` page, a route guard for every other route, sign-out, and a signed-in user chip (name + role) in the top
  bar.
- The token is attached to every API request and to the STOMP connect frame; a 401 on a request that carried a
  token ends the session.
- VIEWER: no Acknowledge/Resolve/Reopen buttons, a short read-only note instead. ADMIN: unchanged behavior.

Docs: README demo credentials, sign-in flow, role table, known limitations; `docs/api.md` auth section.

## Out of scope

- Refresh tokens, token revocation or server-side logout, password change, user management, registration, lockout
  or rate limiting, "remember me", OAuth/SSO. Sign-out is client-side only; the token simply expires.
- Real user storage: no `users` table, no Flyway migration. The two users are configuration.
- Per-event ownership or audit of who changed a status (no `changedBy` column, no history). The username is not
  stored on events.
- Securing the producer (`POST /produce` on :8082), Kafka UI, Redis or Postgres. Only the backend and the SPA change.
- Features 21-24.

## Build loop

`workflow.stepReview` is `feature`: implement every step below, running that step's checks after each one, then
present a single review packet at the end. `checkpointCommits` is `disabled`: no per-step commits; `/complete`
creates the final commit. Steps are ordered so the compose stack keeps working after each one (enforcement is
switched on only after the frontend can log in).

## Build steps

- [x] **1. Backend: login endpoint and token service (no enforcement yet).** Add the two dependencies plus
  `spring-security-test` (test scope). A `SecurityFilterChain` that is stateless, has CSRF disabled (bearer header,
  no cookies), and **permits every request for now**, so existing behavior and tests are unchanged. `AuthProperties`
  (`@ConfigurationProperties("auth")`: `adminPassword`, `viewerPassword`, defaults in `application.yml`),
  in-memory `UserDetailsService` for `admin`/`viewer`, `BCryptPasswordEncoder`, HS256 key from `auth.jwt-secret`
  (env `AUTH_JWT_SECRET`, **no default committed**: a published default key would let anyone mint an ADMIN token).
  `SigningKeyConfig` rules: blank/unset -> generate 32 random bytes with `SecureRandom` and log one WARN line that
  sessions will not survive a restart; set but shorter than 32 UTF-8 bytes -> fail startup with a clear message;
  the secret is never logged, `TokenService` issuing 8-hour tokens with claims
  `sub`, `role`, `iat`, `exp`. `AuthController` (`POST /api/auth/login`) authenticates through `AuthenticationManager`
  and maps `BadCredentialsException` to a 401 ProblemDetail in `ApiExceptionHandler`. Adding the security starter alone
  turns on Boot's default lock-down (401, and 403 on every PUT from CSRF), so in this step `EventControllerTest` and
  `DashboardControllerTest` get `@Import` of the security configuration (a `@Configuration` class is not picked up by
  `@WebMvcTest`; confirm rather than assume for Boot 3.5.16). Record the backend test count before and after.
  *Done when:* `mvn -B -pl backend -am verify` passes with the new `AuthControllerTest` and token tests; a login with
  each demo user returns a token whose decoded `role` is `ADMIN`/`VIEWER` and whose `exp` is 8 hours after `iat`; all
  previously existing backend tests still pass with no assertion changed (only the two `@Import` additions), and the
  test count did not decrease.

- [x] **2. Frontend: session, login page, route guard, sign-out (against the still-open backend).**
  `src/lib/session.ts`: a small external store (`useSyncExternalStore`) holding `{token, username, role, expiresAt}`,
  persisted in `localStorage` under one key with every read/write in try/catch, ignoring corrupt or expired stored
  data, and clearing itself at `expiresAt`. `src/api/auth.ts`: `login()` via `sendJson`. `src/api/client.ts`:
  attach `Authorization: Bearer <token>` when a session exists; a 401 on a request that carried a token clears the
  session with reason `expired` (a 401 from the login request itself never does). `LoginPage` (MUI form, see
  states below), `RequireAuth` layout route wrapping the existing routes in `App.tsx` (`/login` sits outside
  `AppLayout`), a user chip and **Sign out** button in `AppLayout`. Signing out clears the session and calls
  `queryClient.clear()` so the next user never sees the previous user's cached data. `renderApp` takes an optional
  session and defaults to a signed-in ADMIN so existing page tests keep working. `src/test/setup.ts` already clears
  `localStorage` between tests.
  *Done when:* `npm test`, `npm run lint`, `npm run build` pass; new tests show a signed-out visit to `/events/EVT-1`
  lands on `/login` and returns to `/events/EVT-1` after a successful sign-in, and sign-out returns to `/login`.

- [x] **3. Backend: enforce roles, secure the WebSocket, update existing tests.** Replace the permit-all chain with
  the authorization matrix under Data / contracts using URL rules in the filter chain (the whole matrix is two rules, so
  keeping it in one file is simpler to read and test than `@PreAuthorize` spread over controllers). Also add
  `@ExceptionHandler(AccessDeniedException.class)` to `ApiExceptionHandler` returning the same 403 ProblemDetail,
  so a `@PreAuthorize` added later cannot fall into the catch-all and become a 500. JWT authority mapping: claim `role` -> `ROLE_<role>`. Custom `AuthenticationEntryPoint` and
  `AccessDeniedHandler` write `application/problem+json` (401 also sets `WWW-Authenticate: Bearer`). In
  `WebSocketConfig`, an interceptor next to `SubscribeOnly` validates the `Authorization` STOMP header on `CONNECT`
  with the `JwtDecoder` and rejects otherwise; `/ws` stays `permitAll` in the HTTP chain because a browser
  WebSocket handshake cannot carry headers. Add the OpenAPI `bearerAuth` scheme and global security requirement.
  Test support is shared, not copied: one `jwt()`-based request helper for the `@WebMvcTest` slices and one
  login-and-attach helper for the `TestRestTemplate` and STOMP tests. Existing tests get tokens: `@WebMvcTest` slices use `SecurityMockMvcRequestPostProcessors.jwt()` with the role
  authority; the `SpringBootTest` classes (`EndToEndIntegrationTest`, `LiveUpdatesIntegrationTest`) log in through
  `/api/auth/login` and send the bearer header, and their STOMP clients set the `Authorization` connect header.
  *Done when:* `mvn -B -pl backend -am verify` passes, including new tests for every row of the matrix (401 with no,
  garbage, expired and wrong-signature token; 403 for VIEWER on the status PUT; 200 for ADMIN), a test that an
  `AccessDeniedException` reaches the 403 handler, and a STOMP integration test that an anonymous `CONNECT` is
  rejected while an authenticated one still receives pushes. The full backend suite is green and its count is at
  least the step-1 count before step 4 starts.

- [x] **4. Frontend: live socket token, role-aware UI, forced sign-out.** `connectLive` sets the STOMP
  `Authorization` connect header from the current session on every (re)connect (`beforeConnect`).
  `LiveUpdatesProvider` opens the connection only while signed in and closes it on sign-out. `StatusActions` (or
  `EventDetailDrawer`) shows the read-only note for VIEWER instead of buttons. A 403 from the status PUT reaches the
  existing rollback + error toast unchanged (its `detail` text is shown). The login page shows an info notice when
  the session ended by expiry/401, and after an expiry sign-in returns to the page the user was on; after an
  explicit **Sign out** the next sign-in goes to `/dashboard`.
  *Done when:* `npm test`, `npm run lint`, `npm run build` pass; tests show VIEWER has no status buttons and ADMIN
  does, the 403 rollback toast, and that the provider connects only while signed in.

- [x] **5. Docs and live verification.** README: demo credentials table (`admin`/`RailOps#Admin2026`, `viewer`/`RailOps#Viewer2026`),
  what each role can do, how the token works (8 h; set `AUTH_JWT_SECRET` in `.env` to keep sessions across restarts,
  otherwise a random key is used and a restart signs everyone out), how to use Swagger's Authorize; replace the
  "Not built: user login" line. Trade-off notes, written as deliberate choices: (a) accounts are two fixed demo users
  from configuration; registration and user management are out of scope, and a production version would store users
  in PostgreSQL behind an admin API; (b) the token lives in `localStorage`, so an XSS bug could steal it (the
  frontend has no raw-HTML sinks and React escapes event text; `HttpOnly` cookies would add CSRF handling and a
  `/me` endpoint for little gain here); (c) no login rate limiting, because every compose port is bound to
  `127.0.0.1`, so only someone on the machine can reach the endpoint; (d) no revocation or lockout; (e) the producer
  and Kafka UI stay unauthenticated.
  `docs/api.md`: `POST /api/auth/login`, the header, the 401/403 shapes, the role matrix. Then bring the stack up
  (`docker compose up -d --build --wait`) and verify against it, recording only what was run: login as each user
  through `:3000`, anonymous `GET /api/events` is 401, VIEWER `PUT` is 403 and ADMIN `PUT` is 200, anonymous
  `/actuator/health` is 200 (the compose healthchecks depend on it), the dashboard shows `Live` for a signed-in user,
  and a VIEWER session shows no action buttons in the drawer.
  *Done when:* the README and `docs/api.md` match observed behavior, the compose stack reports every service healthy,
  and the browser or curl evidence above is captured in the implementation notes.

- [x] **6. Visual polish, review findings and demo passwords (requested after the first independent review).**
  Split-screen login: a brand panel from `md` up (looping muted background video `/Alstom_History_Innovation.mp4`
  with poster `/login_page.jpg`, a navy gradient overlay, the product name and tagline; not rendered below `md`, and
  hidden for `prefers-reduced-motion`) beside the form card headed by the Alstom logo (`/alstom-logo.svg`); no SSO or
  forgot-password controls. Top bar: a divider after the theme switch, then an avatar with initial, the username with
  the role in small caps under it, and a borderless text Sign out button. Fix F-13 (test fixture with a year-2999 expiry) and F-14 (a malformed login body got
  the status-change hint). Demo passwords become `RailOps#Admin2026` / `RailOps#Viewer2026`.
  *Done when:* `mvn -B -pl backend -am verify`, `npm test`, `npm run lint`, `npm run build` pass; the login page,
  top bar and the new passwords are seen on the rebuilt stack.

## Files / areas

New backend files (flat package, like the rest of the module): `SecurityConfig.java`, `AuthProperties.java`,
`AuthController.java`, `LoginRequest.java`, `LoginResponse.java`, `TokenService.java`. Edited:
`backend/pom.xml`, `application.yml` (`auth:` block), `ApiExceptionHandler.java`, `WebSocketConfig.java`, the
OpenAPI annotation (on `SecurityConfig` or `BackendApplication`), and the tests named in step 3. New backend tests:
`AuthControllerTest`, `SecurityRulesTest`, `TokenServiceTest`, a STOMP-connect interceptor test, plus additions to
`LiveUpdatesIntegrationTest`.

New frontend files: `src/lib/session.ts` (+ test), `src/api/auth.ts`, `src/pages/LoginPage.tsx` (+ test),
`src/components/layout/RequireAuth.tsx`, `src/components/layout/UserMenu.tsx`. Edited: `src/api/client.ts`,
`src/api/stompConnection.ts`, `src/api/liveUpdates.tsx`, `src/App.tsx`, `src/components/layout/AppLayout.tsx`,
`src/components/events/StatusActions.tsx`, `src/test/renderApp.tsx`, `src/api/client.test.ts`,
`src/pages/EventsPage.test.tsx`, `src/App.test.tsx`.

Also edited: `docker-compose.yml` (backend `AUTH_JWT_SECRET` passthrough) and `.env.example` (commented-out sample).
New backend file `SigningKeyConfig.java` (or a section of `SecurityConfig`) with a test for the three key rules.

Not touched: `frontend/nginx.conf` (it already forwards the `Authorization` header and proxies
`/ws`), `producer/`, Flyway migrations, `.github/workflows/ci.yml`.

## Data / contracts

**`POST /api/auth/login`**, public. Body: `{"username": string, "password": string}`; both `@NotBlank`, username
`@Size(max=50)`, password `@Size(max=72)` (BCrypt's limit). `200`:

```json
{ "token": "<jwt>", "tokenType": "Bearer", "username": "admin", "role": "ADMIN",
  "expiresAt": "2026-09-30T18:00:00Z" }
```

`expiresAt` is an ISO-8601 UTC instant, like the API's other timestamps. Errors: `400` validation (existing
"Invalid request" ProblemDetail shape); `401` title `Invalid credentials`, detail `Invalid username or password`,
identical for an unknown user and a wrong password.

**Token:** JWT, HS256, claims `sub` (username), `role` (`ADMIN` | `VIEWER`), `iat`, `exp` (= `iat` + 8 h, a constant).
The signing key is the UTF-8 bytes of `auth.jwt-secret` (>= 32 bytes) when set, otherwise 32 random bytes generated
at startup. Compose passes `AUTH_JWT_SECRET: ${AUTH_JWT_SECRET:-}` to the backend, and blank means unset;
`.env.example` documents it with a commented-out sample (`# AUTH_JWT_SECRET=<64 hex chars>`, generated with
`openssl rand -hex 32`) and says to generate your own value rather than reuse the sample.

**Demo users:** `admin`/`RailOps#Admin2026` -> ADMIN, `viewer`/`RailOps#Viewer2026` -> VIEWER; passwords come from
`auth.admin-password` / `auth.viewer-password` (defaults in `application.yml`; overridable via the standard env
binding, not wired into compose). Local demo values only, like `POSTGRES_PASSWORD`. These credentials were chosen
here because the plans say only "demo credentials in the README".

**Authorization matrix** (no token, or an invalid/expired one, is 401 on every protected row):

| Request | Anonymous | VIEWER | ADMIN |
| --- | --- | --- | --- |
| `POST /api/auth/login` | allow | allow | allow |
| `GET /actuator/health/**`, `GET /actuator/prometheus` | allow | allow | allow |
| `GET /swagger-ui/**`, `GET /v3/api-docs/**` | allow | allow | allow |
| `/ws` HTTP handshake | allow (auth is checked at STOMP `CONNECT`) | allow | allow |
| `GET /api/**` | 401 | 200 | 200 |
| `PUT /api/events/{id}/status` | 401 | 403 | 200 |
| any other path | 401 | 401 or 404 | 401 or 404 |

**401 body** (any protected path): `application/problem+json`, `status` 401, title `Unauthorized`, detail
`Sign in to continue`, header `WWW-Authenticate: Bearer`. The server does not say why (missing vs expired vs bad
signature). **403 body:** `status` 403, title `Forbidden`, detail `This action requires the ADMIN role`.

**STOMP:** `CONNECT` must include `Authorization: Bearer <jwt>`; a missing, invalid or expired token is rejected with
an ERROR frame (the same mechanism `SubscribeOnly` uses). A token that expires while the socket stays open is not
re-checked; the connection lives until it closes and the next reconnect needs a valid token.

**Frontend session:** `localStorage["railops.session"]` = `{token, username, role, expiresAt}`. Post-login navigation
target comes from router state only, never from a query string, so there is no open redirect.

**Logging:** never log passwords or tokens, and do not log the submitted username on failure.

## Testing

Backend (`mvn -B -pl backend -am verify`, Docker Desktop running): the tests listed under Files / areas; JUnit 5,
`@WebMvcTest` + MockMvc for the matrix and login, Mockito for the interceptor, Testcontainers for the STOMP
integration. Include an expired token minted with `TokenService` at a past `iat` and a token signed with a different
key. Frontend (`npm test`, `npm run lint`, `npm run build`): session store (round trip, expired, corrupt JSON,
unavailable storage); client (header attached only when signed in, 401 clears only when a token was sent, 403 keeps
the session); `LoginPage` (empty fields, wrong credentials keep the username, network error, pending state,
redirect back to `from`, already-signed-in redirect); route guard; VIEWER vs ADMIN drawer; provider connects only
while signed in. The real STOMP client cannot run in jsdom; the header is unit-tested where it is built and the
socket itself is proved in step 5's live check. No Browser tests command exists, so UI evidence comes from the
running stack. No Verify command exists yet.

**Login page states:** labelled Username and Password fields (`autocomplete="username"` / `"current-password"`),
Enter submits, empty field -> inline error and focus on that field, submitting -> button disabled and labelled
"Signing in...", `401` -> `role="alert"` message "Invalid username or password" with focus back on the password
field and the username kept, network/5xx -> "Couldn't reach the server. Try again.", errors clear when the user
edits, signed in already -> redirect to `/dashboard`.

## Notes for the AI

- `ApiExceptionHandler`'s catch-all turns anything unhandled into a 500, so the explicit `AccessDeniedException`
  handler is required. Filter-chain rejections never reach the advice; the chain-level 401/403 handlers cover them.
- `@WebMvcTest` applies Spring Security's default chain unless the project's security config is imported; expect the
  two existing slice tests to need `@Import` plus a `jwt()` post-processor. Confirm how `SecurityFilterChain` beans
  are picked up in this Boot version (3.5.16) rather than assuming.
- `TestRestTemplate` does not follow redirects for auth and will report 401 quietly; assert status codes in the
  updated integration tests, and keep the login helper in one place.
- `client.test.ts` asserts the exact `fetch` arguments; adding a header conditionally keeps the signed-out case
  byte-identical, so those assertions should stay valid.
- User text (event messages, IDs, username in the chip) renders as plain text, as elsewhere in the app.
- Do not add dependencies beyond the three above. No new frontend dependency is needed.
- Never commit a real or default signing key; the only key text in the repo is the commented-out `.env.example`
  sample. Do not log the secret, tokens or passwords.
- Keep the compose healthchecks working: `/actuator/health` must stay public.
- Update the `Build loop` checkboxes above as steps finish and record what was actually run.

## Implementation notes

What was run (2026-09-30), and how the result differs from the spec where it does.

- **Baseline:** `mvn -B -pl backend -am verify` on the untouched code: 237 backend tests. (The review discussion quoted 264; 237 is what ran.) After step 1: 253. After step 3: 284. Final `mvn -B verify` (producer 27, backend 284): BUILD SUCCESS.
- **Frontend:** `npm run lint`, `npm test` (154 tests in 17 files, up from 108 before this feature), `npm run build`: all pass.
- **Deviations from the spec text:**
  - The two existing `@WebMvcTest` slices use a class-level `@WithMockUser(roles = "ADMIN")` plus `@Import(SecurityConfig.class)`; the shared `jwt()` helper (`TestAuth`) is used by the new tests and the full-context tests log in through it.
  - `SecurityConfig` needed `@EnableConfigurationProperties(AuthProperties.class)` because slice tests do not process `@ConfigurationPropertiesScan`.
  - Every `/api/**` method other than GET requires ADMIN (not only the status PUT), so a new write endpoint is admin-only by default; the matrix row for the status PUT is unchanged.
  - The session store keeps the reason a session ended (`signed-out` or `expired`), which the route guard and login page use for the return-to-page rule and the notice. Clearing the query cache on sign-out lives in an `AppProviders` effect (covers sign-out, expiry and 401) rather than in the sign-out button.
  - Button label is "Signing in…" with an ellipsis character, matching the earlier web-guidelines fix.
- **Live checks against the rebuilt compose stack (all 7 services healthy).** Host port 8080 is held by another Windows process (`AgentService`), so the API was exercised through nginx on :3000 (same backend) and the actuator, Swagger and prometheus paths from inside the backend container:
  - Anonymous `GET /api/events`: 401, `application/problem+json`, `WWW-Authenticate: Bearer`, detail "Sign in to continue"; a garbage bearer token: 401.
  - Login: wrong password and unknown user return the identical 401; blank fields 400 "username is required; password is required"; admin and viewer logins return the expected role and an `expiresAt` 8 hours ahead.
  - VIEWER: `GET` 200, status `PUT` 403 "This action requires the ADMIN role", `DELETE` 403. Anonymous `PUT` 401. ADMIN `PUT` 200 with the new status.
  - Public without a token: `/actuator/health`, `/actuator/prometheus`, `/v3/api-docs`, `/swagger-ui/index.html` all 200; `/actuator` (the index) is 401.
  - STOMP `CONNECT` through `ws://localhost:3000/ws`: no token and a garbage token got an `ERROR` frame ("Sign in to connect") and a closed socket; admin and viewer tokens got `CONNECTED`.
  - Signing key: with no `AUTH_JWT_SECRET`, a token stopped working after a backend restart (401) and the log carried the one-line warning; with `AUTH_JWT_SECRET` set, the same token still worked after a restart and there was no warning. The stack was returned to the default config afterwards.
  - Browser (Playwright): signed-out deep link to `/events/<id>` redirected to `/login`; wrong password showed "Invalid username or password", kept the username and refocused the empty password; a VIEWER sign-in returned to the deep-linked drawer with the read-only note, no status buttons, a "viewer · VIEWER" chip and a Live connection chip; explicit sign-out cleared `localStorage`, showed no expiry notice and the next sign-in went to `/dashboard`; an ADMIN got the lifecycle button and resolving an event showed the new status and its toast; restarting the backend (random key) made the open page return to sign-in with "Your session ended. Sign in again to continue." and a later sign-in returned to the same event drawer. Console errors during that run were 502s while the backend restarted and the 401s that triggered the sign-out.
- **Step 6 (after the first independent review, which is therefore stale):** backend `mvn -B -pl backend -am verify` 285 tests pass; frontend `npm test` 157 tests, `npm run lint` and `npm run build` pass, and the `TimeoutOverflowWarning` is gone. Rebuilt stack, all services healthy: the old passwords answer 401, the new ones 200; a malformed login body gets the login hint while a malformed status body keeps the status hint; the logo, poster and mp4 are served (mp4 with range support). In the browser the video plays at 1920x1080 (H.264, 10.8 s), the split-screen renders as intended in dark mode, and the top bar (divider, stacked name and role, borderless Sign out) in both light and dark, and at 390 px wide there is no video element, one `h1` and no horizontal overflow. Not checked in a real light-mode or Safari/Firefox browser.
- **Not proven by a real pointer in the browser:** Playwright's real mouse input stopped reaching the page part-way through the session (a real click on an ordinary nav link also produced no event), so the drawer close button, Sign out and Sign in submit were triggered with scripted `click()` calls. Typing and form filling were real. A scripted click on the close button did close the drawer.
- **Not run:** a live start-up with `AUTH_JWT_SECRET` shorter than 32 bytes (covered by `SigningKeyTest`), and an expiring-while-connected WebSocket (documented as not re-checked).


<!-- blueprint:completion {"schemaVersion":1,"specBytes":24907,"specSha256":"1678c050e914821b7c2f4fa8fc21530883c4d73a1db3b4a7efed2148b931e887","branch":"refs/heads/feature/role-based-login","head":"8bbbdbc754cbf805a6b7bea393e343630d0e4644","baseRef":"refs/heads/master","baseCommit":"32f770b6eecf65e849c31e5affd842add8a98c5a","sourceTree":"2c66a6416ff597b1c33377c53ea16aa54d07a623","absentOptional":[]} -->

## Findings

### 20/F-13 [P3] closed - Bearer-token client tests use a year-2999 expiry that makes the session expiry timer fire after 1 ms

**File:** frontend/src/api/client.test.ts:85
**Found:** 2026-09-30 by /audit independent (scope: current; lens: tests)
**Why it matters:** The `bearer token` fixture expires in 2999. `signIn()`
schedules `endSession('expired')` with `setTimeout` (`frontend/src/lib/session.ts:52`),
and a delay above 2^31-1 ms overflows: `npm test` prints
`TimeoutOverflowWarning ... Timeout duration was set to 1`. The session store
therefore ends the fixture's session 1 ms after sign-in. The assertions that the
session is still present (`keeps the session when the API answers 403`,
`ignores a 401 for a token that is no longer the current session`) pass only
because the awaited mocked `fetch` settles in microtasks before the timer
macrotask runs. A change that adds a real macrotask to `request()` would fail
these tests for a reason unrelated to the behavior they check. The shared
`sessionFor()` helper in `frontend/src/test/sessions.ts` already exists for this.
Production tokens live 8 hours, so the app itself is not affected.
**Suggested fix:** Build the fixture with `sessionFor('ADMIN')` (overriding
`token` where a test needs it), or any expiry within 24 days. No production
change and no current requirement is lost.
**Resolution:** Fixed 2026-09-30: the `bearer token` fixture now uses `sessionFor('ADMIN')`; `npm test` no longer prints the warning. Awaiting re-review.
Closed 2026-09-30 by /audit independent (fresh subagent, claude-opus-5-5) at
8bbbdbc. `client.test.ts:86` builds the fixture with `sessionFor('ADMIN')`
(expiry 8 h ahead, well under the 2^31-1 ms timer limit), so the expiry timer no
longer fires at 1 ms; `npm test` (157 passed) prints no `TimeoutOverflowWarning`.
The repair is test-only and introduced no new defect.

### 20/F-14 [P3] closed - A missing or malformed login body gets the status-change JSON hint

**File:** backend/src/main/java/com/railops/backend/ApiExceptionHandler.java:125
**Found:** 2026-09-30 by /audit independent (scope: current; lens: quality)
**Why it matters:** `handleHttpMessageNotReadable` answers every unreadable
body with the fixed detail `request body must be JSON like {"status":"ACKNOWLEDGED"}`.
Before this feature only the status PUT took a body. `POST /api/auth/login` now
reaches the same handler for a missing body or broken JSON
(`AuthControllerTest.rejectsAMissingBody` asserts only the title), so a login
client is told to send a status object. `docs/api.md` documents only the
blank/missing-field and length cases for login.
**Suggested fix:** Keep the status example for the status PUT and give the
login endpoint its own hint (for example `{"username":"...","password":"..."}`,
chosen from the request path), then assert the login detail in
`AuthControllerTest.rejectsAMissingBody`. No current requirement is lost.
**Resolution:** Fixed 2026-09-30: the handler picks the hint from the request path (`/api/auth/login` gets `{"username":"...","password":"..."}`, everything else keeps the status hint); `AuthControllerTest` asserts both the missing-body and malformed-JSON cases. Awaiting re-review.
Closed 2026-09-30 by /audit independent (fresh subagent, claude-opus-5-5) at
8bbbdbc. `ApiExceptionHandler.java:129` compares `ServletWebRequest`'s
`uri=<request URI>` description (no query string, no context path configured)
with the login path; only the login endpoint gets the login hint and every
other body keeps the status hint, and the enum branch still overrides both.
The hint names only the documented public body shape, echoes nothing from the
request, and does not vary with credentials, so it leaks nothing.
`AuthControllerTest.rejectsAMissingBodyWithALoginHint` asserts the exact detail
and `rejectsMalformedJsonWithALoginHint` asserts the status hint is absent;
`docs/api.md:72` documents it. Both tests passed in the full backend verify
(285 tests; its one failure is the unrelated F-18).

## Independent review

# Independent Review

**Status:** passed
**Target commit:** 8bbbdbc754cbf805a6b7bea393e343630d0e4644
**Base commit:** 32f770b6eecf65e849c31e5affd842add8a98c5a
**Base ref:** master
**Spec hash:** 1678c050e914821b7c2f4fa8fc21530883c4d73a1db3b4a7efed2148b931e887
**Prepared by:** claude
**Builder model:** claude-sonnet-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-30T10:53:04Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-30T11:04:02Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Commands

- `git rev-parse HEAD`, `git merge-base master 8bbbdbc`, `sha256sum blueprint/context/current-feature.md`, `git status --porcelain --untracked-files=all`: pass (HEAD, merge base and spec hash match the request; only the two evidence files differed at the start)
- `git diff 32f770b..8bbbdbc` (60 files, 4 commits): reviewed in full
- `npm run lint` (frontend): pass
- `npm test` (frontend): pass, 17 files, 157 tests, no `TimeoutOverflowWarning`
- `npm run build` (frontend): pass (existing >500 kB chunk warning only)
- `mvn -B -pl backend -am verify`: fail, 285 tests, 1 failure in `IncidentStatusServiceIntegrationTest.allowedTransitionIsStored[1]` (outside the delta; see F-18); every auth, security, STOMP and existing controller test passed
- `mvn -B -pl backend -am test -Dtest=IncidentStatusServiceIntegrationTest`: pass, 10/10 on rerun
- `javap -c` on spring-messaging 6.2.19 `SimpleBrokerMessageHandler` and spring-security-crypto 6.5.11 `BCrypt`: inspected (library behavior evidence)
- `docker compose logs backend` filtered for JWT-shaped strings and demo passwords: pass (0 JWTs, 0 demo passwords; only the expected one-line random-key warning)

## Evidence

- Authorization matrix: `SecurityConfig.java` permits POST login, GET health/prometheus, Swagger/api-docs and `/ws`; GET `/api/**` needs authentication; every other `/api/**` method needs `ROLE_ADMIN`; everything else needs authentication. `SecurityRulesTest` covers each spec row (401 for none, garbage, expired, re-signed and scheme-less tokens; VIEWER 403 on the status PUT; ADMIN 200; public operational paths).
- Tokens: `NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)` pins the algorithm and applies the default `exp` validator; `TokenService` issues `sub`, `role`, `iat`, `exp = iat + 8 h`. Key from `SigningKey`: blank means 32 `SecureRandom` bytes plus one WARN, under 32 UTF-8 bytes fails startup, and the secret never appears in a message or log. No signing key is committed (only the commented public sample in `.env.example`, as the spec allows).
- Login: `DaoAuthenticationProvider` hides unknown users and applies its timing mitigation; `BadCredentialsException` maps to one identical 401 problem; nothing about the attempt is logged. BCrypt `checkpw` in 6.5.11 skips the 72-byte guard, so a 72-character multi-byte password cannot turn into a 500.
- 401/403 bodies come from `ProblemResponses` (problem+json, `WWW-Authenticate: Bearer`, no reason given); a controller-thrown `AccessDeniedException` is a 403 (`AccessDeniedAdviceTest`).
- STOMP: `AuthenticatedConnect` validates CONNECT/STOMP frames with the same decoder and throws a message-less `MessageDeliveryException` (the token never reaches logs). A SUBSCRIBE sent without CONNECT passes the interceptor, but `SimpleBrokerMessageHandler.sendMessageToSubscribers` delivers only to sessions registered by a broker-processed CONNECT (`sessions.get(id) != null`), so an unauthenticated socket receives nothing.
- F-14 hint: chosen from `ServletWebRequest`'s request-URI description; leaks nothing beyond the documented public body shape.
- Existing tests were not weakened: the two `@WebMvcTest` slices only gained `@Import(SecurityConfig.class)` and `@WithMockUser(roles = "ADMIN")`, and the full-context tests only gained a login step and the STOMP `Authorization` header; no assertion was removed or loosened.
- Frontend: the session store validates stored JSON, ignores expired or corrupt data and catches storage errors; `request()` attaches the bearer header only while signed in and ends the session only on a 401 for the current token; the return target comes from router state only and rejects `//` paths; the query cache is cleared whenever the session ends; the live provider connects only while signed in and re-reads the token in `beforeConnect`. User text renders as plain text.
- Login page: labelled fields with the required autocomplete values, `role="alert"` errors, focus management, one `h1`, and the brand panel is not rendered below `md` (JS `useMediaQuery` with `noSsr`), so phones never load the video.

## Findings

- F-15 [P3] open: the login video is still mounted, and so fetched, for reduced-motion visitors (CSS `display: none` only)
- F-16 [P3] open: the looping background video has no pause control (WCAG 2.2.2)
- F-17 [P3] open: unreferenced duplicate logo SVG and orphaned `favicon.svg` ship in `frontend/public`
- F-18 [P3] open: `IncidentStatusServiceIntegrationTest` fails intermittently on a 1 ms rounding difference (outside the delta)
- F-13 [P3] closed and F-14 [P3] closed after re-examination of the repaired code
- No P0 or P1 finding is open or fixed

## Remaining risk

- `mvn -B -pl backend -am verify` did not finish green in this pass because of the pre-existing flaky test (F-18); the failing class passed on rerun and all feature tests passed in the full run.
- F-15's download for reduced-motion visitors was not observed in a browser network trace; it rests on the code path and standard media-element behavior.
- No browser run in this pass (Check not required; no Browser tests command exists). Top-bar layout at phone width, Safari/Firefox and real light-mode rendering were not re-verified.
- A stray empty Playwright snapshot file, `.playwright-mcp/page-2026-09-30T10-59-19-855Z.yml`, was created by this reviewer's aborted browser probe. It is untracked and not ignored, so it must be removed before the freshness check (no path may differ from the target except the two evidence files). The reviewer did not delete it because deleting files was outside its permitted writes.
- Accepted-by-spec trade-offs remain: the token lives in `localStorage` (XSS could steal it), no login rate limiting, no revocation, and an open socket is not re-checked when its token expires. The client ends the session on the browser clock, so a clock more than 8 h fast would sign the user out right after sign-in (not verified).
