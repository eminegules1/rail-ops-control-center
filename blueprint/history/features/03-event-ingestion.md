# Feature: Event ingestion

**From build-plan:** feature 3
**Build attempt:** 1
**Branch:** feature/event-ingestion
**Status:** Verified

## Goal

Add the `backend/` Spring Boot module. It consumes `incident-events` in consumer
group `incident-processor`, validates each payload, logs and skips invalid ones,
and stores valid events idempotently in PostgreSQL through a Flyway schema. It
also sets up the backend test stack (JUnit 5, Mockito, Testcontainers) and ships
a Dockerfile and compose service, so `docker compose up -d --build --wait` runs
producer -> Kafka -> backend -> Postgres end to end.

## In scope

- New Maven module `backend` (package `com.railops.backend`) registered in the
  root `pom.xml`.
- Flyway migration `V1__create_events.sql` and the JPA entity for `events`.
- Kafka consumer with manual ack: validate, then idempotent insert
  (`INSERT ... ON CONFLICT (event_id) DO NOTHING`), then ack.
- Invalid messages (undeserializable JSON, unknown enum values, failed Bean
  Validation, or data Postgres rejects) are logged at WARN and skipped with
  their offset committed. Transient failures such as Postgres being down are
  retried and never skipped.
- The backend declares the `incident-events` topic idempotently (same name,
  3 partitions, 24h retention as the producer).
- Backend test setup: unit tests, a Testcontainers Postgres repository test,
  and a Testcontainers Kafka + Postgres ingestion test.
- `backend/Dockerfile`, `.dockerignore` entry, compose `backend` service
  (:8080) with a healthcheck, `.env.example`, README section, and the
  AGENTS.md Commands entry for the backend test command.

## Out of scope

- Redis live state and Lua `apply-event` (feature 4). The ingestion contract's
  step 2 slots in between insert and ack later.
- REST endpoints, Swagger, ProblemDetail advice (features 5-7). The only HTTP
  surface is `/actuator/health`.
- Exponential backoff, retry limits, the DLT topic, and the producer's
  invalid-message ratio (feature 11). This feature logs and skips.
- JSON logs, MDC `eventId`, Prometheus metrics (feature 15).
- CI and a `Verify` command (feature 18 / `/ci`).
- A shared model module between producer and backend. Each module keeps its
  own `Severity` and `EventStatus` enums. That is proportional for two small enums,
  and the JSON contract is what is shared.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet for the whole feature. `workflow.checkpointCommits` is `disabled`, so
there are no per-step commits. `/complete` creates the feature commit. The
backend test command (see Testing) is a gate for every logic-bearing step.

## Build steps

- [x] **1. Backend module skeleton and payload validation.**
  - Root `pom.xml`: add `<module>backend</module>` after `producer`.
  - `backend/pom.xml` (parent `com.railops:rail-ops`, `finalName` `backend`,
    `spring-boot-maven-plugin`) with these dependencies, versions managed by
    the Boot parent:
    - `spring-boot-starter-web`, `spring-boot-starter-actuator`,
      `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`
    - `spring-kafka`, `flyway-core`, `flyway-database-postgresql`,
      `org.postgresql:postgresql` (runtime)
    - Test: `spring-boot-starter-test`, `spring-boot-testcontainers`,
      `org.testcontainers:junit-jupiter`, `org.testcontainers:kafka`,
      `org.testcontainers:postgresql`, `org.awaitility:awaitility`
  - Classes: `BackendApplication`, `Severity`, `EventStatus`, and
    `IncidentEventMessage`. The message is a record with the seven payload
    fields and constraints:
    - `eventId`: `@NotBlank`, `@Size(max = 64)`
    - `source`: `@NotNull`, `@Pattern(regexp = "ATS|CBTC|SCADA|TMS|PIS")`
      (case-sensitive; `@Pattern` matches the whole value)
    - `service`: `@NotBlank`, `@Size(max = 64)`
    - `message`: `@NotBlank`
    - `severity`, `status`, `timestamp`: `@NotNull`
  - `backend/src/main/resources/application.yml` with the defaults in
    Data / contracts. Env placeholders fall back to host-run values
    (`localhost`).
  - Unit tests (`IncidentEventMessageValidationTest`) use a plain
    `jakarta.validation.Validator`. They cover a valid producer-shaped payload,
    each blank or missing field, and each over-length field. Include a
    non-`EVT-` id such as `EVT-10001` and an arbitrary non-blank string: both
    are accepted. Every one of the five sources is accepted. `XYZ`, `cbtc`,
    `ATS ` (trailing space), and blank are rejected.
  - **Done when:** the backend test command passes those tests with 0
    failures and 0 skipped.

