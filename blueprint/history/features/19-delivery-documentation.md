# Feature: Delivery documentation

**From build-plan:** feature 19
**Build attempt:** 1
**Status:** verified
**Branch:** feature/delivery-documentation

## Goal

Finish the README and add `docs/api.md` so a reviewer who clones the repo can understand what the system does, how the parts fit together, why the Kafka and Redis designs are shaped as they are, how to call the API, and what is deliberately not solved. Every statement must match the code that exists today; nothing new is built.

## In scope

- README fixes for drift caused by features 13-16 (see Files / areas): remove the "Work in progress" placeholder, replace the "until feature 14 lands" caveats with the actual behaviour, and update the Frontend section for WebSocket live updates.
- README sections for features that are still undocumented: Redis resilience (circuit breaker, PostgreSQL fallback, reconcile flag, reconciler) and Observability (JSON logs, Actuator health, Prometheus metrics).
- An **Architecture** section with a Mermaid diagram (producer, Kafka topic and DLT, backend consumer group, PostgreSQL, Redis, REST, STOMP, nginx, React) and a short data-flow narrative for the event path and the status-change path.
- **Design notes** in the README: Kafka topic, keying and consumer-group behaviour; Redis key design (consolidated pointer plus anything missing); why PostgreSQL is the source of truth and Redis is derived state; **performance notes** based on a measurement actually run on the local stack, with the machine and command stated.
- `docs/api.md`: every REST endpoint (backend and producer), STOMP topics, and operational endpoints, with parameters, response shapes, the ProblemDetail error shape and status codes, plus copy-pasteable `curl` examples.
- **Screenshots**: a small set of PNGs under `docs/images/` captured from the running stack, embedded in the README. Screenshots, not a video: a video cannot be produced or verified here, and the build plan allows either.
- A consolidated **Known limitations** section, a table of contents, and an updated Repository layout (`docs/`, `.github/`).
- A final consistency pass: links, documented commands, stale-phrase search.

## Out of scope

- Any code, config, compose, Dockerfile, CI or test change. If a documented behaviour turns out to be a bug, record it under Known limitations (or raise `/fix`); do not repair it here.
- A demo video, generated API clients, or committing an exported OpenAPI file.
- Choosing whether the GitHub repository is public or private, adding a remote, pushing, or writing a repository URL into the docs (still to be confirmed with the recruiter; there is no remote today).
- Stretch features 20-24 (no login, tracing, CD or Kubernetes content beyond a one-line "not implemented" mention in Known limitations if it helps a reader).
- Copying or paraphrasing the assignment brief or its scoring rubric. All text is written from the code and behaviour, in our own words. Do not add the brief PDF or refer to it.

## Build loop

`workflow.stepReview` is `feature`: build all steps, then present one review packet. `checkpointCommits` is `disabled`; no per-step commits. `/complete` creates the final feature commit. After each step, run that step's check and record the observed result; stop and report if a check fails.

## Build steps

- [x] 1. **Reconcile the README with what is built.** Remove the "Work in progress" line. Find every "until feature N" / "Known limitation until ..." passage (currently near the Event ingestion Redis-outage note, Incident status update, and Dashboard data APIs sections) and replace it with the real behaviour after features 14, read from the code and the feature 14 archive `blueprint/history/features/14-*.md`. Rewrite the Frontend section's polling paragraph for feature 13 (WebSocket cache patching, connection status chip, reconnect, polling as fallback, row highlight), reading `frontend/src` to get it right. Add a Testing subsection that states the module test commands, that backend tests need Docker for Testcontainers, and where the JaCoCo report lands (reuse the existing wording near "JaCoCo" rather than duplicating it).
  **Done when:** `grep -n -i -E "work in progress|until (redis resilience|the reconciler|feature 1[0-9])|lands\)" README.md` returns nothing, and each rewritten passage names a behaviour that a cited class, test or archive confirms.

