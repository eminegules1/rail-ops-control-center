# Feature: Clean-clone startup verification

**From build-plan:** feature 17
**Build attempt:** 1
**Status:** verified
**Branch:** feature/clean-clone-startup-verification

## Goal

An Alstom reviewer clones the repository, runs one Compose command, and gets a
healthy, populated dashboard with no `.env`, no local JDK or Node, and no prior
Docker state. The compose stack already has healthchecks and `depends_on:
service_healthy` from features 1-13; this feature **proves** that on a genuinely
clean clone, fixes whatever the proof shows is broken, and gives the reviewer a
short README quick start with the failure modes they are most likely to hit.

## In scope

- A repeatable clean-clone verification: a fresh `git clone` of the repository
  (no `.env`, no untracked files), an isolated Compose project name so the run
  never touches the developer's `rail-ops` containers, volumes or images, and a
  cold image build (`build --no-cache`) so nothing comes from this machine's
  layer cache.
- Fixes limited to defects that verification actually shows, in the compose
  file, the three Dockerfiles, `frontend/nginx.conf`, the two `.dockerignore`
  files, and `.env.example`. If it shows none, those files stay unchanged and
  the step records that.
- `docker-compose.yml` variables and `.env.example` keys agree (every `${VAR}`
  used in compose is documented in `.env.example`, and every key in
  `.env.example` is used).
- A README **Quick start** section near the top: prerequisites, the one command,
  expected first-build and later-start times (measured, not guessed), what to
  open, stop and reset, and troubleshooting for port conflicts and low Docker
  memory.

## Out of scope

- CI workflow (feature 18) and the final README, architecture diagram, API docs
  and screenshots (feature 19).
- Any change to Java or frontend application code. A startup defect that lives
  there is stopped and routed to `/fix`, not folded into this feature.
- A verification script, Makefile, or other new tooling: the checklist runs from
  the commands below and its results are recorded, not automated.
- Restart policies, base-image digest pinning, resource limits, healthcheck
  redesign, or production hardening (secrets manager, TLS): no current
  requirement. Changing a healthcheck is allowed only when step 1 shows it
  failing or flapping.
- Changing default host ports. The documented defaults (8080, 8081, 8082, 3000,
  5432, 6379, 9092) stay; conflicts are handled by the existing env overrides
  plus the new troubleshooting text.
- Pushing, deploying, or publishing anything.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review
packet. `checkpointCommits` is disabled, so no step commits. `/complete` makes
the final commit.

## Build steps

- [x] **1. Baseline clean-clone run (no repository edits).**
  - Ask before stopping the developer stack: `docker compose down` (no `-v`, data
    kept) in the repo, because it holds host ports 3000, 5432, 6379, 8081, 8082,
    9092 and the Docker VM has about 4 GB of memory. Restore it in step 4.
  - Clone into the scratchpad directory: `git clone <repo> <scratch>/rail-ops-clean`.
    Confirm the clone has no `.env` and that `git status --ignored` there is clean.
  - From the clone, with `COMPOSE_PROJECT_NAME=rail-ops-clean`: `docker compose
    config -q`, then `docker compose build --no-cache`, then the README
    command `docker compose up -d --build --wait`, timing both. Pass
    `BACKEND_PORT=8083` in the shell environment (not a `.env` file): port 8080
    is occupied on this machine by MiniTool ShadowMaker's MTAgentService
    (`netstat` shows it listening on 0.0.0.0:8080), so the 8080 default is not
    exercised here. Record that limit.
  - Run the checklist in **Testing** and record each result.
  - Record every defect as: check, observed, expected, file that owns it.
  - **Done when:** the checklist is filled in with real command output (pass or
    fail per item), the defect list is written down, and `git status` in the
    repository is unchanged.

- [x] **2. Fix only the defects step 1 found.**
  - Edit only the files listed under **Files / areas**. Keep each fix minimal and
    explain it in one line.
  - Reconcile `.env.example` with the `${VAR}` names in `docker-compose.yml` (a
    mechanical grep comparison, both directions).
  - If a defect needs application-code changes, stop and report it for `/fix`.
  - **Done when:** `docker compose config -q` succeeds in the repository, the
    env-name comparison shows no missing or unused name, and every step-1
    defect is either fixed or explained as not-a-defect or `/fix`-bound.
    With no defects, this step records "no change needed" with the step-1
    evidence.

- [x] **3. README Quick start.**
  - Add `## Quick start` before `## Repository layout`. Content: prerequisites
    (Docker Desktop running with Compose v2; the memory Docker needs, from the
    step-1 `docker stats` and OOM check; the ports that must be free), the
    command `docker compose up -d --build --wait` with the measured cold and
    warm times, what to open (dashboard http://localhost:3000, Kafka UI,
    Swagger only if it exists), how to stop and reset, and a troubleshooting
    list: "port is already allocated" with the env var to set for each port
    (using 8080/`BACKEND_PORT` as the worked example), a service that stays
    `unhealthy` (`docker compose ps`, `docker compose logs <service>`), and
    Git Bash's `MSYS_NO_PATHCONV=1`.
  - Trim `## Local infrastructure` only where the new section duplicates it;
    keep its service/port table and the configuration subsection.
  - The bare `docker compose up --build` from the project overview works as the
    same stack in attached mode; say so in one sentence.
  - **Done when:** every command, URL, service name and environment variable in
    the new section exists in `docker-compose.yml` or `.env.example` (checked by
    grep), the numbers in it are the ones measured in step 1, and the README
    still renders with no broken anchors (`[...](#...)` targets exist).

