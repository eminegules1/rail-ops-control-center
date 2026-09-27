# Feature: Events API

**From build-plan:** feature 5
**Build attempt:** 1
**Branch:** feature/events-api
**Status:** verified

## Goal

Expose stored events over REST so operators (and the later Events page) can
list them with filters, free-text search, sorting, and pagination, and open a
single event by its `eventId`. Every error is an RFC 7807 `ProblemDetail`, and
the API is documented in Swagger UI via springdoc. Reads come from Postgres, the
source of truth.

## In scope

- `GET /api/events` - filters `severity`, `status`, `source`, `service`, search
  `q`, and `page` / `size` / `sort`
- `GET /api/events/{eventId}` - event detail; 404 `ProblemDetail` when unknown
- DTO records for the event and the page envelope (controllers never return the
  JPA entity)
- One backend `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`,
  producing `ProblemDetail` for Spring's own errors, the 404, invalid query input
  (400), and unexpected errors (500)
- springdoc-openapi Swagger UI and OpenAPI JSON for the backend
- README section documenting both endpoints, parameters, and the Swagger URL

## Out of scope

- `PUT /api/events/{eventId}/status`, optimistic locking, `version` in DTOs
  (feature 6)
- Dashboard, services, timeline, and recent-events endpoints; summary cache
  (feature 7)
- Frontend, nginx proxy, CORS (features 8-9; nginx proxies `/api` same-origin)
- Redis reads or circuit breaker (feature 14); this API reads only Postgres
- `docs/api.md`, Prometheus, JSON logs (features 15, 19)
- Auth (stretch feature 20); the MVP is unauthenticated by plan

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet. `workflow.checkpointCommits` is `disabled`: no checkpoint commits.
`mvn -B -pl backend -am verify` must be green before the review packet.
`/complete` creates the single feature commit.

## Build steps

- [x] **1. Query layer.** Add `EventResponse` and `EventPageResponse` records,
  `EventNotFoundException`, and an `EventQueryService` (read-only transactions)
  that builds a JPA `Specification` from the filters, parses and allowlists the
  sort, applies the stable tiebreaker, and maps entities to DTOs. Make
  `IncidentEventRepository` also extend `JpaSpecificationExecutor<IncidentEvent>`.
  Done when: `EventQueryServiceTest` (`@DataJpaTest` + Postgres Testcontainer,
  service imported) proves each filter alone and combined, `q` matching over
  message, service, and eventId case-insensitively, literal `%` / `_` in `q`,
  default sort, allowlisted sort asc/desc, pagination totals, empty result, and
  unknown `eventId` -> `EventNotFoundException`; a plain unit test covers sort
  parsing (valid, bad field, bad direction, too many parts) -> `InvalidQueryException`;
  backend verify green.
- [x] **2. Controller and errors.** Add `EventController` (thin, built-in MVC method validation,
  explicit `@RequestParam`s with Bean Validation bounds) and
  `ApiExceptionHandler` (`@RestControllerAdvice` extending
  `ResponseEntityExceptionHandler`). Done when: `EventControllerTest`
  (`@WebMvcTest` + `@MockitoBean EventQueryService`) proves 200 list and detail
  JSON shape with ISO-8601 timestamps, parameters passed through to the service,
  404 / 400 bodies are `application/problem+json` with the fields in Data /
  contracts, invalid enum value -> 400, `size=0` / `size=101` / `page=-1` /
  over-long `q` -> 400, unexpected exception -> 500 with a generic detail that
  does not echo the exception message; backend verify green.
- [x] **3. Swagger and docs.** Add `org.springdoc:springdoc-openapi-starter-webmvc-ui`
  (latest 2.8.x, the line built for Spring Boot 3.5; 3.x targets Boot 4) to
  `backend/pom.xml`; add brief `@Operation` / `@Parameter` descriptions only where
  inference is unclear (enum filters, `sort` format); add the README "Events API"
  section. Done when: backend verify green; `docker compose up -d --build --wait`
  succeeds; `curl` against `localhost:8080` shows `/api/events` returning a page of
  seeded events, a filter + `q` narrowing it, detail for a listed `eventId`, a 404
  and a 400 problem body; `/v3/api-docs` lists both paths and
  `/swagger-ui/index.html` returns 200.

