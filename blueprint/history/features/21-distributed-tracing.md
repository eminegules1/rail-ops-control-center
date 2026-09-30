# Feature: Distributed tracing

**From build-plan:** feature 21
**Build attempt:** 1
**Status:** verified
**Branch:** feature/distributed-tracing

## Goal

One event's journey can be followed as a single trace in Jaeger: producer `/produce` request (or the scheduled
send) -> Kafka send -> backend consumer -> ingestion. The backend's REST API requests appear as their own traces.
The trace ID also appears in the backend's JSON logs, so a Jaeger trace and its log lines can be matched.

Build-plan wording is "OpenTelemetry traces across producer -> Kafka -> backend -> API, Jaeger container in
compose". "API" is read as the backend's REST API server spans; the browser is not instrumented (see Out of scope).

## In scope

- **Both Java modules** (`producer`, `backend`): Micrometer Tracing with the OpenTelemetry bridge and the OTLP/HTTP
  exporter, on the Actuator and Micrometer setup already there. No OpenTelemetry Java agent, no manual span code.
- **Kafka context propagation:** enable Spring Kafka observations on the producer's `KafkaTemplate` and on the
  backend's listener containers, so the W3C `traceparent` header travels on the record and the consumer span is a
  child of the send span.
- **Sampling:** 100% (`management.tracing.sampling.probability: 1.0`); Boot's default of 10% would make the demo
  look broken. Demo volume is about 0.5 events/s plus a 200-event seed burst per producer start.
- **Log correlation:** `traceId` and `spanId` in the backend and producer JSON log lines. Micrometer Tracing puts
  them in the logging context and `LogstashEncoder` already emits the context, so this should need no logback
  change; verify rather than assume.
- **Compose:** a `jaeger` service (Jaeger v2 all-in-one, in-memory storage, pinned image tag) receiving OTLP/HTTP on
  4318 and serving its UI on 16686. Memory is bounded for the 4 GB Docker limit: `mem_limit: 512m` on the service
  and an in-memory trace cap of 10000 traces (`--memory.max-traces=10000`, as requested). That flag is a Jaeger v1
  spelling; step 2 must confirm the pinned v2 image accepts it and otherwise set the same cap through the
  image's config (memory storage `max_traces: 10000`), and confirm the cap took effect. Both ports are published on `127.0.0.1` only, like the other services
  (`JAEGER_UI_PORT`, `JAEGER_OTLP_PORT`). `producer` and `backend` get the collector URL through the
  environment variable the Boot property maps to; the `application.yml` default points at `localhost:4318` so a
  host-run app (IDE) also exports to the published port.
- **Best-effort tracing:** `producer` and `backend` do **not** `depends_on` Jaeger. With Jaeger stopped or slow to
  start they still start, stay healthy, and serve requests and consume events; spans are dropped until it is up.
- **Noise control:** the compose healthchecks hit `/actuator/health` every 10 s on each app and would drown the
  Jaeger service list. Health and Prometheus scrape requests must not produce traces (property if Boot has one,
  otherwise one small `ObservationPredicate` bean per module).
- **Docs:** README (quick start URL, service table, host-port list, Observability section, architecture diagram,
  known limitations), `.env.example`, the URL list in AGENTS.md Commands.

## Out of scope

- Browser or nginx instrumentation: the React app sends no `traceparent`, so an API trace starts at the backend.
- Child spans for PostgreSQL, Redis, WebSocket pushes, or the reconciler; no `@Observed` or hand-written spans.
- Tracing the dead-letter publish. The DLT `KafkaTemplate` is built by hand in `KafkaConsumerConfig` and is left
  as is; the DLT record still carries the original `traceparent` header because the recoverer copies headers.
- Persistent trace storage, Elasticsearch/Cassandra/OpenSearch, Grafana, Tempo, an OpenTelemetry Collector
  container, sampling strategies, trace-based metrics, exemplars.
- Auth in front of Jaeger UI (same status as Kafka UI and the producer; documented as a limitation).
- Feature 22 (image publishing) and later stretch items.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review packet. `checkpointCommits` is
`disabled`, so no per-step commits; `/complete` makes the final feature commit. Each step ends with its Done-when
check before the next starts.

