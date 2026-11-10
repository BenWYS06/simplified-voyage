# CI/CD pipeline (GitHub Actions)

**Goal:** every PR shows one check per part of the system it touches (each core service, the frontend), those checks gate the merge into `main`, and `main` produces versioned images. Each stage goes in as soon as the code it checks is on `main`.

**Scope:** `.github/workflows/`, `.github/dependabot.yml`, `README.md` (badges), `TODO.md`, `docs/decisions.md` (ADR-013).
**Out of scope:** deploying to a server or cloud environment (no environment exists yet); fixing the ESLint errors is its own task (stage 7).
**Contract changes:** none.

## Rules

- A job only goes into CI when it passes on `main`. A red check on every PR teaches everyone to ignore it.
- Rules are not switched off to make a check pass; the errors get fixed.
- Every check that exists is required in branch protection, so a workflow must always report (see the layout below).

## Layout

| Workflow | What it checks | Tests |
|---|---|---|
| `_service-ci.yml` | Reusable: detects whether the PR touches the modules, then `mvn verify -pl <modules> -am` | — |
| `user-service`, `airline-core-service`, `flight-ops-service`, `seat-service`, `booking-service`, `payment-service`, `notification-service`, `api-gateway` | One per core service | Unit, context, concurrency (Testcontainers) |
| `backend-shared` | `common-lib`, service-registry, config-server; location, pricing, ancillary | Tests for the first three; the last three compile only |
| `secret-scan` | gitleaks on new commits; manual run scans all history | — |
| `frontend` (stage 6) | `npm ci`, lint, build | — |
| `docker` (stage 8) | Jib images of every service | — |
| `release` (stage 9) | GitHub Release on a `v*` tag | — |

**Why one workflow per service, always running:** GitHub's path filters on the trigger would leave a required check "waiting" forever on a PR that doesn't touch that service. Instead every workflow starts, `dorny/paths-filter` decides, and the build job is skipped when nothing it uses changed. GitHub counts a skipped job as passed. A change to `common-lib` or a parent `pom.xml` runs every service, which is correct: they all depend on it. Pushes to `main` always build everything.

## Stages

- [x] **1. Backend compile:** one `backend.yml`, Java 17 (Temurin), Maven cache. Skipped booking and payment until their producers existed. Also ran the tests that need no database.
- [x] **1a. Secret scan:** `secret-scan.yml`, gitleaks on every PR and push to `main`.
- [x] **1b. Compile every module:** `-pl` filter removed in the build step 6 PR (#26).
- [x] **2. Testcontainers tests:** airline-core and flight-ops context tests (#25), then the seat, booking and payment concurrency tests (#28). Testcontainers works on `ubuntu-latest`.
- [ ] **3. Core service unit tests:** each core service's tests run in CI as they land ([plan](2026-11-05-core-service-tests.md)). Done: gateway, user, airline-core, flight-ops, notification, seat. Left: booking, payment (no CI change needed, their workflows pick them up).
- [x] **4. One workflow per service:** `backend.yml` replaced by the layout above. README shows a badge per workflow.
- [ ] **5. Branch protection** (owner, GitHub settings): rule on `main`: require a PR, require the checks `<service> / Build and test` for every workflow above plus `Secret scan (gitleaks)`, require branches to be up to date. Each new workflow is added to the list in the PR that brings it.
- [ ] **6. Frontend build:** `frontend.yml`, Node 22 with npm cache, `npm ci` + `npm run build` from `frontend/`, same always-run pattern. Added when the frontend on `main` is complete (routes and pages), so the build has every import it needs.
- [ ] **7. Frontend lint:** fix the ESLint errors (unused code removed, `vite.config.js` gets the Node globals), then add `npm run lint` to the frontend job.
- [ ] **8. Docker images (CD):** `docker.yml` after the docker stack PR. On PRs that touch a service: build its image without pushing. On `main` and on `v*` tags: `mvn jib:build` for every service to `ghcr.io/benwys06/<service>` tagged with the short commit SHA (and the version on a tag), using the built-in `GITHUB_TOKEN`. Jib is already configured in each `pom.xml`, so no Dockerfiles.
- [ ] **9. Release:** `release.yml` on a `v*` tag: a GitHub Release with that version's notes from `CHANGELOG.md`, linking the images from stage 8. First use: `v0.1.0`.
- [ ] **10. Dependency updates:** `.github/dependabot.yml`, weekly, grouped per ecosystem (GitHub Actions, Maven, npm), after `v0.1.0` so the PR flow is not flooded while the code is still landing.

## Verification

- Stages 1–2: green on their PRs and on `main` (#21, #25, #26, #28).
- Stage 4: every workflow file and the expanded change filters parse; every Maven module is covered by exactly one workflow; the PR that brings them shows all ten checks and, because it changes `_service-ci.yml`, builds every module. The next PR that touches one service builds only that one.
- Each later stage: the job passes on its own PR before it is required.

## Result

Stage 1 written 2026-9-26, changed 2026-9-28 to start before the backend was complete. Merged in PR #21; green on that PR, on #22 and on `main`. Stage 4 written 2026-11-10, after the whole backend reached `main`.
