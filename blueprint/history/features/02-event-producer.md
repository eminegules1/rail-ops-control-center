# Feature: Event producer

**From build-plan:** feature 2
**Build attempt:** 1
**Branch:** feature/event-producer
**Status:** Verified

## Goal

Add a small Spring Boot `producer` app that publishes realistic, schema-consistent
rail incident events to the Kafka topic `incident-events`. It runs in three modes:
a ~200-event seed burst at startup, an automatic send at a configurable interval,
and a manual `POST /produce?count=N` burst. A configurable share of sends re-send
an earlier event unchanged, which later demonstrates idempotent ingestion. The app
ships with its Dockerfile and compose service, so `docker compose up --build`
starts it next to the feature 1 infrastructure.

## In scope

- Root Maven aggregator `pom.xml` (Java 21, Spring Boot 3 parent) listing the
  `producer` module only. Feature 3 adds `backend`.
- `producer/` Spring Boot app:
  - Event payload record with the locked field names `eventId`, `source`,
    `service`, `severity`, `message`, `status`, `timestamp`
  - Weighted random generator using a fixed service catalog
  - Duplicate re-send from a bounded in-memory buffer
  - Topic declaration from config
  - Kafka publisher (key = `service`)
  - Startup seed burst
  - Scheduled auto send
  - Manual `POST /produce` endpoint
- `@ConfigurationProperties` settings, validated at startup, bound from env vars
  `PRODUCER_INTERVAL_MS` and `PRODUCER_DUPLICATE_RATIO`, plus seed count and topic
  settings
- Unit tests (JUnit 5, Mockito, `@WebMvcTest`, all through `spring-boot-starter-test`)
  for the generator and the endpoint
- `producer/Dockerfile` (multi-stage Maven build to a JRE image, non-root),
  root `.dockerignore`, and a `producer` compose service with a healthcheck and
  `depends_on: kafka: service_healthy`
- README "Event producer" section, `.env.example` producer entries, and AGENTS.md
  Commands for the producer build and tests

## Out of scope

- `PRODUCER_INVALID_RATIO` and malformed messages, plus the `incident-events.DLT`
  topic. Feature 11 adds these.
- The backend, its consumer, Postgres schema, and Redis. Features 3 and 4 add these.
- JSON logs, MDC, and Prometheus metrics (feature 15). The producer uses default
  Spring Boot console logging.
- Swagger/OpenAPI for the producer, auth, and a Maven wrapper
- CI (feature 18) and the Verify command (`/ci`)

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet for the whole feature. `workflow.checkpointCommits` is `disabled`, so no
per-step commits. `/complete` creates the feature commit.

## Build steps

- [x] **1. Maven skeleton, event model, and generator (pure logic + tests).**
  - Create root `pom.xml`. Use packaging `pom`, parent
    `spring-boot-starter-parent` with the current Spring Boot 3.5.x patch (confirm
    the latest 3.x release before pinning), `java.version` 21, and module
    `producer`.
  - Create `producer/pom.xml` with these dependencies: `spring-boot-starter-web`,
    `spring-boot-starter-validation`, `spring-boot-starter-actuator`,
    `spring-kafka`, and `spring-boot-starter-test` (test scope).
  - Add package `com.railops.producer` with these classes:
    - `ProducerApplication`
    - `ProducerProperties` (record, `@ConfigurationProperties("producer")`,
      `@Validated`)
    - `Severity` and `EventStatus` enums
    - `IncidentEvent` record
    - `ServiceCatalog`
    - `EventGenerator`
  - `EventGenerator` takes an injected `RandomGenerator` and `Clock` (beans in
    config) so tests are deterministic.
  - Configure `producer/src/main/resources/application.yml` with the defaults
    listed in Data / contracts.
  - Tests cover:
    - `eventId` format
    - Every catalog service maps to its source
    - Severity and status weights, checked with a seeded generator over many
      draws within tolerance
    - Timestamps are UTC and truncated to millis
    - Seed timestamps fall inside the last 60 minutes and are sent in ascending
      order
    - Duplicate ratio 0 never duplicates and ratio 1 always re-sends a buffered
      event once one exists
    - An empty buffer falls back to a fresh event
    - The buffer stays bounded at 100
    - JSON serialization produces exactly the seven fields with enum names and
      an ISO-8601 `Z` timestamp
  - **Done when:** the producer test command (see Testing) runs those tests
    with 0 failures and no skipped tests.

