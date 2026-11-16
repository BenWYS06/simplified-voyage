# api-gateway

The only entry point for the frontend and VNPay: validates Keycloak JWTs, resolves the local user, sets trusted identity headers and routes to the services through Eureka.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- OAuth2 resource server: JWT signature, expiry and issuer checked against Keycloak (realm `ben-airline`)
- Removes any incoming `X-User-*` headers and sets `X-User-Id` / `X-User-Roles` from the token; Keycloak `sub` to user id through user-service (cached)
- Routes `/api/**` to the services (`RouteConfig`), never `/internal/**`
- Public: `/actuator/health`, `/actuator/info`, `/fallback`, `GET /api/payments/vnpay/ipn`; a few admin-only rules (`SecurityConfig`)
- CORS for the frontend on :5173
- Details: [docs/authentication.md](../../../docs/authentication.md)

## At a glance

| | |
|---|---|
| Port | 5000 |
| API | every public path (see [api-spec.md](../../../docs/api-spec.md)) |
| Database | none |
| Tables | none |
| Kafka | none |
| Calls | user-service (`/internal/users/resolve`) and every routed service |

Full endpoint list with access rules: [docs/api-spec.md](../../../docs/api-spec.md#conventions).

## Configuration

- Shared settings: `config-repo/api-gateway.yml`; defaults and env placeholders: `src/main/resources/application.yaml`.
- `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_AUDIENCE`, `INTERNAL_API_KEY`, `USER_ID_CACHE_TTL`
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl cloud/api-gateway -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/cloud/api-gateway && mvn spring-boot:run
```
