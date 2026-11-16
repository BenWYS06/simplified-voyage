# ancillary-service

Owns the extras sold with a ticket: meals, insurance and other ancillaries, configured per airline, flight and cabin class.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Airline catalogue of ancillaries and meals
- What is offered on a flight and cabin (`flight-cabin-ancillaries`, `flight-meals`)
- Insurance coverage details
- Price totals for a selection (`/price/total`) used by booking-service

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/ancillaries/**`, `/api/meals/**`, `/api/flight-meals/**`, `/api/flight-cabin-ancillaries/**`, `/api/insurance-coverages/**` |
| Database | MySQL `airline_ancillary_db` |
| Tables | `ancillaries`, `meals`, `flight_meals`, `flight_cabin_ancillaries`, `insurance_coverages` |
| Kafka | none |
| Calls | airline-core-service, location-service, seat-service |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#ancillary-service).

## Configuration

- Shared settings: `config-repo/ancillary-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- Default insurance currency is USD
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/ancillary-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/ancillary-service && mvn spring-boot:run
```
