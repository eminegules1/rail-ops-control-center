# Feature: Observability

**From build-plan:** feature 15
**Build attempt:** 1
**Status:** verified
**Branch:** feature/observability

## Goal

The backend and producer emit structured, machine-parseable JSON logs (with
`eventId` attached to every log line produced while handling a specific event),
and the backend exposes Prometheus counters for the three ingestion outcomes
(processed, invalid, dead-lettered) alongside the existing Actuator health
endpoint, so an operator can see event-flow health without reading raw text logs.

## In scope

- **Structured JSON logs (backend and producer).** Replace Spring Boot's default
  console pattern with `logstash-logback-encoder`'s `LogstashEncoder` via a
  `logback-spring.xml` in each module, so every log line is one JSON object on
  stdout (`timestamp`, `level`, `logger`, `message`, `thread`, MDC fields).
  Matches the already-declared coding standard: "Structured JSON logs with
  `eventId` in the MDC; never log secrets."
- **`eventId` in MDC (backend).** Around each unit of work that processes one
  event, put `eventId` in the MDC before work starts and remove it in a
  `finally` block so it never leaks onto an unrelated log line on a reused
  thread:
  - `IncidentEventListener.onEvent` — covers every log line
    `EventIngestionService.ingest` emits for that record (`Duplicate event ...
    skipped`, `Live state not updated for new event ...`, `Stored event ...`).
  - `IncidentStatusService.changeStatus` — covers its existing Redis-fallback
    warn logs, which already take `eventId` as a parameter.
  - The dead-letter/retry warn logs in `KafkaConsumerConfig` (`Sent event at
    ...`, `Failed to process event at ...`) — best-effort: when the failed
    record's deserialized value is available (an `IncidentEventMessage` with a
    non-null `eventId`), put it in MDC around that log line too. A record that
    failed deserialization has no `eventId` to attach; the line is still valid
    JSON, just without that field.
- **Prometheus counters for the ingestion funnel (backend only).** One
  Micrometer `Counter` family, name `ingestion.events`, tagged `outcome`, with
  exactly three values used by this feature:
  - `outcome=processed` — incremented once per Kafka record that completes
    `EventIngestionService.ingest` without throwing, whether newly stored or a
    duplicate skip (both are the pipeline working correctly).
  - `outcome=invalid` — incremented once per record rejected because the
    payload itself is broken: undeserializable JSON, or `InvalidEventException`
    (null payload or a Bean Validation failure). These never touch Postgres or
    Redis.
  - `outcome=dlt` — incremented once per record actually published to
    `incident-events.DLT`, in the `DeadLetterPublishingRecoverer` callback.
    This includes every `invalid` record (they go straight to the DLT) plus
    records whose retries were exhausted for a transient reason (for example
    Postgres down) — those were never counted as `processed` or `invalid`.
  - `processed` and `invalid` are mutually exclusive per record (a record is
    exactly one or the other); `dlt` is a separate, overlapping count of what
    actually reached the dead-letter topic.
- **Prometheus scrape endpoint (backend and producer).** Add
  `micrometer-registry-prometheus` to both `pom.xml` files and expose
  `/actuator/prometheus` alongside the existing `/actuator/health`
  (`management.endpoints.web.exposure.include: health,prometheus` in both
  `application.yml` files). No custom counters are added to the producer; it
  gets Micrometer's built-in JVM/process/HTTP metrics for free once the
  registry and endpoint are wired up.

## Out of scope

- No new Actuator endpoints beyond `health` and `prometheus` (no `metrics`,
  `env`, etc. — not requested and widens the exposed surface beyond what this
  feature needs).
- No Grafana/Prometheus server, dashboards, scrape config, or alerting — the
  brief only asks for the metrics endpoint to exist; running a collector is
  infrastructure the assignment doesn't ask for.
- No log aggregation stack (ELK, Loki) — JSON-on-stdout is the deliverable;
  shipping logs somewhere is out of scope.
- No MDC/eventId wiring on the producer — its logs are aggregate summaries
  (seed-burst counts, auto-send failures), not per-event, so there is no
  natural per-record scope to attach `eventId` to.
- No new counters for dashboard reads, WebSocket pushes, or the reconciler —
  the build-plan line names processed/invalid/DLT specifically.

## Build loop

Per `blueprint/config.json`: `workflow.stepReview` is `feature`, so the steps
below are implemented together and reviewed once as a single packet;
`workflow.checkpointCommits` is disabled, so no commit happens between steps.
Each step must still leave `mvn -B -pl backend -am verify` (and, for step 1,
`mvn -B -pl producer -am verify`) green before moving to the next.

## Build steps

