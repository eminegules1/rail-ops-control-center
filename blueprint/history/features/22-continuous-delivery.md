# Feature: Continuous delivery

**From build-plan:** feature 22
**Build attempt:** 1
**Status:** verified
**Branch:** feature/continuous-delivery

## Goal

Every push to `main` (or `master`, like the existing CI trigger) that passes the CI jobs builds the three app
images (`producer`, `backend`, `frontend`) and pushes them to GitHub Container Registry (GHCR), so a green
commit always has pullable images. Pull requests never publish.

## In scope

- One new `publish` job in `.github/workflows/ci.yml` (no second workflow file). It needs `java`, `frontend`
  and `docker`, so images are only pushed when the same commit passed all CI jobs.
- Runs only for `push` events. The `if:` covers `main` and `master`, matching the workflow's `push` trigger.
- Matrix of three images, each built with the same context and Dockerfile as `docker-compose.yml`:

  | Image | Context | Dockerfile |
  |---|---|---|
  | `ghcr.io/<owner>/<repo>-producer` | `.` | `producer/Dockerfile` |
  | `ghcr.io/<owner>/<repo>-backend` | `.` | `backend/Dockerfile` |
  | `ghcr.io/<owner>/<repo>-frontend` | `frontend` | `frontend/Dockerfile` |

  Image names are built from `github.repository` and lowercased by `docker/metadata-action`.
- Tags per image: `latest` and `sha-<short-sha>`. Labels come from `docker/metadata-action` (this includes
  `org.opencontainers.image.source`, which links the package to the repository).
- Auth with the built-in `GITHUB_TOKEN`; the job alone declares `permissions: contents: read, packages: write`.
  The workflow-level `contents: read` stays.
- `linux/amd64` only; `provenance: false` so GHCR shows one clean manifest per tag.
- Change the workflow `concurrency` to `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` so a
  newer push to `main` cannot cancel a publish halfway and leave the three `latest` tags out of step.
- README: extend the Continuous integration section with the publish job, image names, tags, a `docker pull`
  example, and the one-time package visibility note (new GHCR packages are private until changed in the
  package settings). Update the "Not built" limitation so it no longer implies images are not published.

## Out of scope

- Deploying anywhere (continuous *deployment*), Kubernetes or Helm (feature 23).
- Changing `docker-compose.yml` to pull registry images; the stack keeps building locally.
- Semver or release tags, multi-arch builds, image signing, SBOM or provenance attestations, vulnerability
  scans, build-cache configuration, deleting old images.
- Publishing from pull requests or forks, extra secrets, PATs, or changes to repository or package settings.
- Editing `blueprint/context/project-overview.md` or the user-owned plans.

## Build loop

`workflow.stepReview` is `feature`: implement all steps, then present one review packet. No step checkpoint
commits (`checkpointCommits: disabled`). `/complete` creates the final feature commit.

## Build steps

- [x] 1. **Add the publish job to ci.yml.** Add `publish` (matrix of three images, `docker/login-action`,
  `docker/metadata-action`, `docker/build-push-action`), the job-level permissions, the push-only `if:` and
  the conditional `cancel-in-progress`. Leave `java`, `frontend` and `docker` jobs untouched. Use the action
  major versions the current docs show (`login-action@v4`, `metadata-action@v6`, `build-push-action@v7`;
  confirm they exist when writing) and pin nothing else new.
  *Done when:* `actionlint` reports no problems (`docker run --rm -v "${PWD}:/repo" -w /repo rhysd/actionlint`,
  prefix `MSYS_NO_PATHCONV=1` in Git Bash); `git diff` shows the existing three jobs unchanged; `docker compose
  build` still succeeds, which proves the three context/Dockerfile pairs used by the matrix.
- [x] 2. **Document delivery in the README.** Update the Continuous integration section as listed in scope and
  the "Not built" bullet under Known limitations, in the README's own voice and without claiming a run that
  has not happened.
  *Done when:* the table and text match the workflow (job name, image names, tags, triggers); every anchor
  and link the edit adds or changes resolves; no text says images were verified on GitHub.

## Files / areas

- `.github/workflows/ci.yml` (extend)
- `README.md` (Continuous integration section; Known limitations "Not built" bullet)
- `blueprint/history/features/22-continuous-delivery.md` is created by `/complete` (path verified free; branch
  name verified free).

## Data / contracts

- Registry: `ghcr.io`, image `ghcr.io/<lowercased owner>/<lowercased repo>-<module>`, tags `latest` and
  `sha-<7-char sha>` (metadata-action default short format).
