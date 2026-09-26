# Findings

> **Generated file.** The findings ledger: review findings raised by `/audit`
> against the work in progress, each with a durable ID, severity (P0-P3), and
> status. `/implement` marks repaired findings `fixed`, a later `/audit` pass
> moves them to `closed`, and `/complete` refuses to merge while any P0 or P1
> finding is `open` or `fixed`, then archives resolved findings with the work
> and resets this file.

### F-04 [P3] open - Skipping of database-rejected records is not exercised through the error handler

**File:** backend/src/main/java/com/railops/backend/KafkaConsumerConfig.java:34
**Found:** 2026-09-26 by /audit independent (scope: current; lens: quality, security, performance, tests)
**Why it matters:** The spec's invalid-message contract includes "a `DataIntegrityViolationException` from Postgres", and that path is reachable: a payload with a NUL byte in `message` or `eventId` passes Bean Validation but Postgres rejects it. A reviewer probe (outside the repository, against the target's `IncidentEventRepository` with Testcontainers Postgres) confirmed both surface as `DataIntegrityViolationException`, so current behavior is correct. But no test drives such a record through the listener and `DefaultErrorHandler`: every invalid record in `EventIngestionIntegrationTest` is stopped by deserialization or validation, `IncidentEventRepositoryTest` only proves the exception type, and `KafkaConsumerConfigTest` only covers `reason`. Removing `DataIntegrityViolationException` from `addNotRetryableExceptions` would keep every test green while one such record blocks its partition forever under the unlimited `FixedBackOff`.
**Suggested fix:** Add one record to `EventIngestionIntegrationTest` that passes validation but violates the database (for example `"message":"a\u0000b"`) before the trailing invalid records, so the existing row-count and committed-offset assertions cover it. Alternatively, unit-test that `kafkaErrorHandler()` classifies a `ListenerExecutionFailedException` wrapping `DataIntegrityViolationException` as non-retryable. Current requirement lost: none.
**Resolution:**