- [x] **2. Schema, entity, repository, and ingestion service.**
  - `backend/src/main/resources/db/migration/V1__create_events.sql` creates the
    `events` table and indexes described in Data / contracts.
  - `IncidentEvent` JPA entity maps every column. Enums use
    `@Enumerated(EnumType.STRING)`, the id uses `IDENTITY`, and `version` uses
    `@Version`. With `ddl-auto: validate`, a mismatch fails startup.
  - `IncidentEventRepository extends JpaRepository<IncidentEvent, Long>` adds
    `int insertIfAbsent(...)`. It is a `@Modifying` native
    `INSERT ... ON CONFLICT (event_id) DO NOTHING` that returns the affected
    row count (1 = stored, 0 = duplicate).
  - `EventIngestionService.ingest(IncidentEventMessage)` validates with the
    injected `Validator`. On violations it throws `InvalidEventException` and
    names the field paths only, never the values. Otherwise it calls
    `insertIfAbsent` (transactional on the repository) and returns `STORED` or `DUPLICATE`.
    It logs stored events at DEBUG and duplicates at INFO
    (`Duplicate event {eventId} skipped`).
  - Tests:
    - `EventIngestionServiceTest` (Mockito): valid -> insert + `STORED`;
      insert returns 0 -> `DUPLICATE`; invalid -> `InvalidEventException` and
      no repository call.
    - `IncidentEventRepositoryTest` (`@DataJpaTest`,
      `@AutoConfigureTestDatabase(replace = NONE)`, Testcontainers
      `postgres:16.15-alpine` via `@ServiceConnection`):
      - Flyway applies and the entity validates.
      - The first insert returns 1 and a second insert with the same
        `eventId` returns 0. It leaves one row with the first payload's
        values, `receivedAt`/`updatedAt` set, and `version` 0.
      - An over-length `service` and an unknown `source` passed straight to
        the repository each raise `DataIntegrityViolationException`. This
        proves the database check and the translation the error handler
        relies on in step 3.
  - **Done when:** the backend test command passes all tests with Docker
    Desktop running.

- [x] **3. Kafka consumer, error handling, and topic declaration.**
  - `IngestionProperties` (`@ConfigurationProperties("ingestion")`,
    `@Validated`) holds the topic name, partitions, and retention.
  - `KafkaTopicConfig` declares the same `NewTopic` as the producer.
  - `KafkaConsumerConfig`:
    - Sets the Boot listener factory's ack mode to `MANUAL_IMMEDIATE`.
    - Registers a `DefaultErrorHandler` whose recoverer logs WARN
      `Skipping invalid event at {topic}-{partition}@{offset}: {reason}` (no
      payload dump).
    - Uses `FixedBackOff(2000, UNLIMITED_ATTEMPTS)` for everything retryable.
    - Calls `addNotRetryableExceptions(InvalidEventException.class,
      DataIntegrityViolationException.class)` on top of the built-in
      non-retryable `DeserializationException`.
    - Sets `setCommitRecovered(true)`, so a skipped record's offset is
      committed even when it is the last one on its partition.
  - Consumer properties go in `application.yml`:
    - Group `incident-processor` and `auto-offset-reset: earliest`, so events
      sent before the backend starts (seed burst) are consumed.
    - `ErrorHandlingDeserializer` delegating to `JsonDeserializer`, with
      `spring.json.value.default.type` = `IncidentEventMessage` and
      `spring.json.use.type.headers: false` (step 6 moves this into
      `IncidentEventDeserializer`). The producer sends plain JSON
      strings without type headers, and so will hand-published messages.
    - Listener concurrency 3.
  - `IncidentEventListener`: `@KafkaListener(topics = "${ingestion.topic.name}")`
    receives `IncidentEventMessage` + `Acknowledgment`, calls
    `ingest`, then `ack.acknowledge()`. Exceptions propagate to the error
    handler, so there is never an ack before the insert commits.
  - `EventIngestionIntegrationTest` (`@SpringBootTest`, Testcontainers
    `apache/kafka:4.2.1` + `postgres:16.15-alpine`, both `@ServiceConnection`,
    `KafkaTemplate<String, String>` sending raw strings with key = service) sends, in order on
    one key:
    1. A valid event.
    2. The same event again.
    3. Malformed JSON (`{not json`).
    4. A payload with `"severity":"critical"` (wrong case, unknown enum).
    5. A payload with a blank `eventId`.
    6. A payload with a 100-character `service`.
    7. A payload with `"source":"XYZ"`.
    8. A second valid event.

    Awaitility then asserts exactly 2 rows (events 1 and 8). The group's
    committed offset for that partition equals the end offset (via `AdminClient`),
    which proves the invalid records were skipped and committed, not retried
    forever.
  - **Done when:** the backend test command passes, including the
    integration test.

