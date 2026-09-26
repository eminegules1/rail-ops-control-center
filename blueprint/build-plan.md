# Build Plan

## MVP

- [ ] 1. **Event producer** - Spring Boot app publishes schema-consistent JSON events to Kafka (auto interval, manual trigger, startup seed burst), with the Kafka/Redis/Postgres/Kafka UI compose stack
- [ ] 2. **Event ingestion** - backend consumer group validates events and stores them idempotently in Postgres
- [ ] 3. **Retry and dead-letter handling** - exponential-backoff retries, non-retryable validation errors, DLT publishing, invalid-message demo ratio in producer
- [ ] 4. **Live service state in Redis** - service health hashes, severity/status/total counters, bounded recent-events list, idempotency TTL keys
- [ ] 5. **Events API** - filtered, searchable, paginated event list and event detail with ProblemDetail errors and Swagger docs
- [ ] 6. **Incident status update** - PUT status endpoint that atomically adjusts Redis counters and service health
- [ ] 7. **Dashboard summary and services API** - cached summary endpoint, services endpoint, Redis circuit breaker with Postgres fallback, startup reconciler
- [ ] 8. **Real-time push** - STOMP WebSocket broadcasting new/updated events and throttled summary updates
- [ ] 9. **Dashboard page** - KPI cards, service health grid, severity and events-over-time charts, recent events list
- [ ] 10. **Events page** - paginated table with filters, search, detail drawer and optimistic status change
- [ ] 11. **Service status page** - per-service health, last event time, latest severity, open incident count
- [ ] 12. **Live UI updates** - WebSocket cache patching, connection status chip, reconnect handling and polling fallback
- [ ] 13. **Observability** - structured JSON logs, Actuator health, Prometheus metrics for processed/invalid/DLT events
- [ ] 14. **Automated tests** - backend unit tests, Testcontainers happy-path and DLT integration tests, frontend component tests
- [ ] 15. **One-command local startup** - fully containerized backend/producer/frontend, healthchecks, env vars, clean-clone verification
- [ ] 16. **CI pipeline** - GitHub Actions building and testing backend, producer and frontend
- [ ] 17. **Delivery documentation** - README, architecture diagram, API docs, Redis/Kafka design notes, screenshots/demo video, known limitations

## Stretch (bonus - only if time allows, in value order)

- [ ] 18. **Role-based login** - JWT auth with ADMIN (status changes) and VIEWER (read-only) roles, login page, protected routes
- [ ] 19. **Distributed tracing** - OpenTelemetry traces across producer → Kafka → backend → API, Jaeger container in compose
- [ ] 20. **Continuous delivery** - CI builds and pushes Docker images to GitHub Container Registry on main
- [ ] 21. **Kubernetes manifests** - Helm chart or manifests deploying the full stack
- [ ] 22. **Performance notes** - README section on partitioning, consumer concurrency, DB indexes, Redis caching, WS throttling, with a simple load test result
- [ ] 23. **AI incident assistant** - panel that summarizes an incident and suggests next steps via an LLM API (disabled when no API key is set)