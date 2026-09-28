# Feature: Retry and dead-letter handling

**From build-plan:** feature 11
**Build attempt:** 1
**Branch:** feature/retry-and-dead-letter-handling
**Status:** verified

## Goal

Make the backend consumer route failures to `incident-events.DLT` rather than
skipping them or retrying forever. Invalid messages (unreadable, failed
validation, rejected by Postgres) go straight to the DLT. Every other failure is
retried with exponential backoff and goes to the DLT once retries run out. The
producer gets `PRODUCER_INVALID_RATIO`, so a demo shows this without anyone
publishing by hand.

## In scope

- Backend: declare `incident-events.DLT` in code. It has the same partition
  count as `incident-events` (the recoverer keeps the source partition). Its
  name and retention come from config.
- Backend: replace the `FixedBackOff(2s, unlimited)` + log-and-skip recoverer in
  `KafkaConsumerConfig` with a `DefaultErrorHandler` that uses
  `ExponentialBackOffWithMaxRetries` and a `DeadLetterPublishingRecoverer`.
- Backend: keep the existing non-retryable set, so these go straight to the DLT
  after one attempt: `DeserializationException` (Spring default),
  `InvalidEventException` (validation, empty or tombstone payload), and
  `DataIntegrityViolationException` (Postgres rejects the data).
- Backend: add a Kafka producer config (the backend has none today) so the
  recoverer can publish:
  - A record that could not be deserialized goes to the DLT as its **original
    bytes**. The recoverer takes them from the `ErrorHandlingDeserializer`
    header.
  - A record that deserialized but failed later goes to the DLT as the
    `IncidentEventMessage` JSON (field names unchanged, ISO-8601 `timestamp`).
  - The key is kept as a String and may be null (tombstones).
- Backend: log one line per dead-lettered record: topic-partition@offset, the
  DLT name, and the existing value-free `reason(...)`. The per-attempt retry log
  stays.
- Backend: if publishing to the DLT fails (for example, Kafka is unavailable),
  the offset is not committed and the record is attempted again. It is never
  silently lost.
- Producer: add `producer.invalid-ratio` (`PRODUCER_INVALID_RATIO`, 0.0–1.0,
  validated like `duplicate-ratio`). That share of messages are intentionally
  broken payloads that the backend classifies as invalid.
- Producer: add an `invalid` count to `ProduceResult`
  (`{"sent":N,"duplicates":D,"invalid":I}`). The change is additive.
- Wire `PRODUCER_INVALID_RATIO` through `docker-compose.yml` and
  `.env.example`.
- README: update the ingestion section with the DLT, the retry schedule, how to
  inspect the DLT (Kafka UI and console consumer), the invalid ratio, and the
  new known limitations.

## Out of scope

- Prometheus counters for processed, invalid and DLT events, and JSON logs
  (feature 15).
- Redis circuit breaker, the reconcile flag and the Postgres fallback
  (feature 14).
- A DLT consumer, a replay tool or an API/UI for dead-lettered messages.
- End-to-end DLT coverage beyond this feature's tests, and coverage reporting
  (feature 16).
- Non-blocking retry topics (`@RetryableTopic`). The plan specifies blocking
  `DefaultErrorHandler` retries, which keep per-service ordering.

## Build loop

`workflow.stepReview` is `feature` and `checkpointCommits` is `disabled`.
Implement all steps and run each step's gate, then present one review packet at
the end. Make no commits. `/complete` creates the feature commit.

## Build steps

1. [x] **DLT topic and retry settings.** Extend `IngestionProperties` with:
   - `deadLetter` (`name` `@NotBlank`, `retention` `@NotNull Duration`)
   - `retry` (`initialInterval` and `maxInterval` as `@NotNull Duration`,
     `multiplier` `@DecimalMin("1.0")`, `maxRetries` `@Min(1)`; spring-kafka's
     `ExponentialBackOffWithMaxRetries(0)` does not stop, so zero retries is
     rejected)

   Set the defaults in `application.yml`: `incident-events.DLT`, retention
   `7d`, `1s` initial interval, ×2, `30s` max interval, `8` retries (about
   2 minutes of retries in total). Declare the DLT `NewTopic` in
   `KafkaTopicConfig`, with partitions taken from `ingestion.topic.partitions`.

   **Done when:** backend tests pass and `KafkaTopicConfig` declares both
   topics. Before this step, check with Context7 that the Spring Kafka version
   Boot 3.5 manages provides `ExponentialBackOffWithMaxRetries`.

