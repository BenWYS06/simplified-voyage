# CI/CD pipeline (GitHub Actions)

**Goal:** every PR shows one check per part of the system it touches (each core service, the frontend), those checks gate the merge into `main`, and `main` produces versioned images. Each stage goes in as soon as the code it checks is on `main`.

**Scope:** `.github/workflows/`, `.github/dependabot.yml`, `README.md` (badges), `TODO.md`, `docs/decisions.md` (ADR-013).
**Out of scope:** deploying to a server or cloud environment (no environment exists yet); fixing the ESLint errors is its own task (stage 7).
**Contract changes:** none.

## Rules

- A job only goes into CI when it passes on `main`. A red check on every PR teaches everyone to ignore it.
- Rules are not switched off to make a check pass; the errors get fixed.
- One plain workflow file per service, same template everywhere: path-filtered triggers, checkout, JDK, `mvn verify`, test reports on failure. No reusable workflows or extra actions.

## Layout

| Workflow | What it checks | Tests |
|---|---|---|
| `user-service`, `airline-core-service`, `flight-ops-service`, `seat-service`, `booking-service`, `payment-service`, `notification-service`, `api-gateway` | One per core service: `mvn verify -pl <service> -am` | Unit, context, concurrency (Testcontainers) |
| `backend-shared` | `common-lib`, service-registry, config-server; location, pricing, ancillary | Tests for the first three; the last three compile only |
| `secret-scan` | gitleaks on new commits; manual run scans all history | — |
| `frontend` (stage 6) | `npm ci`, lint, build | — |
| `docker` job (stage 8) | Added to each service workflow: its Jib image | — |
| `release` (stage 9) | GitHub Release on a `v*` tag | — |

**Triggers:** a workflow runs when its service, `common-lib` or a parent `pom.xml` changes, so a `common-lib` change runs every service (they all depend on it) and a seat-only PR runs only seat. `-am` builds `common-lib` too, so a stale jar is never used.

## Stages

- [x] **1. Backend compile:** one `backend.yml`, Java 17 (Temurin), Maven cache. Skipped booking and payment until their producers existed. Also ran the tests that need no database.
- [x] **1a. Secret scan:** `secret-scan.yml`, gitleaks on every PR and push to `main`.
- [x] **1b. Compile every module:** `-pl` filter removed in the build step 6 PR (#26).
- [x] **2. Testcontainers tests:** airline-core and flight-ops context tests (#25), then the seat, booking and payment concurrency tests (#28). Testcontainers works on `ubuntu-latest`.
- [x] **3. Core service unit tests:** each core service's tests run in its own workflow ([plan](2026-11-05-core-service-tests.md)): gateway, user, airline-core, flight-ops, notification, seat, payment, booking, all on `main` by #33.
- [x] **4. One workflow per service:** `backend.yml` replaced by the layout above. README shows a badge per workflow.
- [ ] **5. Branch protection** (owner, GitHub settings): rule on `main`: require a pull request and require the `Secret scan (gitleaks)` check, which runs on every PR. The service checks are not marked required: with path filters a PR that doesn't touch a service never runs its check, and GitHub would wait for it forever. A red service check is still visible on the PR and is not merged.
- [ ] **6. Frontend build:** `frontend.yml`, Node 22 with npm cache, `npm ci` + `npm run build` from `frontend/`, same template, triggered on `frontend/**`. Added when the frontend on `main` is complete (routes and pages), so the build has every import it needs.
- [ ] **7. Frontend lint:** fix the ESLint errors (unused code removed, `vite.config.js` gets the Node globals), then add `npm run lint` to the frontend job.
- [x] **8. Docker images (CD):** each service workflow has a `docker` job after `verify` (`backend-shared` runs it as a matrix for service-registry, config-server, location, pricing and ancillary). On `main`: `mvn jib:build` pushes `ghcr.io/benwys06/<service>` tagged with the commit SHA and `latest`, using the built-in `GITHUB_TOKEN`. On a PR: `jib:buildTar`, so a broken image config fails the PR without pushing anything. Jib is configured in each `pom.xml`, so there are no Dockerfiles.
- [ ] **9. Release:** `release.yml` on a `v*` tag: a GitHub Release with that version's notes from `CHANGELOG.md`, linking the images from stage 8. First use: `v0.1.0`.
- [ ] **10. Dependency updates:** `.github/dependabot.yml`, weekly, grouped per ecosystem (GitHub Actions, Maven, npm), after `v0.1.0` so the PR flow is not flooded while the code is still landing.

## Verification

- Stages 1–2: green on their PRs and on `main` (#21, #25, #26, #28).
- Stage 4: every workflow file parses; every Maven module is in exactly one workflow; the PR that brings them changes every workflow file, so all nine backend workflows run on it.
- Each later stage: the job passes on its own PR before it is required.

## Result

Stage 1 written 2026-9-26, changed 2026-9-28 to start before the backend was complete. Merged in PR #21; green on that PR, on #22 and on `main`. Stage 4 written 2026-11-10, after the whole backend reached `main`. Stage 8 written 2026-11-13 with the docker stack: the PR path (`jib:buildTar` with the GHCR name) checked locally on seat-service; the push path runs for the first time on `main` after merge.
