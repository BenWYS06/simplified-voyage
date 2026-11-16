# payment-service

Creates one payment per booking with a signed VNPay URL and records the result VNPay reports.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- `POST /api/payments/initiate` (from booking-service): the same booking gets the same payment and link
- VNPay IPN (public, signed): the source of truth for the result, safe to receive twice
- VNPay return (from the frontend): display only, except code 24 (user cancelled)
- Payment lists for the admin pages
- Rules: [CLAUDE.md](CLAUDE.md); setup: [VNPAY.md](../../../docs/booking-refactor/VNPAY.md)

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/payments/**` |
| Database | MySQL `airline_payment_db` |
| Tables | `payments` (unique booking, transaction and provider ids), `outbox` |
| Kafka | produces `payment.completed`, `payment.failed` through the outbox (key = bookingId) |
| Calls | none |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#payment-service).

## Configuration

- Shared settings: `config-repo/payment-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_RETURN_URL` from the environment
- `vnpay.expire-minutes: 10`
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/payment-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/payment-service && mvn spring-boot:run
```

Tests: `VnPayIpnConcurrencyTest` (Testcontainers MySQL): the same signed IPN twice at once gives one SUCCESS and one event. Run them with `mvn test -am -pl services/payment-service -Dsurefire.failIfNoSpecifiedTests=false` while Docker is running.
