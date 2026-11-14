# Plan: restructure the booking flow

> This file is the **source of truth** for the refactor. New decisions or plan changes → edit this file.
> Progress per step is logged in [PROGRESS.md](PROGRESS.md).
> **Redesign agreed (2026-10-21):** see [DESIGN.md](DESIGN.md) (what to build). Implementation order = the build order in [LEARNING-PATH.md](LEARNING-PATH.md) (replaces §6 below; same work as DESIGN.md §13, ordered one concept per step). Where this file differs from DESIGN.md, DESIGN.md wins.

## 1. Goals

Learn by integrating, in real code, the most common patterns of distributed systems:
idempotency, row locks, deadlock, race conditions (duplicate requests, two people competing for one seat), saga, transactional outbox.

- A learning / CV project: **no real users**, no production needed.
- Only do what serves the patterns above. Don't expand into other work.
- The app still runs after every step.
- Prefer the **common, industry-standard** approach, nothing fancy.

## 2. Decisions

| Topic | Decision |
|---|---|
| Saga | **Orchestration**, booking-service is the orchestrator (state machine + compensation) |
| Payment gateway | **VNPay** (sandbox), remove Razorpay. The IPN is the source of truth; the Return URL is for display only |
| Migrations | **Flyway for all 9 services with a DB** (user, airline-core, flight-ops, location, seat, pricing, ancillary, booking, payment). Each service gets a `V1__init.sql` generated from Hibernate, then `ddl-auto: validate`. Notification and subscription have no DB |
| Duplicate booking creation (decided 2026-10-13, not yet coded) | **Redis `SET NX EX 20 min`**, key = hash(userId + flightInstanceId + sorted seat set), value = the cached answer: `PROCESSING` (TTL 1 min) → 202 + FE spinner, resend; `CREATED` + VNPay link (TTL = remaining link time, 15 min) → return the link without reading the DB; `CONFIRMED` + bookingId (TTL 20 min, set in stage D when payment completes) → return the success URL. Cancel / payment failed / expired → `DEL key`. The link's source of truth is still `payments.checkout_url`. Last line of defence when Redis loses the key: seat hold 409 → look for a PENDING booking with the same content in the DB → return the old link. Replaces an FE-generated Idempotency-Key + an `idempotency_keys` table (a page refresh loses the key). **Superseded by DESIGN.md §2 (partial unique index in PostgreSQL).** |
| Duplicate IPN / Kafka events | **Check the state in the DB, in the same transaction** (no Redis: not in the same transaction as the DB, and keys can be lost). No `processed_events` table |
| Saga scope | Booking statuses: `PENDING → CONFIRMED / CANCELLED / EXPIRED`, plus `REFUND_REQUIRED` (money arrives late). No intermediate statuses, no SKIP LOCKED, no refund API call. **Superseded by DESIGN.md §1 (adds `AWAITING_PAYMENT`).** |
| Refactor safety | Don't run the original app first. Instead, every change must be **checked for impact in the code** (BE, FE, other services' Feign clients, events) and **compile** (see §7) |
| Tests | **Testcontainers** (real MySQL + Kafka) for concurrency tests |
| Local run | Docker only for infrastructure (1 MySQL + Kafka + Redis). Services run from the IDE |
| Calling APIs when testing | Call services directly with Postman, with `X-User-Id`, `X-User-Roles` headers (no Keycloak, gateway or FE needed) |

## 3. Scope

- **Logic changes:** `seat-service`, `booking-service`, `payment-service`, `common-lib`, a little of `notification-service`.
- **Flyway only (no logic change):** the other 6 services with a DB.
- **FE:** only the places affected, listed in §4b.
- **Don't touch:** Keycloak, config server, the `ben/gds-*` Docker images, the logic of other services.
- **Gateway:** only add a public route for the VNPay IPN (Step 1), because VNPay calls it without a JWT.
- The `database-schema*.sql` files at the repo root are documentation only (no code runs them) and are outdated. Don't use them as a source.

### Flyway notes
- `ddl-auto: update` is set in **2 places**: `config-repo/application.yml` and each service's `application.yaml` (`${SPRING_JPA_HIBERNATE_DDL_AUTO:update}`). Change both.
- `user-service` has a test using H2 + `create-drop` → add `spring.flyway.enabled=false` in that test.
- No service seeds data (`data.sql`, `CommandLineRunner`) → no conflict.
- Spring Boot 4: use `spring-boot-starter-flyway` + `flyway-mysql`.

## 4. Current state: the bug behind each pattern

