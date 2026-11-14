# Booking flow: final design (agreed section by section)

> Source of truth for the redesign agreed with the user (from 2026-10-17).
> Where this file and [PLAN.md](PLAN.md) disagree, **this file wins**. PLAN will be updated once all sections are agreed.
> Status: §1–13 agreed; final review G1–G4 approved (2026-10-21). Implementation follows §13; waiting for "start step 1".
> Overview page (architecture, lifecycle, timing, stages, CV bullets): https://claude.ai/artifact/KJ32p8eRupnxwimdtjTKSX
> Learning path (read code lesson by lesson, in build order): [LEARNING-PATH.md](LEARNING-PATH.md)
> Principle: design for common failures only. Rare cases (a service down for a long time, delayed network races) are accepted, not engineered for.

## Outline (fixed, so nothing gets lost)

| # | Section | Status |
|---|---|---|
| 1 | Saga scope, statuses, timing, sync-call resilience, compensation, recovery job | ✅ agreed (A1 applied) |
| 2 | Create booking (FE → booking → seat → payment) | ✅ agreed (A2–A4 applied) |
| 3 | Seat hold (seat-service) | ✅ agreed |
| 4 | Payment initiate + VNPay URL (payment-service) | ✅ agreed |
| 5 | User on the VNPay page + result page | ✅ agreed |
| 6 | VNPay result handler: IPN + return (payment-service) | ✅ agreed |
| 7 | Payment result → booking (consumer, late money) | ✅ agreed |
| 8 | Seat confirm / release (seat-service) | ✅ agreed |
| 9 | User cancel + recovery/expiry job | ✅ agreed |
| 10 | Notification | ✅ agreed |
| 11 | Outbox + Kafka retry / DLT | ✅ agreed |
| 12 | FE + API contract changes | ✅ agreed |
| 13 | Implementation order, what to undo, tests | ✅ agreed |

