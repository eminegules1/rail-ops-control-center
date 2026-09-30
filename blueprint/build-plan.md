# Build Plan

## MVP

Every app feature adds its own Dockerfile and compose service and updates the README, so `docker compose up --build` always runs what exists. Features 1-10 are the runnable end-to-end version that covers every mandatory criterion (target: about day 3).

- [x] 1. **Local infrastructure** - Compose stack for Kafka (KRaft), Kafka UI, Redis and Postgres with healthchecks and `.env.example`
- [x] 2. **Event producer** - Spring Boot app publishes UUID-keyed, schema-consistent JSON events (auto interval, manual trigger, seed burst, duplicate ratio), with its Dockerfile and compose service
- [x] 3. **Event ingestion** - backend consumer group validates events, logs and skips invalid ones, and stores them idempotently in Postgres, with the backend test setup (JUnit, Mockito, Testcontainers) and its Dockerfile and compose service
- [x] 4. **Live service state in Redis** - atomic Lua apply-event: brief-style counters, active-per-severity health, open and active counts, timeline buckets, recent list, apply-once guard
- [x] 5. **Events API** - filtered, searchable, paginated event list and event detail with ProblemDetail errors and Swagger docs
- [x] 6. **Incident status update** - lifecycle rules, 409 on invalid transitions, optimistic locking, atomic Redis counter and health update
- [x] 7. **Dashboard data APIs** - summary, services, timeline and recent-events endpoints with the summary cache
- [x] 8. **Dashboard page** - app shell and routes, KPI cards, service health grid, severity and events-over-time charts, recent events list, polling refresh, with the frontend test setup (Vitest, RTL) and the nginx frontend compose service
- [x] 9. **Events page** - paginated table with URL-synced filters and search, deep-linkable detail drawer, optimistic status change
- [x] 10. **Service status page** - per-service health, last event time, latest severity, open and active incident counts
- [x] 11. **Retry and dead-letter handling** - exponential-backoff retries, non-retryable validation errors, DLT publishing, invalid-message demo ratio
- [x] 12. **Real-time push** - STOMP WebSocket broadcasting new/updated events and throttled summary updates
- [x] 13. **Live UI updates** - WebSocket cache patching, connection status chip, reconnect handling, polling as fallback only, brief highlight on rows that were just created or updated (own or other operators’ status changes)
- [x] 14. **Redis resilience** - Redis circuit breaker, Postgres fallback for dashboard reads, reconcile-needed flag and pause-and-rebuild reconciler
- [x] 15. **Observability** - structured JSON logs, Actuator health, Prometheus metrics for processed/invalid/DLT events
- [x] 16. **End-to-end test coverage** - Testcontainers flows for happy path, DLT and Redis-down fallback, plus coverage report
- [x] 17. **Clean-clone startup verification** - harden the compose stack (healthchecks, startup order, env defaults) and verify one-command startup on a clean clone
- [x] 18. **CI pipeline** - GitHub Actions building and testing backend, producer and frontend
- [x] 19. **Delivery documentation** - final README, architecture diagram, API docs, Redis/Kafka and consumer-group design notes, performance notes, screenshots/demo video, known limitations

## Stretch (bonus - only if time allows, in value order)

- [x] 20. **Role-based login** - lightweight JWT auth with ADMIN (status changes) and VIEWER (read-only) demo users, login page, protected routes
- [ ] 21. **Distributed tracing** - OpenTelemetry traces across producer → Kafka → backend → API, Jaeger container in compose
- [ ] 22. **Continuous delivery** - CI builds and pushes Docker images to GitHub Container Registry on main
- [ ] 23. **Kubernetes manifests** - Helm chart or manifests deploying the full stack
- [ ] 24. **AI incident assistant** - panel that summarizes an incident and suggests next steps via an LLM API (disabled without an API key)