- Triggers unchanged: `pull_request` and `push` on `main`/`master`. The publish job's condition is
  `github.event_name == 'push'`; PR runs skip it.
- Trust boundary: `packages: write` exists only on the publish job, which never runs for pull requests, so
  PR code (including forks) cannot push. The token is the automatic `GITHUB_TOKEN`; no new secrets.
- No application, API or data changes.

## Testing

- No application logic changes, so no unit tests apply and no Verify command exists.
- Static check: `actionlint` on `ci.yml` (step 1).
- Local build check: `docker compose build` (step 1).
- **Not verifiable here:** the repository has no git remote, so the real login, push and GHCR result cannot be
  run in this session. The review handoff must say so. First-push confirmation (Actions run is green, three
  packages appear with `latest` and `sha-…` tags) is left to the user after pushing.

## Notes for the AI

- Extend `ci.yml`; do not add `cd.yml`. Keep the file's style (two-space YAML, `timeout-minutes`, `actions/checkout@v7`).
- Image source-of-truth is `docker-compose.yml`'s build context and Dockerfile for each service. Do not
  duplicate Dockerfile logic in the workflow.
- Keep the README free of the actual owner name; use `<owner>/<repo>` placeholders.
- Do not push, create a remote, or change GitHub settings.

## Open questions

None blocking. Assumptions recorded above, changeable at review: trigger on `main` and `master` to match CI
(the plan only says `main`), flat image names (`<repo>-backend`) instead of nested paths, `latest` plus `sha-`
tags only.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":6233,"specSha256":"5c5f3846076a1098c467a2033edbb562581ba512123eac956457275ae11005e3","branch":"refs/heads/feature/continuous-delivery","head":"aa94f92d923280f3168dec4f3e751b5280f7d76c","baseRef":"refs/heads/master","baseCommit":"0c3f8fb7509c92aabd5976596a75b5324eaf8774","sourceTree":"7ea2ca58e6568a7855a9ab385dcc30b1dc812ad0","absentOptional":[]} -->

## Findings

### 22/F-04 [P3] closed - Cross-origin handshake rejection on /ws has no automated test

**File:** backend/src/main/java/com/railops/backend/WebSocketConfig.java:40
**Found:** 2026-09-28 by /audit independent (scope: current; lens: tests, security)
**Why it matters:** The spec relies on Spring's default same-origin handshake
check as the only browser-facing guard on `/ws` (no auth in the MVP). No
backend test sends a handshake with a foreign `Origin`; `LiveUpdatesIntegrationTest`
connects without any `Origin`, which Spring always accepts. A later
`setAllowedOrigins("*")` or `setAllowedOriginPatterns("*")` would keep every
test green. The only evidence is the manual curl check in step 4.
**Suggested fix:** In `LiveUpdatesIntegrationTest`, connect once with
`WebSocketHttpHeaders` carrying `Origin: http://evil.example` and assert the
handshake fails (403), and optionally once with `Origin: http://localhost:<port>`
and assert it succeeds. No production change.
**Resolution:** Fixed by fix `test-cross-origin-rejection-on-the-websocket-endpoint`. `LiveUpdatesIntegrationTest.refusesHandshakeFromAnotherOrigin` connects with `Origin: http://evil.example` and requires the handshake to fail with 403; `acceptsHandshakeFromTheServersOwnOrigin` connects with the server's own origin. Widening the endpoint to `setAllowedOriginPatterns("*")` makes the first test fail. Awaiting re-review.

Closed 2026-09-30 by /audit (scope: full; all lenses). `WebSocketConfig.registerStompEndpoints` still uses the default same-origin check, both tests exist at `LiveUpdatesIntegrationTest.java:145-154`, and they passed in the full `mvn -B verify` run (289 backend tests, 0 failures).

## Independent review

**Status:** passed
**Target commit:** aa94f92d923280f3168dec4f3e751b5280f7d76c
**Base commit:** 0c3f8fb7509c92aabd5976596a75b5324eaf8774
**Base ref:** master
**Spec hash:** 5c5f3846076a1098c467a2033edbb562581ba512123eac956457275ae11005e3
**Prepared by:** claude
**Builder model:** claude-sonnet-5-5
**Requested reviewer:** claude
**Requested model:** claude-opus-5-5
**Requested execution:** automatic
**Requested at:** 2026-09-30T18:01:02Z
**Workflow:** regular
**Check required:** no
**Reviewer adapter:** claude
**Reviewer model:** claude-opus-5-5
**Reviewer context:** fresh subagent
**Actual execution:** automatic
**Reviewed at:** 2026-09-30T18:04:04Z
**Scope:** current
**Lenses:** quality, security, performance, tests
**Verdict:** passed
**Check result:** not-required