- [x] **4. Container, compose service, docs, and live verification.**
  - `backend/Dockerfile` mirrors `producer/Dockerfile` (multi-stage,
    `-pl backend -am`, non-root user `backend`, `EXPOSE 8080`).
  - `.dockerignore`: add `!backend/` and `backend/target/`.
  - `docker-compose.yml` `backend` service:
    - Builds from root context `backend/Dockerfile`.
    - Env: `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092`,
      `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-incidents}`,
      and `SPRING_DATASOURCE_USERNAME`/`PASSWORD` from the same `POSTGRES_*`
      defaults as the postgres service.
    - Port `127.0.0.1:${BACKEND_PORT:-8080}:8080`.
    - `depends_on` kafka and postgres `service_healthy`.
    - Healthcheck: `curl -fs http://localhost:8080/actuator/health`, same
      timings as the producer.
  - `.env.example`: add `BACKEND_PORT=8080`.
  - README: add an "Event ingestion (backend)" section covering:
    - The flow and the at-least-once + idempotent insert contract.
    - What counts as invalid, including an unknown `source`, and that it is
      logged and skipped until the DLT lands.
    - How to inspect rows (`docker compose exec postgres psql ...`).
    - How to hand-publish an invalid message, the test command, and the Docker
      requirement for Testcontainers.
    - Update the repository layout list.
  - AGENTS.md Commands: add the backend test command and the backend URL.
    Replace "the planned `backend/` module adds its commands here".
  - Live verification (record observed output, do not claim otherwise):
    - `docker compose up -d --build --wait` reports all services healthy.
    - `select count(*), count(distinct event_id) from events;` shows equal
      counts of at least 200 that grow over time. Backend logs show
      `Duplicate event ... skipped` lines.
    - Publish `{not json` and a blank-`eventId` payload with
      `kafka-console-producer.sh` inside the kafka container (Git Bash needs
      `MSYS_NO_PATHCONV=1`). Each logs one WARN skip line and ingestion
      continues.
    - Kafka UI shows group `incident-processor` with lag 0.
    - `docker compose stop postgres` for about 20 seconds, then
      `docker compose start postgres`. The backend logs retries, resumes, and
      the row count catches up with no skipped valid events.
  - **Done when:** all live checks above are observed and the backend and
    producer test commands pass.