## Build steps

- [x] **1. Tracing in both modules, verified without Docker for the Java side.**
  Look up the current Spring Boot 3.5 tracing and Kafka observation property names in the docs before writing
  config (Context7). Add `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` (both managed by the
  Boot BOM, so no version pins) to `producer/pom.xml` and `backend/pom.xml`. In each `application.yml`:
  sampling probability 1.0, OTLP endpoint default `http://localhost:4318/v1/traces`,
  `spring.kafka.template.observation-enabled: true` (producer) and
  `spring.kafka.listener.observation-enabled: true` (backend). Add a backend integration test (existing
  Testcontainers Kafka setup, `@AutoConfigureObservability`, in-memory span capture from a test-scoped dependency,
  OTLP export off) that publishes a record with a known `traceparent` header and asserts the consumer span carries
  that trace ID and that the ingested event's log lines carry `traceId`. Existing tests must not start exporting
  to `localhost:4318`: `EndToEndIntegrationTest` already uses `@AutoConfigureObservability`, so turn OTLP export
  off there (and wherever else it appears).
  **Done when:** `mvn -B verify` passes for `producer` and `backend` (Docker running), the new test fails if the
  Kafka observation property is removed, and no test logs OTLP connection errors.

- [x] **2. Jaeger in compose, end-to-end on the live stack, noise and outage behavior.**
  Add the `jaeger` service and the environment wiring described above. Before pinning, pull the candidate tag and
  check what the image offers: that its default config accepts OTLP/HTTP from other containers (not localhost
  only), and whether it contains a probe binary for a healthcheck. Add a healthcheck in the repo's style when it
  can be done; otherwise leave it out and say why in a comment (no shell in the image). Confirm the `--build`
  images still build (`docker compose build`; Dockerfiles need no change because no module was added). Add the
  health/Prometheus exclusion and confirm it against Jaeger.
  **Done when**, on `docker compose up -d --build --wait`:
  0. `docker compose config` shows `mem_limit` of 512m on `jaeger`, `docker compose ps` shows Jaeger running (not
     restarting or OOM-killed), and the 10000-trace cap is confirmed active (container starts with it; note how it
     was confirmed).
  1. Jaeger UI at http://localhost:16686 lists services `producer` and `backend`.
  2. `POST http://localhost:8082/produce?count=1` yields one trace containing the producer's HTTP server span, the
     Kafka send span, and the backend's Kafka consumer span, all under one trace ID.
  3. The backend log line for that event has the same `traceId` (`docker compose logs backend | grep <traceId>`).
  4. An authenticated `GET /api/events` and a status `PATCH` as ADMIN each appear as `backend` traces; the 401/403
     path is not required.
  5. No trace in Jaeger is for `/actuator/health` or `/actuator/prometheus`.
  6. With `docker compose stop jaeger`, `producer` and `backend` `/actuator/health` stay `UP`, events keep being
     ingested (event count still rises), and log output does not flood with repeated exporter errors. Report what
     the exporter logs actually look like; if it is noisy, tune it in this step or record it as a limitation.
  Record what was observed, not assumed.

- [x] **3. Documentation and config.**
  README: Jaeger URL in Quick start and the service table (Jaeger row: `http://localhost:16686`, `jaeger:16686`,
  OTLP `jaeger:4318`), free-ports list, `JAEGER_UI_PORT`/`JAEGER_OTLP_PORT` in the env table and `.env.example`,
  a tracing subsection under Observability (what is traced, how to find one event's trace, `traceId` in logs, 100%
  sampling, what is not traced), Jaeger added to the architecture Mermaid diagram (apps -> Jaeger), and Known
  limitations (in-memory traces are lost when Jaeger restarts; not behind the login; browser not instrumented;
  DLT publish not traced). Add the Jaeger URL to AGENTS.md Commands next to Kafka UI. Do not describe anything
  step 2 did not observe.
  **Done when:** every link/URL/port in the changed README sections matches `docker-compose.yml`, the Mermaid
  diagram renders (checked in a Mermaid preview or `mmdc` if available; otherwise say it was not rendered), and
  `git diff --stat` touches no file outside the areas below.

