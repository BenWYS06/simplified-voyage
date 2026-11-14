# VNPay: integration flow and how to test it

## 1. Flow

```
1. FE  POST /api/bookings ──► booking-service ──Feign──► payment-service POST /api/payments/initiate
                                                            ├─ save Payment PENDING (transactionId = vnp_TxnRef)
                                                            └─ VnPayService.createPaymentUrl(): vnp_* params + vnp_SecureHash
   FE receives checkoutUrl → window.location = checkoutUrl (VNPay payment page)

2. The user pays on VNPay

3. VNPay ──server→server──► GET /api/payments/vnpay/ipn?vnp_...        ← SOURCE OF TRUTH
      bad signature → 97 | order not found → 01 | wrong amount → 04 | already processed → 02
      OK → Payment SUCCESS/FAILED → Kafka payment.completed / payment.failed → 00
      (then booking-service CONFIRMED → seats BOOKED → email)

4. VNPay ──browser redirect──► FE /booking-success/{bookingId}?vnp_...
      FE calls POST /api/payments/vnpay/return?vnp_... → checks the signature; code 24 (user cancelled, usually no IPN) is recorded here via the same handler as the IPN, everything else is for DISPLAY
      FE re-reads the booking every 3s (up to 30s) until it is no longer PENDING
```

Why the IPN is the source of truth: the user's browser can close the tab, lose the connection or edit the URL. The IPN is called directly by VNPay's server and signed with HMAC-SHA512, whose secret only VNPay and we know.

Why `02` when already processed: VNPay may call the IPN several times (it retries when it gets no response). Later calls must not be processed again (no second event published). This is basic idempotency; Step 3 makes it stronger (unique constraints, row lock).

## 2. Main code

| File | Role |
|---|---|
| `payment-service/.../service/gateway/VnPayService.java` | Builds the signed URL, verifies signatures, converts the amount (× 100) |
| `payment-service/.../service/impl/PaymentServiceImpl.java` | `initiatePayment`, `handleVnPayIpn`, `handleVnPayReturn` (both use `applyVnPayResult`) |
| `payment-service/.../controller/PaymentController.java` | `GET /vnpay/ipn`, `POST /vnpay/return` |
| `api-gateway/.../config/RouteConfig.java`, `SecurityConfig.java` | Public IPN route (VNPay has no JWT) |
| `frontend/src/pages/traveler/BookingSuccess/BookingSuccess.jsx` | Calls `/vnpay/return`, re-reads the booking |

## 3. Configuration (env)

Sandbox sign-up: https://sandbox.vnpayment.vn/devreg/ → an email arrives with `vnp_TmnCode` and `vnp_HashSecret`.

| Variable | Meaning | Default |
|---|---|---|
| `VNPAY_TMN_CODE` | Website (merchant) code | `CHANGE_ME` |
| `VNPAY_HASH_SECRET` | Secret for the HMAC-SHA512 signature | `CHANGE_ME` |
| `VNPAY_PAY_URL` | Payment page | sandbox `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` |
| `VNPAY_RETURN_URL` | FE page that receives the redirect (`/{bookingId}` is appended) | `http://localhost:5173/booking-success` |

- Running from the IDE: set these variables in `payment-service`'s Run Configuration (Environment variables).
- Running with Docker: fill them in `docker/.env` (copied from `docker/.env.example`).

Sandbox test card (from VNPay's docs): bank NCB, card number `9704198526191432198`, name `NGUYEN VAN A`, issue date `07/15`, OTP `123456`.

## 4. Testing the IPN without ngrok

VNPay on the internet can't reach `localhost`, so the IPN never arrives on its own. But the browser redirect URL (step 4) carries **exactly the same parameters and signature** as the IPN. To simulate it:

1. After paying, the browser lands on `http://localhost:5173/booking-success/12?vnp_Amount=...&vnp_SecureHash=...`
2. Copy everything after the `?`
3. Call `GET http://localhost:5000/api/payments/vnpay/ipn?<the copied part>` (Postman or the browser)
4. Result `{"RspCode":"00",...}` → the booking becomes CONFIRMED. Call it a 2nd time → `{"RspCode":"02",...}` (idempotency).

## 5. ngrok (to have VNPay call the real IPN)

ngrok opens a tunnel from a public URL to your machine: `https://xxx.ngrok-free.app` → `localhost:5000` (api-gateway).

```
1. Install: winget install ngrok.ngrok   (or download from https://ngrok.com/download)
2. Create a free account, copy the authtoken from the dashboard
3. ngrok config add-authtoken <token>
4. ngrok http 5000
   → shows "Forwarding https://xxx.ngrok-free.app -> http://localhost:5000"
5. Register the IPN URL https://xxx.ngrok-free.app/api/payments/vnpay/ipn with VNPay
   (sandbox merchant admin page, or as described in the sign-up email)
```

- The free plan includes 1 static domain (see Domains in the ngrok dashboard): use `ngrok http --url=<domain> 5000` so the URL stays the same each run and doesn't need registering again.
- ngrok's page at `http://127.0.0.1:4040` shows every request VNPay sends, handy for inspecting IPNs.
