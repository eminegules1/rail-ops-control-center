# Coding Standards

> Tuned by `/onboard` for this project. Frontend conventions reflect the Vite
> scaffold that exists today; backend and producer conventions reflect the stack
> chosen in `blueprint/project-plan.md` and apply once those modules exist.

## Project Structure

- Frontend: React 19 + TypeScript + Vite SPA in `frontend/`, with its own
  `package.json`, lockfile, and ESLint config. Paths below are relative to it.
- Planned: Maven multi-module repo with a root `pom.xml` listing `backend/` and
  `producer/` (Spring Boot), plus `docs/`, `docker-compose.yml`, `.env.example`.
  The frontend stays outside the Maven build.

## TypeScript (frontend)

- Strict mode, plus `noUnusedLocals`, `noUnusedParameters`,
  `verbatimModuleSyntax`, and `erasableSyntaxOnly` (no enums or namespaces; use
  union types and `as const` objects)
- No `any` types - use proper typing or `unknown`
- Define types for props, API DTOs, and WebSocket messages
- Use type inference where obvious, explicit types where helpful

## React (frontend)

- Functional components and hooks only
- Keep components focused - one job per component; extract reusable logic into
  custom hooks
- Routing with React Router: Dashboard, Events, Services pages
- Server state lives in TanStack Query; WebSocket messages patch the query cache
  instead of keeping a parallel store
- Real-time via `@stomp/stompjs` over `/ws`
- UI components from MUI, charts with Recharts
- Product name "Alstom Rail Operations Control Center"; the top bar and tab
  title use the short label "Rail Ops Control Center"
- Severity and health colors come from one shared mapping, never hard-coded per
  component

## Frontend File Organization

> TODO: confirm once the first UI feature lands. Suggested:

- Pages: `src/pages/PageName.tsx`
- Components: `src/components/[feature]/ComponentName.tsx`
- API client and query hooks: `src/api/[resource].ts`
- Types: `src/types/[feature].ts`
- Utils: `src/lib/[utility].ts`

## Naming

- Components: PascalCase (`EventTable.tsx`)
- TS files: match component name or camelCase/kebab-case
- Functions and variables: camelCase
- Constants: SCREAMING_SNAKE_CASE
- Types: PascalCase (no prefix)
- Java: packages lowercase, classes PascalCase, DTOs as `record`s

## Styling (frontend)

- MUI theme and `sx` props for component styling; plain CSS only for global
  resets
- Control-room look: dense but calm; loading skeletons, empty states, error toasts
- Dark mode is nice to have, not required

## Backend (Java 21, Spring Boot 3)

- Layering: controller -> service -> repository; controllers stay thin and speak
  DTO records, never JPA entities
- Constructor injection only; configuration via `@ConfigurationProperties` bound
  to env vars
- PostgreSQL is the source of truth; schema changes only through Flyway
  migrations
- Redis holds derived live state; key design follows `project-plan.md` section 4.
  Every Redis failure path must degrade to Postgres, not break the API
- Kafka: topics and partition counts declared in code from config, manual
  acknowledgement, idempotent processing by `eventId`

## Validation and Error Handling

- Validate Kafka payloads and request bodies with Bean Validation
- Invalid or undeserializable messages are non-retryable and go to the DLT;
  transient failures retry with exponential backoff
- REST errors return RFC 7807 `ProblemDetail` through a single
  `@RestControllerAdvice`
- Frontend: surface API errors as user-friendly toasts; optimistic updates roll
  back on failure
- Structured JSON logs with `eventId` in the MDC; never log secrets

## Testing

The blueprint installs no test runner; testing is opt-in at the project level,
because the overlay can't know your stack. Adding unit testing is an explicit
setup task the AI can do through the normal workflow, either as a build-plan item
or with `/tests`. The setup should choose the stack-native runner, wire the
scripts or commands, add a small example test, and update the Commands section
of `AGENTS.md`.

When `AGENTS.md` declares a `Verify` command, treat it as the umbrella automated
gate. It combines only the checks this project actually has, in this order when
available: typecheck, tests, then build. The command does not enable an absent
test runner or replace focused evidence. It gives local work and optional CI one
exact command to run. `/ci` owns Verify and CI setup. `/tests` adds the real test
command to Verify when it already exists, but never creates CI only because
testing was configured.

