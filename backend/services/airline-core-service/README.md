# airline-core-service

Owns airlines and their aircraft, including the airline approval lifecycle run by the system admin.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Airline registration by an airline owner; approve / suspend / ban by the system admin
- Aircraft fleet per airline (used for seat maps and flights)
- Dropdown lists for the frontend

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/airlines/**`, `/api/aircrafts/**` |
| Database | MySQL `airline_core_db` |
| Tables | `airlines`, `aircrafts` |
| Kafka | none |
| Calls | none |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#airline-core-service).

## Configuration

- Shared settings: `config-repo/airline-core-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- Redis cache (`airlines`, `airlinesByOwner`, `airlinesDropdown`, `aircrafts`): slow-changing reference data
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/airline-core-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/airline-core-service && mvn spring-boot:run
```
