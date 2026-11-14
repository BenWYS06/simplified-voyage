# Environments and configuration

How configuration reaches each service, which environment variables exist, and what differs between the ways of running the system.

## 1. Where config comes from

```
service application.yaml   (defaults, env placeholders: ${VNPAY_TMN_CODE:CHANGE_ME})
        ▲ overridden by
config-server :8888  ──reads──►  config-repo/application.yml (shared) + config-repo/<service>.yml
        ▲ overridden by
environment variables  (docker/.env through Docker Compose, or exported in your shell)
```

- `spring.config.import: optional:configserver:http://localhost:8888`: a service still starts without config-server (it uses its own `application.yaml`).
- config-server reads `config-repo/` from Git: `CONFIG_GIT_URI` (default: this repository on GitHub) and `CONFIG_GIT_LABEL` (default `main`). To test local, uncommitted config changes, use `docker/docker-compose.local-config.yml`.
- A key that exists both in `config-repo/<service>.yml` and the service's `application.yaml` must be changed in both.

## 2. Ways to run

| | Local dev | Full Docker |
|---|---|---|
| Compose file | `docker/docker-compose.dev.yml` | `docker/docker-compose.yml` |
| Infra | MySQL :3306 (all DBs), PostgreSQL :5432, Redis :6379, Kafka :9092 | One DB container per service, Redis, Kafka, Keycloak :8180 |
| Services | From the IDE / `mvn spring-boot:run` | `ben/gds-*` images built with Jib |
| Gateway | :5000 | :5000 |
| DB host ports | 3306 / 5432 | user 3302, airline-core 3303, flight-ops 3304, location 3305, pricing 3307, ancillary 3308, payment 3310, seat 3311, booking (PostgreSQL) 5433; all bound to `127.0.0.1` |
| Used for | Daily coding, running one or two services | Whole-system checks, demos |

Tests (Testcontainers) start their own MySQL / PostgreSQL containers and need only Docker running.

## 3. Environment variables

Copy `docker/.env.example` to `docker/.env` and fill it in. Never commit `.env`. For services started from the IDE, set the same variables in the run configuration.

| Variable | Used by | Default / example | Notes |
|---|---|---|---|
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | keycloak | `admin` / *(set it)* | Admin console login |
| `KEYCLOAK_HOSTNAME` | keycloak | `http://localhost:8180` | Must match the token issuer |
| `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI` | api-gateway | realm `ben-airline` on :8180 | JWT validation |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_AUDIENCE` | api-gateway | `airline-api` | Roles are read from this client |
| `INTERNAL_API_KEY` | api-gateway, user-service | `local-development-only-key` | Shared secret for `/internal/users/resolve`; use a long random value |
| `USER_SERVICE_URL`, `USER_ID_CACHE_TTL` | api-gateway | `http://user-service`, `PT5M` | Keycloak `sub` → user id lookup and its cache |
| `DB_PASSWORD` | every service with a DB | `root` (MySQL), `postgres` (booking) | Local runs only; compose sets its own |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | services with caches, notification | `localhost`, `6379` | |
| `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` | payment-service | `CHANGE_ME` | From the VNPay sandbox registration; see [VNPAY.md](booking-refactor/VNPAY.md) |
| `VNPAY_PAY_URL` | payment-service | sandbox `vpcpay.html` | |
| `VNPAY_RETURN_URL` | payment-service | `http://localhost:5173/booking-success` | `/{bookingId}` is appended |
| `MAIL_USERNAME`, `MAIL_APP_PASSWORD`, `MAIL_FROM` | notification-service | empty | Gmail SMTP with an app password |
| `TWILIO_ENABLED`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | notification-service | `false`, empty | SMS is off unless enabled |
| `CONFIG_GIT_URI`, `CONFIG_GIT_LABEL` | config-server | this repo, `main` | |

## 4. Fixed local endpoints

| What | URL |
|---|---|
| API gateway | http://localhost:5000 |
| Frontend (Vite) | http://localhost:5173 |
| Keycloak | http://localhost:8180 (realm `ben-airline`, clients `airline-frontend`, `airline-api`) |
| Eureka dashboard | http://localhost:8761 |
| Config server | http://localhost:8888/<service>/default |
| Kafka | localhost:9092 |

## 5. Not here yet

There is no shared or production environment. Deployment (registry, secrets store, TLS) is a later milestone in the [roadmap](roadmap.md); when it exists, its settings go into a new section of this file.