| Pattern | Bug in the original code | Where |
|---|---|---|
| Race condition / lock | Two people on the same seat can both create a booking and pay. No hold, and `BOOKED` is set without checking the previous status | `BookingServiceImpl.createBooking`, `SeatInstanceServiceImpl.updateSeatInstanceStatus` |
| Deadlock | Not yet; appears once several seats are held in no fixed order | (Step 2) |
| HTTP idempotency | FE calls `verifyPayment` in `useEffect` + `StrictMode`, so it runs twice; F5 calls it again. Each call publishes `payment.completed` | `BookingSuccess.jsx`, `PaymentServiceImpl.verifyPayment` |
| Booking creation idempotency | Clicking twice creates 2 bookings and 2 payments. `findByBookingId` returns an `Optional` but there can be several rows | `PaymentRepository` |
| Kafka idempotency | No consumer deduplicates: emails sent twice, confirm twice | `PaymentEventListener`, `BookingNotificationListener` |
| Outbox | Saves to the DB, then a separate `kafkaTemplate.send`. If Kafka fails after the commit, the event is lost | `PaymentServiceImpl`, `PaymentEventListener` |
| Saga | No compensation, no expiry; cancelling a booking doesn't release the seats | `cancelBooking`, no `@Scheduled` |
| Kafka | No message key, no retry/DLT, the seat listener swallows exceptions | The `KafkaConsumerConfig` classes, `BookingEventListener` |

Redundant / badly structured (cleaned up in Step 0):
- `SeatInstance.isBooked/isAvailable` duplicate `status`.
- `booking_seat_instances` (`@ElementCollection` on Booking) → should be `Ticket.seatInstanceId`.
- `PassengerServiceImpl.findOrCreatePassengerEntity` reuses an old passenger then calls `setBooking(new)` → pulls the passenger off the old booking.
- `FlightInstanceCabinServiceImpl.createFlightInstanceCabin` generates seats twice alongside the Kafka consumer (hard-coded prices 1000/500), and the FE doesn't use it.
- `PATCH /api/seat-instances/{id}/status` allows setting `BOOKED` directly.

## 4b. API contract and affected FE code

Principle: **keep the request/response shapes the FE and other services use**. Where a change is unavoidable, fix the FE **in the same step**.

| BE change | Affected | How it's handled | Step |
|---|---|---|---|
| Remove `SeatInstance.isBooked/isAvailable` | `SeatSelection.jsx` reads `isAvailable`, `booked`, `isOccupied` | The response keeps these fields, computed from `status` | 0 |
| Remove `booking_seat_instances` | `BookingDetails.jsx`, `TravellerBookingCard.jsx`, `BookingSuccess.jsx`, `Ticket.jsx`, `TicketPDF.jsx` read the booking's seats | Same `BookingResponse` shape, only the storage changes | 0 |
| Razorpay → VNPay | `bookingThunk.js`, `BookingReview.jsx` redirect using `checkoutUrl` | **Keep the field name `checkoutUrl`** | 1 |
| Remove `verify` | `BookingSuccess.jsx`, `paymentThunk.js`, `paymentSlice.js` | Fix the FE: only read the booking status | 1 |
| Enum `RAZORPAY` → `VNPAY` | `TransactionsManagement.jsx`, `FinancialManagement.jsx` | Change label / value | 1 |
| Seat already held → 409 | `BookingReview.jsx` | Show "seat already taken" | 2 |
| `Idempotency-Key` | `bookingThunk.js` | FE generates the key once per booking attempt | 3 |
| New booking statuses | ~16 FE places comparing `PENDING`/`CONFIRMED` | Review each one, fix in the same step | 6 |
| Seat-service API changes | Feign `SeatClient` in booking, flight-ops, ancillary | Review and fix in the same step | 0, 2 |

## 5. Target design

```
POST /bookings (Idempotency-Key)
  ├─ seat-service: HOLD seats (sync, UPDATE ... WHERE status='AVAILABLE', ascending ids, all-or-nothing)
  │     failure → 409
  ├─ Booking AWAITING_PAYMENT, seats HELD, held_until = +10 min
  └─ payment-service: create the VNPay URL

VNPay IPN (idempotent by transaction id)
  └─ Payment SUCCESS + outbox (1 transaction) ──► payment.completed (key = bookingId)
        booking-service (orchestrator, deduplicating consumer)
           → CONFIRMED + outbox ──► command seat.confirm ──► seat HELD→BOOKED (matching booking_id)
                                ──► booking.confirmed ──► notification (deduplicated)

Compensation: payment.failed / cancel / expiry job (SKIP LOCKED) → CANCELLED/EXPIRED → command seat.release
Money arrives after expiry → REFUND_PENDING
```

Statuses:
- Booking: `PENDING → SEATS_HELD → AWAITING_PAYMENT → CONFIRMED`, failure branches `CANCELLED`, `EXPIRED`, `REFUND_PENDING`.
- SeatInstance: `AVAILABLE ⇄ HELD → BOOKED`, plus `BLOCKED` (admin), `OCCUPIED` (unused).

