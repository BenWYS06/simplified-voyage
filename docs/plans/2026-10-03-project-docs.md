# Project docs and Claude Code setup, complete

**Goal:** after the booking refactor, bring the docs and the Claude Code setup up to the level of a team project: a newcomer can run the system and make a change from the docs alone, and every kind of work (feature, bug, new service, QC, performance) has an agent or skill and a place to record it.

**Scope:** root docs, `docs/`, per-service docs, `.claude/`. **Out of scope:** application code, CI, deployment.
**Contract changes:** none.

## Tasks

### Root
- [x] 1. `ONBOARDING.md`: tools, clone, two ways to run, first login, what to read before changing what, first PR.
- [x] 2. `SECURITY.md`: reporting, how the system is protected, known limitations.
- [x] 3. `TODO.md`: open work by area, including the gaps found while writing these docs (frontend login still targets the original `/auth/*` endpoints).

### docs/
- [x] 4. `roadmap.md`: milestones with real dates, linked to specs, plans and PROGRESS.
- [x] 5. `environments.md`: config layering, ways to run, every env variable, local endpoints.
- [x] 6. `api-spec.md`: every endpoint generated from the controllers with its access rule, booking flow statuses, Kafka topics.
- [x] 7. `observability.md`: what can be seen today, how to debug one booking, what the observability plan adds.
- [x] 8. `runbooks/local-stack.md`, `runbooks/troubleshooting.md` (from problems recorded in PROGRESS).
- [x] 9. `specs/`: index + the designs that were agreed but only lived in PROGRESS (first refactor design, Flyway, VNPay), with their later changes.
- [x] 10. `handoff/`: index + the first session handoff.
- [x] 11. Plans for past work that had none (AI workflow setup, subscription removal) and this one; index updated.

### Per service
- [x] 12. `README.md` in each service and the gateway: role, endpoints, data, events, dependencies, config, how to run.
- [x] 13. `CLAUDE.md` for seat-service, payment-service and the frontend.

### Claude Code
- [x] 14. Agents for the full feature loop: `feature-planner`, `backend-dev`, `web-dev`, `qa-agent`; and `perf-reviewer` for the observability plan.
- [x] 15. Skills: `fix-issue`, `session-handoff`, `qc-check`, `new-service`; `add-feature` uses the new agents and writes a spec for larger features.
- [x] 16. Rules: `clean-code`, `security`, `testing`.
- [x] 17. `CLAUDE.md`, `AI_WORKFLOW.md`, `CONTRIBUTING.md`, README and CHANGELOG point to the new docs.

## Verification

- Every link in the new and changed files resolves to a file in the repo.
- Facts are checked against the code: ports and env variables against `docker/` and `application.yaml`, endpoints and roles against the controllers and the gateway `SecurityConfig`, method names against the repositories.
- No secret values in any new file.
