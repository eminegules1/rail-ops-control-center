# Alstom Rail Operations Control Center

**Real-time railway operations and mobility incident monitoring**

> A technical assignment for Alstom's Full Stack Software Designer role; not an
> official Alstom product.

Real-time incident monitoring for a rail operations center: a producer publishes
service events to Kafka, a Spring Boot backend processes them into PostgreSQL and
Redis live state, and a React dashboard shows service health and incidents live.

> Work in progress. Full setup, architecture, and API docs will land here.

## Repository layout

- `frontend/` - React + TypeScript + Vite dashboard
- `backend/`, `producer/` - Spring Boot modules (planned)
- `docker-compose.yml` - local stack

## Local infrastructure

**Prerequisite:** Docker Desktop running (`docker info` succeeds).

From the repository root:

```bash
docker compose up -d --wait   # start everything and wait until healthy
docker compose ps             # every service should show (healthy)
docker compose logs -f kafka  # follow one service's logs
docker compose down           # stop, keeping data
docker compose down -v        # stop and DELETE all data (Kafka, Redis, Postgres)
```

| Service | From the host | From other containers |
|---|---|---|
| Kafka | `localhost:9092` | `kafka:29092` |
| Kafka UI | http://localhost:8081 | - |
| Redis | `localhost:6379` | `redis:6379` |
| PostgreSQL | `localhost:5432`, database `incidents` | `postgres:5432` |

Kafka runs in KRaft mode (no ZooKeeper) with automatic topic creation turned
off, so topics exist only when the application declares them with their
intended partitions and retention. Redis uses append-only persistence, so its
live counters survive restarts together with the Postgres data.

### Configuration and credentials

Every setting has a default in `docker-compose.yml`, so the stack starts
without a `.env` file. To override ports or credentials, copy `.env.example` to
`.env` and edit it. The database user `rail_ops` / `rail_ops_local_only` is a
local demo default only; applications read credentials from the same
environment variables and never hardcode them. A production deployment would
take credentials from a secrets manager or Docker secrets and would not publish
these ports. All ports are bound to `127.0.0.1`.

## Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173
npm run build
npm run lint
```
