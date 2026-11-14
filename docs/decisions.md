# Architecture Decision Records

Key technical decisions and why they were made. Newest last. The full reasoning for the booking saga lives in [booking-refactor/DESIGN.md](booking-refactor/DESIGN.md).

---

## ADR-001: Authenticate at the gateway, pass trusted headers to services

**Date:** 2026-10-01 · **Status:** Accepted

**Context:** Keycloak issues JWTs whose subject is a UUID. Every service needs the app's numeric user id and role, not Keycloak's identity.

**Decision:** The gateway validates the JWT, resolves the local user via user-service (cached), strips the token and forwards `X-User-Id`, `X-User-Email`, `X-User-Roles`. Services authorise with `@RequiredRoles`.

**Consequences:** JWT handling exists in one place. Services can be tested with plain headers. Services must only be reachable through the gateway. Details: [authentication.md](authentication.md).

---

## ADR-002: Flyway in every service, Hibernate only validates

**Date:** 2026-10-11 · **Status:** Accepted

**Context:** Schemas were created by `ddl-auto: update`, which can't express constraints the booking flow relies on (unique keys, partial indexes) and silently drifts between machines.

**Decision:** Each of the 9 services with a database gets `V1__init.sql` generated from its entities; from then on every change is a new `V{n}__*.sql`. Hibernate runs with `ddl-auto: validate`.

**Consequences:** Schema history is versioned and reviewable. Constraints that protect against races live in migrations. Databases created earlier by `ddl-auto` need to be recreated (or baselined).

---

## ADR-003: VNPay as the only payment gateway; the IPN is the source of truth

**Date:** 2026-10-11 · **Status:** Accepted (replaces Razorpay / Stripe)

**Context:** Razorpay and Stripe were wired in, but the payment was confirmed by a `verify` call from the frontend. It ran twice under React StrictMode and again on refresh, publishing `payment.completed` each time.

**Decision:** Use VNPay (sandbox). The server-to-server IPN, verified with HMAC-SHA512, is the only writer of payment results. The browser return URL is display-only (except user-cancel code 24, which VNPay sends without an IPN). No multi-gateway abstraction: one gateway, kept simple.

**Consequences:** A public gateway route for the IPN. Local testing replays the IPN URL by hand or uses ngrok ([VNPAY.md](booking-refactor/VNPAY.md)).

---

## ADR-004: Saga orchestration with booking-service as orchestrator

**Date:** 2026-10-17 · **Status:** Accepted

**Context:** Booking, seat and payment must end consistent (paid ⇔ seats booked) without a distributed transaction.

**Decision:** Orchestration, not choreography. The booking status *is* the saga state (`PENDING → AWAITING_PAYMENT → CONFIRMED`, failure branches `CANCELLED`, `EXPIRED`, `REFUND_REQUIRED`). Forward steps (hold, initiate) are synchronous Feign calls that fail fast; failures trigger compensation (seat release). A recovery job (every minute) closes bookings abandoned mid-saga.

**Consequences:** The whole flow is readable in one service. Booking depends on seat and payment being reachable at create time (mitigated by timeouts, retries, circuit breakers).

---

## ADR-005: Seat hold as one conditional UPDATE

**Date:** 2026-10-17 · **Status:** Accepted

**Context:** Two users could book the same seat. A per-seat loop also risks deadlocks when two requests lock overlapping seats in different orders.

**Decision:** One statement: `UPDATE seat_instance SET status = 'HELD' ... WHERE id IN (:ids) AND (status = 'AVAILABLE' OR own retry OR expired hold)`. If the updated count ≠ requested count, roll back and return 409. Hold lasts 15 minutes (longer than the 10-minute payment link plus grace).

**Consequences:** The row lock serialises competing users; the loser gets 409. A single `IN` statement locks rows in primary-key order, so overlapping requests can't deadlock. All-or-nothing by construction.

---

## ADR-006: Idempotent booking create via a partial unique index (PostgreSQL)

**Date:** 2026-10-17 · **Status:** Accepted (replaces the `Idempotency-Key` table and the Redis `SET NX` idea)

**Context:** Double clicks, refreshes and retries created duplicate bookings. A frontend-generated `Idempotency-Key` is lost on refresh; a Redis key is not in the same transaction as the booking row.

**Decision:** Move booking's database to PostgreSQL and add `UNIQUE (user_id, flight_instance_id, seats_hash) WHERE status IN ('PENDING','AWAITING_PAYMENT','CONFIRMED')`. A duplicate insert means "answer from the existing booking" (202 still processing / same payment link / already confirmed).

**Consequences:** The gate is the database itself, in the same transaction, with no extra table. Booking is the only PostgreSQL service (MySQL has no partial indexes).

---

## ADR-007: Deduplicate IPNs and events with DB state, not Redis

**Date:** 2026-10-13 · **Status:** Accepted

**Context:** VNPay retries IPNs and Kafka delivers at least once, so every handler can run twice, sometimes concurrently.