1. [x] **JSON logging and the Prometheus endpoint, both modules.** Add
   `logstash-logback-encoder` and `micrometer-registry-prometheus` to
   `backend/pom.xml` and `producer/pom.xml`. Add a `logback-spring.xml` under
   each module's `src/main/resources` using `LogstashEncoder` for the console
   appender (replacing Boot's default pattern layout). Add
   `management.endpoints.web.exposure.include: health,prometheus` to both
   `application.yml` files (replacing the current `health`-only value). No
   application logic changes.
   **Done when:** both modules build; `mvn -B -pl backend -am verify` and
   `mvn -B -pl producer -am verify` stay green; running either app locally
   prints one-JSON-object-per-line to stdout instead of the default pattern;
   `GET /actuator/prometheus` returns Prometheus text-format `200` on both.

2. [x] **Ingestion outcome counters.** Inject `MeterRegistry` into
   `EventIngestionService` and increment `ingestion.events{outcome=processed}`
   at the point `ingest()` returns normally (both `STORED` and `DUPLICATE`).
   Inject `MeterRegistry` into `KafkaConsumerConfig` and increment
   `ingestion.events{outcome=invalid}` when the recovered exception's cause
   chain contains `InvalidEventException` or a deserialization failure (reuse
   the existing cause-chain walk in `reason()` as a guide), and increment
   `ingestion.events{outcome=dlt}` unconditionally in the
   `DeadLetterPublishingRecoverer` callback, alongside the existing "Sent event
   ... to {}" log line.
   **Done when:** `EventIngestionServiceTest` asserts the `processed` counter
   increments on store and on duplicate-skip (using a real
   `SimpleMeterRegistry`, not a mock); `KafkaConsumerConfigTest` /
   `DeadLetterRecoveryTest` assert `invalid` and `dlt` both increment for a
   validation/deserialization failure; `EventRetryIntegrationTest` asserts
   `dlt` increments (and `invalid` does not) once retries are exhausted for a
   transient failure; `mvn -B -pl backend -am verify` is green.

3. [x] **`eventId` in MDC around per-event processing.** Wrap
   `IncidentEventListener.onEvent`'s body and
   `IncidentStatusService.changeStatus`'s body in `MDC.put("eventId", ...)` /
   `finally { MDC.remove("eventId"); }`. In `KafkaConsumerConfig`, set the same
   MDC key around the DLT-publish and retry-listener log lines when the
   record's value is an `IncidentEventMessage` with a non-null `eventId`.
   **Done when:** a focused test on `IncidentEventListener` (or
   `EventIngestionIntegrationTest`) asserts `MDC.get("eventId")` is set during
   ingestion and `null` immediately after the call returns, both on success and
   when `ingest` throws; `mvn -B -pl backend -am verify` is green.

## Files / areas

- `backend/pom.xml`, `producer/pom.xml` — new dependencies
- `backend/src/main/resources/application.yml`,
  `producer/src/main/resources/application.yml` — Actuator exposure
- `backend/src/main/resources/logback-spring.xml`,
  `producer/src/main/resources/logback-spring.xml` — new files
- `backend/src/main/java/com/railops/backend/EventIngestionService.java` —
  `processed` counter, MDC is set by the caller (`IncidentEventListener`)
- `backend/src/main/java/com/railops/backend/IncidentEventListener.java` —
  MDC around `onEvent`
- `backend/src/main/java/com/railops/backend/IncidentStatusService.java` — MDC
  around `changeStatus`
- `backend/src/main/java/com/railops/backend/KafkaConsumerConfig.java` —
  `invalid`/`dlt` counters, MDC around DLT/retry log lines

## Data / contracts

- Micrometer counter `ingestion.events` (Prometheus name
  `ingestion_events_total`), tag `outcome` ∈ `processed | invalid | dlt`, as
  described in **In scope** above. This is a new observability contract other
  tooling could scrape; keep the name and tag values exactly as specified since
  a later feature (16, end-to-end coverage; 19, delivery docs) may reference
  them.
- No changes to the `events` table, Kafka payload, Redis keys, or any REST
  endpoint.

## Testing

- `EventIngestionServiceTest` — extend to assert the `processed` counter using
  a real `SimpleMeterRegistry` injected in `setUp()`.
- `KafkaConsumerConfigTest`, `DeadLetterRecoveryTest` — extend to assert
  `invalid` and `dlt` counters for a payload-defect record.
- `EventRetryIntegrationTest` — extend to assert `dlt` increments (and
  `invalid` does not) once retries are exhausted for a transient (non-payload)
  failure.
- A focused MDC test on the listener path (new or added to
  `EventIngestionIntegrationTest`) asserting `eventId` is present in MDC during
  processing and cleared after, on both the success and thrown-exception paths.
- No frontend changes, so no Vitest coverage for this feature.
- Manual/build evidence for step 1 (JSON log format, `/actuator/prometheus`
  reachability) since it is config/wiring with no branching logic to unit test.

## Notes for the AI

- `EventIngestionServiceTest` currently constructs `EventIngestionService` with
  six positional constructor args (see
  `backend/src/test/java/com/railops/backend/EventIngestionServiceTest.java:40`);
  adding `MeterRegistry` changes that constructor signature, so update every
  call site, not just production code.
- Use `io.micrometer.core.instrument.simple.SimpleMeterRegistry` in tests, not
  a Mockito mock — asserting real counter values is simpler and less brittle
  than verifying `increment()` calls.
- `KafkaConsumerConfig.errorHandler(...)` is a `static` factory used directly
  by `KafkaConsumerConfigTest` (see the class's existing tests) precisely so it
  can be tested without a Spring context; keep it static and thread the
  `MeterRegistry` through as a parameter rather than making it an instance
  field that only the `@Bean` method can reach.
- `logback-spring.xml` (not `logback.xml`) is required for Spring's
  `<springProfile>`/property-placeholder support to be available, matching
  Spring Boot convention even though this feature doesn't use profiles yet.
- Keep the existing `logging.level.org.hibernate...SqlExceptionHelper: off`
  line in `backend/application.yml` — it suppresses row-value logging on a
  Postgres rejection and is unrelated to this feature's JSON-format change.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":11187,"specSha256":"c01585bd465423ed16a9e09207ec6d7cf8c4c5da79dacc80fe006632c1948b2e","branch":"refs/heads/feature/observability","head":"e646d9cdcd7e25d6f04ac32b6ddd1f5a0d4d2a59","baseRef":"refs/heads/master","baseCommit":"e646d9cdcd7e25d6f04ac32b6ddd1f5a0d4d2a59","sourceTree":"d04144ac436c53230bfca381d5ef70135fbc480d","absentOptional":[]} -->
