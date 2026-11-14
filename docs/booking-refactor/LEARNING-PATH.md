# Learning path: build the booking saga from scratch

[DESIGN.md](DESIGN.md) says **what** we build (the agreed decisions). This file says **in what order** we build it, so you learn it the way you would build it yourself: make it work, then correct under races and repeats, then recoverable, then reliable. One concept per step. The app compiles and runs after every step. DESIGN.md §13 lists the same work grouped by service; this order replaces it for implementation.

## Build order (what gets coded, step by step)

| Step | Concept you learn | What gets built | Read before | Upgraded later in |
|---|---|---|---|---|
| 1 ✅ | **Foundations** | Booking DB → PostgreSQL (`V1` rewritten, same tables); statuses `AWAITING_PAYMENT`, `EXPIRED`, `REFUND_REQUIRED` added to the enum. No behaviour change | Lesson 1 | — |
| 2 ✅ | **Race condition + deadlock** | Seat hold as one `UPDATE … IN` + count check; `flightInstanceId` validation; hold 15 min | Lesson 2 | — |
| 3 ✅ | **Idempotency** | `V2__…`: columns `seats_hash`, `checkout_url`, `payment_expires_at` + partial unique index. Create: duplicate → 202 / same link (`reused`) / success, final status `AWAITING_PAYMENT`. Payment initiate: catch the duplicate-key race. Seat confirm: "already booked by me = OK". Payment consumers: state guards | Lessons 4, 9 | — |
| 4 ✅ | **Saga completion + compensation** | Reads before writes; booking confirms seats synchronously (seat's `booking.confirmed` listener removed); late money → `CONFIRMED` / `REFUND_REQUIRED`; cancel with owner check; recovery/expiry job; cancel tickets with the booking; owner check on `GET /bookings/{id}`. Release is still a **plain sync call** | Lessons 8, 10, 11 | Step 6 |
| 5 ✅ | **Resilience** | Feign connect/read timeouts + Resilience4j Retry (transient errors only) | Lesson 7 | — |
| 6 ✅ | **Reliability** | `outbox` tables (booking `V3__…`, payment `V4__…`) + relay in payment and booking; release via `booking.cancelled` consumer (fixes step 4's "release lost if seat is down"); Kafka retry + backoff + DLT in every consumer | Lessons 6, 12 | — |
| 7 ✅ | **VNPay extras** | Shared result handler; `POST /vnpay/return` writes only for code 24; link 10 min; `expiresAt` | Lessons 3, 5 | — |
| 8 ✅ | **Notification** | Redis `SET NX` per booking and channel; stop swallowing send errors | Lesson 13 | — |
| 9 ✅ | **Front end** | 202 spinner, `reused` dialog, 409 → reload seat map, result page, status badges, Continue payment / Cancel, admin refund filter | Lesson 14 | — |
| 10 ✅ | **Tests** | One Testcontainers test per race we designed for | Lesson 15 | — |

The lessons below are reading notes per concept; the "Built in step N" lines point to the step in this table.

**How to use each lesson**
1. **Idea:** the problem and the trick, in two lines.
2. **Read now:** code that already exists (from the old Steps 0–3). Read it before we build the step.
3. **Built in step N:** what new code that step adds, and what to look at once it's written.
4. **Check yourself:** if you can answer these questions, move on.

Overview to keep open while reading: https://claude.ai/artifact/KJ32p8eRupnxwimdtjTKSX

| # | Lesson | Pattern | Build step | Existing code? |
|---|---|---|---|---|
| 0 | Big picture | Saga, the reason for everything | — | — |
| 1 | Data model = states | State machine in tables | 1 | Partly |
| 2 | Hold seats | Race condition, deadlock | 2 | Yes (to be upgraded) |
| 3 | VNPay basics | Signed payment URL | 7 | Yes |
| 4 | Payment link once per booking | Idempotency via UNIQUE | 3, 7 | Yes (+ 1 fix) |
| 5 | Record the payment result | Pessimistic lock + state guard | 7 | Yes |
| 6 | Never lose an event | Transactional outbox | 6 | No (new) |
| 7 | Call other services safely | Timeout, retry, circuit breaker | 5 | Partly |
| 8 | The orchestrator's create flow | Saga forward steps + compensation | 3, 4, 6 | Yes (to be reworked) |
| 9 | Same booking request twice | Idempotent create (partial unique index) | 3 | No (new) |
| 10 | Finish the saga after payment | Idempotent consumer, orchestration | 3, 4 | Old version |
| 11 | Cancel and the recovery job | Compensation triggers, scheduler | 4 | Old version |
| 12 | Kafka that never drops a message | Retry + DLT | 6 | Weak version |
| 13 | No duplicate emails | Redis `SET NX` | 8 | No (new) |
| 14 | Front end | Polling, 202 / 409 handling | 9 | Partly |
| 15 | Prove it | Concurrency tests | 10 | No (new) |

---

## Lesson 0: Big picture (15 min)
**Idea:** three services, three databases, no shared transaction. Every technique below exists because a step can fail, run twice, or run at the same time as another step.

**Read now:** the overview page (architecture + booking lifecycle + timing), then [DESIGN.md §1](DESIGN.md).

**Check yourself:** name the 6 booking statuses and what moves a booking between them. Why does the payment link expire before the seat hold?

## Lesson 1: Data model = states
**Idea:** the status columns are what make everything else possible. Idempotency, locks and the saga are all just "move this status only if it is still X".

**Read now:**
- Seat: [V2__seat_hold.sql](../../backend/services/seat-service/src/main/resources/db/migration/V2__seat_hold.sql), [SeatInstance.java](../../backend/services/seat-service/src/main/java/com/ben/services/model/SeatInstance.java) (`status`, `bookingId`, `heldUntil`).
- Payment: [V3__payment_idempotency.sql](../../backend/services/payment-service/src/main/resources/db/migration/V3__payment_idempotency.sql) (UNIQUE `booking_id`, `transaction_id`), [Payment.java](../../backend/services/payment-service/src/main/java/com/ben/services/model/Payment.java).
- Booking: [Booking.java](../../backend/services/booking-service/src/main/java/com/ben/services/model/Booking.java), [Ticket.java](../../backend/services/booking-service/src/main/java/com/ben/services/model/Ticket.java) (`seat_instance_id`).

**Built in step 1:** booking DB on PostgreSQL: `V1__init.sql` rewritten in PostgreSQL syntax with the same tables, enums stored as text; the 3 new statuses in `BookingStatus`. (The new columns + partial unique index come in step 3 as `V2`, the `outbox` table in step 6.)

**Check yourself:** which column tells you "this seat belongs to booking 10"? Which constraint stops two payments for one booking?

## Lesson 2: Hold seats (race condition + deadlock)
**Idea:** never "SELECT, check, then UPDATE". Put the check **inside** the UPDATE's `WHERE`. The database runs it atomically, so only one request can win. Several seats in one statement are locked in primary-key order, so two requests can't deadlock.

**Read now:**
- [SeatInstanceRepository.java](../../backend/services/seat-service/src/main/java/com/ben/services/repository/SeatInstanceRepository.java): `holdAll`, `countInFlightInstance`, `releaseHeld`, `confirmHeld`. Read the `WHERE` clauses carefully.
- [SeatInstanceServiceImpl.java](../../backend/services/seat-service/src/main/java/com/ben/services/service/impl/SeatInstanceServiceImpl.java): `holdSeats` (check the seats belong to the flight, one `holdAll`, fewer rows than seats → 409 rolls back everything).
- [InternalSeatHoldController.java](../../backend/services/seat-service/src/main/java/com/ben/services/controller/InternalSeatHoldController.java): the internal endpoints booking calls.

**Built in step 2:** the loop becomes **one** `UPDATE … WHERE id IN (…)` plus a count check; hold time 15 min; the new **confirm** statement (accepts "already booked by me" and seats that are still free); the `booking.cancelled` release consumer.

**Check yourself:** two users click seat 5 at the same instant. Walk through what each UPDATE returns. Why does the "held by me" clause make a retry safe?

## Lesson 3: VNPay basics
**Idea:** we never handle card data. We build a URL with the order details, **sign** it with a secret (HMAC-SHA512), and redirect the browser there. VNPay signs its answers the same way, so we can verify them.

**Read now:**
- [VnPayService.java](../../backend/services/payment-service/src/main/java/com/ben/services/service/gateway/VnPayService.java): `createPaymentUrl`, `isValidSignature`.
- [PaymentController.java](../../backend/services/payment-service/src/main/java/com/ben/services/controller/PaymentController.java): `/vnpay/ipn`, `/vnpay/return`.
- [RouteConfig.java](../../backend/cloud/api-gateway/src/main/java/com/ben/cloud/config/RouteConfig.java): the public IPN route (VNPay has no JWT).
- [VNPAY.md](VNPAY.md): flow, sandbox card, testing IPN without ngrok.

**Check yourself:** what is the difference between the return URL and the IPN, and which one is the source of truth?

## Lesson 4: Payment link once per booking (idempotency via UNIQUE)
**Idea:** "create" has no existing row to check, so let a **UNIQUE** constraint be the gate. A duplicate either finds the existing row, or hits the constraint and then reads it.

**Read now:** [PaymentServiceImpl.java](../../backend/services/payment-service/src/main/java/com/ben/services/service/impl/PaymentServiceImpl.java), `initiatePayment` (finds an existing payment by `bookingId`, returns its link).

**Built in step 3:** catch the duplicate-key error when two calls race (both saw "no payment yet") → re-read → return the existing link. (Step 7: link 10 min, plus `expiresAt` in the response.)

**Check yourself:** why isn't "SELECT first, then INSERT" enough on its own?

## Lesson 5: Record the payment result (pessimistic lock + state guard)
**Idea:** the result arrives more than once (VNPay retries, return and IPN together). Lock the row (`SELECT … FOR UPDATE`) so copies wait in line, then process it only if it's still `PENDING`. If anything fails, roll back and answer 99 so VNPay retries.

**Read now:** [PaymentServiceImpl.java](../../backend/services/payment-service/src/main/java/com/ben/services/service/impl/PaymentServiceImpl.java) `handleVnPayIpn`, and [PaymentRepository.java](../../backend/services/payment-service/src/main/java/com/ben/services/repository/PaymentRepository.java) `findByTransactionIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`).

**Built in step 7:** one shared handler used by the IPN (all results) and by `POST /vnpay/return` (only code 24, cancel).

**Check yourself:** what would go wrong without `FOR UPDATE` but with the PENDING check? What about with the lock but without the check?

## Lesson 6: Never lose an event (transactional outbox)
**Idea:** "save to DB" and "send to Kafka" can't be one transaction. So save the **event as a row** in the same DB transaction, and let a small scheduled relay publish the rows to Kafka.

**Read now (the problem):** [PaymentEventProducer.java](../../backend/services/payment-service/src/main/java/com/ben/services/event/PaymentEventProducer.java). It sends straight to Kafka: if the service dies right after the DB commit, the event is gone.

**Built in step 6 (payment and booking):** an `outbox` table and entity, `outboxRepository.save(...)` next to the status change, and an `@Scheduled` relay (`SELECT unpublished ORDER BY id` → `send(topic, key = bookingId).get()` → mark published, stop at the first failure).

**Check yourself:** the relay sends, then crashes before marking the row. What happens next, and why is it fine?

## Lesson 7: Call other services safely (Resilience4j)
**Idea:** a remote call can hang, fail briefly, or fail for minutes. Timeout stops the hang, retry absorbs a blip, the circuit breaker stops hammering a dead service, and the fallback answers fast (503) without faking success.

**Read now:**
- [SeatClient.java](../../backend/services/booking-service/src/main/java/com/ben/services/clients/SeatClient.java), [SeatClientFallbackFactory.java](../../backend/services/booking-service/src/main/java/com/ben/services/clients/SeatClientFallbackFactory.java) (409 passes through, everything else becomes 503).
- `resilience4j` in [booking application.yaml](../../backend/services/booking-service/src/main/resources/application.yaml).

**Built in step 5:** Feign connect/read timeouts and a Retry (2–3×, only timeouts / 503 / connection refused, never 4xx, not when the breaker is open).

**Check yourself:** why is retry safe for `seat.hold` but would be dangerous without the "held by me" clause?

## Lesson 8: The orchestrator's create flow (saga + compensation)
**Idea:** booking runs the steps in order (save, hold, create link). If a step fails, it **undoes** the ones that succeeded (cancel the booking, release the seats). Database transactions stay short and never wrap a remote call.

**Read now:** [BookingServiceImpl.java](../../backend/services/booking-service/src/main/java/com/ben/services/service/impl/BookingServiceImpl.java), `createBooking` (the numbered comments), `TransactionTemplate`, `markCancelled`, and the `catch` blocks that release the seats.

**Built in steps 3, 4 and 6:** step 3 adds the final `AWAITING_PAYMENT` update; step 4 adds price lookups **before** the hold (reads before writes); the final `UPDATE … SET AWAITING_PAYMENT … WHERE status = PENDING`; tickets cancelled with the booking; step 6 turns the release into outbox row + sync fast path.

**Check yourself:** list what must be undone if the payment call fails, and what must be undone if the hold fails.

## Lesson 9: Same booking request twice (idempotent create)
**Idea:** refresh, Back or double click sends the same booking again. A **partial unique index** on `(user, flight, seats)` only for live bookings makes the second INSERT fail; we then return the existing booking according to its status.

**Read now:** nothing yet (the old header-based version is being removed). Re-read [DESIGN.md §2](DESIGN.md).

**Built in step 3 (on the PostgreSQL DB from step 1):** the columns + index in `V2__…`; in `createBooking`: catch the duplicate key → find the live booking → 202 (still `PENDING`), 200 + same link + `reused: true` (`AWAITING_PAYMENT`), or success (`CONFIRMED`).

**Check yourself:** why does a cancelled booking not block booking the same seats again?

## Lesson 10: Finish the saga after payment (idempotent consumer)
**Idea:** the payment result arrives via Kafka, possibly twice. Only act if the booking is still waiting (state guard). On success, confirm the seats **first**, then mark the booking confirmed. A failure throws, so Kafka delivers the same message again.

**Read now (old version):** [PaymentEventListener.java](../../backend/services/booking-service/src/main/java/com/ben/services/event/listener/PaymentEventListener.java). It sets the status blindly. Compare it with seat-service's [BookingEventListener.java](../../backend/services/seat-service/src/main/java/com/ben/services/event/BookingEventListener.java) (choreography, being removed).

**Built in steps 3 + 4:** state guards (step 3); sync `seat.confirm` outside the transaction, late money (`EXPIRED` → confirm free seats, else `REFUND_REQUIRED`), `booking.confirmed` through the outbox in step 6.

**Check yourself:** the booking expired 2 minutes ago and the payment succeeds now. Follow the event to the final status.

## Lesson 11: Cancel and the recovery job
**Idea:** every way a booking dies before payment runs the **same** compensation. A scheduled job is the saga's memory: it finds bookings stuck in `PENDING` (crash) or past payment expiry and closes them.

**Read now (old version):** `cancelBooking` in [BookingServiceImpl.java](../../backend/services/booking-service/src/main/java/com/ben/services/service/impl/BookingServiceImpl.java) and [BookingController.java](../../backend/services/booking-service/src/main/java/com/ben/services/controller/BookingController.java) (no owner check yet).

**Built in step 4:** owner check, `WHERE status IN (PENDING, AWAITING_PAYMENT)`, `@EnableScheduling` + `BookingExpiryJob`.

**Check yourself:** why may two copies of the job run at the same time without harm?

## Lesson 12: Kafka that never drops a message (retry + DLT)
**Idea:** the offset is committed only after success. On an error the handler re-reads the same message with growing waits; after a limit it parks it in a dead-letter topic, so other bookings aren't blocked.

**Read now (weak version):** notification's `KafkaConsumerConfig` (`FixedBackOff(5s, 2)`, no DLT, so a message is dropped after 2 tries).

**Built in step 6:** `DefaultErrorHandler` + `ExponentialBackOff` + `DeadLetterPublishingRecoverer` in booking, seat and notification.

**Check yourself:** what is the difference between Resilience4j retry and Kafka retry?

## Lesson 13: No duplicate emails (Redis)
**Idea:** an email is a side effect outside our DB, and a lost key only means one extra email, so Redis `SET NX` with a TTL is the right tool here (and only here).

**Read now:** [BookingNotificationListener.java](../../backend/services/notification-service/src/main/java/com/ben/service/listener/BookingNotificationListener.java) (errors are swallowed today).

**Built in step 8:** one key per channel (`notif:email:{bookingId}`, `notif:sms:{bookingId}`); delete the key and throw on failure.

**Check yourself:** why one key per channel instead of one per booking?

## Lesson 14: Front end
**Idea:** the browser never decides the result. It shows a spinner and asks the backend for the booking status.

**Read now:** [BookingReview.jsx](../../frontend/src/pages/traveler/BookingReview/BookingReview.jsx) (the old idempotency key), [BookingSuccess.jsx](../../frontend/src/pages/traveler/BookingSuccess/BookingSuccess.jsx) (polling).

**Built in step 9:** 202 spinner and re-send, the `reused` dialog, 409 → reload the seat map, a result page polling `AWAITING_PAYMENT`, status badges, and Continue payment / Cancel in My bookings.

## Lesson 15: Prove it (concurrency tests)
**Idea:** races only show up when things really run in parallel, so test against a real MySQL / PostgreSQL / Kafka (Testcontainers) and fire requests from several threads at once.

**Built in step 10:** two users and one seat; the same booking twice at once; IPN twice at once; a duplicate Kafka event; the job vs the consumer.

---

### Rhythm per build step
1. Before the step: read the lessons for that step (the **Read now** parts).
2. I implement the step and list the changed files.
3. You read the new code using the lesson's **Built in step N** notes, and answer **Check yourself**.
4. Next step.

---

## Reading tour: existing code (Lessons 1–5, 7–8)

Read the files in this order. Each line says what the file does in the workflow and what to look at. About 2 hours in total.

### Tour A: seat-service, one table and one UPDATE (race condition, deadlock)
Workflow position: booking asks seat-service to reserve seats before asking for money.

1. [SeatInstance.java:36-51](../../backend/services/seat-service/src/main/java/com/ben/services/model/SeatInstance.java#L36-L51): **the seat row is the state.** `status` (AVAILABLE / HELD / BOOKED), `bookingId` (who owns the hold), `heldUntil` (when the hold dies), `version`. Everything later is "change this row only if it is still X".
2. [V2__seat_hold.sql](../../backend/services/seat-service/src/main/resources/db/migration/V2__seat_hold.sql): how those columns were added. Flyway rule: **never edit an old file, add a new `V{n}`**.
3. [InternalSeatHoldController.java:16-29](../../backend/services/seat-service/src/main/java/com/ben/services/controller/InternalSeatHoldController.java#L16-L29): **the door booking knocks on.** `/internal/.../hold` and `/release`. "Internal" = not exposed through the gateway; only other services call it.
4. [SeatInstanceServiceImpl.java:131-164](../../backend/services/seat-service/src/main/java/com/ben/services/service/impl/SeatInstanceServiceImpl.java#L131-L164) `holdSeats`: **the hold algorithm** (step 2). De-duplicate the ids, check every seat belongs to the flight (else 400), run **one** `holdAll`, and if fewer rows changed than seats asked for, throw 409 → the whole transaction rolls back (**all-or-nothing**). `holdMinutes` (15) at line 42.
5. [SeatInstanceRepository.java:40-63](../../backend/services/seat-service/src/main/java/com/ben/services/repository/SeatInstanceRepository.java#L40-L63) `holdAll` + `countInFlightInstance`: **the most important query in the project.** One `UPDATE … WHERE id IN (…)`; the check lives inside the `WHERE`: `AVAILABLE`, or `HELD` by this same booking (retry), or `HELD` but expired. Returns how many rows changed: equal to the seat count = won, fewer = lost. One statement on the primary key locks rows in id order, which is why it cannot deadlock.
6. [SeatInstanceRepository.java:72-76](../../backend/services/seat-service/src/main/java/com/ben/services/repository/SeatInstanceRepository.java#L72-L76) `releaseHeld`: **the compensation statement.** Only seats of this booking, only `HELD`, so it can never free someone else's or a paid seat. Safe to run twice.
7. [SeatInstanceRepository.java:26](../../backend/services/seat-service/src/main/java/com/ben/services/repository/SeatInstanceRepository.java#L26) + [SeatInstanceMapper.java:53](../../backend/services/seat-service/src/main/java/com/ben/services/mapper/SeatInstanceMapper.java#L53) `isFree`: **lazy expiry on the seat map.** An expired hold is shown as free, without any job.

Skip: `confirmHeld` (line 65) and `confirmSeats`; replaced in build step 3.

**Check yourself:** two users hold seats 5 and 6 at the same instant; what does `holdAll` return to each? Why can one `UPDATE … IN` not deadlock where the old one-seat-per-statement loop needed sorted ids?

### Tour B: payment-service (VNPay, idempotency, pessimistic lock)
Workflow position: booking asks for a payment link; later VNPay reports the result.

1. [Payment.java:31-51](../../backend/services/payment-service/src/main/java/com/ben/services/model/Payment.java#L31-L51): **the payment row.** `bookingId` (one payment per booking), `transactionId` (= VNPay's `vnp_TxnRef`), `status`, `checkoutUrl`.
2. [V3__payment_idempotency.sql](../../backend/services/payment-service/src/main/resources/db/migration/V3__payment_idempotency.sql): **idempotency in the database.** UNIQUE on `booking_id` and `transaction_id`: duplicates are impossible even if the code has a bug.
3. [PaymentController.java:35-56](../../backend/services/payment-service/src/main/java/com/ben/services/controller/PaymentController.java#L35-L56): **three doors, three callers.** `/initiate` ← booking, `/vnpay/ipn` ← VNPay's server, `/vnpay/return` ← the user's browser.
4. [PaymentServiceImpl.java:43-85](../../backend/services/payment-service/src/main/java/com/ben/services/service/impl/PaymentServiceImpl.java#L43-L85) `initiatePayment`: **create once.** A payment already exists for this booking → return its link. Otherwise save `PENDING`, build the URL, store it.
5. [VnPayService.java:50-90](../../backend/services/payment-service/src/main/java/com/ben/services/service/gateway/VnPayService.java#L50-L90): **how a payment gateway integration works.** Build `vnp_*` params (amount × 100 at line 57, expiry at line 66), sort, sign with HMAC-SHA512; `isValidSignature` (line 73) does the reverse for VNPay's answers.
6. [RouteConfig.java:29-31](../../backend/cloud/api-gateway/src/main/java/com/ben/cloud/config/RouteConfig.java#L29-L31): **IPN is public.** VNPay has no login token, so this route skips JWT; the signature is the protection.
7. [PaymentRepository.java:27-29](../../backend/services/payment-service/src/main/java/com/ben/services/repository/PaymentRepository.java#L27-L29): **`@Lock(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`.** A second copy of the same IPN waits here.
8. [PaymentServiceImpl.java:88-143](../../backend/services/payment-service/src/main/java/com/ben/services/service/impl/PaymentServiceImpl.java#L88-L143) `handleVnPayIpn`: **the result handler.** Read the checks in order: signature (97) → find + lock (01) → amount (04) → still `PENDING`? (02) → paid or failed → event → 00. On an exception: `setRollbackOnly` + 99 so VNPay retries.
9. [PaymentServiceImpl.java:161](../../backend/services/payment-service/src/main/java/com/ben/services/service/impl/PaymentServiceImpl.java#L161) `checkVnPayReturn`: **display only today.** Becomes POST and handles cancel (code 24) in build step 3.

**Check yourself:** IPN and VNPay's retry arrive together; walk both through lines 88–143. Why is the IPN route public but still safe?

### Tour C: booking-service (orchestrator calling seat + payment)
Workflow position: the user clicks Book; booking drives the steps and undoes them on failure.

1. [SeatClient.java:17-31](../../backend/services/booking-service/src/main/java/com/ben/services/clients/SeatClient.java#L17-L31): **a remote call that looks like a method** (OpenFeign). Note `fallbackFactory`.
2. [SeatClientFallbackFactory.java:37-51](../../backend/services/booking-service/src/main/java/com/ben/services/clients/SeatClientFallbackFactory.java#L37-L51): **what the caller gets when the call fails.** A 409 (seat taken) passes through as 409; anything else becomes 503 "try again". It never pretends the hold worked.
3. [booking application.yaml:34-56](../../backend/services/booking-service/src/main/resources/application.yaml#L34-L56): **the circuit breaker settings.** Opens when ≥ 50% of the last 20 calls fail. A 409 is ignored, because "seat taken" doesn't mean seat-service is sick.
4. [BookingServiceImpl.java:69-138](../../backend/services/booking-service/src/main/java/com/ben/services/service/impl/BookingServiceImpl.java#L69-L138) `createBooking`: **the saga's forward steps and compensation.** Follow the numbered comments: (1) short transaction saves the booking (line 88), (2) hold seats (line 95), failure → `markCancelled` (line 101), (3) price + payment link (lines 107–125), failure → `markCancelled` + `releaseSeats` (lines 135–136).
5. [BookingServiceImpl.java:163](../../backend/services/booking-service/src/main/java/com/ben/services/service/impl/BookingServiceImpl.java#L163) `markCancelled`: **a short transaction via `TransactionTemplate`.** No Feign call ever runs inside a DB transaction.


**Check yourself:** the payment call fails after the seats were held. Which lines undo what? Why isn't `createBooking` annotated with `@Transactional`?

After the three tours: say **"start step 1"**.