## 6. Steps

### Step 0: Foundations
- [x] `docker/docker-compose.dev.yml` adds 1 MySQL running `init-databases.sql`
- [x] Flyway for 9 services (V1 generated from Hibernate → `validate`), change `ddl-auto` in both places, disable Flyway in user-service's H2 test
- [x] Remove `isBooked/isAvailable` from the `SeatInstance` entity (the response still computes them from `status` so the FE doesn't break)
- [x] Remove `booking_seat_instances` → `Ticket.seatInstanceId`
- [x] Fix the Passenger being pulled into a new booking
- [x] Delete the REST version of `createFlightInstanceCabin`
- [x] Lock down `PATCH /seat-instances/{id}/status` (no `BOOKED`)
- [x] Delete `PUT /bookings/{id}` (`updateBooking`), unused by the FE
- ~~Seed SQL~~: dropped; data is created through the admin UI when running the app (create a flight → Kafka generates the seats)

### Step 1: VNPay replaces Razorpay
- [x] `VnPayService` (build the URL, HMAC-SHA512). No multi-gateway interface: there is only one gateway, keep it simple
- [x] IPN endpoint `GET /api/payments/vnpay/ipn` + Return `GET /api/payments/vnpay/return`; the gateway opens the IPN publicly
- [x] Remove Razorpay + Stripe (dependencies, code, DTO fields), VND currency, Flyway `V2__vnpay_provider.sql`
- [x] FE `BookingSuccess`, `paymentThunk`, `paymentSlice`: drop verify, call return for display, re-read the booking
- [x] FE `TransactionsManagement`, `FinancialManagement`: Razorpay → VNPay
- Waiting on the user: `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` (env), ngrok to test a real IPN. Guide: [VNPAY.md](VNPAY.md)

### Step 2: Race condition, row lock, deadlock
- [x] V2 seat: `booking_id`, `held_until`, status `HELD`
- [x] Seat API hold / release / confirm (conditional update, sorted ids, all-or-nothing)
- [x] `createBooking` holds seats before payment, Feign moved out of `@Transactional`
- [x] FE `BookingReview`: show the 409 seat-already-held error

### Step 3: Idempotency
- [x] `Idempotency-Key` for `POST /bookings`, FE `bookingThunk` sends the key
- [x] Payment unique `booking_id` + VNPay transaction id; a duplicate IPN returns `RspCode 02`
- [x] IPN locks the row (`SELECT ... FOR UPDATE`) + rolls back on error (fixes 2 bugs found while reviewing Step 1)

### Step 4: Transactional Outbox
- [ ] `outbox_events` table in payment, booking + publishing poller
- [ ] Message key = `bookingId`

### Step 5: Safe consumers
- [ ] `processed_events` (unique `eventId`) in booking, seat, notification
- [ ] `DefaultErrorHandler` retry + DLT, stop swallowing exceptions

### Step 6: Saga orchestration
- [ ] Booking state machine, command/reply with seat
- [ ] Expiry job `SELECT ... FOR UPDATE SKIP LOCKED`
- [ ] Money arrives late → `REFUND_PENDING`
- [ ] FE: review the ~16 places comparing booking status, show the new statuses

### Step 7: Tests (Testcontainers)
- [x] 2 threads hold the same seat → only 1 succeeds
- [x] 2 bookings hold seats in opposite order → no deadlock
- [x] IPN sent twice → processed once
- [x] Duplicate Kafka event → processed once (`payment.completed` twice → one CONFIRMED, one `booking.confirmed`)
- [ ] Stop Kafka, then start it again → the outbox catches up

## 7. Working conventions

1. Work **one step at a time**. Before changing code: list the specific changes for the user to approve.
2. **Check impact before removing / changing** any field, method, endpoint, enum, DTO or event. Find every usage in:
   - BE: the current service, other services (Feign clients, DTOs in `common-lib`), Kafka consumers
   - FE: `frontend/src` (Redux thunks/slices, pages, components)
   - Config: `config-repo`, `application.yaml`, `docker-compose`
   Record the list of affected places in PROGRESS. Change only once nothing is missed.
3. **Keep the API contract** (§4b). If a change is unavoidable, fix every caller in the same step.
4. A step is done only when it **compiles**: `mvn -q compile` for every module touched (including `common-lib` and dependent services), `npm run build` if the FE was touched. Verify by code review + compile; **no need to rerun the services** (user request, it takes too long).
5. Step done: tick the checkbox in §6, log it in [PROGRESS.md](PROGRESS.md), tell the user before moving on.
6. DB changes = add a new `V{n}__*.sql` file, **never edit** a migration that has already run.
7. A new decision comes up → update §2.
