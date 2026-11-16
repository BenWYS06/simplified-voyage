# seat-service

Owns seat maps, cabin classes and seat instances (one row per seat per flight instance), and holds, confirms and releases seats for bookings.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Cabin classes and seat maps per aircraft
- Seat instances generated when a flight instance is created
- Seat hold / confirm / release for booking-service: one conditional UPDATE each, idempotent per booking, all or nothing (details in [CLAUDE.md](CLAUDE.md))
- Seat map and availability for the frontend; an expired hold counts as free

## At a glance

| | |
|---|---|
| Port | 5005 |
| API | `/api/cabin-classes/**`, `/api/seat-maps/**`, `/api/seats/**`, `/api/seat-instances/**`, `/api/flight-instance-cabins/**`, `/internal/seat-instances/**` |
| Database | MySQL `airline_seat_db` |
| Tables | `cabin_classes`, `seat_maps`, `seats`, `flight_instance_cabins`, `seat_instances` |
| Kafka | consumes `flight-instance-created` and `booking.cancelled` (release); retry with backoff, then `<topic>.DLT` |
| Calls | airline-core-service |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#seat-service).

## Configuration

- Shared settings: `config-repo/seat-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- `seat.hold-minutes: 15` (longer than the VNPay link + IPN grace)
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/seat-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/seat-service && mvn spring-boot:run
```

Tests: `SeatHoldConcurrencyTest` (Testcontainers MySQL): 10 bookings on one seat give 1 winner; overlapping seats in opposite order give no deadlock. Run them with `mvn test -am -pl services/seat-service -Dsurefire.failIfNoSpecifiedTests=false` while Docker is running.