### Commands

- `git rev-parse HEAD`: pass (equals Target commit)
- `git merge-base master HEAD`: pass (equals Base commit; local `master` exists, no remote)
- `sha256sum blueprint/context/current-feature.md`: pass (equals Spec hash; spec is tracked and unchanged from target)
- `git status --porcelain=v1 --untracked-files=all`: pass (only `blueprint/context/review.md` modified before review)
- `git diff 0c3f8fb..aa94f92`: reviewed in full (`.github/workflows/ci.yml`, `README.md`, `blueprint/context/current-feature.md`; 3 files, +182/-11)
- `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint -no-color -verbose` (actionlint 1.7.12, local image): pass, 0 errors in `.github/workflows/ci.yml`
- `docker compose build`: pass (rail-ops-producer, rail-ops-backend and rail-ops-frontend built)

### Evidence

- The delta changes only `concurrency.cancel-in-progress` and appends the `publish` job; the `java`, `frontend` and `docker` jobs are byte-for-byte unchanged.
- Trust boundary: the workflow triggers only on `pull_request` and on `push` to `main`/`master` (a branches-only filter, so tag pushes do not trigger). `publish` has `if: github.event_name == 'push'`, so PR runs, including fork PRs, skip it. No `pull_request_target` or `workflow_run` trigger exists. `packages: write` is declared only at job level on `publish`; the workflow default stays `contents: read`. Auth uses `secrets.GITHUB_TOKEN` with `github.actor`; no new secrets or PATs.
- `needs: [java, frontend, docker]` with the implicit `success()` condition means a failed or skipped check job skips publishing.
- The matrix pairs match `docker-compose.yml`: producer and backend use context `.` with `<module>/Dockerfile`; frontend uses context `frontend` with the default `frontend/Dockerfile`. `file:` is resolved from the workspace root, which is correct for `frontend/Dockerfile`.
- No token leaks into the image: `actions/checkout` persists the token in `.git/config`, but the root `.dockerignore` is an allowlist (`*` plus `pom.xml`, `producer/`, `backend/`), and the frontend context does not contain the repository `.git`. The frontend final stage copies only `dist` and `nginx.conf`.
- Tags come from `type=raw,value=latest` and `type=sha` (metadata-action default `sha-` prefix, short format); labels come from `steps.meta.outputs.labels`; `provenance: false` is set. The image is `ghcr.io/${{ github.repository }}-<module>`, and metadata-action lowercases it.
- `linux/amd64` is implicit: no `platforms` input, and the build runs natively on the x64 `ubuntu-latest` runner with the default builder (no `setup-buildx-action`, and none of the features that need it).
- Concurrency: pushes no longer cancel an in-progress run. GitHub still replaces an older pending run in the same group with the newest one, so the final `latest` tags come from the newest queued push. PR runs still cancel in progress.
- README: the CI table, the Publishing images section, the tags and the `docker pull` example match the workflow; it uses `<owner>/<repo>` placeholders, adds no links or anchors, and claims no GitHub run or GHCR verification. The one mismatch is recorded as F-22.
- Tests lens: no application logic changed and no test files changed. actionlint and `docker compose build` are the Done-when signals, and both pass.
- Performance lens: `publish` rebuilds the images that the `docker` job has already built. Build cache is explicitly out of scope in the spec, so this is not a finding.

### Findings

- F-22 [P3] open - README Known limitations says images publish on `main` only (README.md:1099)

### Remaining risk

- No git remote exists, so the workflow never ran on GitHub: the real `docker/login-action` login, the GHCR push, package creation, package linking through the source label, and the `latest`/`sha-` tags are unverified until the first push to `main`/`master`.
- The versions `docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7` (and the existing `actions/checkout@v7`) were not checked against the GitHub Marketplace or registry. actionlint passing does not prove that a tag exists.
- The GHCR default package visibility (private) and metadata-action lowercasing were checked against the reviewer's knowledge, not against current docs, because the review used no network.
- Actions are pinned to major tags, not commit SHAs, matching the project's existing style. A compromised upstream tag would receive the job's `packages: write` token.
- A partial matrix failure (`fail-fast: false`) can still leave the three `latest` tags on different commits for that run. The run would be red, and the next green push realigns them.
- No `Verify` command or `/check` was run (Check not required). No browser or runtime flow applies to this change.