**The opt-in switch is one signal: a `test` command in the Commands section of
`AGENTS.md`.** Declare one and **tests become a gate for logic-bearing steps**,
not an optional extra; leave it out and the loop verifies logic with the evidence
it already uses (run it, a screenshot, the build). Adding the runner is itself a
deliberate step, never a silent mid-step install. This is the single definition
of the switch; the skills and `ai-interaction.md` only point back here.

- **What to test (the scope rule):** pure logic where a wrong answer is possible -
  parsers, formatters, validators, id/slug builders, server actions. These have
  assertable inputs and outputs and real edge cases (empty, missing, malformed).
- **What not to test:** UI components and integration-level surfaces (render or
  export routes, anything driving a real browser or external service). Verify those
  with a screenshot and the build, not brittle unit tests.
- **The gate (when a runner is configured):** a build step that adds in-scope logic
  must ship a passing test in the same reviewable diff. The project's test command
  must be green before the step is approved, before any checkpoint commit, and
  before `/complete` merges. UI and integration-only steps are exempt and ride on
  screenshot plus build evidence.
- **When it's named:** the `/feature` spec's Testing section predicts the coverage,
  `/implement` writes the test with the step, and if a step surfaces logic the spec
  didn't foresee, add a focused test then.
- An empty suite should fail, not pass, so "no tests ran" never looks like "passed".
- Test files live next to source files (for example `feature.test.ts`).
- Run them via the project's test command (see Commands in `AGENTS.md`), not a
  hardcoded tool name.

Stack binding (planned, not yet configured): the frontend uses Vitest + React
Testing Library, with `vi.mock()` for the API client and STOMP connection and
`vi.useFakeTimers()` for debounce, throttle, and polling logic. The backend and
producer use JUnit 5 + Mockito for unit tests and Testcontainers (Kafka, Redis,
Postgres) for the happy-path and DLT integration tests, run through
`mvn verify`. The project plan explicitly asks for these tests, so they are in
scope once the runners exist.

## Browser Verification

For UI and integration behavior, prefer real browser evidence over reading the
code and assuming it works.

- Browser automation is separately opt-in through `/tests browser`. That setup
  reuses a compatible runner or prefers Playwright for supported projects, then
  documents the exact command as `Browser tests` in `AGENTS.md`.
- When `Browser tests` is declared, add focused coverage for stable behavioral
  done-whens when it is proportionate, and run the documented command during
  `/check`. Do not assume it proves visual fidelity, real authenticated-profile
  behavior, browser chrome, or another claim the test does not observe.
- If no Browser tests command is declared, do not add a runner silently in the
  middle of an unrelated feature. Use the available dev server, browser
  screenshots, build output, API output, or manual evidence instead.
- Browser tests are not part of the default Verify command or CI unless the user
  separately chooses that slower gate.
- Browser evidence is especially important for flows that click, type, submit,
  navigate, download files, render complex layouts, or depend on client-side
  state.

## Code Quality

- No commented-out code unless specified
- No unused imports or variables
- Keep functions under 50 lines when possible

## Comments

Write code that explains itself; comment only what the code cannot say.
Over-commenting is a common AI tell, so resist it.

- Comment the **why**, not the **what**. Delete any comment that restates the code.
- No banner/header blocks, section dividers, or step-by-step narration of obvious
  code. A file does not need a comment announcing each region.
- A comment earns its place only when it captures something the code can't: a
  non-obvious decision, a gotcha or workaround, why a value is what it is, or a
  link to a spec or issue.
- Prefer self-documenting names and small functions over explanatory comments.
- Keep doc comments minimal: a one-line purpose on an exported type or function is
  plenty; don't write JSDoc that just repeats the signature.
- When in doubt, leave the comment out.

## Writing

- No em dashes (U+2014) in generated content: docs, comments, commit messages,
  READMEs, specs. They read as AI-generated.
- Use a hyphen for `term - description` separators; rephrase prose with commas,
  parentheses, or a colon. Avoid en dashes and the ellipsis character too.
