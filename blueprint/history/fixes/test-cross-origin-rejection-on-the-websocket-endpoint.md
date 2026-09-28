# Fix: Test cross-origin rejection on the WebSocket endpoint

**Type:** Fix
**Status:** verified
**Branch:** fix/test-cross-origin-rejection-on-the-websocket-endpoint
**Fixes:** F-04

## The problem

`/ws` has no authentication in the MVP. The only guard for browsers is Spring's
default same-origin handshake check in `WebSocketConfig.registerStompEndpoints`
(`backend/src/main/java/com/railops/backend/WebSocketConfig.java`), which is
kept by not calling `setAllowedOrigins`. No automated test covers it.
`LiveUpdatesIntegrationTest` connects without an `Origin` header, and Spring
always accepts that. A later `setAllowedOrigins("*")` or
`setAllowedOriginPatterns("*")` would let any website's scripts subscribe to
live incident data, and every test would stay green. The only evidence today is
the manual curl check from Feature 12: 101 for the same origin and 403 for a
foreign one through nginx.

## The fix

Test only; production code does not change. In `LiveUpdatesIntegrationTest`
(`backend/src/test/java/com/railops/backend/LiveUpdatesIntegrationTest.java`),
connect through the existing `WebSocketStompClient` setup, passing
`WebSocketHttpHeaders` with an explicit `Origin`:

- `Origin: http://evil.example`: the handshake fails. The connect future
  completes exceptionally within 10 s and no session is created. Assert the
  failure is a handshake rejection (the cause chain carries HTTP 403) rather
  than any failure, so a broken server can't pass the test by accident.
- `Origin: http://localhost:<port>` (the test server's own origin): the
  handshake succeeds and a connected session comes back. This proves the
  rejection above is caused by the origin, not by the extra header.

Reuse the existing `connect` helper by adding an overload that takes the
headers, rather than copying the client setup. It must not break the two
existing tests or their session cleanup.

## Build steps

- [x] **1. Origin handshake tests.** Add the two cases above to
  `LiveUpdatesIntegrationTest`.
  **Done when:** both pass in `mvn -B -pl backend -am verify`. As a temporary
  local mutation check, adding `.setAllowedOriginPatterns("*")` to the `/ws`
  endpoint makes the foreign-origin test fail. Revert the mutation, and keep
  the backend suite green.

## Verify

- `mvn -B -pl backend -am verify` (Docker Desktop running): all backend tests
  pass, including the two new cases.
- Record the mutation check result: the foreign-origin test fails while origins
  are widened and passes again after the revert.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":2515,"specSha256":"5bf4e36089176142a69ff3f29fceeba4b0a64f00eec8e4a1e056c87aac5dd37f","branch":"refs/heads/fix/test-cross-origin-rejection-on-the-websocket-endpoint","head":"c21528547ae37c05e9cc527fdd61712ce0f6c0b5","baseRef":"refs/heads/master","baseCommit":"c21528547ae37c05e9cc527fdd61712ce0f6c0b5","sourceTree":"0b9b618a76f86b3c515ba18c847cec4ca12cf085","absentOptional":[]} -->
