# Feature: Local infrastructure

**From build-plan:** feature 1
**Build attempt:** 1
**Branch:** feature/local-infrastructure
**Status:** verified

## Goal

One `docker compose up -d` from the repository root starts Kafka (KRaft, single
broker), Kafka UI, Redis 7, and PostgreSQL 16, each with a healthcheck, so every
later feature has a working stack and adds its own service to the same file.
A clean clone must work without creating a `.env` file first.

## In scope

- Root `docker-compose.yml` with services `kafka`, `kafka-ui`, `redis`,
  `postgres`, healthchecks, named volumes, and one project network
- Kafka dual listeners: an internal one for containers and an external one on
  `localhost:9092` so the Spring apps can also run from an IDE during development
- Broker-level auto topic creation disabled, so topics only come from code with
  the planned partitions and retention
- Redis append-only persistence, so Redis counters and Postgres rows survive a
  restart together (the startup reconciler only arrives in feature 14)
- `.env.example` documenting every variable, with compose defaults so no `.env`
  is required
- Root README section and `AGENTS.md` Commands for starting, checking, and
  resetting the stack

## Out of scope

- Topic creation, partitions, and 24h retention (declared in code by features 2
  and 3)
- Database schema (Flyway in feature 3)
- Backend, producer, and frontend services, Dockerfiles, and nginx (features 2,
  3, 8)
- `PRODUCER_*` variables (feature 2)
- Clean-clone hardening across the whole app (feature 17), CI (feature 18)

## Build loop

`workflow.stepReview` is `feature`: build all steps, then present one
feature-level review packet. `workflow.checkpointCommits` is `disabled`: no
per-step commits. `/complete` creates the final feature commit.

Prerequisite: Docker Desktop must be running (`docker info` succeeds). At spec
time the engine was stopped.

## Build steps

- [x] 1. **Postgres and Redis** - create `docker-compose.yml` (`name: rail-ops`)
  with `postgres` (`postgres:16-alpine`) and `redis` (`redis:7-alpine`, pinned to
  an exact minor tag), named volumes `postgres-data` and `redis-data`, Redis
  started with `--appendonly yes`, and healthchecks (`pg_isready -U ... -d ...`,
  `redis-cli ping`). Create `.env.example`.
  Done when: `docker compose config` succeeds with no `.env` present;
  `docker compose up -d postgres redis` shows both `healthy` in
  `docker compose ps`; `docker compose exec postgres psql -U <user> -d <db> -c
  "select 1"` returns 1; `docker compose exec redis redis-cli ping` returns
  `PONG`; a key set before `docker compose restart redis` is still present after.

- [x] 2. **Kafka broker** - add `kafka` using the official `apache/kafka` image
  (exact 4.x tag, confirmed to exist at implement time) in KRaft combined
  broker+controller mode with a fixed `CLUSTER_ID`, listeners `INTERNAL`
  (`kafka:29092`, for containers), `EXTERNAL` (`localhost:9092`, published to
  the host), and `CONTROLLER`; `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`;
  replication factors of 1 for internal topics; a bounded heap for laptops;
  named volume `kafka-data`; healthcheck with
  `kafka-broker-api-versions.sh` against the internal listener.
  Done when: `kafka` is `healthy`; inside the container a `smoke-test` topic can
  be created, a message produced and consumed back via the internal listener,
  and the topic deleted; producing to a non-existent topic fails instead of
  auto-creating it; `Test-NetConnection localhost -Port 9092` succeeds on the
  host.

- [x] 3. **Kafka UI** - add `kafka-ui` using `kafbat/kafka-ui` (the maintained
  fork of the archived provectus image, exact tag) on host port 8081, pointed
  at `kafka:29092`, `depends_on: kafka: condition: service_healthy`.
  Done when: http://localhost:8081 shows the cluster online with 1 broker and no
  topics other than internal ones.

- [x] 4. **Docs and reset check** - README "Local infrastructure" section
  (prerequisites, start, status, URLs and ports table, logs, reset with
  `docker compose down -v`), a short "Configuration and credentials" note
  (demo credentials are local defaults; override them in `.env` copied from
  `.env.example`; production would use a secrets manager or Docker secrets and
  would not publish these ports), and matching `AGENTS.md` Commands entries.
  Done when: `docker compose down -v` then `docker compose up -d` brings all four
  services to `healthy` without a `.env` file, and every command in the README
  was run once and behaves as written.

## Files / areas

- `docker-compose.yml` (new, repository root)
- `.env.example` (new, repository root; `.env` stays gitignored)
- `README.md` (new "Local infrastructure" section)
- `AGENTS.md` (Commands section)

## Data / contracts

Later features depend on these names; changing them later means touching
several modules.

| Service | Container address | Host address | Image |
|---|---|---|---|
| `kafka` | `kafka:29092` | `localhost:9092` | `apache/kafka:<4.x>` |
| `kafka-ui` | - | http://localhost:8081 | `kafbat/kafka-ui:<tag>` |
| `redis` | `redis:6379` | `localhost:6379` | `redis:7.<minor>-alpine` |
| `postgres` | `postgres:5432` | `localhost:5432` | `postgres:16-alpine` |

Environment variables (all with compose defaults, all documented in
`.env.example`):

| Variable | Default | Used by |
|---|---|---|
| `POSTGRES_DB` | `incidents` | postgres, later backend |
| `POSTGRES_USER` | `rail_ops` | postgres, later backend |
| `POSTGRES_PASSWORD` | `rail_ops_local_only` | postgres, later backend (obviously dev-only default) |
| `POSTGRES_PORT` | `5432` | host port mapping |
| `REDIS_PORT` | `6379` | host port mapping |
| `KAFKA_PORT` | `9092` | host port mapping (external listener advertised on it) |
| `KAFKA_UI_PORT` | `8081` | host port mapping |

- Host ports bind to `127.0.0.1` only; the stack is a local demo.
- Credentials have one source of truth: environment variables. Compose defaults
  exist only so a clean clone starts with one command; no credential is ever
  written into application code or config files in later features, which read
  the same variables.
- Compose project name `rail-ops`; volumes `postgres-data`, `redis-data`,
  `kafka-data`.
- Topics are never auto-created. Features 2 and 3 must declare
  `incident-events` (3 partitions, 24h retention) and `incident-events.DLT` in
  code before producing.

## Testing

No test runner exists yet and this feature adds no application logic, so there
are no unit tests. Evidence is the command output named in each step's
`Done when`: `docker compose config`, `docker compose ps` health states, the
Postgres query, the Redis ping and restart check, the Kafka smoke round trip and
auto-create refusal, the host port check, and Kafka UI in the browser.

## Notes for the AI

- The dev machine is Windows 10 with Docker Desktop; run commands from the repo
  root. Healthchecks stay inline in YAML (no shell scripts to break on line
  endings).
- Use `${VAR:-default}` everywhere so a clean clone starts without `.env`.
- Do not add application services, topic-init containers, or schema scripts.
- Pin exact image tags; no `latest`.
- `docker compose down -v` deletes all data; say so in the README.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":7233,"specSha256":"56c6bcb982ca24a2a3cb0164e1cfd0ac471fee1479593a5057dc495f35aa65a2","branch":"refs/heads/feature/local-infrastructure","head":"54396c5b5c839f69ac190a4e13382f4cb93e5f29","baseRef":"refs/heads/master","baseCommit":"54396c5b5c839f69ac190a4e13382f4cb93e5f29","sourceTree":"99aafec2b7f54243d84168755993128afe671d87","absentOptional":[]} -->
