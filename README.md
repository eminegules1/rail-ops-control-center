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

## Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173
npm run build
npm run lint
```