2. [x] **Dead-letter recoverer.** In `KafkaConsumerConfig`:
   - Replace `skip` with a `DeadLetterPublishingRecoverer` whose destination
     resolver sends a record to `(deadLetter.name, record.partition())`.
   - Use `ExponentialBackOffWithMaxRetries` built from `ingestion.retry`.
   - Keep `setCommitRecovered(true)`, the non-retryable classification and the
     retry listener.
   - Log each recovered record with the value-free reason.
   - Add backend producer settings to `application.yml`: String key serializer,
     and a value serializer that writes `byte[]` unchanged and
     `IncidentEventMessage` as JSON (for example `DelegatingByTypeSerializer`
     or a template map on the recoverer, whichever the docs show as the
     repository-native fit).
   - Update the class Javadoc (it currently says records are "skipped" and
     retried "until it succeeds").

   **Done when:** backend tests pass. `KafkaConsumerConfigTest` still covers
   `reason(...)` and adds tests that the back-off matches the configured values
   (attempt count and intervals, from `BackOff.start()`).

3. [x] **Integration coverage.** Update `EventIngestionIntegrationTest`:
   - Rename `storesValidEventsOnceAndSkipsInvalidOnes` to reflect DLT routing.
   - Keep all its current invalid payloads.
   - Assert that each one appears on `incident-events.DLT`, with the same key
     and partition, for example by consuming the DLT with a test consumer.
   - Assert that `{not json` arrives as its original bytes, and that the
     DLT-exception headers are present.
   - Keep the committed-offset-equals-end-offset assertion.

   Add a retry-exhaustion test, using test properties for a short back-off
   (for example 50ms and 2 retries):
   - Make ingestion of one valid `eventId` throw a transient `RuntimeException`
     (for example, a `@MockitoSpyBean` on `EventIngestionService` or
     `LiveStateUpdater`; check which the repository's Boot version supports).
   - Assert that it was attempted `maxRetries + 1` times, then landed on the
     DLT, and that its offset is committed.
   - Assert that a following valid event on the same partition is still
     stored.

   If the shared static containers make property overrides conflict, put the
   retry test in its own class.

   **Done when:** `mvn -B -pl backend -am verify` passes with both tests.

4. [x] **Producer invalid ratio.**
   - Add `invalidRatio` to `ProducerProperties` and `application.yml`
     (`${PRODUCER_INVALID_RATIO:0.02}`).
   - Before the duplicate roll, the generator (or a small collaborator using the
     same injected `RandomGenerator`) decides whether a message is invalid. An
     invalid message is a String payload chosen at random from a fixed set that
     covers both backend paths:
     - not JSON
     - an unknown `severity` name
     - a blank `service`
     - a missing `eventId`
   - Invalid messages are keyed by a real catalog service, never enter the
     duplicate buffer, and are counted in `ProduceResult.invalid`.
   - Update `ProduceControllerTest`'s exact JSON (`"invalid":0`), and add
     generator tests like the existing ratio tests: ratio 0 never, ratio 1
     always, and the ratio is roughly honoured. Assert that the invalid
     payloads are not readable as valid events.
   - Add `PRODUCER_INVALID_RATIO` to the compose producer environment and to
     `.env.example`.

   **Done when:** `mvn -B -pl producer -am verify` passes.

5. [x] **README and live check.** Update the backend ingestion section:
   - Replace "skipped … a later feature sends them to a dead-letter topic" and
     "retried every 2 seconds until it succeeds, so valid events are never
     dropped" with the DLT behaviour and the retry schedule.
   - Change the hand-published example to grep the new log line, and add the
     DLT console-consumer command and a Kafka UI pointer.
   - Add `PRODUCER_INVALID_RATIO` to the variables table.
   - Add the known limitations below.

   Then run `docker compose up -d --build --wait` and confirm that:
   - Kafka UI shows `incident-events.DLT` with 3 partitions, receiving the
     producer's invalid messages.
   - The dashboard keeps updating.
   - `POST /produce?count=100` returns an `invalid` count.

   **Done when:** the live observations are recorded in the review packet.

6. [x] **Repair F-04: close the dead-letter producer.**
   - `KafkaConsumerConfig` keeps the dead-letter `DefaultKafkaProducerFactory`
     out of the bean graph (a `ProducerFactory` bean would replace Boot's).
   - It holds the factory and destroys it when the context closes, so the
     Kafka producer shuts down with the app.

   **Done when:** `mvn -B -pl backend -am verify` passes, and F-04 is marked
   `fixed`.

7. [x] **Repair F-05: prove the retry-exhausted offset is committed.**
   - In `EventRetryIntegrationTest`, the partition ends on a retry-exhausted
     record: failing, valid, failing. A later valid ack then cannot cover the
     last commit.
   - Assert both failing records reach the DLT after `maxRetries + 1` attempts
     each, and that the valid event is stored.
   - Assert the consumer group's committed offset reaches the partition's end
     offset.

   **Done when:** `mvn -B -pl backend -am verify` passes, and F-05 is marked
   `fixed`.

## Files / areas

- `backend/src/main/java/com/railops/backend/KafkaConsumerConfig.java`,
  `KafkaTopicConfig.java`, `IngestionProperties.java`,
  `InvalidEventException.java` (Javadoc: "skips" becomes dead-letters)
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/railops/backend/KafkaConsumerConfigTest.java`,
  `EventIngestionIntegrationTest.java` (plus a new retry test class if needed)
- `producer/src/main/java/com/railops/producer/EventGenerator.java`,
  `EventPublisher.java`, `ProducerProperties.java`, `ProduceResult.java`
- `producer/src/main/resources/application.yml`
- `producer/src/test/java/com/railops/producer/EventGeneratorTest.java`,
  `ProduceControllerTest.java`
- `docker-compose.yml`, `.env.example`, `README.md`

## Data / contracts

- **DLT topic:** `incident-events.DLT`, with partitions equal to
  `incident-events` (3) and 7d retention, both from config. It is declared by
  the backend only, since KafkaAdmin creates missing topics.
- **DLT record:**
  - Key: the source key.
  - Partition: the source partition.
  - Value: the original bytes for deserialization failures, otherwise the
    `IncidentEventMessage` JSON.
  - Headers: Spring's standard `kafka_dlt-*` headers (original
    topic/partition/offset/timestamp, exception class/message/stacktrace).
- **Classification:**
  - Non-retryable, sent to the DLT after one attempt:
    `DeserializationException`, `InvalidEventException`,
    `DataIntegrityViolationException`.
  - Everything else is retryable: exponential back-off of 1s, ×2, capped at
    30s, for 8 retries, then sent to the DLT.
- **Offsets:** the offset is committed after a successful DLT publish. It is
  not committed while the publish fails.
- **`POST /produce`:** the response adds `invalid` (int, ≥ 0). `sent` still
  counts every message sent, including invalid ones.
- **Ingestion contract:** unchanged. Postgres insert, then Redis apply, then
  ack. It is idempotent, so a DLT'd event that already reached Postgres is safe
  to replay later.

## Testing

- **Backend unit:** back-off parameters and the existing `reason(...)` cases.
- **Backend integration (Testcontainers Kafka + Postgres + Redis):**
  - invalid messages go to the DLT with their key, partition, original bytes
    and headers
  - retry exhaustion goes to the DLT after `maxRetries + 1` attempts
  - offsets are committed
  - processing continues after a dead-lettered record
- **Producer unit:** invalid-ratio behaviour, invalid payloads, and the
  `ProduceResult` JSON.
- **Gates:** `mvn -B -pl backend -am verify` and
  `mvn -B -pl producer -am verify`. The frontend is untouched.
- **Live:** the compose stack check in step 5. Not a browser test; no Browser
  tests command exists.

## Notes for the AI

- **Behaviour change to call out in the README:** before this feature, a valid
  event was retried forever during an outage. Now, an outage that lasts past
  the ~2-minute retry budget moves that event to the DLT. It is preserved there
  and can be replayed idempotently, but it is not in the dashboard until then.
  This follows the plan ("invalid and retry-exhausted messages").
- **Known limitation until feature 14:** while Redis is down, an event can be
  stored in Postgres and then dead-lettered after its Redis apply exhausts
  retries. The Redis counters then miss it until the reconciler (feature 14)
  rebuilds them from Postgres. Document this. Do not add a circuit breaker
  here.
- **Blocking retries and Kafka timeouts:** blocking retries hold up only the
  affected partition. The longest single sleep (30s) is well under
  `max.poll.interval.ms` (300s default); keep it that way if the values change.
- **Log redaction:** logs must never contain payload values; reuse `reason()`.
  DLT headers may carry the exception message. That is acceptable because the
  DLT value already holds the payload, and the topic is internal to the local
  stack.
- Do not change the listener, the ingestion order or the validation rules.
- Follow `coding-standards.md` → Backend, Validation and Error Handling,
  Testing, Comments.
- Use Context7 for spring-kafka `DeadLetterPublishingRecoverer`,
  `ExponentialBackOffWithMaxRetries` and the serializer wiring before coding
  step 2.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":13915,"specSha256":"97636c6e58461699a70780e64e1834989cd1882feb2c4f909c5f04abb8aed605","branch":"refs/heads/feature/retry-and-dead-letter-handling","head":"acb5e18af58ccc036bb17ae6dac5eb345ac544f9","baseRef":"refs/heads/master","baseCommit":"d06e293e2706f2c512876f5393a2aa2f677ed262","sourceTree":"32f001427e052a56daf812f43cebb17576f444ff","absentOptional":[]} -->

## Findings

### 11/F-04 [P3] closed - Dead-letter producer factory is never closed

**File:** backend/src/main/java/com/railops/backend/KafkaConsumerConfig.java:85
**Found:** 2026-09-27 by /audit (scope: current; lens: quality)
**Why it matters:** `deadLetterTemplate` builds a `DefaultKafkaProducerFactory`
that is deliberately not a bean, so Spring never calls its `destroy()`. The
`KafkaProducer` it creates is not closed when the context shuts down, and in
the test suite each cached context keeps a producer that retries against
stopped containers. DLT sends are waited on synchronously, so no record is lost;
the cost is an unclosed client and log noise, not a correctness defect.
**Suggested fix:** Keep the factory out of the bean graph as intended, but close
it with the context, for example by holding it in the configuration class and
destroying it from a `@PreDestroy`/`DisposableBean` hook (or register it under a
non-`ProducerFactory` wrapper). No current requirement is lost.
**Resolution:** Fixed in feature 11 step 6 - `KafkaConsumerConfig` holds the dead-letter producer factory and destroys it in `DisposableBean.destroy()`; it stays out of the bean graph. Closed 2026-09-28 by independent /audit (scope: current; all lenses) at acb5e18: `KafkaConsumerConfig` implements `DisposableBean`, keeps the factory in a field (no `ProducerFactory` bean, so Boot's is not replaced) and `destroy()` closes it; the `kafkaErrorHandler` bean depends on the configuration bean and listener containers stop before singleton destruction, so the producer closes after consumption ends. No new defect introduced.

### 11/F-05 [P3] closed - Retry test does not assert the dead-lettered offset is committed

**File:** backend/src/test/java/com/railops/backend/EventRetryIntegrationTest.java:69
**Found:** 2026-09-27 by /audit (scope: current; lens: tests)
**Why it matters:** Spec step 3 asks the retry-exhaustion test to assert that
the dead-lettered record's offset is committed. The test waits for `EVT-OK` to
be stored, which happens before its ack, and then checks attempts and the DLT
record, but never reads the committed offset. Recovered-offset commits are
proven in `EventIngestionIntegrationTest` for non-retryable records, so the
retry-exhausted path is covered only indirectly.
**Suggested fix:** After the DLT assertion, await the consumer group's committed
offset for the partition reaching the end offset, reusing the
`committedOffset`/end-offset pattern from `EventIngestionIntegrationTest`.
**Resolution:** Fixed in feature 11 step 7 - `EventRetryIntegrationTest` ends the partition on a retry-exhausted record and asserts the committed offset reaches the end offset; with `setCommitRecovered(false)` it fails (committed 2, end 3). Closed 2026-09-28 by independent /audit (scope: current; all lenses) at acb5e18: the test sends failing, valid, failing on one key, awaits the group's committed offset equal to the end offset, then verifies 3 attempts per failing record and both DLT records; `mvn -B -pl backend -am verify` passed. No new defect introduced.

## Independent review

# Independent Review

**Status:** passed
**Target commit:** acb5e18af58ccc036bb17ae6dac5eb345ac544f9
**Base commit:** d06e293e2706f2c512876f5393a2aa2f677ed262
**Base ref:** master
**Spec hash:** 97636c6e58461699a70780e64e1834989cd1882feb2c4f909c5f04abb8aed605
**Prepared by:** claude
**Builder model:** claude-opus-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-27T20:50:19Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-28T09:30:32Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

## Handoff

Review the active spec and the complete `d06e293e2706f2c512876f5393a2aa2f677ed262..acb5e18af58ccc036bb17ae6dac5eb345ac544f9` delta in a fresh
session or isolated subagent without the builder conversation. Run all Audit lenses from scratch.
Run Check when required above. Do not edit product code, accept findings, or
reuse the existing findings as the review scope.

## Commands

- `mvn -B -pl producer -am verify`: pass (27 tests, 0 failures)
- `mvn -B -pl backend -am verify`: fail on first run (EventRetryIntegrationTest could not start its Testcontainers Kafka container: container exited with code 126 before the wait strategy; environmental, no test assertion ran), then pass on an unchanged rerun (192 tests, 0 failures, 0 errors)

## Evidence

- Freshness: HEAD = acb5e18af58ccc036bb17ae6dac5eb345ac544f9; merge-base(master, HEAD) = d06e293e2706f2c512876f5393a2aa2f677ed262; spec SHA-256 matches; only review.md differed from the target.
- Reviewed the full d06e293..acb5e18 delta against the spec: KafkaConsumerConfig (DeadLetterPublishingRecoverer to (DLT, source partition), ExponentialBackOffWithMaxRetries from ingestion.retry, non-retryable set unchanged, setCommitRecovered(true), value-free recovered-record log, DisposableBean closing the non-bean DLT producer factory), KafkaTopicConfig DLT NewTopic, IngestionProperties validation, application.yml defaults (1s x2 cap 30s, 8 retries, about 121s), producer invalid ratio, Defect payloads, ProduceResult.invalid, compose/.env.example wiring, README.
- EventIngestionIntegrationTest asserts all 9 invalid records on the DLT with key, partition, original-topic and exception headers, original bytes for unreadable payloads, event JSON for read-but-invalid ones, a null tombstone, and committed offset = end offset.
- EventRetryIntegrationTest asserts maxRetries + 1 attempts for each failing record, DLT delivery with the cause header, the following valid event stored, and committed offset = end offset on a partition ending with a retry-exhausted record.
- Security: DLT key/partition/value come only from the consumed record; logs reuse the value-free reason(); DLT exception headers are accepted by the spec. No secrets introduced.
- Re-reviewed F-04 and F-05 against the repaired code; both closed.

## Findings

- F-04 closed (re-reviewed)
- F-05 closed (re-reviewed)
- No new findings

## Remaining risk

- The DLT-publish-failure path (Kafka unavailable during recovery, offset not committed, record re-attempted) is not covered by an automated test; confirmed only by reading spring-kafka recoverer semantics (send result awaited, exception propagates).
- Backend integration tests depend on Testcontainers startup; one run failed to start the Kafka container (exit code 126) and passed on rerun.
- Frontend untouched; no frontend commands run. Live compose check (spec step 5) was not repeated by the reviewer; Check was not required.
