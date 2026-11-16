# booking-service

The saga orchestrator of the booking flow: creates bookings, holds seats, starts the payment, and finishes or compensates the booking from the payment result.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- `POST /api/bookings`: idempotent create (201 / 202 / 200 reused / 409), returns the VNPay link
- Passengers (a snapshot per booking) and tickets (one per seat)
- Consumes the payment result: confirm seats and mark `CONFIRMED`, or compensate (`CANCELLED`, `EXPIRED`, `REFUND_REQUIRED`)
- Cancel by the owner, an expiry / recovery job, booking history and airline statistics
- Design and rules: [CLAUDE.md](CLAUDE.md), [DESIGN.md](../../../docs/booking-refactor/DESIGN.md)

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/bookings/**`, `/api/tickets/**`, `/api/passengers/**` |
| Database | **PostgreSQL** `airline_booking_db` |
| Tables | `bookings` (partial unique index `uk_booking_active`), `passengers`, `tickets`, `booking_ancillaries`, `booking_meals`, `outbox` |
| Kafka | consumes `payment.completed`, `payment.failed`; produces `booking.confirmed`, `booking.cancelled` through the outbox |
| Calls | seat-service, payment-service, flight-ops-service, pricing-service, ancillary-service, user-service, airline-core-service (Feign with timeouts, retry on transient errors, fallbacks that throw) |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#booking-service).

## Configuration

- Shared settings: `config-repo/booking-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- `app.scheduling.enabled` (default true) turns the outbox relay and the expiry job on; tests switch it off
- Scheduler pool of 2, so a relay blocked on Kafka can't stall the expiry job
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/booking-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/booking-service && mvn spring-boot:run
```

Tests: `BookingSagaConcurrencyTest` (Testcontainers PostgreSQL): duplicate create, duplicate `payment.completed`, expiry job vs payment. Run them with `mvn test -am -pl services/booking-service -Dsurefire.failIfNoSpecifiedTests=false` while Docker is running.