- [x] **2. Topic, Kafka publishing, seed burst, auto mode, and container.**
  - `KafkaTopicConfig` declares a `NewTopic` for `producer.topic.name`, using the
    configured partitions and `retention.ms`. Broker auto-create is disabled, so
    the producer must declare the topic. Feature 3's backend declares the same
    topic idempotently.
  - `EventPublisher` uses `KafkaTemplate<String, String>` with `StringSerializer`
    for the key and value. It serializes the payload with the Spring Boot
    `ObjectMapper`, uses `service` as the key, and adds no type headers.
  - Bounded client timeouts: `max.block.ms=5000`, `request.timeout.ms=5000`, and
    `delivery.timeout.ms=10000`. A Kafka outage then fails quickly instead of
    hanging for 60+ seconds.
  - `ProducerRunner` does two things:
    - On `ApplicationReadyEvent`, it sends the seed burst and logs one info line
      with the sent and duplicate counts.
    - It runs `@Scheduled(fixedDelayString = "${producer.interval-ms}")` for the
      auto send.
    - A send failure in either mode logs one warning and never stops the
      scheduler or the app.
  - `producer/Dockerfile` has two stages:
    - Build: `maven:3.9-eclipse-temurin-21`. Copy the root and module poms first
      so dependency resolution is cached, then run
      `mvn -B -pl producer -am -DskipTests package`.
    - Runtime: `eclipse-temurin:21-jre`, running as a non-root user.
    - Confirm both tags exist and that the runtime image has an HTTP client for
      the healthcheck. If it has none, use a JDK-free alternative and record the
      choice.
  - Root `.dockerignore` is an allowlist containing only `pom.xml` and
    `producer/`, and excludes `producer/target/`.
  - The compose `producer` service:
    - `build: { context: ., dockerfile: producer/Dockerfile }`
    - `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092`
    - `PRODUCER_INTERVAL_MS: ${PRODUCER_INTERVAL_MS:-2000}` and
      `PRODUCER_DUPLICATE_RATIO: ${PRODUCER_DUPLICATE_RATIO:-0.05}`
    - Port `127.0.0.1:${PRODUCER_PORT:-8082}:8080`
    - Healthcheck on `/actuator/health`
    - `depends_on: kafka: condition: service_healthy`
  - **Done when:**
    - `docker compose up -d --build --wait` reports all five services healthy.
    - `kafka-topics.sh --describe` shows `incident-events` with 3 partitions and
      `retention.ms=86400000`.
    - Topic offsets show at least 200 messages shortly after start and keep
      growing at about one message per interval.
    - A console-consumer sample (with `print.key=true`) shows key == `service`,
      valid JSON with exactly the seven fields, and no `__TypeId__` header.
    - Producer logs show the seed summary line and no errors.

- [x] **3. Manual trigger `POST /produce?count=N`.**
  - `ProduceController` delegates to the publisher and waits for every send
    future. It returns `200` with the `ProduceResult` record.
  - Validation and errors:
    - `count` must be between 1 and 1000. A value out of range, or one that is
      not an integer, returns `400` `ProblemDetail`.
    - Enable `spring.mvc.problemdetails.enabled=true` so Spring's built-in
      errors are ProblemDetail as well.
    - A Kafka send failure or timeout returns `503` `ProblemDetail` with detail
      "Kafka is unavailable; no confirmation for all events" plus the counts
      confirmed so far. This goes through a single `@RestControllerAdvice`.
  - `@WebMvcTest` tests with a mocked publisher cover:
    - Omitted `count` → 1
    - `count=50` → 200 with the body shape
    - `count` of 0, 1001, and `abc` → 400 `application/problem+json`
    - Publisher failure → 503 `application/problem+json`
  - **Done when:**
    - The tests pass.
    - Against the running stack, `curl -X POST "localhost:8082/produce?count=50"`
      returns `{"sent":50,"duplicates":d}` and topic offsets grow by 50.
    - `count=0` returns a 400 problem body.
    - With Kafka stopped (`docker compose stop kafka`), the call returns 503
      within about 15 seconds and the producer keeps running. It resumes auto
      sends after `docker compose start kafka`.

- [x] **4. Duplicate demo evidence and documentation.**
  - Run the stack once with `PRODUCER_DUPLICATE_RATIO=0.5` and confirm from a
    topic sample that repeated `eventId`s carry byte-identical values. Then
    restore the default.
  - Update the README:
    - Add a "Event producer" section covering what it sends, the three modes, a
      `curl` example, the env vars and defaults, and viewing messages in Kafka UI.
    - Mark `producer/` as present in "Repository layout".
  - Add `PRODUCER_INTERVAL_MS`, `PRODUCER_DUPLICATE_RATIO`, and `PRODUCER_PORT`
    to `.env.example` with their defaults.
  - Update the AGENTS.md Commands section with the `producer/` module, the
    producer test command (local and containerized fallback), and `docker compose up -d --build --wait`.
    Keep the "no Verify command" note.
  - **Done when:**
    - The duplicate sample shows repeated ids.
    - README, `.env.example`, and AGENTS.md match the running behavior.
    - `docker compose config` is valid.
    - The frontend `npm run build` and `npm run lint` still pass.

