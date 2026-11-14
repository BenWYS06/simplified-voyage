# VNPay replaces Razorpay / Stripe

Date: 2026-10-12 · Status: **done** (refactor step 1), extended on 2026-10-26 (build step 7). Decision record: [ADR-003](../decisions.md). Setup and testing: [VNPAY.md](../booking-refactor/VNPAY.md).

## Intent

The original project took payments with Razorpay (and had unused Stripe code). The result was confirmed by the browser calling `POST /verify`, which ran twice under React `StrictMode` and again on refresh, publishing `payment.completed` each time. The target market is Vietnam, and the refactor needs a payment provider with a server-to-server result to practise idempotent result handling.

## Goals

- One payment provider: **VNPay sandbox**.
- The server decides the payment result from VNPay's **IPN** (server to server, signed). The browser return is display only.
- The IPN is safe to receive twice and safe against two at the same moment.
- Booking, frontend and Feign contracts stay as close as possible to today (`checkoutUrl` keeps its name).

## Non-goals

- Refund API calls (refunds stay manual; see `REFUND_REQUIRED` in the saga design).
- Currency conversion: amounts are sent as VND with the same number.

## How it works

```
booking → POST /api/payments/initiate → PENDING payment + URL signed with HMAC-SHA512 (vnp_TxnRef = transactionId)
browser → VNPay → pays
VNPay   → GET /api/payments/vnpay/ipn  (public route) → signature → order → amount → only PENDING → SUCCESS / FAILED → event
browser → /booking-success → POST /api/payments/vnpay/return → display (re-reads the booking until it is final)
```

- `VnPayService`: build the URL, verify signatures with a constant-time compare, amount × 100.
- IPN answers: `00` ok, `01` not found, `02` already processed, `04` wrong amount, `97` bad signature, `99` error (VNPay retries).
- Gateway: the IPN route is public and has its `X-User-*` headers removed.
- `V2__vnpay_provider.sql`: the provider enum becomes `('VNPAY')` (expand → migrate old rows → contract).
- `V3__payment_idempotency.sql`: unique `booking_id`, `transaction_id`, `provider_payment_id` + `checkout_url`, so one booking has one payment and `initiate` returns the same link when called again.
- IPN under a pessimistic lock (`findByTransactionIdForUpdate`); an exception sets rollback-only and answers `99`, so a half-done result is never committed.

## Removed

`RazorpayService`, `razorpay-java`, `stripe-java`, `POST /verify`, `PaymentVerifyRequest`, `PaymentLinkResponse`, the payment-service `UserClient` (only Razorpay used it), the `razorpayOrderId` field, and the frontend Razorpay leftovers.

## Later changes (build step 7, 2026-10-26)

- One shared result handler for the IPN and the return.
- `GET /vnpay/return` → `POST`, and it writes for code `24` only (user cancelled on VNPay, which sends no IPN) → payment `FAILED` → booking `CANCELLED`, seats released.
- Link lifetime 15 → 10 minutes, so link + 2-minute IPN grace stays inside the 15-minute seat hold.
- Events written through the outbox inside the IPN transaction (build step 6).