- [x] **5. Review repairs (independent review of checkpoint `245acae`).**
  The automatic reviewer reported these findings but could not write the
  ledger. A new fresh-session review records them against the new checkpoint.
  - F-01 (P2): Hibernate's `SqlExceptionHelper` logs the full failing row at
    ERROR when Postgres rejects an event. Turn that logger off. Add a
    retry listener that logs WARN
    `Failed to process event at {topic}-{partition}@{offset} (attempt {n}): {reason}`,
    so outages stay visible without row data. Spring Kafka calls it for the
    single failed attempt of a non-retryable record too, so the wording stays
    neutral and the `Skipping invalid event` line follows it.
  - F-02 (P3): the skip reason for unreadable JSON names only the field path
    (`invalid value for severity`) or `not valid event JSON`, never the value.
  - F-03 (P3): unit-test `KafkaConsumerConfig.reason` (every branch and the
    200-character cap). The integration test ends on invalid records, including
    a `null` (tombstone) value, so the committed-offset assertion proves
    `setCommitRecovered(true)`. A `null` payload is rejected by the listener as
    `empty payload` and skipped, never retried.
  - Found during the live check: `ingest` was `@Transactional`, so it took a
    database connection before validating. During an outage an invalid event
    was retried until Postgres returned, and only then skipped. The transaction
    now sits on `insertIfAbsent`, so validation runs first.
  - **Done when:** the backend and producer test commands pass, and a
    hand-published invalid message on the live stack logs a skip line without
    its value and no Hibernate row dump, including while Postgres is down.

- [x] **6. Review repairs (independent review of checkpoint `649f2db`).**
  - F-01 (P2): the default Spring Kafka Jackson mapper accepted enum ordinals
    (`"severity":3` or `"3"` became `CRITICAL`) and epoch-number timestamps.
    The value deserializer delegate in `application.yml` is now
    `IncidentEventDeserializer`, a `JsonDeserializer<IncidentEventMessage>`
    (type headers ignored) whose mapper fails on numbers for enums and reads
    `timestamp` only from a string. JSR-310 ignores leniency and coercion
    settings for `Instant`, so a small string-only `Instant` deserializer
    delegates to the standard one. The `spring.json.*` properties leave
    `application.yml`.
  - F-02 (P2): `timestamp` must fall in `[2000-01-01T00:00:00Z,
    10000-01-01T00:00:00Z)`. Earlier values were stored by the JDBC driver as
    `-infinity`. The check is an `@AssertTrue` on the message, so it reports as
    the field path `timestampInRange`.
  - Tests: deserializer cases in `KafkaConsumerConfigTest` (producer payload
    accepted, unknown field ignored, numeric and string-number enums rejected,
    numeric timestamp rejected, each naming only the field) and range cases in
    `IncidentEventMessageValidationTest` (both bounds). The integration test
    adds a `"severity":"3"` payload to prove the configured delegate is used.
  - **Done when:** the backend and producer test commands pass.

- [x] **7. Review repair (independent review of checkpoint `ed3a770`).**
  - F-03 (P2): JSR-310's `Instant` deserializer read digit strings such as
    `"1790000000"` as epoch seconds. The string-only `Instant` deserializer
    now parses the text itself with `DateTimeFormatter.ISO_INSTANT` (which
    accepts `Z` or an offset) and reports anything else as an invalid
    `timestamp` value.
  - Tests: `KafkaConsumerConfigTest` rejects `"1790000000"`,
    `"1790000000.5"`, and a timestamp without an offset, each naming only the
    field.
  - **Done when:** the backend and producer test commands pass.

## Files / areas

- `pom.xml` (module list)
- `backend/pom.xml`, `backend/Dockerfile`
- `backend/src/main/java/com/railops/backend/`: `BackendApplication`,
  `Severity`, `EventStatus`, `IncidentEventMessage`, `IncidentEvent`,
  `IncidentEventRepository`, `EventIngestionService`, `IngestionResult`
  (enum `STORED`/`DUPLICATE`), `InvalidEventException`,
  `IngestionProperties`, `KafkaTopicConfig`, `KafkaConsumerConfig`,
  `IncidentEventDeserializer`, `IncidentEventListener`
- `backend/src/main/resources/application.yml`,
  `backend/src/main/resources/db/migration/V1__create_events.sql`
- `backend/src/test/java/com/railops/backend/` (tests listed above)
- `.dockerignore`, `docker-compose.yml`, `.env.example`, `README.md`,
  `AGENTS.md` (Commands)

## Data / contracts

**Kafka input (unchanged, produced by feature 2):** topic `incident-events`,
key = `service`, value is UTF-8 JSON with exactly the producer's fields:

```json
{"eventId":"EVT-…","source":"CBTC","service":"signal-service","severity":"CRITICAL","message":"…","status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}
```

- Enum values are case-sensitive (`INFO|WARNING|MAJOR|CRITICAL`,
  `OPEN|ACKNOWLEDGED|RESOLVED`). `timestamp` is an ISO-8601 instant with an
  offset or `Z`, from 2000-01-01 up to (not including) year 10000; a number is
  rejected. Enums given as numbers (ordinals) are rejected. Unknown extra JSON
  fields are ignored.
- `eventId` is any non-blank string of at most 64 characters. `source` must be
  exactly one of the five control systems `ATS`, `CBTC`, `SCADA`, `TMS`, `PIS`
  (case-sensitive, like the enums). It stays a `String` in Java: no new enum is
  added, because the producer and the planned API treat it as a plain string.
  `service` is free-form (non-blank, at most 64 characters), because the
  service list is dynamic (Redis `services` set in feature 4).

**Invalid:** any of the following. Each one is logged at WARN with
topic/partition/offset and a reason, not retried, and its offset is committed.
- Deserialization failure (malformed JSON, unknown enum, unparsable timestamp).
- Bean Validation failure.
- A `DataIntegrityViolationException` from Postgres.

**Retryable:** everything else, such as Postgres being unreachable. It is
retried every 2s without limit, blocking only that partition, and the offset is
not committed. Feature 11 replaces this with exponential backoff plus the DLT.

**Postgres `events` (Flyway V1):**

| Column | Type | Notes |
|---|---|---|
| `id` | `bigint generated by default as identity` | primary key (internal) |
| `event_id` | `varchar(64) not null` | `unique` constraint |
| `source` | `varchar(16) not null` | `check (source in ('ATS','CBTC','SCADA','TMS','PIS'))` |
| `service` | `varchar(64) not null` | |
| `severity` | `varchar(16) not null` | `check (severity in ('INFO','WARNING','MAJOR','CRITICAL'))` |
| `message` | `text not null` | |
| `status` | `varchar(16) not null` | `check (status in ('OPEN','ACKNOWLEDGED','RESOLVED'))` |
| `timestamp` | `timestamptz not null` | event creation time |
| `received_at` | `timestamptz not null default now()` | |
| `updated_at` | `timestamptz not null default now()` | |
| `version` | `bigint not null default 0` | JPA `@Version` |

Indexes: `severity`, `status`, `source`, `service`, `timestamp`. The unique
constraint already indexes `event_id`. Enums are stored as checked varchar,
which keeps `@Enumerated(STRING)` mapping simple. On a duplicate `eventId`,
the first stored row wins unchanged: `status` changes only through the feature 6
endpoint.

**Backend `application.yml` defaults:**
- `spring.datasource.url`: `jdbc:postgresql://localhost:5432/incidents`,
  username `rail_ops`, password `rail_ops_local_only` (overridden by
  `SPRING_DATASOURCE_*` env).
- `spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.open-in-view: false`.
- `spring.kafka.bootstrap-servers: localhost:9092`.
- Consumer: group `incident-processor`, earliest, the deserializers above.
- `spring.kafka.listener.concurrency: 3`.
- `ingestion.topic`: `incident-events`, 3 partitions, retention `24h`.
- `management.endpoints.web.exposure.include: health`.

Compose sets the container values. `BACKEND_PORT` (default 8080) is the only
new `.env` variable.

## Testing

- Command (new, add to AGENTS.md): `mvn -B -pl backend -am verify` from the
  repository root. It needs JDK 21, Maven 3.9, and Docker Desktop running for
  Testcontainers. The dockerized-Maven fallback used for the producer cannot
  run these tests, because the container has no Docker access.
- Unit: `IncidentEventMessageValidationTest`, `EventIngestionServiceTest`.
- Testcontainers: `IncidentEventRepositoryTest` (Postgres) and
  `EventIngestionIntegrationTest` (Kafka + Postgres). All class names end in
  `Test`, so Surefire runs them under `verify` with no Failsafe setup.
- The producer command `mvn -B -pl producer -am verify` must still pass
  (the root pom changed).
