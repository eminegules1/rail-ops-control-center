# Feature: CI pipeline

**From build-plan:** feature 18
**Build attempt:** 1
**Status:** verified
**Branch:** feature/ci-pipeline

## Goal

Every pull request and every push to the default branch automatically builds and
tests all three modules, so a reviewer who opens the GitHub repository sees a
green (or red) check backed by the same commands the team runs locally. The
project has no `.github/` directory yet. Everything CI needs already exists and
passes locally: `mvn verify` for `producer` and `backend` (JUnit, Mockito,
Testcontainers, JaCoCo), `npm test`, `npm run build` and `npm run lint` for
`frontend`, and Dockerfiles plus a compose file for all three app images. This
feature only wires them into GitHub Actions.

## In scope

- One workflow file, `.github/workflows/ci.yml`, named `CI`, triggered by
  `pull_request` and by `push` to the default branch. The repository has no
  remote yet and the local branch is `master` while other project docs say
  `main`, so the push filter lists both `main` and `master`.
- Least privilege and hygiene: `permissions: contents: read`, a `concurrency`
  group per workflow and ref with `cancel-in-progress: true`, and a
  `timeout-minutes` on every job.
- Three independent jobs on `ubuntu-latest`, so a failure names the module:
  - **java** - checkout, `actions/setup-java` (Temurin 21, Maven cache), then
    `mvn -B verify` from the repository root. That builds `producer` and
    `backend`, runs all their tests (the backend Testcontainers flows use the
    runner's Docker) and produces the JaCoCo reports. An empty or failing
    suite fails the job.
  - **frontend** - checkout, `actions/setup-node` (Node 24, npm cache keyed on
    `frontend/package-lock.json`), then in `frontend/`: `npm ci`,
    `npm run lint`, `npm test`, `npm run build` (typecheck plus bundle).
  - **docker** - checkout, then `docker compose build` from the repository
    root, proving all three Dockerfiles still build on a clean runner with no
    `.env`.
- A short "Continuous integration" section in `README.md` listing the three
  jobs and the exact local commands each one runs.

## Out of scope

- A single combined `Verify` command in `AGENTS.md`, a local pre-push hook, and
  a matching `verify.yml`. Those belong to the separate `/ci` skill; this
  feature delivers the build-plan CI pipeline only.
- Starting the stack in CI (`docker compose up --wait`) and any smoke test
  against running services. Feature 17 verified startup; the plan asks for
  `docker compose build` only.
- Pushing images (feature 22), deployment, secrets, branch protection or
  rulesets (remote settings), build matrices, browser tests, dependency
  scanning, and uploading coverage reports as artifacts.
- A status badge in the README: it needs the final GitHub repository URL,
  which does not exist yet (feature 19 finalises the README).
- Changing any source, test, Dockerfile or compose file. If a job fails only
  because CI differs from the local machine, that surfaces as a finding and a
  `/fix`, not scope creep here.

## Build loop

`workflow.stepReview` is `feature`: build all steps, then present one review
packet. `workflow.checkpointCommits` is `disabled`: no step commits.
`/complete` creates the final feature commit.

## Build steps

- [x] 1. **Workflow file.** Confirm the current major versions of
  `actions/checkout`, `actions/setup-java` and `actions/setup-node` and the
  `cache` inputs (use the current docs, not memory), then create
  `.github/workflows/ci.yml` with the triggers, permissions, concurrency and the
  three jobs above. Pin actions to major-version tags. Use `node-version: 24`
  to match `frontend/Dockerfile`, `java-version: 21` with `distribution:
  temurin`, and `cache-dependency-path: frontend/package-lock.json`.
  **Done when:** the YAML parses and passes `actionlint` (run the
  `rhysd/actionlint` image through Docker if the binary is not installed), and
  a read-through shows every job has a timeout and no job needs a secret or an
  `.env`.
- [x] 2. **Prove each job's commands on this machine.** Run exactly the
  workflow's commands: `mvn -B verify` at the root (Docker Desktop running),
  `npm ci`, `npm run lint`, `npm test` and `npm run build` in `frontend/`, and
  `docker compose build` at the root.
  **Done when:** all six commands exit 0, the Maven output shows tests ran in
  both modules (no "No tests to run"), and the vitest run reports a non-zero
  test count. Record the observed results in the review packet. A real GitHub
  run cannot be observed here (no remote, push withheld), so say so plainly
  rather than claiming a green Actions run.
- [x] 3. **README section.** Add "Continuous integration" to `README.md`,
  after the Quick start and before Repository layout, describing the trigger,
  the three jobs, and the local command for each. Do not add a badge.
  **Done when:** every command in the section matches `ci.yml` character for
  character and the existing README anchors and links still resolve.

## Files / areas

- `.github/workflows/ci.yml` (new)
- `README.md` (one new section)
- `blueprint/build-plan.md` (checkbox, by `/complete`)

## Data / contracts

No application data or API changes. Workflow contract:

| Job | Runs in | Commands | Fails when |
|---|---|---|---|
| java | repo root | `mvn -B verify` | compile error, failing or missing tests in `producer` or `backend` |
| frontend | `frontend/` | `npm ci`, `npm run lint`, `npm test`, `npm run build` | lockfile mismatch, lint or type error, failing test, build error |
| docker | repo root | `docker compose build` | any of the three image builds fails |

Triggers: `pull_request`; `push` to `main` and `master`. Token permissions:
`contents: read` only. No secrets, no `.env`, no registry login.

## Testing

No new logic, so no new unit tests. The existing suites are the gate and CI runs
them unchanged. Evidence for this feature is step 1's `actionlint` result and
step 2's six local command results. Live GitHub Actions behavior is not
verifiable until the repository is pushed; the first hosted run is the real test
and belongs in the user's post-push checklist, not in a claim made here.

## Notes for the AI

- Keep the workflow minimal and boring: no reusable workflows, composite
  actions, matrices or custom scripts. Three jobs, plain `run:` steps.
- Do not use `-pl` in the java job; the whole reactor is the point. If it is
  slower than expected, that is not a reason to skip Testcontainers tests.
- Stale project text (the overview says only `frontend/` exists) is not ours to
  fix here.
- Commit and PR text must carry no AI attribution.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":6632,"specSha256":"d2e7fd81525614281e24186fb7c7d3ba4ec6f0c4dbbc9a0cf946e26bb0723ccd","branch":"refs/heads/feature/ci-pipeline","head":"026092bc3e1bb8a30325e1e906edbee28d1a3957","baseRef":"refs/heads/master","baseCommit":"026092bc3e1bb8a30325e1e906edbee28d1a3957","sourceTree":"489cf922ef475c33b389a84360c5a9f1a3fe1f08","absentOptional":[]} -->