## Files / areas

- `pom.xml` (new, root aggregator)
- `producer/pom.xml`, `producer/Dockerfile`
- `producer/src/main/java/com/railops/producer/` with these files:
  - `ProducerApplication`
  - `ProducerProperties`
  - `Severity`
  - `EventStatus`
  - `IncidentEvent`
  - `ServiceCatalog`
  - `EventGenerator`
  - `EventPublisher`
  - `KafkaTopicConfig`
  - `ProducerRunner`
  - `ProduceController`
  - `ProduceResult`
  - `ApiExceptionHandler`
  - A small config class for the `Clock` and `RandomGenerator` beans
- `producer/src/main/resources/application.yml`
- `producer/src/test/java/com/railops/producer/` (`EventGeneratorTest`,
  `IncidentEventJsonTest`, `ProduceControllerTest`)
- `.dockerignore` (new), `docker-compose.yml`, `.env.example`, `README.md`,
  `AGENTS.md`

## Data / contracts

**Kafka message** (topic `incident-events`, locked field names shared with features 3-7):

- Key: the event's `service` string, UTF-8, which gives per-service partition
  ordering.
- Value: UTF-8 JSON object with exactly these fields and no type headers:

```json
{"eventId":"EVT-3f1c2a9e-8b7d-4e21-9c55-0a6b1d2e3f40","source":"CBTC","service":"signal-service","severity":"CRITICAL","message":"Signal SG-14 failed to clear","status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}
```

- `eventId`: `EVT-` followed by lowercase `UUID.randomUUID()`, generated once per
  event.
- `severity`: one of `INFO`, `WARNING`, `MAJOR`, `CRITICAL`, with weights
  50/30/15/5.
