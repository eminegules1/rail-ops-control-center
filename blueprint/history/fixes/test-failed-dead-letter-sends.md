# Fix: Test failed dead-letter sends

**Type:** Fix
**Status:** verified
**Branch:** fix/test-failed-dead-letter-sends

## The problem

Feature 11 promises that a record is never silently dropped. When Kafka does not
confirm the dead-letter send, the record's offset must not be committed, and the
record must be attempted again. The README says the same.

Two things make this work today:

- `DeadLetterPublishingRecoverer` throws when the send fails.
- The recoverer lambda in `KafkaConsumerConfig.kafkaErrorHandler` lets that
  exception propagate, so `DefaultErrorHandler` seeks back instead of
  committing (`setCommitRecovered(true)` commits only after recovery
  succeeds).

No automated test covers this path. The feature 11 independent review listed
it as a remaining risk that was confirmed only by reading the code. A later
change, such as wrapping `publisher.accept(...)` in a try/catch that only logs,
would drop records and still pass every test.

An integration test cannot stage this failure reliably:

- Stopping the broker also stops the consumer.
- The test broker auto-creates topics, and `KafkaAdmin` adds back missing
  partitions.

## The fix

Test the handler as the app builds it, with the dead-letter sender replaced by
a test double.

- **Test seam in `KafkaConsumerConfig`.** Move the handler construction out of
  the `kafkaErrorHandler` bean method into a package-private static method,
  for example `errorHandler(IngestionProperties, KafkaOperations<?, ?>)`. The
  bean keeps building the real dead-letter producer factory and template and
  passes the template in. Runtime behaviour, bean graph, logging and F-04's
  shutdown close all stay unchanged.
- **Unit test `KafkaConsumerConfigTest`** (or a new sibling test class) drives
  the real handler through `handleRemaining(...)`. It uses:
  - a mocked `Consumer`
  - a mocked `MessageListenerContainer` whose container properties use
    `MANUAL_IMMEDIATE`, as in `application.yml`
  - one record that failed with a non-retryable `InvalidEventException`, so
    recovery runs on the first attempt
- **No production behaviour change.** No new dependency, configuration or
  retry behaviour.

## Build steps

- [x] **1. Seam and failed-send test.** Extract the static builder method and
  keep the bean delegating to it. Add these tests:
  - **Failed send:** the test double's send fails (a failed future, or a
    `MockProducer`-backed template whose send throws; use whichever
    spring-kafka 3.3 supports without blocking for the send timeout).
    Assertions:
    - the handler does not treat the record as handled (it throws or
      reports failure)
    - `consumer.seek(partition, offset)` is called for that record
    - `consumer.commitSync(...)` is never called
  - **Successful send (control):** assert the record is published to
    `(incident-events.DLT, same partition)`, and that `commitSync` is called
    with `offset + 1` for that partition.

  _Done when:_
  - `mvn -B -pl backend -am verify` passes.
  - The failed-send test fails when the recoverer lambda is temporarily
    changed to catch and log the exception (mutation check, then restored).

## Verify

- `mvn -B -pl backend -am verify` passes.
- Mutation check: wrap `publisher.accept(record, exception)` in `KafkaConsumerConfig`
  in a try/catch that only logs. The failed-send test fails. Restore the code.
- There is no user-visible change, so no live check is needed.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":3434,"specSha256":"16fc38efef171f74e09f763550be362535be47aab3bcf6776d22b79594a578da","branch":"refs/heads/fix/test-failed-dead-letter-sends","head":"10c0776449e281c207a016e7702593f15bfcd230","baseRef":"refs/heads/master","baseCommit":"10c0776449e281c207a016e7702593f15bfcd230","sourceTree":"e30142517b94d0ed5a018c8085ffa9926cc745a3","absentOptional":[]} -->
