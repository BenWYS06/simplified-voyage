# Onboarding

Welcome to **Ben Airline**, an airline GDS built as Spring Boot microservices with a React frontend. It covers flight search → seat selection → booking → VNPay payment → notification, and the booking flow is a saga built to practise idempotency, row locks, the outbox and concurrency tests.

This page gets you from clone to a running system and your first change. What the product is: [README.md](README.md). How the pieces talk: [docs/architecture.md](docs/architecture.md). Code style and PRs: [CONTRIBUTING.md](CONTRIBUTING.md).

---

## 1. Tools

| Tool | Version | Why |
|---|---|---|
| Java | 17 | All services |
| Maven | 3.9+ | Multi-module build from `backend/pom.xml` |
| Node.js + npm | 20.19+ | Vite 7 frontend |
| Docker Desktop | recent | Databases, Kafka, Redis, Keycloak, Testcontainers |
| Git | any | One branch per piece of work |

Optional: Postman (call services directly with `X-User-Id` / `X-User-Roles`), ngrok (receive a real VNPay IPN).

## 2. Clone and configure

```bash
git clone https://github.com/BenWYS06/AirlineGDS-Mircoservice.git
cd AirlineGDS-Mircoservice
cp docker/.env.example docker/.env      # fill in values; never commit .env
```

Which values you need: [docs/environments.md](docs/environments.md). For a first run, the Keycloak admin password and `INTERNAL_API_KEY` are enough. Mail, SMS and VNPay can stay empty until you test those flows.

## 3. Pick a way to run it

### A. Everything in Docker (closest to a real deployment)

```bash
cd backend && mvn clean package jib:dockerBuild -DskipTests && cd ..   # builds ben/gds-* images
docker compose -f docker/docker-compose.yml up -d
curl http://localhost:5000/actuator/health
```

### B. Infra in Docker, services from the IDE (daily development)

```bash
docker compose -f docker/docker-compose.dev.yml up -d     # MySQL 3306, PostgreSQL 5432, Redis 6379, Kafka 9092
```
Then start, in this order: `config-server` → `service-registry` → `api-gateway` → the services you need. Keycloak comes from compose A (`keycloak` + `keycloakdb`) when you need real logins.

Step-by-step commands, ports and checks: [docs/runbooks/local-stack.md](docs/runbooks/local-stack.md).

### Frontend

```bash
cd frontend && npm install && npm run dev      # http://localhost:5173, calls the gateway on :5000
```

## 4. Log in and try the flow

- Keycloak admin console: http://localhost:8180, realm `ben-airline` (imported from `docker/keycloak/realm-export.json`). Self-registration is on.
- Roles: `ROLE_CUSTOMER` (traveller), `ROLE_AIRLINE_OWNER` (airline admin), `ROLE_SYSTEM_ADMIN` (super admin).
- Without Keycloak, call a service directly with headers `X-User-Id: 1` and `X-User-Roles: ROLE_CUSTOMER` (Postman).
- VNPay sandbox and testing an IPN without ngrok: [docs/booking-refactor/VNPAY.md](docs/booking-refactor/VNPAY.md).

## 5. Read before you change things

| If you touch | Read first |
|---|---|
| Anything | [CLAUDE.md](CLAUDE.md): rules, ports, verify commands |
| Booking, seat, payment | [docs/booking-refactor/DESIGN.md](docs/booking-refactor/DESIGN.md) and the service's `CLAUDE.md` |
| Auth, roles, gateway | [docs/authentication.md](docs/authentication.md) |
| An endpoint or DTO | [docs/api-spec.md](docs/api-spec.md), `.claude/rules/api-contract.md` |
| The database | `.claude/rules/db-migrations.md` (new `V{n}` file, never edit an old one) |
| Why something is the way it is | [docs/decisions.md](docs/decisions.md) |

## 6. Your first change

1. Branch from `main`: `feat/<short-name>` (or `fix/`, `docs/`, `chore/`).
2. Small or medium feature: write a plan in `docs/plans/YYYY-MM-DD-<name>.md` first (with Claude Code: `/add-feature`).
3. Verify by scan + compile: `cd backend && mvn -q compile -pl services/<service> -am`, and `npm run build` for the frontend.
4. Commit in small steps (`type(scope): subject` + a short body), open a PR with the template, merge after review, delete the branch.

Working with Claude Code on this repo: [docs/AI_WORKFLOW.md](docs/AI_WORKFLOW.md).

## 7. When something breaks

[docs/runbooks/troubleshooting.md](docs/runbooks/troubleshooting.md) lists the problems that already cost time: port 3306 taken, a stale `common-lib` jar, IDE-compiled classes, Flyway validate errors, Kafka not reachable.