- Live compose behavior (step 4) is manual evidence and is reported as
  observed.

## Notes for the AI

- Keep the listener -> service -> repository layering and constructor
  injection. Validate in the service with the injected `Validator`, not via
  `@Valid` on the listener, so the rule is unit-testable and throws the one
  exception type the error handler classifies.
- `commitRecovered` only takes effect with `AckMode.MANUAL_IMMEDIATE` (Spring
  Kafka docs). That is why this mode was chosen over `MANUAL`. The per-record
  sync commit is fine at demo throughput.
- Classifying `DataIntegrityViolationException` as non-retryable prevents a
  payload that passes validation but that Postgres rejects (a NUL byte in
  text, an out-of-range timestamp) from blocking a partition forever. Connection
  failures do not map to that type, so they still retry. If the step 2 test
  shows Hibernate surfaces a different type, adjust the classification to the
  observed type and note it.
- Use the same image tags in Testcontainers as in compose (`apache/kafka:4.2.1`,
  `postgres:16.15-alpine`) and `org.testcontainers.kafka.KafkaContainer` (not
  the Confluent one). If the Boot-managed Testcontainers version cannot reach
  Docker Desktop, stop and report before overriding versions.
- The consumer never produces to Kafka in this feature. Test messages go through
  a `KafkaTemplate<String, String>`, which Boot autoconfigures with String
  serializers.
- Do not log full payloads or validation values, only field paths and the
  `eventId` when known.
- `spring.datasource.hikari.connection-timeout: 5000` (ms; the default is 30000).
  With the default, each attempt during a Postgres outage blocked a listener
  thread for 30s, and `/actuator/health` took 24-30s to answer. With 5000,
  health answers DOWN in about 5s, and ingestion resumed with lag 0 within 5s
  of Postgres returning (observed). Feature 11's backoff does not change this
  pool timeout.
- A host port clash on 8080 is a local machine issue: override `BACKEND_PORT`
  in the gitignored `.env`. Keep the documented 8080 default.
- Leave a clear seam for feature 4: `ingest` returns the outcome, and the
  Redis apply step will run after a `STORED`/`DUPLICATE` result and before the
  ack.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":22731,"specSha256":"7942bd80002f1585f188f203c1b3ac46d370be9d3e3bf4a237428d8393ee2fe3","branch":"refs/heads/feature/event-ingestion","head":"d74f65cc3a8d2e9d8687c8e5d1928bf33b19a40c","baseRef":"refs/heads/master","baseCommit":"64aea9973e04b605b075ecdb733761aa1bf32fcf","sourceTree":"97544b4a21fb08d0ac7329eeddc609d8495c0b07","absentOptional":[]} -->

## Findings

### 3/F-01 [P2] closed - Numeric enum ordinals and epoch-number timestamps are accepted as valid events