- [x] **4. Final gate.**
  `mvn -B verify` (root, Docker running) and `docker compose build` pass; the frontend is untouched so its suite
  is not required. Re-run the step 2 trace check once on a fresh `docker compose down -v` then `up -d --build --wait`
  to prove a clean start.
  **Done when:** both commands pass and the trace check repeats. No `Verify` command exists in the repo yet, so
  these are the Commands-section gates for the Java modules.

## Files / areas

- `producer/pom.xml`, `backend/pom.xml`; `producer/src/main/resources/application.yml`,
  `backend/src/main/resources/application.yml`
- `TracingConfig` (an `ObservationPredicate` skipping `/actuator` requests) and its test in each module; no property exists for it. Backend `application.yml` also sets `management.observations.enable.spring.security: false`, because skipping the actuator server span otherwise leaves Spring Security's filter-chain spans as traces of their own
- `backend/src/test/java/com/railops/backend/` (new tracing test; `EndToEndIntegrationTest` and any other
  `@AutoConfigureObservability` user gets OTLP export off)
- `docker-compose.yml`, `.env.example`
- `README.md`, `AGENTS.md`
- Not touched: `frontend/`, Dockerfiles, `KafkaConsumerConfig` DLT template, database migrations, Redis scripts,
  `SecurityConfig` (the `/actuator/health` and `/actuator/prometheus` permit rules stay as they are).

## Data / contracts

- **No API, event JSON, or database change.** The Kafka value and key are unchanged; only record headers gain
  `traceparent` (W3C Trace Context; `tracestate` optional). Consumers that ignore headers, including the
  existing deserializers, are unaffected.
- **Service names** in Jaeger are `spring.application.name`: `producer` and `backend` (both already set).
- **Log fields:** `traceId` and `spanId` (Micrometer Tracing MDC keys) beside the existing `eventId`.
- **Export:** OTLP over HTTP/protobuf to `.../v1/traces`, not gRPC. Config key
  `management.otlp.tracing.endpoint`; compose sets it as `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces`
  on both apps. Confirm the exact key against the docs in step 1.
- **Ports:** Jaeger UI 16686, OTLP/HTTP 4318, both `127.0.0.1` only; env overrides `JAEGER_UI_PORT`,
  `JAEGER_OTLP_PORT`. OTLP gRPC (4317) is not published.
- **Data sensitivity:** spans carry topic, partition, HTTP method and path, status. Boot does not record request
  headers or bodies, so the JWT and event payloads are not in traces; step 2 spot-checks a trace's tags for this.

## Testing

- Backend integration test for consumer-side propagation and `traceId` in logs (step 1); it is the regression
  guard for the Kafka observation setting.
- Producer-side propagation is proven by the live stack check in step 2, not by a unit test: a fake broker for
  `KafkaTemplate` would be more machinery than it is worth. No live or Jaeger result is claimed before it is run.
- No frontend or browser tests; nothing in the browser changes.
- Commands: `mvn -B -pl producer -am verify`, `mvn -B -pl backend -am verify` (Docker running),
  `docker compose build`.

## Notes for the AI

- Read current docs for Micrometer Tracing, Spring Kafka observations, and the Jaeger v2 image with Context7
  before writing config or pinning the image tag; do not rely on memory for property names or the tag.
- Prefer properties over code. Add a bean only for the actuator exclusion if no property exists.
- The backend's `application.yml` has one `management` block and the producer's has its own; extend those, do not
  add a second top-level key.
- Every Jaeger-related detail that cannot be confirmed from the docs or a run stays out of the README.
- `AGENTS.md` and `CLAUDE.md` must not gain AI attribution; commit and PR text follows the project rule.
- Dockerfiles copy every module pom; no module is added here, so they stay unchanged.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":12622,"specSha256":"701ea10f7abea5012fe233bdc0adb1ec2cd0a3622a697bd39ec17a9c9d927fbd","branch":"refs/heads/feature/distributed-tracing","head":"11e94354ee15cb4add633ffa703d27becb5b9e1c","baseRef":"refs/heads/master","baseCommit":"11e94354ee15cb4add633ffa703d27becb5b9e1c","sourceTree":"cce546987a118d4dd6929c01eabc0ae575a40c75","absentOptional":[]} -->