- [x] **4. Clear 400 messages.** In `ApiExceptionHandler`, override the
  `HandlerMethodValidationException` and type-mismatch handlers so `detail` names
  the parameter and its rule (for example "size must be between 1 and 100",
  "page must be 0 or more", "q must be at most 200 characters",
  "severity must be one of INFO, WARNING, MAJOR, CRITICAL", "page must be a
  whole number") and never echoes the rejected value. Keep the rule text next to
  the constraint annotations in `EventController`. Done when: `EventControllerTest`
  asserts the exact `detail` for each invalid parameter case and that the
  rejected value is absent; backend verify green; live `curl` shows the new
  messages.

Implementation notes (recorded after build):

- Filters travel as an `EventFilter` record (severity, status, source, service,
  q) from the controller to `EventQueryService`.
- `EventController` has no class-level `@Validated`: Spring MVC's built-in
  method validation (as in the producer) raises `HandlerMethodValidationException`,
  which the inherited handler maps to 400. `@Validated` would switch to
  `ConstraintViolationException` and surface as 500.
- springdoc pinned to `2.8.17`, the latest 2.8.x on Maven Central at build time.
- `@Parameter` descriptions only on `q`, `page`, and `sort`.
- Step 4: every 400 uses title "Invalid query". The sort error became
  "sort must be field or field,asc|desc with field one of ..." so it no longer
  echoes the rejected value either.

## Files / areas

- `backend/pom.xml` - springdoc dependency
- `backend/src/main/java/com/railops/backend/` (flat package, like existing code):
  - `IncidentEventRepository.java` - add `JpaSpecificationExecutor<IncidentEvent>`
  - new `EventResponse.java`, `EventPageResponse.java`, `EventQueryService.java`,
    `EventNotFoundException.java`, `InvalidQueryException.java`,
    `EventController.java`, `ApiExceptionHandler.java`
- `backend/src/test/java/com/railops/backend/` - new `EventQueryServiceTest.java`,
  `EventSortTest.java` (or equivalent unit test name), `EventControllerTest.java`
- `README.md` - new "Events API" section after "Live service state (Redis)";
  update the backend line in "Repository layout"
- No Flyway migration: the existing indexes on `severity`, `status`, `source`,
  `service`, `timestamp` cover the filters and default sort

## Data / contracts

**`GET /api/events`** query parameters (all optional):

| Param | Type | Rule |
|---|---|---|
| `severity` | `Severity` enum | exact; unknown value -> 400 |
| `status` | `EventStatus` enum | exact; unknown value -> 400 |
| `source` | string | exact, case-sensitive (unknown value -> empty page) |
| `service` | string | exact, case-sensitive |
| `q` | string, max 200 chars | trimmed; blank ignored; case-insensitive substring match on `message` OR `service` OR `eventId`; `%`, `_`, `\` matched literally (escaped `LIKE`) |
| `page` | int >= 0, default 0 | zero-based; past the last page -> 200 with empty `content` |
| `size` | int 1-100, default 20 | out of range -> 400 (no silent clamping) |
| `sort` | `field` or `field,asc\|desc`, default `timestamp,desc` | fields allowlisted: `timestamp`, `receivedAt`, `service`, `source`, `eventId`; anything else -> 400 |

Filters combine with AND. `severity` and `status` are not sortable because they
are stored as text and would sort alphabetically, not by rank. Every sort appends
`id desc` as a tiebreaker so pages are stable.

**Event JSON** (`EventResponse` record; list items and detail share it):

```json
{
  "eventId": "EVT-3f1c...",
  "source": "CBTC",
  "service": "signal-service",
  "severity": "CRITICAL",
  "message": "Signal failure at ...",
  "status": "OPEN",
  "timestamp": "2026-09-26T14:30:05.123Z",
  "receivedAt": "2026-09-26T14:30:05.456Z",
  "updatedAt": "2026-09-26T14:30:05.456Z"
}
```

Field names follow the locked data model. The internal `id` and `version` are
not exposed. Instants serialize as ISO-8601 UTC strings (Boot's Jackson default).

**Page JSON** (`EventPageResponse` record, our own stable envelope rather than
Spring Data's `PageImpl` serialization):

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

**`GET /api/events/{eventId}`** -> 200 `EventResponse`, or 404.

**Errors** (`application/problem+json`):

- 404: `title` "Event not found", `detail` "No event with id <eventId>",
  `status` 404, `instance` the request path
- 400 from `InvalidQueryException` (bad sort): `title` "Invalid query",
  `detail` names the parameter and allowed values
- 400 from Spring (type mismatch on enums/ints, `@Min`/`@Max`/`@Size`
  violations): Spring's standard `ProblemDetail` from the inherited
  `ResponseEntityExceptionHandler` methods
- 500 for any other exception: `title` "Internal error", fixed generic `detail`;
  the exception is logged server-side, never echoed (no SQL, stack, or values)

## Testing

Backend test command: `mvn -B -pl backend -am verify`. It is the gate for steps 1
and 2 and must be green before the review packet. No Verify command exists yet
(nothing was run for it during planning).

- `EventQueryServiceTest` - real Postgres (Testcontainers, same image as
  `IncidentEventRepositoryTest`), rows seeded with `insertIfAbsent`
- sort-parsing unit test - pure logic, no Spring
- `EventControllerTest` - `@WebMvcTest` slice with `@MockitoBean`, mirroring the
  producer's `ProduceControllerTest`
- Swagger UI and the live compose stack are integration surfaces: verified in
  step 3 by `curl`, not unit tests. No browser harness exists.

## Notes for the AI

- Keep the controller thin; it builds the query from params and delegates.
  Filtering, sort parsing, and mapping live in `EventQueryService`.
- Use `Specification` + criteria parameters; never concatenate user input into
  JPQL or SQL. Escape `\`, `%`, `_` in `q` and pass the escape char to `like`.
- `@Transactional(readOnly = true)` on service reads; `open-in-view` stays false,
  so map to DTOs inside the transaction.
- Match the producer `ApiExceptionHandler` style (package-private class, one-line
  purpose comment), but extend `ResponseEntityExceptionHandler`: the catch-all
  `Exception` handler then lives in the same advice as Spring's handlers, so the
  most specific one wins and framework 400s never become 500s. Boot's own
  problem-details advice backs off when this bean exists, so do not set
  `spring.mvc.problemdetails.enabled`. Log the 500 case with the exception at `error`; log nothing
  for 400/404.
- Verify that springdoc's `/v3/api-docs` and `/swagger-ui/**` are reachable with
  the default paths; do not add custom springdoc config unless needed.
- The Dockerfile copies module poms; adding a dependency to `backend/pom.xml`
  needs no Dockerfile change. Confirm the image still builds in step 3.
- No em dashes in code comments or README.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":11065,"specSha256":"be2eb3645e9c59a408938e4010de61ae8fbf3b1be45a5acd289d852f4efcd44c","branch":"refs/heads/feature/events-api","head":"d32dc0df2f3653bd94073dd7e7eace965b42ba02","baseRef":"refs/heads/master","baseCommit":"d32dc0df2f3653bd94073dd7e7eace965b42ba02","sourceTree":"a52db74fb0d184b762aad5e4af5454ba05d1ca6e","absentOptional":[]} -->
