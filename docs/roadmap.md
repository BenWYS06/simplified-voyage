# Roadmap

> **Now:** the booking saga refactor is done (build steps 1–10, all concurrency tests green on 2026-10-28). CI runs in stages ([plan](plans/2026-11-04-ci-pipeline.md), backend compile first). Next is observability and performance, then deployment.
> Detailed open items: [TODO.md](../TODO.md). Current state of the work: the latest file in [handoff/](handoff/README.md).
> Each milestone below links to the plan or progress log where the real work is recorded.

---

## Milestone 0: Baseline system ✅

The original airline GDS: Spring Boot microservices behind a gateway, a React frontend, Docker Compose.

- Services: user, airline-core, location, flight-ops, seat, pricing, ancillary, booking, payment, notification (+ gateway, config server, Eureka).
- Flight search, seat maps, fares, ancillaries (meals, insurance, baggage), bookings, e-tickets, email / SMS notifications.
- Airline owner dashboard and super admin pages.

## Milestone 1: Security at the gateway ✅

- Keycloak realm `ben-airline`; the gateway is the only OAuth2 resource server.
- Trusted `X-User-Id` / `X-User-Roles` headers, `@RequiredRoles` in services, Keycloak `sub` → local user id in the gateway ([ADR-001](decisions.md)).
- Details: [authentication.md](authentication.md).

## Milestone 2: Booking refactor, first design ✅ (2026-10-11 → 2026-10-13, superseded in part)

[Spec](specs/2026-10-11-booking-refactor-v1-design.md) · [progress](booking-refactor/PROGRESS.md)

- Step 0: Flyway in all 9 services with a DB, `ddl-auto: validate`, dev MySQL compose ([spec](specs/2026-10-11-flyway-migrations-design.md), [ADR-002](decisions.md)).
- Step 1: VNPay replaces Razorpay / Stripe; the IPN is the source of truth ([spec](specs/2026-10-12-vnpay-payment-design.md), [ADR-003](decisions.md)).
- Step 2: first seat hold (one UPDATE per seat, sorted ids).
- Step 3: `Idempotency-Key` table, IPN row lock + rollback, unique constraints in payment.
- Review on 2026-10-13: the `Idempotency-Key` approach loses the key on a page refresh. Redis `SET NX` was proposed, then dropped in the redesign.

## Milestone 3: Booking saga redesign ✅ (2026-10-17 → 2026-10-21)

[DESIGN.md](booking-refactor/DESIGN.md) agreed section by section, final review G1–G4.

- Statuses `PENDING → AWAITING_PAYMENT → CONFIRMED`, failure branches `CANCELLED`, `EXPIRED`, `REFUND_REQUIRED`.
- booking-service moves to PostgreSQL; a partial unique index is the idempotency gate ([ADR-006](decisions.md)).
- Outbox for every event, sync release as a fast path ([ADR-008](decisions.md)); Redis only for notification dedupe ([ADR-009](decisions.md)).

## Milestone 4: Booking saga build ✅ (2026-10-22 → 2026-10-28)

One concept per step, in [LEARNING-PATH.md](booking-refactor/LEARNING-PATH.md) order:

| Step | Concept | Done |
|---|---|---|
| 1 | Booking DB → PostgreSQL | 2026-10-22 |
| 2 | Race condition + deadlock: seat hold in one UPDATE | 2026-10-23 |
| 3 | Idempotent create (partial unique index) | 2026-10-24 |
| 4 | Saga completion + compensation, expiry job | 2026-10-25 |
| 5 | Resilience: timeouts + retry | 2026-10-26 |
| 6 | Outbox + Kafka retry / DLT | 2026-10-26 |
| 7 | VNPay extras (return code 24, link 10 min) | 2026-10-26 |
| 8 | Notification dedupe (Redis) | 2026-10-26 |
| 9 | Frontend: 202 / 409 / reused, status badges | 2026-10-26 |
| 10 | Concurrency tests on Testcontainers (seat 21, booking 16, payment 6) | 2026-10-28 |

## Milestone 5: Project hygiene ✅ (2026-10-30 → 2026-11-03)

- Removed the unused subscription service ([ADR-011](decisions.md), [plan](plans/2026-10-30-remove-subscription-service.md)).
- Onboarding, security policy, API reference, environments, runbooks, per-service READMEs, specs and handoff structure; Claude Code agents and skills for the full feature loop ([plan](plans/2026-11-03-project-docs.md)).

## Milestone 6: Observability and performance ⏳ next

[Plan](plans/2026-10-31-observability-and-performance.md), approved, not started.

1. See the flow: OpenTelemetry Java agent + Jaeger + Kafka UI, `trace_id` in every log line, lab scripts (seed data, fake VNPay).
2. Measure: realistic data volume, k6 scenarios (search, seat map, history, create, 200 users on one seat), baseline report.
3. Fix what the baseline confirms: flight search N+1 and paging bug, seat map N+1, booking history fan-out, missing indexes.
4. Write-up: before / after numbers in `docs/performance/`.

## Milestone 7: CI ⏳

GitHub Actions on each PR: compile all modules, run the Testcontainers tests, build and lint the frontend. Spec + plan to be written.

## Milestone 8: Deployment infrastructure ⏳

Run the stack outside a laptop: image registry, environment config and secrets, TLS in front of the gateway. Spec + plan to be written.

## Later (not planned yet)

- Frontend login through Keycloak OIDC (PKCE).
- Gateway rate limiting.
- Refund API instead of manual refunds for `REFUND_REQUIRED`.
