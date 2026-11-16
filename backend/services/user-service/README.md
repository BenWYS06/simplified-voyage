# user-service

Owns user profiles and maps a Keycloak identity (`sub`) to the local numeric user id and app role that every other service uses.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Profile reads for the current user and by id; user list for admins
- `/internal/users/resolve`: called by the gateway on each new token subject; finds the user by Keycloak id, links an existing account by email, or creates one (`409` on an identity conflict)

## At a glance

| | |
|---|---|
| Port | dynamic (registered in Eureka, reached through the gateway) |
| API | `/api/users/**`, `/internal/users/resolve` |
| Database | MySQL `airline_user` |
| Tables | `users` |
| Kafka | none |
| Calls | none (called by the gateway and booking-service) |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#user-service).

## Configuration

- Shared settings: `config-repo/user-service.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- `INTERNAL_API_KEY`: shared secret checked on `/internal/users/resolve`
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Schema

Flyway migrations in `src/main/resources/db/migration/`; Hibernate only validates. A schema change is a new `V{n}__*.sql` file.

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/user-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/user-service && mvn spring-boot:run
```
