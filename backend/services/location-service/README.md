# location-service

Owns cities and airports, the reference data every flight points to.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- City and airport CRUD (writes are system-admin only, enforced at the gateway and with `@RequiredRoles`)
- City search and lookup by country or code

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/cities/**`, `/api/airports/**` |
| Database | MySQL `airline_location_db` |
| Tables | `cities`, `airports` |
| Kafka | none |
| Calls | none |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#location-service).

## Configuration

- Shared settings: `config-repo/location-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- Redis cache (`cities`, `airports`, `allAirports`, `airportsByCity`); if Redis is down the cached methods fall through to the database
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/location-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/location-service && mvn spring-boot:run
```
