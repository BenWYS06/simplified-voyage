# payment-service

> Loaded when working inside `backend/services/payment-service/`. Design: [DESIGN.md](../../../docs/booking-refactor/DESIGN.md) §4–6. VNPay setup and testing: [VNPAY.md](../../../docs/booking-refactor/VNPAY.md).

## Role

Creates **one payment per booking** and a signed VNPay payment URL, then records the result VNPay reports. It publishes `payment.completed` / `payment.failed`; booking-service finishes the saga from there.

```
POST /api/payments/initiate      (called by booking)   → PENDING payment + signed checkoutUrl (same booking again → same payment, same link)
GET  /api/payments/vnpay/ipn     (VNPay → us, public)  → verify → lock row → SUCCESS / FAILED + outbox event → RspCode
POST /api/payments/vnpay/return  (browser, via FE)     → verify; display only, except code 24 (user cancelled) → FAILED
```

## Rules for this service

- **MySQL**, Flyway `V1`–`V4` (`V3` unique constraints for idempotency, `V4` outbox).
- **The IPN is the source of truth.** The return URL only displays, except code 24 (VNPay sends no IPN when the user cancels).
- **IPN handling order** (`applyVnPayResult`): signature (constant-time compare) → `findByTransactionIdForUpdate` (row lock) → amount → only a `PENDING` payment changes → status + outbox event in **one transaction**. Any exception → `setRollbackOnly()` + RspCode 99, so VNPay retries.
- A duplicate IPN finds the payment no longer `PENDING` and answers `02` without a second event.
- Events go through the **outbox** (`OutboxWriter` with `Propagation.MANDATORY`), never `kafkaTemplate.send` in a request. `OutboxRelay` sends in id order with key = bookingId.
- Link lifetime `vnpay.expire-minutes: 10` stays shorter than the seat hold (15 min) minus the IPN grace (2 min).
- `VNPAY_TMN_CODE` and `VNPAY_HASH_SECRET` come from the environment only.

## Key classes

| Class | Purpose |
|---|---|
| `service/gateway/VnPayService` | Build and sign the URL (HMAC-SHA512), verify signatures, amount × 100 |
| `service/impl/PaymentServiceImpl` | Initiate (idempotent per booking), IPN + return handling |
| `repository/PaymentRepository` | `findByTransactionIdForUpdate` (pessimistic lock for the IPN) |
| `event/PaymentEventProducer` | Writes `payment.completed` / `payment.failed` to the outbox |
| `outbox/*` | Outbox table, writer, relay, cleanup |

## Verify

```bash
cd backend && mvn -q compile -pl services/payment-service -am
mvn test -am -pl services/payment-service -Dsurefire.failIfNoSpecifiedTests=false   # Docker on: VnPayIpnConcurrencyTest
```