**Open decisions (need the user's answer):**
- **D1 ✅ decided:** no body check. Duplicate on the key (user + flight + seats) → handled by the existing booking's status as in §2, even if passengers or meals differ. To change content the user cancels first (My bookings → Cancel), then books again. No `request_hash` column. **Plus:** a response served from an existing booking carries `reused: true`; FE shows "You already have a pending booking for these seats" → [Continue previous booking] / [Cancel it and book with new details] (cancel API, then re-send). Prevents silently paying an old booking with a wrong passenger name.
- **D2 ✅ decided:** a `CONFIRMED` booking cannot be cancelled. FE hides the Cancel button; BE still enforces it (owner check + `status IN (PENDING, AWAITING_PAYMENT)`, else 409). Never trust the FE alone.
- **D3 ✅ decided:** include. Kafka is at-least-once, so `booking.confirmed` can arrive twice; Redis `SET NX` stops a second email (the right place for Redis: a side effect outside the DB, a lost key only means one extra email).
- **D4 ✅ decided:** skip `querydr`. The late-money path (§7) covers a late IPN.

**Amendments to agreed sections (found while designing §3–13):**
- **A1 (§1) ✅ applied:** "Payment result events require `AWAITING_PAYMENT`" is too strict: late money must also handle `EXPIRED` / `CANCELLED` (§7).
- **A2 (§2) ✅ applied (no code change):** Correction: the price is NOT taken from the client. Booking already computes it server-side from ids (fare, seat, ancillary, meal prices via Feign). Keep that.
- **A3 (§2) ✅ applied:** **Reads before writes:** the flight check and price calls run after the INSERT gate but **before** `seat.hold`. A pricing or ancillary failure then needs no seat release.
- **A4 (§2) ✅ applied:** Validation also checks `seats.size == passengers.size` (400).

**Final completeness check:** see the end of this file.

## 1. Saga scope, statuses, timing, sync-call resilience ✅ agreed

**Saga scope:** booking (orchestrator), seat, payment. Notification is outside the saga (fire-and-forget).

**Booking statuses (the status is the saga state):**
```
PENDING            seats being held / payment link being created
AWAITING_PAYMENT   VNPay link ready (checkout_url stored on booking)
CONFIRMED | CANCELLED | EXPIRED | REFUND_REQUIRED
```
- "Alive" statuses: `PENDING`, `AWAITING_PAYMENT`. Every guard that closes a booking (cancel, job) uses `status IN (PENDING, AWAITING_PAYMENT)`. Payment result events: normal path `AWAITING_PAYMENT`; late money also handles `EXPIRED` / `CANCELLED` (§7).
- FE: `BookingSuccess.jsx` must keep polling on `AWAITING_PAYMENT` as well as `PENDING`.

**Timing:** VNPay link 10 min, grace 2 min (late IPN), seat hold **15 min** (G2: covers the job running up to 1 min late). Our expiry always comes **after** VNPay's (never cancel while the link can still be paid).

**Forward steps (seat.hold, payment.initiate): sync Feign, fail fast**
- Feign connect/read timeouts (not TimeLimiter) → Resilience4j Retry (2–3×, backoff, only timeout / 503 / connection refused; ignore `CallNotPermittedException`; never 4xx) → circuit breaker → fallback **throws 503** (never fakes a hold or a link).
- On failure: booking `CANCELLED` + compensation.

**Compensation (seat release): sync fast path + durable retry**
```
tx: booking CANCELLED/EXPIRED + outbox "BookingCancelled"          ← durable to-do (guarantee)
after commit: seat.release sync, short timeout, no retry, ignore failure  ← seat free immediately
outbox → Kafka → seat consumer: release WHERE booking_id = ? AND status = HELD  ← no-op if already done; retries until seat is back
```
- Same release path for every case: compensation, user cancel, payment FAILED, expiry job.
- Saga pattern itself does not retry; the durable outbox row is what makes compensation survive downtime and crashes.
- Why release uses the outbox but confirm does not: confirm is only called from the `payment.completed` consumer, so that Kafka message (unacked until success) is its retry. Release has 4 triggers (payment.failed, create failure, booking cancel, job); 3 are not Kafka messages and have nothing that redelivers, so release gets its own durable to-do. One release path for all 4 (decided).
- A failed payment crosses Kafka twice (`payment.failed`, then `booking.cancelled`): normal in a saga (payment reports, booking decides, seat executes); seats are freed at once by the sync fast path.

**Recovery / expiry job (booking-service, on startup + every 1 min) = saga recovery from DB state**
- `PENDING` older than 1 min → orchestrator died mid-saga → `CANCELLED` + release. Always compensate, never resume forward (the user's request is already gone). Release is idempotent, so no per-step state is needed.
- `AWAITING_PAYMENT` past `payment_expires_at` + grace → `EXPIRED` + release (user never paid). This is what frees the seat in that case.
- 1 min > max forward-step time (timeouts × retries ≈ 30s), so it never cancels a booking still being created.

**Three mechanisms to reason about (draw these):** saga compensation (orchestrator alive) · recovery/expiry job (orchestrator crashed, user never paid) · outbox release (seat-service down).

**`held_until` lazy expiry:** kept because it is already in code and costs nothing; not needed for correctness, not drawn in the diagram.

**Accepted / skipped (rare):** a hold arriving after its own release; a service down so long the job cannot run. No "cancelled booking ids" table, no persisted failed-release table.

## 2. Create booking (FE → booking → seat → payment) ✅ agreed

**Data: booking DB moves to PostgreSQL** (other services stay MySQL; DB per service). Rewrite booking `V1__init.sql` in PG syntax, drop `V2__idempotency_keys.sql`, swap driver + `flyway-database-postgresql`, add a `postgres` service to compose. No Java change for the switch.
```sql
booking: user_id, flight_instance_id, seats_hash (sha256 of sorted seat ids),
         status, checkout_url, payment_expires_at, created_at, ...

CREATE UNIQUE INDEX uk_booking_active
  ON booking (user_id, flight_instance_id, seats_hash)
  WHERE status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED');
```
- Key = columns on `booking`, not a separate table. Freed when status leaves the active set (same UPDATE). One DB transaction, no Redis.
- Seat protection between different users is still seat-service's conditional update (section 3).

**Happy path**
```
1. validate (duplicate seat ids, seats.size != passengers.size → 400)
2. tx: INSERT booking PENDING                       ← idempotency gate (partial unique index)
2b. reads: flight check + price (fare, seat, ancillary, meal; server-side, sync) ← before any write, so a failure needs no release
3. seat.hold        (sync: timeout → retry → CB)
4. payment.initiate (sync: timeout → retry → CB)
5. tx: UPDATE booking SET AWAITING_PAYMENT, checkout_url, payment_expires_at WHERE id = ? AND status = PENDING
6. return checkout_url → FE redirects to VNPay
```
No Feign call inside a transaction.

**Duplicate requests** (refresh, Back, lost response, double click, 2 tabs): INSERT hits the duplicate key → SELECT the active booking → respond by status. Sequential and concurrent duplicates take the same path.

| Existing booking | Response |
|---|---|
| `PENDING` | 202 → FE spinner, re-sends the same POST every 1–2s, gives up after ~60–90s |
| `AWAITING_PAYMENT`, link valid | 200 + `checkout_url` |
| `AWAITING_PAYMENT`, link expired, within grace | 202 "checking payment…" → FE polls |
| `AWAITING_PAYMENT`, past grace | Expire inline (same UPDATE as the job + release), then create the new booking |
| `CONFIRMED` | 200 + bookingId → success page |
| `CANCELLED` / `EXPIRED` | Not in the index → new booking |

**Forward-step failures (before payment: fail fast + compensate)**

| Failure | Seats held? | Handling | User sees |
|---|---|---|---|
| Reads fail (flight / price) after retries | No (nothing written yet) | `CANCELLED` | 503 |
| Hold → 409 | No (all-or-nothing) | `CANCELLED` | 409 "Seat just taken" |
| Hold → timeout / 503 after retries (unknown) | Maybe | `CANCELLED` + release | 503 "Try again" |
| Hold → circuit open | No | `CANCELLED` | 503 |
| Initiate → fail after retries / circuit open | Yes | `CANCELLED` + release | 503 |
| Step 5 updates 0 rows (job already cancelled) | Released by the job | Error | 503 |

**Crash:** booking dies mid-saga → recovery job (section 1).

**Accepted edge case:** rebooking the same seats right after a cancel before the release lands → 409; retry fixes it.

**FE:** disable the Book button while in flight; axios timeout; on timeout or network error re-send the same request (safe); 202 → spinner; 409 / 503 → message.

## 3. Seat hold (seat-service) ✅ agreed

One statement, one transaction:
```sql
UPDATE seat_instance
SET status = 'HELD', booking_id = :bookingId, held_until = now + 15 min, version = version + 1
WHERE id IN (:seatIds)
  AND ( status = 'AVAILABLE'                                -- free
     OR (status = 'HELD' AND booking_id = :bookingId)       -- my own retry (idempotent)
     OR (status = 'HELD' AND held_until < now) )            -- abandoned (free extra)
→ updated == seatIds.size() ? OK : throw 409 (rolls back everything)
```
- Replaces the per-seat loop. MySQL locks the rows in primary-key order, so overlapping requests cannot deadlock.
- `SeatHoldRequest` gains `flightInstanceId`. Validate in the same transaction that every seat belongs to it (else 400).

| Case | Solution |
|---|---|
| Two users, same seat | The row lock serializes them; the loser matches 0 rows → 409 |
| Overlapping sets (5,6 vs 6,5) | One `IN` statement in PK order → no deadlock; all-or-nothing |
| Booking retries after a timeout | Own-booking clause → OK |
| Seat BOOKED / OCCUPIED / BLOCKED | 409 |
| Stale seat map | 409 → FE shows "seat just taken" and reloads the seat map |
| Seat not in this flight instance / not found | 400 |
| Duplicate / empty ids | Booking rejects with 400; seat de-duplicates defensively |
| Seat-service down | Handled on the booking side, §1 |

Config: `seat.hold-minutes` 20 → 15 (G2).

Skipped (user decision): guarding admin seat-status edits. Admin changes happen during maintenance; the admin endpoint stays as is.

## 4. Payment initiate + VNPay URL (payment-service) ✅ agreed

Called by booking (sync) with `bookingId`, `amount` (computed by booking, A2) and `userId`.

| Case | Solution |
|---|---|
| Normal | Payment `PENDING`, unique `transaction_id` (= `vnp_TxnRef`), `vnp_ExpireDate` = now + **10 min** (GMT+7), amount × 100, HMAC-SHA512 → store `checkout_url`; return `checkout_url` + **`expiresAt`**. Booking stores it as `payment_expires_at`, so there is one clock (payment's). |
| Retry / duplicate (same booking) | UNIQUE `booking_id` → existing `PENDING` → return the stored link + `expiresAt` |
| Retry arrives while the 1st call is still running | Both INSERT → the 2nd hits UNIQUE `booking_id` → catch the duplicate → re-read → return the existing link (missing in current code) |
| Existing payment for this booking is not `PENDING` | 409 bug guard: unreachable (initiate runs only while booking is `PENDING`; SUCCESS/FAILED needs the link, which only exists after `AWAITING_PAYMENT`) |
| Booking times out and compensates | Booking `CANCELLED`; the orphan payment stays `PENDING`. Harmless, because nobody received the link. |
| Abandoned payments | Stay `PENDING` forever. They are never touched, so a late IPN can still be recorded. |

Config: `vnpay.expire-minutes` 15 → 10. `PaymentInitiateResponse` gains `expiresAt`.

## 5. User on the VNPay page + result page ✅ agreed

After payment, VNPay does two things at the same time: (A) redirects the browser to our result page, and (B) calls IPN server-to-server. (A) usually arrives before (B) has updated the booking.

| Real problem | Solution |
|---|---|
| FE trusts the return URL | Display only; FE polls the booking status (source of truth) |
| User lands before the result is processed | Spinner while `AWAITING_PAYMENT`, poll every 3s up to ~30s, then "We're confirming your payment, check My bookings" |
| Payment fails (wrong OTP, no money…) | IPN → FAILED → booking `CANCELLED` + release |
| User cancels on VNPay (code 24; VNPay usually sends no IPN, only the browser redirect) | The return endpoint writes **only for code 24**: same handler → FAILED → booking `CANCELLED` + release, immediately |
| User silently leaves (Back / close / idle) | Seats stay HELD (normal checkout reservation). My bookings: Continue payment / Cancel. Otherwise job at ~12 min → `EXPIRED` + release |
| Back, then Book again | Same link + `reused: true` dialog (D1) |

**Result page** (one page, simple): spinner → `CONFIRMED` → "Booking confirmed" / `CANCELLED` or `EXPIRED` → "Payment failed or cancelled"; both with a link to My bookings. A failed VNPay transaction can't be re-paid with the same link, so retry = book again.

Seat timeline while the user is on VNPay: HELD 0–10 min (link) → job frees at ~12 min. HELD is a status, not a DB lock: other users just get 409.

## 6. VNPay result handler: IPN + return (payment-service) ✅ agreed

One handler `handleVnPayResult(params)`. **IPN** calls it for every result (source of truth for money). **Return** (`POST /vnpay/return`, called by the FE result page) calls it **only for code 24** (cancel, which usually has no IPN); for any other code it is read-only. So success and fail have a single writer (IPN). `FOR UPDATE` + status check still cover a slow IPN + VNPay retry, and a cancel that also gets an IPN. Locally without ngrok: replay the IPN URL by hand (VNPAY.md).

| Case | Solution |
|---|---|
| Bad signature | 97 |
| Unknown `vnp_TxnRef` | 01 |
| Amount mismatch | 04 |
| Concurrent IPNs | `SELECT ... FOR UPDATE` (pessimistic) |
| Already processed (not `PENDING`) | 02 |
| `vnp_ResponseCode = 00` and `vnp_TransactionStatus = 00` | `SUCCESS`, store `vnp_TransactionNo` + **outbox `payment.completed`** in the same transaction → 00 |
| Any other code (including 24) | `FAILED` + **outbox `payment.failed`** → 00 |
| Exception | `setRollbackOnly` → 99 → VNPay retries |
| Late IPN (booking already closed) | Payment does not look at the booking and records the IPN normally. Booking decides what to do (§7). |

Gateway: `GET /api/payments/vnpay/ipn` stays public (already done).

## 7. Payment result → booking (Kafka consumer) ✅ agreed

Events come from payment's outbox. Kafka key = `bookingId`, which keeps per-booking ordering.

**`payment.completed`:**
```
booking status?
  AWAITING_PAYMENT or EXPIRED → seat.confirm(bookingId, seatIds)  (sync Feign, outside tx, §8)
        ├─ OK        → tx: UPDATE CONFIRMED, paymentId WHERE status IN (AWAITING_PAYMENT, EXPIRED)
        │                 + outbox booking.confirmed (notification)
        └─ seats lost → tx: UPDATE REFUND_REQUIRED WHERE status IN (AWAITING_PAYMENT, EXPIRED) + release
  CANCELLED (user cancelled)  → REFUND_REQUIRED (do not revive a booking the user cancelled)
  CONFIRMED / REFUND_REQUIRED → duplicate → ignore
  PENDING                     → impossible (the user gets the link only after AWAITING_PAYMENT) → log, ignore
```
`seat.confirm` handles the normal case, retries and late money in one operation (§8), so `EXPIRED` needs no separate re-hold step.

**`payment.failed`:** `AWAITING_PAYMENT` → `CANCELLED` + release (compensation pattern, §1). Anything else → ignore.

| Case | Solution |
|---|---|
| Duplicate event | The state guards above |
| Seat-service down during confirm | Throw → Kafka retry with backoff → DLT |
| The job expires the booking at the same moment | Both use conditional UPDATEs; the consumer accepts `EXPIRED`. Ends `CONFIRMED` if the seats are still free (gap is milliseconds, so practically always), else `REFUND_REQUIRED` |
| Order: booking CONFIRMED but seats not BOOKED | Seats are confirmed **first**; the booking changes only after seat confirm succeeds |
| Some seats lost (late money) | Confirm is all-or-nothing → rollback → `REFUND_REQUIRED` + release the rest |
| Notification enrichment (flight, fare, user) | Feign calls run **before** the transaction; failures are non-fatal (as today) |

## 8. Seat confirm / release (seat-service) ✅ agreed

**Confirm** (internal sync endpoint, called by booking's consumer):
```sql
UPDATE seat_instance SET status = 'BOOKED', booking_id = :b, held_until = NULL
WHERE id IN (:ids)
  AND ( (status IN ('HELD','BOOKED') AND booking_id = :b)   -- normal + retry
     OR status = 'AVAILABLE'                               -- late money: hold was released, seat still free
     OR (status = 'HELD' AND held_until < now) )           -- late money: abandoned hold by someone else
→ updated == ids.size() ? OK : rollback → "seats lost" (409)
```
- Idempotent: a seat already BOOKED for this booking counts as OK. One statement covers the normal case, retries and late money.
- The `booking.confirmed` listener in seat-service is **removed**: booking confirms the seats synchronously (orchestration).

**Release** (sync fast-path endpoint + consumer of `booking.cancelled`):
`UPDATE ... SET AVAILABLE, booking_id = NULL, held_until = NULL WHERE booking_id = ? AND status = 'HELD'`. 0 rows is fine. It never touches `BOOKED` seats.
- `booking_id = :b` → never touches another booking's seat (delayed event after someone else held it).
- `status = HELD` → never frees paid seats (delayed release event arriving after late money confirmed them).
- Diagram with every case: https://claude.ai/artifact/BtE5Xjma3Jz48kHr1jwvPb

## 9. User cancel + recovery/expiry job (booking-service) ✅ agreed

**User cancel** (existing `PATCH /bookings/{id}/cancel`, contract unchanged; `cancelBooking(id)` → `cancelBooking(id, userId)`; today it has no owner check and cancels any status):
- Only the owner (`X-User-Id` = `booking.user_id`), else 403.
- `UPDATE CANCELLED WHERE id = ? AND status IN (PENDING, AWAITING_PAYMENT)` + outbox `booking.cancelled`, then the sync release fast path.
- 0 rows and already `CANCELLED` / `EXPIRED` → return it (idempotent). `CONFIRMED` → 409 (D2).
- Cancel while the user is paying in another tab → late money → `REFUND_REQUIRED` (accepted).

**Job** (on startup + every 1 min, batches of ~100):
- `PENDING` and `created_at` < now − 1 min → `CANCELLED` + release.
- `AWAITING_PAYMENT` and `payment_expires_at` + 2 min < now → `EXPIRED` + release.
- Each row: conditional UPDATE + outbox in one transaction, then the sync release fast path. Safe with several instances (state guard).
- Payment rows are never touched. `querydr`: D4.
- IPN during the grace period: the link expiry only stops new attempts on VNPay; payment never checks it. Booking is still `AWAITING_PAYMENT` and seats still `HELD`, so it takes the normal path. After grace → late money (§7).
- Needs `@EnableScheduling` (not set up in booking-service yet).

## 10. Notification ✅ agreed

- Consumes `booking.confirmed` (from booking's outbox; seat-service no longer consumes it).
- Dedupe (D3), **one key per channel**: `SET NX notif:{email|sms}:{bookingId} EX 1d` → key exists = already sent → skip; else send → on failure `DEL` the key and throw (Kafka retry → DLT). Stop swallowing send errors (today they are only logged).
- Email OK + SMS failed → the retry skips email, retries only SMS.
- Redis down → send anyway (fail open): a duplicate email beats a missing one. Redis losing keys (dev Redis: no persistence, LRU) → at worst one extra email.
- Needs `spring-boot-starter-data-redis` + Redis host config (container already in docker-compose.dev).
- An email failure must never affect the booking.
- No emails for cancel / expire / refund (out of scope).

## 11. Outbox + Kafka retry / DLT ✅ agreed

**Outbox table** (booking DB and payment DB): `id, aggregate_id (bookingId), topic, payload (json), created_at, published_at NULL`.

| Service | Events written in the same transaction as the state change |
|---|---|
| payment | `payment.completed`, `payment.failed` |
| booking | `booking.cancelled` (CANCELLED / EXPIRED / REFUND_REQUIRED with release), `booking.confirmed` |

**Relay** (`@Scheduled` every 500 ms in each service): `SELECT unpublished ORDER BY id LIMIT 100` → `kafkaTemplate.send(topic, key = aggregate_id, payload).get()` → set `published_at`.
- At-least-once: a crash between send and mark means the event is sent again, and consumers are idempotent.
- This project runs a single instance. Several instances would need `FOR UPDATE SKIP LOCKED` (noted, not built).
- Optional cleanup: delete published rows older than 7 days.

**Layers:** Resilience4j wraps one Feign call (seconds, blips). The Kafka error handler wraps the whole message (minutes, outages): it seeks back to the failed offset and re-polls with backoff; after the limit it parks the message in the DLT and commits, so later messages in the partition are not blocked. A crashed consumer resumes from the last committed offset. Offsets are committed only after success, so every consumer must be idempotent.
- Today: notification uses `FixedBackOff(5s, 2)` with no DLT (message dropped after 2 tries); booking and seat have no error handler.
- Relay stops at the first failed send to keep per-booking order.

**Kafka:**
- Producer: `acks=all`, idempotence on (the default).
- Consumers: `DefaultErrorHandler` + `ExponentialBackOff` (≈ 1s → 1 min, ~10 tries) + `DeadLetterPublishingRecoverer` → `<topic>.DLT`. The DLT is logged and replayed manually (no tooling).
- The offset is committed after the listener returns successfully (the default).

## 12. FE + API contract changes ✅ agreed

| Area | Change |
|---|---|
| `POST /bookings` | Drop the `Idempotency-Key` header. Responses: 200 `{bookingId, status, checkoutUrl, reused}` / 202 `{status: PENDING}` / 400 / 409 (seat taken) / 503 |
| `BookingResponse` | Add `checkoutUrl`, `paymentExpiresAt` |
| `BookingStatus` (common-lib) | Add `AWAITING_PAYMENT`, `EXPIRED`, `REFUND_REQUIRED` → FE badges and filters (My bookings, admin `BookingManagement`, `Ticket`) |
| `BookingReview.jsx` | Remove the UUID key; disable the button; `reused: true` → dialog Continue / Cancel and rebook (D1); axios timeout; on timeout or network error re-send; 202 → spinner and re-send; 409 → message + reload the seat map |
| `BookingSuccess.jsx` → result page | Spinner while `AWAITING_PAYMENT` (today it polls only on `PENDING`); success / failed view + link to My bookings (§5); sends the VNPay params to `POST /payments/vnpay/return` (§6) |
| My bookings (`BookingHistory`, `TravellerBookingCard`) | Existing bug: unknown status falls back to "Confirmed" → add the 3 new statuses, neutral fallback. "Complete Payment" button exists but has no `onClick` → show for `AWAITING_PAYMENT` while the link is valid, open `checkoutUrl`. Add **Cancel** (thunk exists, unused) for `PENDING` / `AWAITING_PAYMENT` only |
| Admin `BookingManagement` | Same fallback-to-CONFIRMED bug → add the 3 statuses, neutral fallback |
| `PATCH /bookings/{id}/cancel` | Same path; now 403 if not the owner, 409 if `CONFIRMED` |
| `GET /payments/vnpay/return` | → `POST` (writes on cancel) |

## 13. Implementation order, what to undo, tests ✅ agreed

1. **Booking → PostgreSQL:** new `V1__init.sql` (PG) with the new columns (`flight_instance_id`, `seats_hash`, `checkout_url`, `payment_expires_at`), new statuses, the partial unique index and the `outbox` table. Delete `V2__idempotency_keys.sql`. Add `postgres` to compose.
2. **Undo Step 3 idempotency:** the `Idempotency-Key` header, `IdempotencyService`, `IdempotencyRecord` + repository, and the FE key.
3. **Seat-service:** single `UPDATE ... IN` hold + `flightInstanceId` check, confirm as one statement, 15 min, release endpoint + `booking.cancelled` consumer, remove the `booking.confirmed` listener.
4. **Payment-service:** 10 min expiry, `expiresAt`, outbox table + relay (replaces the direct `kafkaTemplate.send`).
5. **Booking create flow** (§2 + A2–A4): gate, reads, hold, initiate, `AWAITING_PAYMENT`, duplicate handling, compensation pattern; Feign timeouts + Resilience4j Retry; booking outbox + relay.
6. **Booking consumers** (§7) + **cancel and job** (§9).
7. **Kafka retry / DLT** config for all consumers.
8. **Notification** dedupe (D3).
9. **FE** (§12).
10. **Tests** (Testcontainers): two users hold the same seat concurrently; concurrent duplicate create; concurrent IPN; duplicate Kafka event; job vs consumer race.

After each step: scan impacts + compile (PLAN §7). PLAN.md steps get renumbered to this order once approved.

**Existing code from old Steps 0–3 vs this design (checked 2026-10-21): continue, no rollback.** Remove column done 2026-10-22 (plus dead `BookingResponse.paymentLink`, FE `payment_link_url` fallback and unused `paymentDetails` sessionStorage); full backend clean compile + FE build pass.

| Keep as is | Modify (in the §13 step shown) | Remove (§13 step 1) |
|---|---|---|
| Flyway + `ddl-auto: validate` in all services | Seat hold loop → one `UPDATE … IN` (keep the own-booking + expired clauses); `hold-minutes` 20 → 15 (step 2) | `IdempotencyService`, `IdempotencyRecord` + repository |
| VNPay: `VnPayService` signing, IPN with `FOR UPDATE` + PENDING guard + `setRollbackOnly`/99, gateway IPN route | `confirmHeld` → new confirm statement as a sync endpoint (step 2) | booking `V2__idempotency_keys.sql` (booking V1 is rewritten for PostgreSQL) |
| Payment: unique `booking_id` / `transaction_id`, `checkout_url`, reuse existing PENDING (V2, V3 migrations) | Payment: catch duplicate insert, `expiresAt`, 10 min, shared handler, return → POST writing on code 24, outbox instead of direct send (step 3) | `Idempotency-Key` header in `BookingController` / `BookingService` signature |
| Seat: `HELD`, `booking_id`, `held_until` (V2), `InternalSeatHoldController`, `releaseHeld`, lazy expiry in availability queries | Booking create: partial-index gate, reads before writes, `AWAITING_PAYMENT`, outbox release (step 4) | FE `crypto.randomUUID()` key + header in `BookingReview` / `bookingThunk` |
| Booking: short transactions via `TransactionTemplate`, no Feign inside tx, compensation skeleton, `SeatClientFallbackFactory` (409 passthrough, 503) | `PaymentEventListener` → §7 consumer; `cancelBooking` owner check + guard (step 5) | |
| Ticket `seat_instance_id`, derived `getSeatInstanceIds()`; FE VNPay result polling; ₹ → $ | FE result page polls `AWAITING_PAYMENT`; status badges (step 7) | |

## Final review findings (2026-10-21) — G1–G4 approved, G5 dropped

Found by re-walking the whole flow against the current code after §1–13 were agreed.

- **G1 (approved) Tickets of dead bookings stay `BOOKED`.** Tickets are issued with status `BOOKED` at the `PENDING` insert and nothing changes them when the booking is cancelled or expires, so an e-ticket / check-in could use a ticket that was never paid. Fix: in the same tx that closes a booking (`CANCELLED` / `EXPIRED` / `REFUND_REQUIRED`), `UPDATE ticket SET status = CANCELLED WHERE booking_id = ?`. One statement in the shared close method.
- **G2 (approved) Hold can lapse before the job runs.** Job fires at link expiry + 2 min grace, but runs once a minute, so up to ~13 min after the hold started; with a 13 min hold the seat can become free while the booking is still `AWAITING_PAYMENT`. Fix: hold **15 min** (10 link + 2 grace + 1 job interval + 2 margin). Config only.
- **G3 (approved) `GET /bookings/{id}` has no owner check** and will now return `checkoutUrl`. Fix: owner, or airline/admin role, else 403.
- **G4 (approved) Admin cannot find `REFUND_REQUIRED` bookings.** Fix: a status filter / tab for it in admin `BookingManagement` (part of §12 status work).
- **G5 dropped (user decision):** no special timezone handling; keep the existing VNPay date formatting.
- **G6 Implementation notes:** new `BookingCancelledEvent` in common-lib + trusted packages in consumers; new topic `booking.cancelled` and `*.DLT` topics (broker auto-create is on; or `NewTopic` beans). `seats_hash` uses the passengers' `seatInstanceId`s already in the request, so no Feign call is needed before the INSERT gate.

## Final completeness check

**State machines (every status has an exit; nothing gets stuck):**

| Booking status | Exits |
|---|---|
| `PENDING` | → `AWAITING_PAYMENT` (create OK) · `CANCELLED` (forward failure, job after 1 min, user cancel) |
| `AWAITING_PAYMENT` | → `CONFIRMED` / `REFUND_REQUIRED` (paid) · `CANCELLED` (payment failed, user cancel) · `EXPIRED` (job) |
| `EXPIRED` | → `CONFIRMED` / `REFUND_REQUIRED` (late money) · otherwise final |
| `CANCELLED` | → `REFUND_REQUIRED` (late money) · otherwise final |
| `CONFIRMED`, `REFUND_REQUIRED` | Final (the admin refunds manually) |

| Seat status | Exits |
|---|---|
| `AVAILABLE` | → `HELD` (hold) · `BOOKED` (late-money confirm) · `BLOCKED` (admin) |
| `HELD` | → `BOOKED` (confirm) · `AVAILABLE` (release; or anyone after `held_until`) |
| `BOOKED` | Final in scope (`OCCUPIED` at check-in is outside the saga) |

| Payment status | Exits |
|---|---|
| `PENDING` | → `SUCCESS` / `FAILED` (IPN), or stays `PENDING` forever (abandoned; harmless) |

**Every call / event × failure mode:**

| Call / event | Definite failure | Timeout (unknown) | Duplicate | Crash before / after commit |
|---|---|---|---|---|
| FE → create | 4xx/503 message | Re-send (idempotent) | Partial index | Job cleans `PENDING` |
| booking → seat.hold | 409 → `CANCELLED` | Retry → release | Own-booking clause | Job |
| booking → payment.initiate | → `CANCELLED` + release | Retry → release | UNIQUE `booking_id` | Job |
| VNPay → IPN | 97 / 01 / 04 | VNPay retries | `FOR UPDATE` + 02 | Rollback → 99 → retry |
| payment → Kafka | — | — | Consumer guards | Outbox |
| booking consumer → seat.confirm | Lost → `REFUND_REQUIRED` | Kafka retry | Idempotent confirm | Kafka redelivers |
| release (sync + outbox) | Ignored → outbox retries | Outbox retries | 0 rows OK | Outbox |
| Job | — | — | State guard | Next run |
| Notification | Kafka retry | Kafka retry | Redis `SET NX` | Kafka redelivers |