- [x] 2. **Document Redis resilience and Observability.** Add a "Redis resilience" section (what trips the circuit breaker, which reads fall back to PostgreSQL, what the reconcile-needed flag is, what the pause-and-rebuild reconciler does and when it runs) and an "Observability" section (JSON log fields including `eventId` in MDC, `/actuator/health`, `/actuator/prometheus`, the exact metric names for processed, invalid and DLT events). Take names, thresholds and defaults from `RedisResilienceConfig`, `LiveStateReconciler`, `ReconcileState`, `logback-spring.xml`, `application.yml` and the metrics code, not from memory.
  **Done when:** with the stack running, `curl -s localhost:8080/actuator/prometheus` shows every metric name written in the README and `curl -s localhost:8080/actuator/health` matches the documented shape; each threshold or default quoted appears in code or `application.yml`.

- [x] 3. **Architecture section and repository layout.** Add a Mermaid `flowchart` (or `sequenceDiagram` if clearer) covering the event path (producer -> `incident-events` -> `incident-processor` consumer group -> PostgreSQL + Redis -> REST/STOMP -> nginx -> React) plus the DLT branch, and a short numbered narrative for the ingest path and the status-change path. Update Repository layout to add `docs/` and `.github/`.
  **Done when:** the Mermaid source parses without error using a local parser (for example `mermaid-cli` run through Docker or a scratch `npx` install in the scratchpad, not committed), and every box and arrow name matches a real topic, class, service or endpoint. State in the review handoff that GitHub rendering itself could not be checked (no remote).

- [x] 4. **Design notes and performance notes.** Add "Design notes" covering: topic name, 3 partitions, the service name as the message key and what that guarantees (per-service order on one partition; idempotent replay via `eventId`), consumer concurrency 3, manual ack and when offsets commit, retry then DLT (link to the existing DLT section), the Redis key table (link to the existing table and fill any missing key), and the "PostgreSQL is authoritative, Redis is rebuildable derived state" rule. Add "Performance notes": run the documented burst (`POST /produce?count=N` with a stated N, defaults otherwise) on the local stack, record the observed ingestion time or rate from the Prometheus counters or logs, and write the figure with the machine (Windows 10, Docker Desktop, memory limit) and the exact command. If a figure cannot be measured reliably, say so instead of estimating. State plainly what was not measured.
  **Done when:** every design claim points at a class, config value or test; the performance section contains a command a reviewer can paste and a number this session actually observed, or an explicit "not measured" line.

- [x] 5. **Write `docs/api.md`.** One file, in our own words: base URLs and ports; `GET /api/events` (all query parameters with limits and defaults), `GET /api/events/{eventId}`, `PUT /api/events/{eventId}/status` (request body, allowed transitions, 400/404/409 cases), the four dashboard endpoints, producer endpoints (`POST /produce`, `/actuator/health`), the STOMP endpoint `/ws` and both topics with message shapes, and Actuator endpoints. Include the ProblemDetail error shape and one real example per endpoint group. Link it from the README and mention that Swagger UI at `/swagger-ui/index.html` and `/v3/api-docs` are the generated reference. Take parameters and responses from the controllers, DTO records and `ApiExceptionHandler`, then compare with the live `/v3/api-docs`.
  **Done when:** each `curl` example in the file was run against the running stack and its response shape and status code match what is written, and the endpoint list equals the paths in `/v3/api-docs` plus the producer, WebSocket and Actuator paths.

- [x] 6. **Screenshots.** With the stack up and populated, capture PNGs with the Playwright tooling into `docs/images/`: dashboard, events page with a detail drawer open, services page, and one dark-mode dashboard if it adds value (at most 4). Use consistent viewport size, descriptive kebab-case file names and alt text, and embed them in a README "Screenshots" section near the top. Keep the total added size under about 1.5 MB (downscale or crop if needed). Confirm no screenshot shows secrets, host-specific paths or unrelated browser chrome.
  **Done when:** every embedded image path exists in the repo, the images show live data (non-empty KPIs and a populated table), and `git status` shows them as new tracked-eligible files (not gitignored).

