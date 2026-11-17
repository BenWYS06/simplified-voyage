# Feature plans: status index

> One plan per feature, written with `/add-feature` before any code: `YYYY-MM-DD-<feature-name>.md`. Larger features first get a design in [`../specs/`](../specs/README.md).
> This table is the status of record. The booking saga refactor has its own plan in [`../booking-refactor/`](../booking-refactor/PLAN.md).

| Plan | Goal | Status |
|---|---|---|
| [2026-11-05-core-service-tests](2026-11-05-core-service-tests.md) | Unit tests for the 8 core services, merged as each service completes on `main`, run by CI | DONE |
| [2026-11-04-ci-pipeline](2026-11-04-ci-pipeline.md) | GitHub Actions in stages: backend compile, frontend build, lint, tests, branch protection | IN PROGRESS |
| [2026-11-03-project-docs](2026-11-03-project-docs.md) | Onboarding, security, API reference, environments, runbooks, specs, handoffs, per-service docs; full set of Claude Code agents and skills | DONE |
| [2026-10-31-observability-and-performance](2026-10-31-observability-and-performance.md) | Trace every hop of a booking, load-test the main APIs, fix the measured bottlenecks with before/after numbers | PENDING |
| [2026-10-30-remove-subscription-service](2026-10-30-remove-subscription-service.md) | Drop the unused subscription service (ADR-011) | DONE |
| [2026-10-23-ai-workflow-setup](2026-10-23-ai-workflow-setup.md) | CLAUDE.md, rules, agents, skills, contributor docs for the AI-assisted workflow | DONE |
| [Booking saga refactor](../booking-refactor/PLAN.md) | Idempotency, row locks, saga, outbox, concurrency tests (build steps 1–10) | DONE |

Statuses: **PENDING** (planned, approved, not started) · **IN PROGRESS** · **DONE** (merged into `main`) · **DROPPED** (with reason).
