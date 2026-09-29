# API reference

A hand-written companion to the generated OpenAPI reference. If the two ever
differ, the generated one is authoritative: Swagger UI at
http://localhost:8080/swagger-ui/index.html and the JSON at
http://localhost:8080/v3/api-docs (both served by the backend). Back to the
[README](../README.md).

Every `curl` example below was run against the local stack and the responses are
real, shortened only where noted. There is no authentication and no CORS
configuration; the dashboard reaches the API through nginx on the same origin.

## Contents

- [Base URLs](#base-urls)
- [Errors](#errors)
- [Events](#events) - [list](#get-apievents), [detail](#get-apieventseventid), [status change](#put-apieventseventidstatus)
- [Dashboard](#dashboard) - [summary](#get-apidashboardsummary), [services](#get-apiservices), [timeline](#get-apidashboardtimeline), [recent events](#get-apidashboardrecent-events)
- [Real-time push (STOMP over WebSocket)](#real-time-push-stomp-over-websocket)
- [Producer](#producer)
- [Operational endpoints](#operational-endpoints)

## Base URLs

| Service | URL | Notes |
|---|---|---|
| Backend | http://localhost:8080 | REST, WebSocket, Actuator, Swagger UI. Override the host port with `BACKEND_PORT`. |
| Producer | http://localhost:8082 | `POST /produce`, Actuator |
| Dashboard (nginx) | http://localhost:3000 | Proxies only `/api/**` and `/ws` to the backend; every other path returns the dashboard's `index.html`, so use the backend port for Actuator and Swagger UI. |

The examples use the backend on port 8080.

## Errors

Every error from the backend is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
`application/problem+json` body (Spring's `ProblemDetail`):

```json
{
  "type": "about:blank",
  "title": "Invalid query",
  "status": 400,
  "detail": "size must be between 1 and 100",
  "instance": "/api/events"
}
```

`detail` names the rejected parameter or field and the rule, and never echoes the
rejected value. `type` is `about:blank` except for status conflicts (below).

| Status | When | `title` |
|---|---|---|
| 400 | a query parameter or body field fails validation, is the wrong type, or is not one of the allowed values | `Invalid query` (parameters) or `Invalid request` (body) |
| 404 | the event does not exist, or the path is not an endpoint | `Event not found` or `Not Found` |
| 405 / 415 | wrong HTTP method, or a body that is not `application/json` | `Method Not Allowed` / `Unsupported Media Type` |
| 409 | a status change the state machine forbids, or a write that raced another request | `Invalid status transition` / `Concurrent update` |
| 500 | anything unexpected; details go to the log, not the response | `Internal error` |

## Events

Events are always read from PostgreSQL, so these endpoints work while Redis is
down. An event looks like this (`timestamp` is when the event happened,
`receivedAt` when the backend stored it, `updatedAt` the last status change; all
are UTC ISO-8601 with millisecond precision):

```json
{
  "eventId": "EVT-1681e836-dc30-4929-8dfd-5ecc31e3671b",
  "source": "TMS",
  "service": "timetable-service",
  "severity": "WARNING",
  "message": "Crew roster conflict for evening shift",
  "status": "OPEN",
  "timestamp": "2026-09-29T23:35:28.253Z",
  "receivedAt": "2026-09-29T23:35:28.270Z",
  "updatedAt": "2026-09-29T23:35:28.270Z"
}
```

Enumerations: `severity` is `INFO`, `WARNING`, `MAJOR` or `CRITICAL`; `status` is
`OPEN`, `ACKNOWLEDGED` or `RESOLVED`; `source` is one of `ATS`, `CBTC`, `SCADA`,
`TMS`, `PIS`.

### GET /api/events

A filtered, sorted, paginated list.

| Parameter | Default | Rules |
|---|---|---|
| `severity` | none | one of the severities above (case-sensitive) |
| `status` | none | one of the statuses above (case-sensitive) |
| `source` | none | exact match; an unknown source returns an empty page, not an error |
| `service` | none | exact match on the service name |
| `q` | none | case-insensitive text found in `message`, `service` or `eventId`; at most 200 characters |
| `page` | `0` | zero-based, 0 or more |
| `size` | `20` | 1 to 100 |
| `sort` | `timestamp,desc` | `field` or `field,asc\|desc`; `field` is `timestamp`, `receivedAt`, `service`, `source` or `eventId`. `severity` and `status` are not sortable. Ties are broken by insertion order, newest first, so pages are stable. |

Filters combine with AND. The response is a stable page envelope:

```bash
curl -s "http://localhost:8080/api/events?severity=CRITICAL&status=OPEN&size=2&sort=timestamp,desc"
```

```json
{
  "content": [
    { "eventId": "EVT-bc8e1bb1-12c7-441b-a8c6-b7b062b88760", "source": "ATS", "service": "route-service",
      "severity": "CRITICAL", "message": "Automatic route request rejected for platform 3", "status": "OPEN",
      "timestamp": "2026-09-29T23:34:25.453Z", "receivedAt": "2026-09-29T23:34:25.460Z",
      "updatedAt": "2026-09-29T23:34:25.460Z" },
    { "eventId": "EVT-0f9a4cff-fdcc-4919-a760-4a9e3ed08415", "...": "second event, shortened" }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 3519,
  "totalPages": 1760
}
```

More examples:

```bash
curl -s "http://localhost:8080/api/events?q=signal&service=signal-service&size=1&sort=eventId,asc"   # 200
curl -s "http://localhost:8080/api/events?size=0"          # 400 "size must be between 1 and 100"
curl -s "http://localhost:8080/api/events?severity=SEVERE" # 400 "severity must be one of INFO, WARNING, MAJOR, CRITICAL"
curl -s "http://localhost:8080/api/events?sort=status"     # 400 "sort must be field or field,asc|desc with field one of eventId, receivedAt, service, source, timestamp"
curl -s "http://localhost:8080/api/events?page=x"          # 400 "page must be a whole number"
```

### GET /api/events/{eventId}

One event, in the shape shown above. An unknown ID is a 404.

```bash
ID=$(curl -s "http://localhost:8080/api/events?size=1" | grep -o '"eventId":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s "http://localhost:8080/api/events/$ID"                 # 200, the event
curl -s "http://localhost:8080/api/events/EVT-does-not-exist"  # 404
```

```json
{"type":"about:blank","title":"Event not found","status":404,"detail":"No event with id EVT-does-not-exist","instance":"/api/events/EVT-does-not-exist"}
```

### PUT /api/events/{eventId}/status

Changes an incident's status and returns the updated event. The body is
`{"status": "<OPEN|ACKNOWLEDGED|RESOLVED>"}`; clients send no version number
(the backend uses optimistic locking internally).

| From | Allowed targets |
|---|---|
| `OPEN` | `ACKNOWLEDGED`, `RESOLVED` |
| `ACKNOWLEDGED` | `RESOLVED` |
| `RESOLVED` | `OPEN` (reopen) |

Sending the event's current status is not an error: the event comes back
unchanged, with the same `updatedAt`. Any other change is a 409 that lists what
would have been allowed. Redis counters are updated and an `UPDATED` message is
pushed on success (see [Incident status update](../README.md#incident-status-update)).

```bash
curl -s -X PUT "http://localhost:8080/api/events/$ID/status" \
  -H 'Content-Type: application/json' -d '{"status":"ACKNOWLEDGED"}'   # 200, status now ACKNOWLEDGED
curl -s -X PUT "http://localhost:8080/api/events/$ID/status" \
  -H 'Content-Type: application/json' -d '{"status":"OPEN"}'           # 409
```

```json
{
  "type": "/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "detail": "Cannot change status from ACKNOWLEDGED to OPEN",
  "instance": "/api/events/EVT-1681e836-dc30-4929-8dfd-5ecc31e3671b/status",
  "allowedTransitions": ["RESOLVED"]
}
```

| Request | Result |
|---|---|
| `{"status":"CLOSED"}` | 400, `status must be one of OPEN, ACKNOWLEDGED, RESOLVED` |
| `{}` | 400, `status is required` |
| body that is not JSON | 400, `request body must be JSON like {"status":"ACKNOWLEDGED"}` |
| unknown `eventId` | 404 `Event not found` |
| `Content-Type: text/plain` | 415 |
| another request changed the event at the same moment | 409 `Concurrent update`: "reload it and try again" |

The concurrent-update 409 comes from the code path (`ApiExceptionHandler`); it
was not provoked in the manual run.

## Dashboard

These four endpoints read the Redis live state and fall back to PostgreSQL
aggregates when Redis is unavailable, with the same response shapes
([Redis resilience](../README.md#redis-resilience)).

### GET /api/dashboard/summary

Totals, severity distribution and each service's health. Cached for up to
5 seconds; a status change refreshes it immediately. `criticalEvents` counts
`CRITICAL` events that are not `RESOLVED`; `severityDistribution` counts all
events.

```bash
curl -s http://localhost:8080/api/dashboard/summary
```

```json
{
  "totalEvents": 100115,
  "openEvents": 69952,
  "acknowledgedEvents": 19959,
  "criticalEvents": 4511,
  "severityDistribution": { "INFO": 50057, "WARNING": 30054, "MAJOR": 15021, "CRITICAL": 4983 },
  "services": [
    { "name": "passenger-info", "status": "DOWN", "lastEventTime": "2026-09-29T23:36:00.953Z" },
    { "name": "power-supply", "status": "DOWN", "lastEventTime": "2026-09-29T23:35:50.781Z" }
  ]
}
```

(`services` shortened to two of six.) A service's `status` is `DOWN` while it has
an active (`OPEN` or `ACKNOWLEDGED`) `CRITICAL` event, `DEGRADED` while it has an
active `MAJOR` or `WARNING` one, otherwise `HEALTHY`. The example is from a stack
that had just received large test bursts, which is why every service is `DOWN`.

### GET /api/services

Every known service, sorted by name, with its live state.

```bash
curl -s http://localhost:8080/api/services
```

```json
[
  { "name": "passenger-info", "status": "DOWN", "lastEventTime": "2026-09-29T23:36:00.953Z",
    "latestSeverity": "MAJOR", "openCount": 11651, "activeCount": 14954 }
]
```

(one of six services shown). `openCount` counts `OPEN` events and `activeCount`
counts `OPEN` plus `ACKNOWLEDGED` events. `lastEventTime` and `latestSeverity`
follow the newest event `timestamp`, not arrival order.

### GET /api/dashboard/timeline

Events per UTC minute and severity, by event `timestamp`. Oldest first, ending
with the current minute; minutes without events are present with zero counts.

| Parameter | Default | Rules |
|---|---|---|
| `minutes` | `60` | window length, 1 to 120 |

```bash
curl -s "http://localhost:8080/api/dashboard/timeline?minutes=3"
```

```json
[
  { "minute": "2026-09-29T23:34:00Z", "counts": { "INFO": 8, "WARNING": 13, "MAJOR": 5, "CRITICAL": 2 } },
  { "minute": "2026-09-29T23:35:00Z", "counts": { "INFO": 14, "WARNING": 8, "MAJOR": 5, "CRITICAL": 0 } },
  { "minute": "2026-09-29T23:36:00Z", "counts": { "INFO": 2, "WARNING": 0, "MAJOR": 1, "CRITICAL": 0 } }
]
```

`minutes=0` and `minutes=121` are both 400 (`minutes must be between 1 and 120`).

### GET /api/dashboard/recent-events

The most recently processed events, newest first, in the order the backend applied
them (not sorted by `timestamp`). Each item has the event shape from
[Events](#events).

| Parameter | Default | Rules |
|---|---|---|
| `limit` | `20` | 1 to 50 |

```bash
curl -s "http://localhost:8080/api/dashboard/recent-events?limit=1"
```

```json
[
  {
    "eventId": "EVT-8235748d-7013-45d7-9ab9-ed8b772e24cc",
    "source": "SCADA",
    "service": "power-supply",
    "severity": "INFO",
    "message": "Auxiliary power switched to backup at depot",
    "status": "OPEN",
    "timestamp": "2026-09-29T23:36:04.988Z",
    "receivedAt": "2026-09-29T23:36:05.078Z",
    "updatedAt": "2026-09-29T23:36:05.078Z"
  }
]
```

`limit=51` is a 400 (`limit must be between 1 and 50`).

## Real-time push (STOMP over WebSocket)

The backend serves STOMP 1.2 over a plain WebSocket (no SockJS) at `/ws`:
`ws://localhost:8080/ws`, or `ws://localhost:3000/ws` through nginx. Clients only
subscribe; a `SEND` frame is rejected with an `ERROR` frame
(`Clients can only subscribe`). Both sides send heartbeats every 10 seconds. The
handshake keeps Spring's default same-origin check, so a browser page must be
served from the same origin (the dashboard is, through nginx). Delivery is best
effort: nothing is sent on subscribe and missed messages are not replayed
([Real-time push](../README.md#real-time-push)).

| Destination | Payload | Sent |
|---|---|---|
| `/topic/events` | `{"type": "CREATED" or "UPDATED", "event": <event>}` | for every newly stored event and every status change |
| `/topic/summary` | the same object as `GET /api/dashboard/summary` | at most once per second, and only after something changed |

Frames captured from a real session (handshake, then one produced event):

```text
CONNECTED
version:1.2
heart-beat:10000,10000

MESSAGE
destination:/topic/events
content-type:application/json
subscription:0
message-id:25082156-bfd1-4eab-9d2b-f6ca3e3a7031-0
content-length:334

{"type":"CREATED","event":{"eventId":"EVT-909dfba8-1f75-4931-8918-ef5b99e82e38","source":"ATS","service":"train-tracking","severity":"WARNING","message":"Train T-212 position update delayed","status":"ACKNOWLEDGED","timestamp":"2026-09-29T23:36:29.281Z","receivedAt":"2026-09-29T23:36:29.290Z","updatedAt":"2026-09-29T23:36:29.290Z"}}
```

A minimal client with Node 22 or newer (built-in `WebSocket`), printing every
frame for five seconds:

```js
const ws = new WebSocket('ws://localhost:8080/ws');
ws.onopen = () => ws.send('CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\0');
ws.onmessage = (m) => {
  console.log(String(m.data));
  if (String(m.data).startsWith('CONNECTED')) {
    ws.send('SUBSCRIBE\nid:0\ndestination:/topic/events\n\n\0');
    ws.send('SUBSCRIBE\nid:1\ndestination:/topic/summary\n\n\0');
  }
};
setTimeout(() => process.exit(0), 5000);
```

Save it as `watch.mjs`, run `node watch.mjs`, and trigger events with
`curl -X POST "http://localhost:8082/produce?count=3"` in another terminal.

## Producer

The producer publishes generated events to Kafka. It has one business endpoint;
what the events look like and how the automatic mode is configured is in
[Event producer](../README.md#event-producer).

### POST /produce

Publishes a batch now and returns once Kafka has confirmed every message.

| Parameter | Default | Rules |
|---|---|---|
| `count` | `1` | 1 to 1000 |

```bash
curl -s -X POST "http://localhost:8082/produce?count=3"
```

```json
{"sent":3,"duplicates":0,"invalid":0}
```

`sent` is the number of Kafka messages; `duplicates` of them are deliberate
re-sends of an earlier event ID, and `invalid` of them are deliberately broken
messages that the backend sends to the dead-letter topic. Both are random
fractions of the batch (about 5% and 2% by default), so small batches often show
zeros.

| Request | Result |
|---|---|
| `count=0` or `count=1001` | 400, `detail` is `Validation failure` |
| `count=abc` | 400, `detail` is `Failed to convert 'count' with value: 'abc'` |
| `GET /produce` | 405 |
| Kafka does not confirm every message | 503 `Kafka unavailable`, with `requested` and `confirmed` counts in the body |

The producer's errors use the same problem-detail shape but are less specific
than the backend's, because it relies on Spring's defaults for validation
failures.

## Operational endpoints

| Endpoint | Service | Purpose |
|---|---|---|
| `GET /actuator/health` | backend `:8080`, producer `:8082` | `{"status":"UP"}`; used by the Compose healthchecks |
| `GET /actuator/prometheus` | backend, producer | Prometheus text format; metric names are listed in [Observability](../README.md#observability) |
| `GET /actuator` | backend, producer | index of the exposed endpoints |
| `GET /swagger-ui/index.html`, `GET /v3/api-docs` | backend | generated API reference (see the top of this page) |

The backend and the producer expose only `health` and `prometheus` from Actuator.
`/api/**` returns 404 for unknown paths.

```bash
curl -s http://localhost:8080/actuator/health   # {"status":"UP"}
curl -s http://localhost:8082/actuator/health   # {"status":"UP"}
```