- `status`: one of `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, with weights 70/20/10.
- `timestamp`: an `Instant` in UTC, truncated to milliseconds and serialized as
  ISO-8601 with `Z`.
  - Auto and manual events use the injected clock's "now".
  - Seed events are spread randomly across the previous 60 minutes and sent in
    ascending timestamp order, so the later events-over-time chart is not a
    single spike.
- **Duplicate re-send:** each send has a `duplicateRatio` chance of re-sending
  one of the last 100 sent events, picked at random. The re-send is the same
  object with the same JSON bytes, the same key, and a new Kafka offset. With an
  empty buffer, a fresh event is sent instead. The ratio applies to all three
  modes.

**Service catalog** (fixed; `source` is `ATS`, `CBTC`, `SCADA`, `TMS`, or `PIS`):

| service | source |
|---|---|
| `route-service` | ATS |
| `train-tracking` | ATS |
| `signal-service` | CBTC |
| `power-supply` | SCADA |
| `timetable-service` | TMS |
| `passenger-info` | PIS |

Each service has 2-4 short, plain operational message templates, such as "Train
T-212 position update delayed". Templates hold no user data.

**Configuration** (`producer.*`, validated at startup; an invalid value stops
startup with a binding error):

| property | env var | default | rule |
|---|---|---|---|
| `interval-ms` | `PRODUCER_INTERVAL_MS` | 2000 | ≥ 100 |
| `duplicate-ratio` | `PRODUCER_DUPLICATE_RATIO` | 0.05 | 0.0-1.0 |
| `seed-count` | `PRODUCER_SEED_COUNT` | 200 | 0-1000 |
| `topic.name` | - | `incident-events` | not blank |
| `topic.partitions` | - | 3 | ≥ 1 |
| `topic.retention` | - | `24h` (Duration) | positive |

`spring.kafka.bootstrap-servers` defaults to `localhost:9092` for IDE runs.
Compose overrides it to `kafka:29092`.

**HTTP** (producer, container port 8080, host `127.0.0.1:${PRODUCER_PORT:-8082}`,
unauthenticated local demo tool):

- `POST /produce?count=N`
  - `count` is an optional integer from 1 to 1000 and defaults to 1.
  - On success, returns `200 {"sent": N, "duplicates": D}`, where D is the
    number of the N messages that were re-sends. The call returns only after the
    broker confirms all N sends.
  - Returns `400 application/problem+json` for an invalid `count`.
  - Returns `503 application/problem+json` when any send fails or times out.
- `GET /actuator/health` returns `{"status":"UP"}`. This is the only exposed
  actuator endpoint.

## Testing

No test command is configured in AGENTS.md yet. The host has JDK 21.0.12 and
Maven 3.9.16. This feature records the local command in AGENTS.md as the
producer test command, run from the repository root:

```bash
mvn -B -pl producer -am verify
```

For machines without a JDK, AGENTS.md also lists a containerized fallback:
`docker run --rm -v "${PWD}:/workspace" -w /workspace -v rail-ops-m2:/root/.m2
maven:3.9-eclipse-temurin-21 mvn -B -pl producer -am verify`.

The host locale is `tr_TR`. Every case conversion or enum parse must use
`Locale.ROOT`, and there must be a test that the generator and JSON output are
unaffected by the default locale. In Turkish, `"info".toUpperCase()` gives
`"İNFO"`.

- Unit: `EventGeneratorTest` and `IncidentEventJsonTest` use a seeded
  `RandomGenerator` and a fixed `Clock`. `ProduceControllerTest` uses
  `@WebMvcTest` with a mocked publisher.
- Integration evidence is manual against the running compose stack: topic
  describe, offsets, console-consumer samples, curl, and the Kafka stop/start
  check. Kafka Testcontainers tests are not added here. Feature 16 owns
  end-to-end Testcontainers coverage.
- No browser tests. This feature has no UI.

## Notes for the AI

- Keep it small and explainable in an interview. There is no service layer
  beyond generator → publisher → runner/controller.
- Use constructor injection only. The DTOs (`IncidentEvent`, `ProduceResult`)
  are records.
- `EventGenerator.next()` is shared by the scheduler thread and HTTP threads.
  Guard the buffer and the random draws with one lock, or make the method
  `synchronized`.
- `StringSerializer` with our own JSON is deliberate. It keeps the payload free
  of Spring type headers, so any consumer and hand-published messages (such as
  `EVT-10001`) look identical, and feature 11 can send deliberately malformed
  strings through the same template.
- Restarting the producer seeds another ~200 events. This is expected for a demo
  and should be noted in the README.
- Comments only where the why is non-obvious: the topic declared in two apps, and
  the bounded timeouts.
- Never commit the assignment brief or copy its text into the README.

## Implementation notes

- Spring Boot pinned to 3.5.16, the latest 3.x release on Maven Central.
- `max.in.flight.requests.per.connection: 1` was added to the producer client.
  The seed burst hits a just-created topic. With several batches in flight, a
  first batch rejected with `NOT_LEADER_OR_FOLLOWER` left sequence gaps
  (`OUT_OF_ORDER_SEQUENCE_NUMBER`), and 6 of 200 seed events were lost. With
  this setting, two fresh-topic runs confirmed 200/200 with no warnings.
- The `RandomGenerator` bean is `java.util.Random`. The Temurin JRE image omits
  the `jdk.random` module that `RandomGenerator.getDefault()` needs, and the app
  failed at startup.
- `PublishException` (requested and confirmed counts) carries Kafka failures
  from `EventPublisher` to the runner logs and the 503 handler. The `Clock` and
  `RandomGenerator` beans live in `GeneratorConfig`.
- Env vars are bound explicitly in `application.yml`
  (`${PRODUCER_INTERVAL_MS:2000}` and so on), so the names in `.env.example`
  map directly.

## Verification evidence

- `mvn -B -pl producer -am verify`: 19 tests, 0 failures, 0 skipped
  (EventGeneratorTest 10, IncidentEventJsonTest 2, ProduceControllerTest 7).
- `docker compose up -d --build --wait`: all five services healthy.
- Topic: `incident-events`, 3 partitions, `retention.ms=86400000`.
- Seed: "Seed burst sent 200 events". Offsets grew about one message per 2s.
- Sample messages: key == service, exactly seven JSON fields, `NO_HEADERS`.
- `POST /produce?count=50` returned `{"sent":50,"duplicates":2}`, and offsets
  grew by exactly 50 (auto interval set to 10 min for the measurement).
- Other `/produce` cases:
  - `count` omitted returned 200 with `sent: 1`.
  - `count=0` and `count=abc` returned 400 `ProblemDetail`.
  - With Kafka stopped, the call returned 503 `ProblemDetail`
    (`requested: 5`, `confirmed: 0`) after 10s, and the producer stayed healthy.
- Recovery: with Kafka down, auto sends logged one warning each. After Kafka
  restarted, 6 events arrived in 10s.
- Duplicates at ratio 0.5: 200 messages held 101 distinct ids and 101 distinct
  payloads, so every repeat is byte-identical.
- `docker compose config -q`, frontend `npm run build`, and `npm run lint` all
  passed.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":17846,"specSha256":"0321960609eb690382d576a84205dda782cfad121a601554c72d9f1e1e888fd1","branch":"refs/heads/feature/event-producer","head":"45e7d191e4abe1f560bd85bd3ec9e0b66e44a0fd","baseRef":"refs/heads/master","baseCommit":"45e7d191e4abe1f560bd85bd3ec9e0b66e44a0fd","sourceTree":"ff0691b2e5f9f9ee8bc6b408f61809b7fca3f755","absentOptional":[]} -->
