---
name: new-service
description: Adds a new Spring Boot microservice wired the same way as the existing ones - Maven module, config-repo file, Eureka, gateway route, Flyway baseline, Docker image and compose entry, docs. Use when the user asks for a new service. Ask first whether an existing service should own the feature instead.
---

# Add a microservice

**First question:** does this need its own service? A new service means another DB, another deploy and more network calls. If an existing service owns the domain, add the feature there (`/add-feature`). Record the choice as an ADR in `docs/decisions.md`.

## Checklist (copy the closest existing service, e.g. `location-service`)

1. **Module**
   - `backend/services/<name>-service/` with `pom.xml` (parent: services pom, artifact `<name>-service`), `com.ben.services` packages.
   - Add `<module>` to `backend/services/pom.xml`.
   - Depend on `common-lib` for DTOs, exceptions, `@RequiredRoles`.
2. **Config**
   - `src/main/resources/application.yaml`: `spring.application.name`, `spring.config.import: optional:configserver:`, datasource with env placeholders, `ddl-auto: validate`.
   - `config-repo/<name>-service.yml`: the shared values. No secrets.
3. **Database**
   - Add the DB to `docker/init-databases.sql` (MySQL) and a `<name>db` container in `docker/docker-compose.yml`.
   - `db/migration/V1__init.sql` + `spring-boot-starter-flyway` and `flyway-mysql`.
4. **Gateway**
   - Route `/api/<resource>/**` to `<name>-service` (load-balanced through Eureka) in `backend/cloud/api-gateway/.../config/RouteConfig.java`, same shape as the other routes.
   - Roles: `@RequiredRoles` on the controllers. `/internal/**` is never routed.
5. **Docker**
   - Jib image `ben/gds-<name>:1.0.0` (same plugin config as the other services).
   - Compose service with `depends_on` config-server, service-registry and its DB. New env vars go into `docker/.env.example`.
6. **Callers**
   - Feign client with a fallback (factory) in each calling service.
   - Frontend thunk + slice if the UI uses it.
7. **Docs**
   - `backend/services/<name>-service/README.md` (same layout as the others).
   - Row in the `CLAUDE.md` services table, the README services table, `docs/architecture.md` diagram, `docs/api-spec.md`.
   - CHANGELOG line.

## Verify

```bash
cd backend && mvn -q compile -pl services/<name>-service -am
docker compose -f docker/docker-compose.yml config --quiet
```
Then `/qc-check` phase 3 (drift scan) to confirm nothing was missed.
