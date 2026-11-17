# Session handoff: 2026-11-03

> Read this at the start of the next session, after [CLAUDE.md](../../CLAUDE.md).

## 1. Where things stand

- **Booking saga refactor: done.** Build steps 1–10 written between 2026-10-22 and 2026-10-28; the concurrency tests pass on Testcontainers (seat 21, booking 16, payment 6). Details: [PROGRESS.md](../booking-refactor/PROGRESS.md).
- **Subscription service removed** (2026-10-30, [ADR-011](../decisions.md)). 10 business services + gateway, config server, Eureka.
- **Project docs and Claude Code setup completed** ([plan](../plans/2026-11-03-project-docs.md)).
- **Next feature:** [observability and performance](../plans/2026-10-31-observability-and-performance.md), approved, not started.

## 2. Done this session

- Root: `ONBOARDING.md`, `SECURITY.md`, `TODO.md`.
- `docs/`: `roadmap.md`, `environments.md`, `api-spec.md` (174 endpoints from the controllers, with access rules), `observability.md`, `runbooks/` (local stack, troubleshooting), `specs/` (index + three designs that only lived in PROGRESS), `handoff/`, plans for past work.
- Service `README.md` files; `CLAUDE.md` for seat-service, payment-service, frontend.
- `.claude/`: agents `feature-planner`, `backend-dev`, `web-dev`, `qa-agent`, `perf-reviewer`; skills `fix-issue`, `session-handoff`, `qc-check`, `new-service`; rules `clean-code`, `security`, `testing`.
- Verified by reading the code, not by running: ports and variables against `docker/` and `application.yaml`, endpoint roles against controllers and the gateway `SecurityConfig`.

## 3. Decisions

- Specs keep their original date and get a "Superseded by" status instead of being rewritten (see [specs/README.md](../specs/README.md)).
- `@RequiredRoles({})` means system admin only (`RoleCheckInterceptor` always lets `ROLE_SYSTEM_ADMIN` through); documented in `api-spec.md`.

## 4. Open work, in order

1. Observability plan, Phase 1: Kafka UI + Jaeger compose override, OpenTelemetry agent on the five flow services, `trace_id` in logs, lab scripts.
2. Run the full booking flow once with VNPay sandbox keys + ngrok (needs `VNPAY_TMN_CODE` / `VNPAY_HASH_SECRET`).
3. CI spec (GitHub Actions: compile, Testcontainers tests, frontend build + lint).
4. Frontend login through Keycloak: the login and sign-up thunks still call the original project's `/auth/*` endpoints, which no service serves now.

## 5. Gotchas

- Port 3306 on the dev machine is used by another MySQL; stop it before `docker-compose.dev.yml`.
- Tests need `-am` (stale `common-lib` jar otherwise) and Docker running.
- "Unresolved compilation problem" = an IDE-compiled class; `mvn clean`.
- `flight-instance-created` is sent without a key (ordering per flight instance is not guaranteed; fine today, note it before adding consumers).

## 6. Read first next time

- [plans/2026-10-31-observability-and-performance.md](../plans/2026-10-31-observability-and-performance.md)
- [observability.md](../observability.md)
- [runbooks/local-stack.md](../runbooks/local-stack.md)
