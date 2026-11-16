# pricing-service

Owns fares, fare rules and baggage policies per flight and cabin class.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Fares per flight and cabin class (base fare, taxes and fees, airline fees)
- Lowest fare per flight and cabin class for search
- Batch lookups by ids and a fare search for other services
- Fare rules (change / cancel conditions) and baggage policies

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/fares/**`, `/api/fare-rules/**`, `/api/baggage-policies/**` |
| Database | MySQL `airline_pricing_db` |
| Tables | `fares`, `fare_rules`, `baggage_policies` |
| Kafka | none |
| Calls | none |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#pricing-service).

## Configuration

- Shared settings: `config-repo/pricing-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- Redis cache `fares`, `faresByFlight`
- booking-service prices a booking from these fares on the server; a client never sends a price
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/pricing-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/pricing-service && mvn spring-boot:run
```