- [x] **4. Final clean-clone run over the changes, cleanup, restore.**
  - Make a fresh clone of the current commit, then apply the working-tree
    changes to it: `git diff HEAD | git -C <clone> apply`, and copy any file from
    `git ls-files -o --exclude-standard`. (Nothing is committed until
    `/complete`, so the clone alone would test the old files.)
  - Repeat the step-1 sequence (cold `build --no-cache`, one-command `up`,
    checklist) on this clone, and add the two restart scenarios from the
    checklist.
  - Clean up only what this feature created: `docker compose -p rail-ops-clean
    down -v --rmi local`, and delete the scratch clone. Do not run
    `docker system prune` or `docker builder prune`, and never pass `-v` to the
    `rail-ops` project.
  - Restore the developer stack: `docker compose up -d --wait` in the
    repository (its own `.env` still applies) and confirm all 7 services healthy.
  - **Done when:** every checklist item passes on the clean clone with the
    changes applied, `docker compose ps -a --filter name=rail-ops-clean` shows
    nothing left, the developer stack is healthy again, and `git status` shows
    only files from **Files / areas**.

## Files / areas

- `docker-compose.yml` (only if step 1 shows a defect)
- `producer/Dockerfile`, `backend/Dockerfile`, `frontend/Dockerfile`,
  `frontend/nginx.conf`, `.dockerignore`, `frontend/.dockerignore` (same rule)
- `.env.example` (reconciliation with compose)
- `README.md` (new Quick start; trim of duplicated lines in Local infrastructure)
- No source under `producer/src`, `backend/src`, or `frontend/src`.

## Data / contracts

No data, API, or schema change. The contract this feature holds and documents:

- One command from the repository root with no `.env`: `docker compose up -d
  --build --wait` (README) and `docker compose up --build` (project overview)
  start the same stack.
- "Started" means all 7 services (`kafka`, `kafka-ui`, `postgres`, `redis`,
  `producer`, `backend`, `frontend`) report `healthy`, and the command exits 0.
- Startup order stays `depends_on: condition: service_healthy`: kafka first;
  kafka-ui and producer after kafka; backend after kafka, postgres and redis;
  frontend after backend.
- Host ports keep their current defaults and are bound to `127.0.0.1`, each
  overridable by the existing variable in `.env.example`.

## Testing

No application logic changes, so no unit or integration tests are added or
required, and no Browser tests command exists. No `Verify` command exists yet, so
none was run while writing this spec. Evidence is the recorded clean-clone
checklist, run by hand. Run in the clone, with `COMPOSE_PROJECT_NAME=rail-ops-clean`
and `BACKEND_PORT=8083`:

1. `docker compose config -q` exits 0.
2. `docker compose build --no-cache` exits 0 (time recorded, and whether any
   build was OOM-killed).
3. `docker compose up -d --build --wait` exits 0 (time recorded).
4. `docker compose ps`: 7 services, all `healthy`, none restarting.
5. `curl -fs localhost:3000/` and `/dashboard` return the app HTML.
6. Within 60 s of step 3 returning, `curl -fs localhost:3000/api/dashboard/summary`
   returns JSON with a total event count above 0 (the producer's seed burst
   reached the backend), and `/api/services` lists at least one service.
7. `curl -fs localhost:8083/actuator/health` reports `UP`, and
   `curl -fs localhost:8082/actuator/health` reports `UP`.
8. `curl -fs -X POST "localhost:8082/produce?count=5"` returns `"sent":5`.
9. `curl -fs localhost:8081/` returns 200 (Kafka UI).
10. No container has `OOMKilled` true (`docker inspect`); record `docker stats
    --no-stream` totals.
11. Backend and producer logs: list every `"level":"ERROR"` line after startup.
    None is expected; any hit is investigated, not assumed benign.
12. Restart with data kept: `docker compose down`, then `up -d --wait` exits 0,
    all healthy.
13. Reset: `docker compose down -v`, then `up -d --wait` exits 0, all healthy,
    and item 6 passes again.
14. If Playwright is available, open http://localhost:3000/dashboard once and
    confirm the KPI cards show non-zero counts and the connection chip reads
    Live; otherwise say this was not checked.

Claim only what was run. The 8080 default host port cannot be exercised on this
machine while the ShadowMaker agent holds it; report that plainly.

## Notes for the AI

- The local gitignored `.env` sets `BACKEND_PORT=8083` for the same 8080 conflict.
  A clone has no `.env`, so pass overrides in the shell environment.
- `-p`/`COMPOSE_PROJECT_NAME` overrides the file's `name: rail-ops`, giving the
  clean run its own containers, volumes and images (`rail-ops-clean-*`).
- In Git Bash, prefix `docker exec`-style commands that take container paths
  with `MSYS_NO_PATHCONV=1`. The root `.gitattributes` forces LF, so Windows
  checkouts do not get CRLF in `nginx.conf`, Lua or SQL.
- Considered and deliberately not changed: floating `maven:3.9-...` and
  `eclipse-temurin:21-jre` tags (they receive fixes; the rest are pinned); the
  unused `PRODUCER_SEED_COUNT` passthrough; `restart:` policies.
- Time-box the cold build: three parallel `--no-cache` image builds on a 4 GB
  Docker VM is the likeliest place for a real failure (OOM, network). If it
  fails, that is the finding, so record it before fixing.
- The second and later `up` re-seeds the producer's ~200 events by design
  (documented); do not treat rising counts after restarts as a defect.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":11581,"specSha256":"96839d7ec04bde05bb85c732c7137dbee1d55ac25c00b1c02bf58a1244d9cdf3","branch":"refs/heads/feature/clean-clone-startup-verification","head":"17b1ba7edd72457a7403085432dbba9eb7cd420d","baseRef":"refs/heads/master","baseCommit":"17b1ba7edd72457a7403085432dbba9eb7cd420d","sourceTree":"9ce1af7ea15ec322a9d4969098a238d9ce4a454c","absentOptional":[]} -->