- [x] 7. **Known limitations, table of contents, final pass.** Consolidate a "Known limitations" section from the per-feature notes still true after the rewrite (at least: the Redis rebuild does not restore `processed:{eventId}` keys so re-consuming a stored event after a manual offset reset within 24h would count it twice; no DLT replay tool; best-effort WebSocket delivery without replay; no authentication, local single-machine demo only; anything step 1 or 4 found), and link back to the detailed sections instead of repeating them. Add a table of contents after the intro. Run the final checks below.
  **Done when:** all relative links and `#anchors` in `README.md` and `docs/api.md` resolve (checked with a scratch script), `git diff --stat` touches only `README.md`, `docs/api.md`, `docs/images/*`, and the blueprint bookkeeping files, no text from the brief appears, and `docker compose config -q` still succeeds.

## Files / areas

- `README.md` (edit): stale passages near the "Event ingestion (backend)", "Incident status update", "Dashboard data APIs" and "Frontend" sections; new sections for Testing, Redis resilience, Observability, Architecture, Design notes, Performance notes, Screenshots, Known limitations, table of contents.
- `docs/api.md` (new).
- `docs/images/*.png` (new).
- Read-only sources of truth: `backend/src/main/java/com/railops/backend/` (`EventController`, `DashboardController`, `ApiExceptionHandler`, `RedisResilienceConfig`, `LiveStateReconciler`, `ReconcileState`, `KafkaConsumerConfig`, `IngestionProperties`, `WebSocketConfig`), `backend/src/main/resources/application.yml`, `backend/src/main/resources/logback-spring.xml`, `backend/src/main/resources/redis/*.lua`, `producer/src/main`, `frontend/src`, `frontend/nginx.conf`, `docker-compose.yml`, `.github/workflows/ci.yml`, and the archives under `blueprint/history/features/` (notably 12-17).
- `blueprint/` bookkeeping is updated by `/complete`, not by hand.

## Data / contracts

No data, API or configuration contract changes. The docs describe existing contracts and must not invent defaults, limits, response fields or behaviours. Where code and current README disagree, the code wins and the README is corrected. New documentation contract: paths are relative, images live in `docs/images/`, and `docs/api.md` is a hand-written companion to the generated OpenAPI reference, which stays authoritative if they ever differ.

## Testing

- No logic changes, so no new unit or browser tests. No `Verify` command is declared in `AGENTS.md`, and none was run while writing this spec.
- Documentation checks, run per step as listed above: grep for stale phrases, `curl` against the running stack for every documented command and example, Prometheus and health output comparison, a local Mermaid parse, a scratch link/anchor checker (kept in the scratchpad, not committed), `docker compose config -q`, and `git diff --stat` scope.
- Running the stack needs Docker Desktop with the app images built (`docker compose up -d --build --wait`); state the actual results observed in the review handoff and do not claim GitHub rendering, which cannot be checked without a remote.

## Notes for the AI

- Write for a reviewer who is a senior engineer seeing the repo for the first time: concise, concrete, honest about trade-offs. Prefer tables and short paragraphs over long prose; README is already about 590 lines, so avoid duplicating text that exists and link to it instead.
- Do not restructure or move the existing per-feature sections; edit in place and add new sections around them. Preserve working commands and anchors that other sections link to.
- Every number, name, default and status code you write must come from code, config, a test, or a command you ran this session. Performance figures are observations from this machine, labelled as such, never benchmark claims.
- The assignment brief is restricted and gitignored: never quote, paraphrase or reference its wording or rubric.
- Do not add AI attribution anywhere (README, docs, commits).
- Leave the dev stack in the state it was found (running is fine; do not run `down -v` unless the documented step needs a reset, and then restore with the Quick start command).


<!-- blueprint:completion {"schemaVersion":1,"specBytes":13231,"specSha256":"23dbf730408b4eb870e3a78f78e357d5de8a09d2ce66d63bd0513e11587c1709","branch":"refs/heads/feature/delivery-documentation","head":"094cfe4899f6b727ef01d8704ee3f7bc66437b39","baseRef":"refs/heads/master","baseCommit":"094cfe4899f6b727ef01d8704ee3f7bc66437b39","sourceTree":"4cfddabb596cc91036d6b3940f45947d4b7cdfcc","absentOptional":[]} -->
