# Changelog

All notable changes to this project. One version per milestone merged into `main`.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## Unreleased

### Added
- **Booking saga refactor** (build steps 2–10 in [LEARNING-PATH.md](docs/booking-refactor/LEARNING-PATH.md)): seat hold in one atomic UPDATE, idempotent create through a partial unique index, saga completion and compensation with an expiry job, Feign timeouts and retry, transactional outbox with Kafka retry and DLT, VNPay return handling for user cancel, notification dedupe, frontend handling of 202 / 409 / reused bookings, concurrency tests on Testcontainers.
- **Project docs:** [ONBOARDING](ONBOARDING.md), [SECURITY](SECURITY.md), [TODO](TODO.md), [roadmap](docs/roadmap.md), [API reference](docs/api-spec.md), [environments](docs/environments.md), [observability](docs/observability.md), [runbooks](docs/runbooks/local-stack.md), [specs](docs/specs/README.md), [handoffs](docs/handoff/README.md), a README per service.
- **CI:** GitHub Actions compiles the backend and runs its tests on each PR ([ADR-013](docs/decisions.md)); frontend build, lint, the concurrency tests and unit tests for the core services join in stages.
- **Claude Code:** agents for the full feature loop (`feature-planner`, `backend-dev`, `web-dev`, `qa-agent`) and `perf-reviewer`; skills `fix-issue`, `new-service`, `qc-check`, `session-handoff`; rules for clean code, security and tests.

### Removed
- The unused subscription service ([ADR-011](docs/decisions.md)).

## 0.1.0

First complete baseline: all services, frontend and local stack in the repository, plus the AI-assisted workflow.

### Added
- **13 Spring Boot services** behind an API gateway: user, airline-core, location, flight-ops, seat, pricing, ancillary, booking, payment, notification, plus gateway, config server and Eureka.
- **Keycloak authentication at the gateway** with trusted `X-User-*` headers and `@RequiredRoles` in services.
- **VNPay payments** (sandbox): signed payment URL, IPN as the source of truth, return page for display.
- **Seat hold** before payment (`HELD` with expiry), so two users can't pay for the same seat.
- **Flyway migrations in every service**; Hibernate validates the schema.
- **React frontend**: traveller search and booking, airline admin, super admin.
- **Local stack**: Docker Compose for the full system and an infra-only dev compose.
- **AI workflow**: `CLAUDE.md`, folder-scoped rules, review agents and skills for Claude Code; [AI_WORKFLOW.md](docs/AI_WORKFLOW.md).
- Docs: [architecture](docs/architecture.md), [authentication](docs/authentication.md), [decisions](docs/decisions.md), booking saga [design](docs/booking-refactor/DESIGN.md).

### Changed
- **booking-service runs on PostgreSQL** (partial unique index for idempotent create, coming in build step 3).
- Payment responses keep `checkoutUrl`; amounts in VND.

### Removed
- Razorpay and Stripe integrations, and the frontend `verify` call that confirmed payments twice.
