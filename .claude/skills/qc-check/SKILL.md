---
name: qc-check
description: Full-project quality check before a release or a big new feature - compile every module, run the unit and Testcontainers tests, build and lint the frontend, scan config and docs for drift, and report a pass/fail table. Use when the user asks for "QC", "check everything", "is the project healthy", or before tagging a version.
---

# Full-project QC

Run the phases in order. Stop at the first phase that fails, fix it (or report it), then continue.

## Phase 1: Static build

```bash
cd backend && mvn -q clean compile          # all modules (clean avoids stale IDE-compiled classes)
cd frontend && npm run build && npm run lint
```
Lint errors that already existed are listed in the report, not fixed here.

## Phase 2: Tests

Needs Docker running (Testcontainers starts real MySQL / PostgreSQL).

```bash
cd backend
mvn test -am -pl services/seat-service,services/booking-service,services/payment-service -Dsurefire.failIfNoSpecifiedTests=false
mvn test -pl services/user-service
```
`-am` matters: without it Maven uses an old `common-lib` jar from `~/.m2`.
Expected now: seat 21, booking 16, payment 6 concurrency tests pass.

## Phase 3: Drift scan

- Every service in `backend/services/pom.xml` has a `config-repo/<service>.yml`, a compose entry in `docker/docker-compose.yml`, a row in the `CLAUDE.md` services table and a `README.md`.
- Every entity change has a matching migration (look at the latest `V{n}` per service vs the entity).
- `docker/.env.example` has every env var the compose file reads, with placeholder values only.
- `docs/api-spec.md` lists every controller path (`@RequestMapping` + method mappings).
- No secrets in tracked files: `git grep -nE "(password|secret|token)\s*[:=]\s*[^$<{ ]" -- config-repo docker backend/**/application*.y*ml`.

## Phase 4: Smoke run (only if the user asks)

```bash
docker compose -f docker/docker-compose.yml up -d
curl http://localhost:5000/actuator/health
```
Then the booking flow from `docs/runbooks/local-stack.md`.

## Report

```markdown
## QC report: YYYY-MM-DD
| Phase | Result | Notes |
|---|---|---|
| Backend compile | PASS / FAIL | |
| Frontend build + lint | PASS / FAIL | new lint errors: n |
| Tests | PASS / FAIL / SKIPPED (no Docker) | counts |
| Drift scan | PASS / FAIL | items |
**Blocking:** ... **To fix later:** ...
```
Record the result in `docs/booking-refactor/PROGRESS.md` (refactor) or the handoff file.