**Decision:** Check and change state in the same DB transaction: IPN uses `SELECT ... FOR UPDATE` + "still `PENDING`?"; consumers use conditional updates (`WHERE status IN (...)`). Unique constraints (`booking_id`, `transaction_id`) as the last line of defence. No `processed_events` table.

**Consequences:** Deduplication is exactly as durable as the data it protects. Redis is used only for side effects outside the DB (ADR-009).

---

## ADR-008: Transactional outbox for events and seat release

**Date:** 2026-10-21 · **Status:** Accepted

**Context:** "Commit, then `kafkaTemplate.send`" loses the event if Kafka fails after the commit. Seat release has four triggers (payment failed, create failure, user cancel, expiry job) and three of them have no message that would redeliver.

**Decision:** Payment and booking write events to an `outbox` table in the same transaction as the state change; a relay publishes them every 500 ms with key = `bookingId`. Release = a sync fast path (seats free immediately) plus a durable `booking.cancelled` event that seat-service consumes idempotently. Consumers get retry with exponential backoff and a dead-letter topic.

**Consequences:** No lost events; at-least-once delivery, so every consumer must be idempotent (ADR-007). Single relay instance (multiple would need `SKIP LOCKED`).

---

## ADR-009: Notification dedupe with Redis per booking and channel

**Date:** 2026-10-21 · **Status:** Accepted

**Context:** `booking.confirmed` can arrive twice; email and SMS are side effects that can't be rolled back.

**Decision:** `SET NX notif:{email|sms}:{bookingId} EX 1d` before sending; on send failure delete the key and rethrow so Kafka retries. If Redis is down, send anyway (fail open).

**Consequences:** At worst one duplicate email, never a missing one. A failed SMS is retried without resending the email.

---

## ADR-010: Verify by impact scan + compile; prove races with Testcontainers

**Date:** 2026-10-11 · **Status:** Accepted

**Context:** Starting 14 services to check every refactor step is too slow to do each time.

**Decision:** Each step is verified by grepping every caller of a changed contract (backend, frontend, config) and compiling all touched modules. Concurrency guarantees are proven separately with Testcontainers tests (real MySQL / PostgreSQL / Kafka) for each race the design handles.

**Consequences:** Fast feedback per step. Runtime behaviour is covered by the targeted concurrency tests rather than manual end-to-end runs.

---

## ADR-011: Remove the subscription service; keep the small domain services separate

**Date:** 2026-10-30 · **Status:** Accepted

**Context:** subscription-service was an empty placeholder: no other service, route or page called it. Pricing, ancillary and location are also small services that could live inside a bigger one.

**Decision:** Remove subscription-service (code, Maven module, config). Keep pricing, ancillary and location as separate services, because this project is for practising service boundaries, Feign calls and failure handling between services.

**Consequences:** One less service to run and maintain. In a production system with a small team, pricing and ancillary could be merged into seat-service and location into airline-core-service, which would cut network calls and fallbacks at the cost of looser domain boundaries.

---

## ADR-012: Dated specs and plans; supersede, don't rewrite

**Date:** 2026-11-03 · **Status:** Accepted

**Context:** The first booking design (2026-10-11) was partly replaced by the redesign (2026-10-21), but the reasons lived only in PROGRESS entries. Feature plans had an index, designs had none, and there was no single place saying where the work stands today.

**Decision:** Designs go to `docs/specs/YYYY-MM-DD-<topic>-design.md`, plans to `docs/plans/YYYY-MM-DD-<name>.md`, each dated the day it was agreed and listed in an index. Specs written up later from the record say so in the index. When a design changes, the old spec keeps its content and gets a "Superseded by" status; the new spec says why. The current state of the work goes to `docs/handoff/SESSION-HANDOFF-<date>.md`.

**Consequences:** The history of a decision stays readable (what we thought, what we learned, what we chose). More files to keep in sync; the `progress-update` and `session-handoff` skills update the indexes as part of their steps.

## ADR-013: Grow CI in stages; a job joins only when it passes on main

**Date:** 2026-11-04 · **Status:** Accepted

**Context:** The project had no CI. On the full code, `mvn compile` and `npm run build` pass, but `npm run lint` fails with 200 errors left from the original project, and the concurrency tests need Docker (Testcontainers).

**Decision:** GitHub Actions, first one workflow per side (`backend.yml`, `frontend.yml`); since 2026-11-10 one plain workflow per core service plus `backend-shared`, each triggered by its own paths, `common-lib` and the parent poms. Start with backend compile plus the tests that need no database, skipping booking and payment until their event producers land in build step 6; add frontend build, then lint (after the errors are fixed, not by disabling rules), then the concurrency tests and unit tests of the core services (gateway, user, airline-core, flight-ops, seat, booking, payment, notification). Location, pricing and ancillary stay compile-only. A job is added only once it passes on `main`.

**Consequences:** Every check that shows is meaningful, and a red check means a real break. Early PRs have only the compile check; the lint and test guarantees arrive later. Plan: [2026-11-04-ci-pipeline](plans/2026-11-04-ci-pipeline.md).