**File:** backend/src/main/resources/application.yml:28
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security)
**Why it matters:** The contract in the spec and README says `severity` and `status` must be one of the case-sensitive names, and `timestamp` must be an ISO-8601 instant. The `JsonDeserializer` configured here uses Spring Kafka's default Jackson mapper, which coerces other forms. A probe against the built classes showed `"severity":3` and `"severity":"3"` both deserialize to `CRITICAL`, `"status":1` to `ACKNOWLEDGED`, and `"timestamp":1790000000` to an instant. Such payloads pass Bean Validation and are stored instead of being skipped as invalid. Each coerced value is well formed, so no data is lost, but the documented boundary is not the enforced one, and features 11 and 13 build on that classification.
**Suggested fix:** Give the listener factory a `JsonDeserializer` built from an `ObjectMapper` with `DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS` enabled and integer-to-`Instant` and string-number-to-enum coercion disabled (wrapped in `ErrorHandlingDeserializer`, with the same default type and `useTypeHeaders(false)`). Then add these cases to `KafkaConsumerConfigTest` or the validation tests. The alternative is to document the coercion as accepted in the spec and README. Current requirement lost if the coercion is rejected: none.
**Resolution:** Fixed in spec step 6. `IncidentEventDeserializer` (the `ErrorHandlingDeserializer` delegate in `application.yml`) enables `FAIL_ON_NUMBERS_FOR_ENUMS` and reads `Instant` only from a JSON string. `KafkaConsumerConfigTest` covers ordinal, string-number, and epoch-number rejection, and the integration test skips a `"severity":"3"` record. Awaiting re-review.
Closed 2026-09-26 by /audit independent (fresh subagent, target `ed3a770`). Re-examined `IncidentEventDeserializer` and `application.yml`: the configured delegate rejects JSON-number and string-number enums and JSON-number timestamps (unit tests plus the integration test's `"severity":"3"` record, all passing under `mvn -B verify`). The repair introduced no new defect. The related residual gap for timestamps given as numeric *strings* predates this repair and is tracked separately as F-03.

### 3/F-02 [P2] closed - Timestamps before Postgres's range are stored as `-infinity` instead of rejected

**File:** backend/src/main/java/com/railops/backend/IncidentEventMessage.java:17
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security)
**Why it matters:** `timestamp` has only `@NotNull`, and Jackson accepts any `Instant`. A rolled-back JDBC probe against the local Postgres showed the driver writes an `OffsetDateTime` of year -5000 as `-infinity` with no error. So a payload with such a timestamp would be stored as a valid event with an infinite time, not skipped. The later dashboard, sorting, and filters (features 5-7) would then receive it. A far-future year (+300000) is rejected with SQLState 22008, which is classified as a data integrity violation and skipped as intended. The probe went through pgjdbc `setObject(OffsetDateTime)` directly. The Hibernate binding path for `Instant` was not exercised separately.
**Suggested fix:** Add a bound on `timestamp` in `IncidentEventMessage`, such as `@PastOrPresent` with clock-skew tolerance, or a custom range check (for example year 2000 to 9999). Add it to `IncidentEventMessageValidationTest`, and state the range in the contract. Current requirement lost: none.
**Resolution:** Fixed in spec step 6. `IncidentEventMessage.isTimestampInRange()` (`@AssertTrue`) limits `timestamp` to 2000-01-01 through the end of year 9999. `IncidentEventMessageValidationTest` covers both bounds and year -5000. Awaiting re-review.
Closed 2026-09-26 by /audit independent (fresh subagent, target `ed3a770`). Re-examined `IncidentEventMessage.isTimestampInRange()`: the package-private `@AssertTrue` getter is picked up by Hibernate Validator (path `timestampInRange`), runs in `EventIngestionService.ingest` before any database work, and the bounds sit inside Postgres's `timestamptz` range. `IncidentEventMessageValidationTest` bound cases pass under `mvn -B verify`. The repair introduced no new defect.

### 3/F-03 [P2] closed - Timestamps given as numeric strings are accepted as epoch seconds

**File:** backend/src/main/java/com/railops/backend/IncidentEventDeserializer.java:44
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security, performance, tests)
**Why it matters:** The contract (spec Data / contracts, README "never an epoch number") says `timestamp` is an ISO-8601 instant string. `IsoStringInstantDeserializer` only rejects non-string tokens, then delegates to `InstantDeserializer.INSTANT`, which treats an all-digit string as epoch seconds (and `"n.m"` as decimal seconds). A probe against the built classes showed `"timestamp":"1790000000"` deserializes to `2026-09-21T14:13:20Z` and `"1790000000.5"` to `2026-09-21T14:13:20.500Z`; both pass the range check and would be stored as valid events. `"-1"` is caught only by the range check. This is the string-number variant of F-01's timestamp case; F-01's string-number test covered enums only. No data is lost, but the enforced boundary still differs from the documented one.
**Suggested fix:** In `IsoStringInstantDeserializer`, parse the string text directly with `DateTimeFormatter.ISO_INSTANT` (or `ISO_OFFSET_DATE_TIME` then `toInstant()`), mapping `DateTimeParseException` to `context.handleWeirdStringValue(Instant.class, text, ...)` so the reason still names only the field, instead of delegating to `InstantDeserializer.INSTANT`. Add `"timestamp":"1790000000"` to `KafkaConsumerConfigTest.rejectsNumericTimestamp`. Alternatively, document numeric-string epochs as accepted. Current requirement lost: none.
**Resolution:** Fixed in spec step 7. `IsoStringInstantDeserializer` parses the text with `DateTimeFormatter.ISO_INSTANT` and reports a failure as an invalid `timestamp` value. `KafkaConsumerConfigTest` rejects `"1790000000"`, `"1790000000.5"`, and a timestamp without an offset. Awaiting re-review.
Closed 2026-09-26 by /audit independent (fresh subagent, target `d74f65c`). Re-examined `IncidentEventDeserializer.IsoStringInstantDeserializer`: it no longer delegates to JSR-310. Non-string tokens go to `handleUnexpectedToken`, and string text is parsed only by `DateTimeFormatter.ISO_INSTANT`, with `DateTimeException` mapped to `handleWeirdStringValue`, so the skip reason names only `timestamp`. A JSON `null` bypasses the deserializer and is caught by `@NotNull`. `KafkaConsumerConfigTest.rejectsTimestampsThatAreNotIsoInstants` (`1790000000`, `"1790000000"`, `"1790000000.5"`, no-offset) and the offset-accepting producer-payload case pass under `mvn -B verify`. The repair introduced no new defect.

