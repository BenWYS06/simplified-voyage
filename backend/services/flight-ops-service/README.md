# flight-ops-service

Owns flights, schedules and dated flight instances, and serves flight search.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Flights and schedules per airline; flight instances (a flight on a date) created one by one or in batches
- Publishes `flight-instance-created` so seat-service builds the seat inventory for that instance
- Flight search by route, date and cabin class, with the lowest fare per result

## At a glance

| | |
|---|---|
| Port | 5002 |
| API | `/api/flights/**`, `/api/flight-instances/**`, `/api/flight-schedules/**`, `GET /api/flights/search` |
| Database | MySQL `airline_flight_db` |
| Tables | `flights`, `flight_schedules`, `flight_instances` |
| Kafka | produces `flight-instance-created` |
| Calls | airline-core-service, location-service, seat-service, pricing-service |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#flight-ops-service).

## Configuration

- Shared settings: `config-repo/flight-ops-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- Redis cache `flightInstances`
- Search makes Feign calls per result today; the [observability plan](../../../docs/plans/2026-10-31-observability-and-performance.md) measures and fixes this (F1)
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/flight-ops-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/flight-ops-service && mvn spring-boot:run
```