## Independent review

# Independent Review

**Status:** passed
**Target commit:** d74f65cc3a8d2e9d8687c8e5d1928bf33b19a40c
**Base commit:** 64aea9973e04b605b075ecdb733761aa1bf32fcf
**Base ref:** master
**Spec hash:** 7942bd80002f1585f188f203c1b3ac46d370be9d3e3bf4a237428d8393ee2fe3
**Prepared by:** claude
**Builder model:** claude-opus-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-26T19:28:25Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-26T19:31:26Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Commands

- `git rev-parse HEAD`, `git merge-base master HEAD`, `sha256sum blueprint/context/current-feature.md`, `git status --porcelain --untracked-files=all`: pass (HEAD, merge base, and spec hash match the request; only `review.md` and `findings.md` differ)
- `mvn -B verify` (repository root): pass (producer 19 tests, backend 50 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS)
- Reviewer probe on a `git archive` export of the target outside the repository, `mvn -B -q -pl backend -am -Dtest=NulProbeTest verify`: pass (NUL byte in `message` and in `eventId` both raise `DataIntegrityViolationException` from `insertIfAbsent`)

## Evidence

- Reviewed the full `64aea99..d74f65c` delta: root and backend `pom.xml`, both Dockerfiles, `.dockerignore`, `.env.example`, `docker-compose.yml`, all 14 `backend/src/main` classes, `application.yml`, `V1__create_events.sql`, all 5 backend test classes, README and AGENTS.md changes, against the active spec (tracked, hash verified).
- `EventIngestionIntegrationTest` log from the `mvn -B verify` run: each invalid record logs one `Failed to process ... (attempt 1)` and one `Skipping invalid event ...` line with a field-path-only reason; no payload values or Hibernate row dumps appear, and the committed offset reaches the end offset after the trailing tombstone.
- Validation runs before any database work (`EventIngestionService.ingest`; transaction only on `insertIfAbsent`); ack follows the insert; DB rejection, deserialization, and validation failures are non-retryable, and everything else retries every 2s.
- Security: only `/actuator/health` is exposed, ports bind to `127.0.0.1`, the native insert uses bound parameters, and the demo database credentials are the documented local-only compose defaults.
- No skipped, disabled, or focused tests in `backend/src/test` or `producer/src/test`.

## Findings

- F-03 [P2] closed (re-reviewed repair in `IncidentEventDeserializer`)
- F-04 [P3] open (database-rejected record path not exercised through the error handler)

## Remaining risk

- Live compose behavior (step 4 and step 5 live checks, including the Postgres outage) was not re-run by the reviewer; it rests on the builder's recorded observations.
- Each skipped record costs about 0.5s of partition time (seek and re-fetch, visible in the integration log). This is negligible at the current 2s producer interval but worth measuring when feature 11 adds an invalid-message ratio.
- Frontend has no test command, and no `Verify` command exists yet; neither is touched by this delta.
